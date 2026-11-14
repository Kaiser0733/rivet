package com.kaiser.rivet.runtime

import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CheckpointBackend {
    suspend fun beginCheckpoint(workspaceId: String, runId: String?, sessionId: String?): String
    suspend fun finishCheckpoint(workspaceId: String, id: String): Boolean
    suspend fun recordCheckpointPost(workspaceId: String, id: String)
    suspend fun checkpointMatchesPost(workspaceId: String, id: String): Boolean
    suspend fun beginRunCheckpoint(
        workspaceId: String,
        runId: String,
        sequence: Int,
        summary: String,
        allowDirty: Boolean = false,
    ): String?
    suspend fun finishRunCheckpoint(workspaceId: String, id: String)
}

class RuntimeControllerCheckpointBackend(
    private val runtime: RuntimeController,
) : CheckpointBackend {
    override suspend fun beginCheckpoint(workspaceId: String, runId: String?, sessionId: String?): String =
        runtime.beginCheckpoint(workspaceId, runId, sessionId)

    override suspend fun finishCheckpoint(workspaceId: String, id: String): Boolean =
        runtime.finishCheckpoint(workspaceId, id)

    override suspend fun recordCheckpointPost(workspaceId: String, id: String) =
        runtime.recordCheckpointPost(workspaceId, id)

    override suspend fun checkpointMatchesPost(workspaceId: String, id: String): Boolean =
        runtime.checkpointMatchesPost(workspaceId, id)

    override suspend fun beginRunCheckpoint(
        workspaceId: String,
        runId: String,
        sequence: Int,
        summary: String,
        allowDirty: Boolean,
    ): String? = runtime.beginRunCheckpoint(workspaceId, runId, sequence, summary, allowDirty)

    override suspend fun finishRunCheckpoint(workspaceId: String, id: String) =
        runtime.finishRunCheckpoint(workspaceId, id)
}

sealed interface TurnCheckpointOutcome {
    data object None : TurnCheckpointOutcome
    data class Finished(val id: String, val hasChanges: Boolean) : TurnCheckpointOutcome
    data object Broken : TurnCheckpointOutcome
}

/**
 * Coordinates the run-wide write lease, shared TurnCheckpoint manifest chain, and
 * RunCheckpoint boundaries across the coordinator and sibling writers.
 */
class RunCheckpointGate(
    private val backend: CheckpointBackend,
    private val onBoundaryChanged: suspend () -> Unit = {},
) {
    constructor(
        runtime: RuntimeController,
        onBoundaryChanged: suspend () -> Unit = {},
    ) : this(RuntimeControllerCheckpointBackend(runtime), onBoundaryChanged)

    private val writeLease = Mutex()
    private var workspaceId: String? = null
    private var runId: String? = null
    private var sessionId: String? = null
    private var turnCheckpointId: String? = null
    @Volatile var checkpointBroken: Boolean = false
        private set
    private var needsPostCheck: Boolean = false
    private var runCheckpointId: String? = null
    private var runCheckpointSequence: Int = 0
    private var mutationCount: Int = 0
    private var boundaryRefreshPending: Boolean = false

    suspend fun startRun(workspaceId: String, runId: String, title: String, resuming: Boolean) {
        writeLease.withLock {
            this.workspaceId = workspaceId
            this.runId = runId
            if (!resuming) {
                mutationCount = 0
                runCheckpointSequence = 0
            } else {
                runCheckpointSequence += 1
            }
            runCheckpointId = try {
                backend.beginRunCheckpoint(
                    workspaceId,
                    runId,
                    runCheckpointSequence,
                    if (!resuming) "Before: ${title.take(120)}" else "Resumed run",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        onBoundaryChanged()
    }

    fun beginTurn(workspaceId: String?, runId: String?, sessionId: String?) {
        this.workspaceId = workspaceId
        this.runId = runId
        this.sessionId = sessionId
        turnCheckpointId = null
        checkpointBroken = false
        needsPostCheck = false
        boundaryRefreshPending = false
    }

    suspend fun beginRunBoundary(summary: String, allowDirty: Boolean = true) {
        val changed = writeLease.withLock {
            beginRunBoundaryLocked(summary, allowDirty)
        }
        if (changed) onBoundaryChanged()
    }

    suspend fun finishRunCheckpoint(overrideWorkspaceId: String? = null) {
        val (ws, id) = writeLease.withLock {
            val current = runCheckpointId ?: return@withLock null to null
            val targetWorkspace = overrideWorkspaceId ?: workspaceId ?: return@withLock null to null
            runCheckpointId = null
            targetWorkspace to current
        }
        if (id == null || ws == null) return
        try {
            backend.finishRunCheckpoint(ws, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        onBoundaryChanged()
    }

    suspend fun beforeMutation(writer: String = COORDINATOR, call: AgentToolCall): String? {
        var locked = false
        writeLease.lock()
        locked = true
        try {
            mutationCount += 1
            val ws = workspaceId
            val rId = runId
            if (mutationCount > 0 && mutationCount % 10 == 0 && ws != null && rId != null) {
                if (beginRunBoundaryLocked("Every 10 file edits", allowDirty = true)) {
                    boundaryRefreshPending = true
                }
            }
            if (ws == null || checkpointBroken) {
                val refresh = boundaryRefreshPending
                boundaryRefreshPending = false
                writeLease.unlock()
                locked = false
                if (refresh) onBoundaryChanged()
                return "checkpoint_unavailable"
            }
            val blocker = try {
                val id = turnCheckpointId
                if (id == null) {
                    turnCheckpointId = backend.beginCheckpoint(ws, rId, sessionId)
                } else if (needsPostCheck) {
                    if (!backend.checkpointMatchesPost(ws, id)) {
                        checkpointBroken = true
                    } else {
                        needsPostCheck = false
                    }
                }
                if (checkpointBroken) "checkpoint_unavailable" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: MirrorFailure) {
                e.code.takeIf {
                    it == "sync_required" || it == "workspace_unavailable" || it == "workspace_changed"
                } ?: "checkpoint_unavailable"
            }
            if (blocker != null) {
                val refresh = boundaryRefreshPending
                boundaryRefreshPending = false
                writeLease.unlock()
                locked = false
                if (refresh) onBoundaryChanged()
            }
            return blocker
        } catch (t: Throwable) {
            boundaryRefreshPending = false
            if (locked) writeLease.unlock()
            throw t
        }
    }

    suspend fun afterMutation(writer: String = COORDINATOR, call: AgentToolCall, result: AgentToolResult?) {
        var refresh = false
        try {
            val ws = workspaceId
            val id = turnCheckpointId
            if (id != null && ws != null && !checkpointBroken) {
                try {
                    backend.recordCheckpointPost(ws, id)
                    needsPostCheck = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    checkpointBroken = true
                }
            }
            refresh = boundaryRefreshPending
            boundaryRefreshPending = false
        } finally {
            writeLease.unlock()
        }
        if (refresh) {
            onBoundaryChanged()
        }
    }

    suspend fun finishTurnCheckpoint(): TurnCheckpointOutcome {
        val (ws, id, broken) = writeLease.withLock {
            Triple(workspaceId, turnCheckpointId, checkpointBroken)
        }
        if (broken) return TurnCheckpointOutcome.Broken
        if (id == null || ws == null) return TurnCheckpointOutcome.None
        val hasChanges = backend.finishCheckpoint(ws, id)
        return TurnCheckpointOutcome.Finished(id, hasChanges)
    }

    private suspend fun beginRunBoundaryLocked(summary: String, allowDirty: Boolean): Boolean {
        val ws = workspaceId ?: return false
        val rId = runId ?: return false
        val old = runCheckpointId
        if (old != null) {
            try {
                backend.finishRunCheckpoint(ws, old)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        runCheckpointSequence += 1
        runCheckpointId = try {
            backend.beginRunCheckpoint(ws, rId, runCheckpointSequence, summary, allowDirty = allowDirty)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return true
    }

    companion object {
        const val COORDINATOR = "coordinator"
    }
}
