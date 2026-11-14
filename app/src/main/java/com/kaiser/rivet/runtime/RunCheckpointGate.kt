package com.kaiser.rivet.runtime

import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.subagent.ChangedFile
import com.kaiser.rivet.subagent.FileChangeKind
import com.kaiser.rivet.subagent.FileScopeRegistry
import com.kaiser.rivet.subagent.WorkerDiff
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
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
    suspend fun readManifest(
        workspaceId: String,
        id: String,
        scanLive: Boolean = false,
    ): Map<String, CheckpointEntry>? = null
    suspend fun readFileBytes(
        workspaceId: String,
        path: String,
        maxBytes: Int = RunCheckpointGate.MAX_DIFF_SOURCE_BYTES,
    ): ByteArray? = null
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

    override suspend fun readManifest(
        workspaceId: String,
        id: String,
        scanLive: Boolean,
    ): Map<String, CheckpointEntry>? = runtime.checkpointManifest(workspaceId, id, scanLive)

    override suspend fun readFileBytes(
        workspaceId: String,
        path: String,
        maxBytes: Int,
    ): ByteArray? = runtime.readWorktreeFileBytes(workspaceId, path, maxBytes)
}

sealed interface TurnCheckpointOutcome {
    data object None : TurnCheckpointOutcome
    data class Finished(val id: String, val hasChanges: Boolean) : TurnCheckpointOutcome
    data object Broken : TurnCheckpointOutcome
}

data class WriterAttribution(
    val files: List<ChangedFile> = emptyList(),
    val outOfScopeChanges: List<String> = emptyList(),
    val diff: WorkerDiff? = null,
)

/**
 * Coordinates the run-wide write lease, shared TurnCheckpoint manifest chain,
 * FileScopeRegistry enforcement, per-writer byte/manifest attribution, and
 * RunCheckpoint boundaries across the coordinator and sibling writers.
 */
class RunCheckpointGate(
    private val backend: CheckpointBackend,
    val scopeRegistry: FileScopeRegistry = FileScopeRegistry(),
    private val onBoundaryChanged: suspend () -> Unit = {},
) {
    constructor(
        runtime: RuntimeController,
        onBoundaryChanged: suspend () -> Unit = {},
    ) : this(RuntimeControllerCheckpointBackend(runtime), FileScopeRegistry(), onBoundaryChanged)

    constructor(
        backend: CheckpointBackend,
        onBoundaryChanged: suspend () -> Unit,
    ) : this(backend, FileScopeRegistry(), onBoundaryChanged)

    private class AttributedFileState(
        val existedBefore: Boolean,
        val firstBeforeEntry: CheckpointEntry?,
        var firstBeforeBytes: ByteArray?,
        var existsAfter: Boolean,
        var latestAfterEntry: CheckpointEntry?,
        var latestAfterBytes: ByteArray?,
        var touchedByFileTool: Boolean,
    )

    private class WriterState {
        val files = linkedMapOf<String, AttributedFileState>()
        val outOfScopeChanges = linkedSetOf<String>()
    }

    private val writeLease = Mutex()
    private val attributionLock = Any()
    private val writerStates = linkedMapOf<String, WriterState>()
    private var activePreManifest: Map<String, CheckpointEntry>? = null
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
                synchronized(attributionLock) {
                    writerStates.clear()
                }
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
        activePreManifest = null
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
            // Scope check happens first: release the lease without incrementing mutationCount.
            val scopeBlocker = synchronized(attributionLock) {
                scopeRegistry.checkWrite(writer, call)
            }
            if (scopeBlocker != null) {
                writeLease.unlock()
                locked = false
                return scopeBlocker
            }

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
                return blocker
            }

            val currentCheckpointId = turnCheckpointId
            val preManifest = if (currentCheckpointId != null) {
                try {
                    backend.readManifest(ws, currentCheckpointId, scanLive = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else null
            activePreManifest = preManifest

            if (call.name != "run_command" && call.name != "create_directory") {
                for (target in FileScopeRegistry.mutationPaths(call)) {
                    if (target.isRoot) continue
                    val path = target.value
                    val preEntry = preManifest?.get(path)
                    if (preEntry?.directory == true) continue
                    val alreadyHasBytes = synchronized(attributionLock) {
                        writerStates[writer]?.files?.containsKey(path) == true
                    }
                    val beforeBytes = if (!alreadyHasBytes) {
                        try {
                            backend.readFileBytes(ws, path, MAX_DIFF_SOURCE_BYTES)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                    synchronized(attributionLock) {
                        val wState = writerStates.getOrPut(writer) { WriterState() }
                        val existing = wState.files[path]
                        if (existing == null) {
                            val existed = preEntry != null || beforeBytes != null
                            wState.files[path] = AttributedFileState(
                                existedBefore = existed,
                                firstBeforeEntry = preEntry,
                                firstBeforeBytes = beforeBytes,
                                existsAfter = existed,
                                latestAfterEntry = preEntry,
                                latestAfterBytes = beforeBytes,
                                touchedByFileTool = true,
                            )
                        } else {
                            existing.touchedByFileTool = true
                        }
                    }
                }
            }
            return null
        } catch (t: Throwable) {
            boundaryRefreshPending = false
            activePreManifest = null
            if (locked) writeLease.unlock()
            throw t
        }
    }

    suspend fun afterMutation(writer: String = COORDINATOR, call: AgentToolCall, result: AgentToolResult?) {
        var refresh = false
        try {
            val ws = workspaceId
            val id = turnCheckpointId
            var postFailed = false
            if (id != null && ws != null && !checkpointBroken) {
                try {
                    backend.recordCheckpointPost(ws, id)
                    needsPostCheck = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    checkpointBroken = true
                    postFailed = true
                }
            }
            val preManifest = activePreManifest
            activePreManifest = null
            val postManifest = if (id != null && ws != null) {
                try {
                    backend.readManifest(ws, id, scanLive = postFailed)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else null

            if (ws != null) {
                attributeMutation(ws, writer, call, result, preManifest, postManifest)
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

    private suspend fun attributeMutation(
        ws: String,
        writer: String,
        call: AgentToolCall,
        result: AgentToolResult?,
        preManifest: Map<String, CheckpointEntry>?,
        postManifest: Map<String, CheckpointEntry>?,
    ) {
        val isFileTool = call.name != "run_command" && call.name != "create_directory"
        if (preManifest != null && postManifest != null) {
            val allPaths = (preManifest.keys + postManifest.keys).distinct()
            for (path in allPaths) {
                val oldEntry = preManifest[path]
                val newEntry = postManifest[path]
                if (oldEntry == newEntry) continue
                if ((oldEntry?.directory == true) && (newEntry == null || newEntry.directory)) continue
                if ((newEntry?.directory == true) && (oldEntry == null || oldEntry.directory)) continue
                val afterBytes = if (isFileTool && newEntry != null && !newEntry.directory) {
                    try {
                        backend.readFileBytes(ws, path, MAX_DIFF_SOURCE_BYTES)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                } else null
                synchronized(attributionLock) {
                    val wState = writerStates.getOrPut(writer) { WriterState() }
                    val existing = wState.files[path]
                    if (existing == null) {
                        wState.files[path] = AttributedFileState(
                            existedBefore = oldEntry != null,
                            firstBeforeEntry = oldEntry,
                            firstBeforeBytes = null,
                            existsAfter = newEntry != null,
                            latestAfterEntry = newEntry,
                            latestAfterBytes = afterBytes,
                            touchedByFileTool = isFileTool,
                        )
                    } else {
                        existing.existsAfter = newEntry != null
                        existing.latestAfterEntry = newEntry
                        if (isFileTool) {
                            existing.touchedByFileTool = true
                            existing.latestAfterBytes = afterBytes
                        } else {
                            // A subsequent shell write invalidates stale file-tool afterBytes unless re-read.
                            existing.latestAfterBytes = null
                        }
                    }
                    recordOutOfScopeIfApplicableLocked(writer, path, wState)
                }
            }
            return
        }

        // Fallback when the backend does not expose full manifests (e.g. unit test fakes):
        // attribute non-error file tool calls using their declared target paths and readFileBytes.
        if (isFileTool && result?.error != true) {
            val targets = FileScopeRegistry.mutationPaths(call).filterNot { it.isRoot }
            for ((index, target) in targets.withIndex()) {
                val path = target.value
                val deleted = call.name == "delete_path" ||
                    ((call.name == "move_path" || call.name == "rename_path") && index == 0 && targets.size > 1)
                val afterBytes = if (deleted) null else {
                    try {
                        backend.readFileBytes(ws, path, MAX_DIFF_SOURCE_BYTES)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val existsAfter = !deleted
                synchronized(attributionLock) {
                    val wState = writerStates.getOrPut(writer) { WriterState() }
                    val existing = wState.files[path]
                    if (existing != null) {
                        existing.existsAfter = existsAfter
                        existing.latestAfterBytes = afterBytes
                        existing.touchedByFileTool = true
                    } else {
                        wState.files[path] = AttributedFileState(
                            existedBefore = deleted,
                            firstBeforeEntry = null,
                            firstBeforeBytes = null,
                            existsAfter = existsAfter,
                            latestAfterEntry = null,
                            latestAfterBytes = afterBytes,
                            touchedByFileTool = true,
                        )
                    }
                    recordOutOfScopeIfApplicableLocked(writer, path, wState)
                }
            }
        }
    }

    private fun recordOutOfScopeIfApplicableLocked(writer: String, path: String, wState: WriterState) {
        if (writer == COORDINATOR) return
        val parsed = try {
            WorkspacePath.parse(path)
        } catch (_: WorkspaceFailure) {
            return
        }
        val ownScope = scopeRegistry.scopeOf(writer)?.paths.orEmpty()
        if (ownScope.none { parsed.isWithin(it) }) {
            wState.outOfScopeChanges += path
        }
    }

    fun writerAttribution(
        writer: String,
        maxDiffBytes: Int = WorkerDiff.MAX_BYTES,
    ): WriterAttribution = synchronized(attributionLock) {
        val wState = writerStates[writer] ?: return WriterAttribution()
        val changedFiles = mutableListOf<ChangedFile>()
        val diffBuilder = StringBuilder()
        var remainingDiffBytes = maxDiffBytes.coerceAtLeast(0)
        var totalAdditions = 0
        var totalDeletions = 0
        var anyTruncated = false
        var firstDiffPath: String? = null

        for ((path, state) in wState.files.entries.sortedBy { it.key }) {
            if (!state.existedBefore && !state.existsAfter) continue
            if (state.existedBefore && state.existsAfter) {
                if (state.firstBeforeEntry != null && state.latestAfterEntry != null &&
                    state.firstBeforeEntry == state.latestAfterEntry) {
                    continue
                }
                if (state.firstBeforeEntry == null && state.latestAfterEntry == null &&
                    state.firstBeforeBytes != null && state.latestAfterBytes != null &&
                    state.firstBeforeBytes.contentEquals(state.latestAfterBytes)) {
                    continue
                }
            }
            val kind = when {
                !state.existedBefore && state.existsAfter -> FileChangeKind.Added
                state.existedBefore && !state.existsAfter -> FileChangeKind.Deleted
                else -> FileChangeKind.Modified
            }
            val beforeBytes = if (!state.existedBefore) ByteArray(0) else state.firstBeforeBytes
            val afterBytes = if (!state.existsAfter) ByteArray(0) else state.latestAfterBytes
            val canDiff = state.touchedByFileTool &&
                beforeBytes != null &&
                afterBytes != null &&
                !isBinary(beforeBytes) &&
                !isBinary(afterBytes)

            if (canDiff) {
                val singleDiff = WorkerDiff.diff(
                    path = path,
                    before = beforeBytes!!,
                    after = afterBytes!!,
                    maxBytes = remainingDiffBytes,
                )
                changedFiles += ChangedFile(
                    path = path,
                    kind = kind,
                    additions = singleDiff.additions,
                    deletions = singleDiff.deletions,
                )
                totalAdditions += singleDiff.additions
                totalDeletions += singleDiff.deletions
                if (firstDiffPath == null) firstDiffPath = path
                if (singleDiff.text.isNotEmpty()) {
                    diffBuilder.append(singleDiff.text)
                    val used = singleDiff.text.toByteArray(Charsets.UTF_8).size
                    remainingDiffBytes = (remainingDiffBytes - used).coerceAtLeast(0)
                }
                if (singleDiff.truncated) {
                    anyTruncated = true
                }
            } else {
                changedFiles += ChangedFile(path = path, kind = kind, additions = 0, deletions = 0)
            }
        }

        val combinedDiff = if (firstDiffPath != null && (diffBuilder.isNotEmpty() || anyTruncated)) {
            WorkerDiff(
                path = firstDiffPath,
                text = diffBuilder.toString(),
                additions = totalAdditions,
                deletions = totalDeletions,
                truncated = anyTruncated,
            )
        } else null

        WriterAttribution(
            files = changedFiles,
            outOfScopeChanges = wState.outOfScopeChanges.sorted(),
            diff = combinedDiff,
        )
    }

    private fun isBinary(bytes: ByteArray): Boolean {
        val checkLen = bytes.size.coerceAtMost(8192)
        for (i in 0 until checkLen) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
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
        const val COORDINATOR = FileScopeRegistry.COORDINATOR
        const val MAX_DIFF_SOURCE_BYTES = 1024 * 1024
    }
}
