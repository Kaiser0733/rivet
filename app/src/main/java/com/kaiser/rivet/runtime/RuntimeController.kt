package com.kaiser.rivet.runtime

import android.content.Context
import com.kaiser.rivet.storage.GitHubTokenStore
import com.kaiser.rivet.workspace.WorkspacePath
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspaceSelection
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class RuntimeCommandResult(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val timedOut: Boolean = false,
    val cwd: String = "",
    val sync: String,
    val syncPath: String? = null,
    val syncSkipped: List<String> = emptyList(),
    val syncConflicts: List<String> = emptyList(),
    val vcsNote: String? = null,
    val error: String? = null,
    val tooManyChildren: Boolean = false,
    val memoryLimit: Boolean = false,
    val stderrPresent: Boolean = false,
    val outputId: String? = null,
)

data class PreviewLaunch(
    val processId: String,
    val url: String,
    val entry: String,
    val reused: Boolean = false,
)

data class BackgroundLaunch(
    val processId: String,
    val status: String,
    val stdout: String,
    val stderr: String,
    val stderrPresent: Boolean,
    val readyMatched: Boolean?,
    val exitCode: Int?,
)

data class ProcessOutputSnapshot(
    val processId: String,
    val status: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val stderrPresent: Boolean,
    val stdoutStart: Long = 0,
    val stderrStart: Long = 0,
    val stdoutEnd: Long? = null,
    val stderrEnd: Long? = null,
    val sync: MirrorSyncResult? = null,
)

/** A project sync is a UI-blocking prerequisite for the terminal; it must never hold
 * the pane forever. On expiry the existing worktree is used and the terminal starts. */
internal const val WORKSPACE_SYNC_TIMEOUT_MS = 15_000L

private data class BackgroundPlan(
    val identity: String,
    val directory: String,
    val environment: Array<String>,
    val command: String,
    val credentialFile: File?,
)

data class RunCheckpointRestoreResult(
    val restored: List<String>,
    val conflicts: List<String>,
)

class RuntimeController(
    context: Context,
    private val selection: WorkspaceSelection = WorkspaceSelection(context.applicationContext),
    private val managedProcesses: ManagedProcesses? = null,
    private val projectName: () -> String? = { null },
) {
    private val app = context.applicationContext
    private val outputCache = CommandOutputCache(File(app.cacheDir, "command-outputs"))
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val backgroundRings = java.util.concurrent.ConcurrentHashMap<String, ProcessLogRing>()
    private val drainedRings = java.util.concurrent.ConcurrentHashMap<String, ProcessLogRing>()
    private val drainedOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val toolchain = Toolchain(app)
    private val gitHub = GitHubTokenStore(app)
    private val governor = TerminalGovernor(app)
    private val operations = Mutex()
    private var mirrorIdentity: String? = null
    private var mirror: WorkspaceMirror? = null

    internal fun isOperationsLocked(): Boolean = operations.isLocked

    suspend fun runCommand(command: String, cwd: String, timeoutMs: Long): RuntimeCommandResult =
        runCommand(command, cwd, timeoutMs, null)

    /**
     * The same governed run as the three-argument form. [onOutput] is a read-only
     * observation hook for surfaces that render the streams live (the user terminal); it
     * receives the same bounded snapshots the Processes registry gets and cannot change
     * the command, limits, timeout, or kill path.
     */
    suspend fun runCommand(command: String, cwd: String, timeoutMs: Long,
                           onOutput: ((String, String) -> Unit)?): RuntimeCommandResult = operations.withLock {
        val active = currentMirror() ?: return@withLock RuntimeCommandResult(cwd = cwd, sync = "not_started", error = "workspace_unavailable")
        // Plan §5 rules 11/12: fresh readings gate every start, so a SERIOUS throttle
        // lifts on the first command after the phone cools.
        val readings = governor.readings()
        managedProcesses?.setActiveLimit(effectiveSlots(readings.thermalStatus))
        commandGate(timeoutMs, readings)?.let { code ->
            return@withLock RuntimeCommandResult(cwd = cwd, sync = "not_started", error = code)
        }
        governor.commandStarted()
        var ready = active.prepare()
        if (ready.dirty) {
            // Push what can be pushed, then run in the private worktree even if a
            // per-file disagreement remains. The command result names the pending
            // paths; it is not a reason to refuse every command in the project.
            try { active.sync() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            ready = active.prepare()
        }
        // First use in this process: build the applet farm before PATH is composed.
        // Without libbusybox.so this is a no-op and the environment is unchanged.
        toolchain.ensurePrepared()
        // Auth is resolved for this git command only; no generic shell environment gets it.
        val credentialFile = if (toolchain.gitReady && GitCommandAuth.needsAuth(command)) {
            withContext(Dispatchers.IO) { gitHub.refresh() }
        } else null
        val effectiveCommand = GitCommandAuth.authenticated(command, credentialFile)
        val directory = runtimeDirectory(ready.worktree, cwd)
        val environment = environment(active, directory)
        val processId = managedProcesses?.let { registry ->
            val job = currentCoroutineContext()[Job]
                ?: return@withLock RuntimeCommandResult(cwd = cwd, sync = "not_started", error = "runtime_unavailable")
            val identity = mirrorIdentity
                ?: return@withLock RuntimeCommandResult(cwd = cwd, sync = "not_started", error = "workspace_unavailable")
            registry.registerCommand(command, cwd, identity, projectName(), job)
                ?: return@withLock RuntimeCommandResult(cwd = cwd, sync = "not_started", error = "process_limit")
        }
        fun publish(stdout: String, stderr: String) {
            processId?.let { managedProcesses?.updateCommandOutput(it, stdout, stderr) }
            onOutput?.invoke(stdout, stderr)
        }
        val publisher: ((String, String) -> Unit)? =
            if (processId == null && onOutput == null) null else ::publish
        val memoryCap = governor.memoryCapBytes()
        val result = try {
            CommandProcess().run(effectiveCommand, directory.absolutePath, environment, timeoutMs,
                onOutput = publisher, memoryCapBytes = memoryCap)
        } catch (e: CancellationException) {
            processId?.let { managedProcesses?.failCommand(it) }
            throw e
        } catch (e: Exception) {
            processId?.let { managedProcesses?.failCommand(it) }
            throw e
        } finally {
            if (credentialFile != null) {
                withContext(NonCancellable + Dispatchers.IO) { gitHub.clearCredentialFile() }
            }
        }
        if (result.memoryLimit) governor.recordStop("memory limit")
        else if (result.tooManyChildren) governor.recordStop("too many child processes")
        val outputId = runCatching {
            outputCache.put(
                result.stdoutFull, result.stderrFull,
                workspace = mirrorIdentity,
                stdoutTruncated = result.stdoutFullTruncated,
                stderrTruncated = result.stderrFullTruncated,
            )
        }.getOrNull()
        processId?.let { id -> managedProcesses?.finishCommand(id,
            RuntimeCommandResult(result.exitCode, result.stdout, result.stderr, result.stdoutTruncated,
                result.stderrTruncated, result.timedOut, cwd, "not_started",
                tooManyChildren = result.tooManyChildren, memoryLimit = result.memoryLimit,
                stderrPresent = result.stderrFull.isNotEmpty(), outputId = outputId)) }
        val sync = try { active.sync() }
            catch (_: CancellationException) {
                // The command already ran. Return its exit status with an
                // interrupted sync so the correlated event can be persisted.
                return@withLock RuntimeCommandResult(result.exitCode, result.stdout, result.stderr,
                    result.stdoutTruncated, result.stderrTruncated, result.timedOut, cwd,
                    "interrupted", vcsNote = worktreeVcsNote(ready.worktree),
                    tooManyChildren = result.tooManyChildren,
                    memoryLimit = result.memoryLimit,
                    stderrPresent = result.stderrFull.isNotEmpty(), outputId = outputId)
            } catch (e: Exception) { MirrorSyncResult(MirrorSync.Failed) }
        RuntimeCommandResult(result.exitCode, result.stdout, result.stderr,
            result.stdoutTruncated, result.stderrTruncated, result.timedOut, cwd,
            sync.state.code, sync.path, syncSkipped = sync.skipped, syncConflicts = sync.conflicts,
            vcsNote = worktreeVcsNote(ready.worktree),
            tooManyChildren = result.tooManyChildren, memoryLimit = result.memoryLimit,
            stderrPresent = result.stderrFull.isNotEmpty(), outputId = outputId)
    }

    /** Plan §5 rule 14: the one plain terminal state sentence. */
    fun terminalStateLine(activeCount: Int): String = governor.stateLine(activeCount)

    /** One interactive shell: the command environment plus a folder-only prompt. */
    internal suspend fun openTerminal(job: Job): TerminalLaunch = operations.withLock {
        if (!TerminalStartGate.canAcceptManagedRegistration(job.isActive)) {
            return@withLock TerminalLaunch(error = "cancelled")
        }
        val active = currentMirror()
            ?: return@withLock TerminalLaunch(error = "workspace_unavailable")
        val readings = governor.readings()
        managedProcesses?.setActiveLimit(effectiveSlots(readings.thermalStatus))
        // No timeout inside the session; admission still treats it as a long-running job.
        commandGate(Long.MAX_VALUE, readings)?.let { code ->
            return@withLock TerminalLaunch(error = code)
        }
        governor.commandStarted()
        val readyCheck = try {
            withTimeout(WORKSPACE_SYNC_TIMEOUT_MS) {
                var state = active.prepare()
                if (state.dirty) {
                    try { active.sync() } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { }
                    state = active.prepare()
                }
                if (state.dirty) {
                    // A pending path does not make the last complete worktree
                    // unusable; open the terminal there instead of a dead end.
                    val fallback = active.worktree.takeIf { it.isDirectory }
                    if (fallback == null) null to "sync_failed"
                    else MirrorReady(fallback, dirty = false) to null
                } else state to null
            }
        } catch (e: TimeoutCancellationException) {
            // The last complete worktree is still a usable terminal root. Never leave
            // the pane on a sync spinner just because SAF or the device is slow.
            val fallback = active.worktree.takeIf { it.isDirectory }
                ?: return@withLock TerminalLaunch(error = "sync_timeout")
            MirrorReady(fallback, dirty = false) to null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock TerminalLaunch(error = "sync_failed")
        }
        readyCheck.second?.let { errorCode -> return@withLock TerminalLaunch(error = errorCode) }
        val ready = readyCheck.first ?: return@withLock TerminalLaunch(error = "sync_failed")
        toolchain.ensurePrepared()
        val directory = try { runtimeDirectory(ready.worktree, "") }
        catch (_: MirrorFailure) { return@withLock TerminalLaunch(error = "sync_timeout") }
        val environment = terminalEnvironment(environment(active, directory).toList())
        val identity = mirrorIdentity
            ?: return@withLock TerminalLaunch(error = "workspace_unavailable")
        val shell = if (toolchain.availability().busybox) {
            terminalShellPath(toolchain.binDir)
        } else SYSTEM_SHELL
        val managedId = managedProcesses?.let { registry ->
            if (!TerminalStartGate.canAcceptManagedRegistration(job.isActive)) {
                return@withLock TerminalLaunch(error = "cancelled")
            }
            registry.registerCommand("Terminal", "", identity, projectName(), job)
                ?: return@withLock TerminalLaunch(error = "process_limit")
        }
        TerminalLaunch(
            shellPath = shell,
            cwd = directory.absolutePath,
            environment = environment.toTypedArray(),
            managedId = managedId,
            workspaceIdentity = identity,
            memoryCapBytes = governor.memoryCapBytes(),
        )
    }

    internal suspend fun syncTerminal(identity: String): MirrorSyncResult? = operations.withLock {
        if (mirrorIdentity != identity || selection.currentIdentity() != identity) return@withLock null
        try { mirror?.sync() } catch (e: CancellationException) { throw e }
        catch (e: MirrorFailure) { MirrorSyncResult(MirrorSync.Failed, e.path) }
        catch (_: Exception) { MirrorSyncResult(MirrorSync.Failed) }
    }

    internal fun recordTerminalStop(reason: String) = governor.recordStop(reason)

    /** What the prompt may honestly claim about the shipped toolchain right now. */
    internal fun toolchainAvailability(): ToolchainAvailability = toolchain.availability()

    suspend fun gitStatus(): RepositoryStatus = operations.withLock {
        gitInspection().status()
    }

    suspend fun gitDiff(path: String): RepositoryDiff = operations.withLock {
        gitInspection().diff(path)
    }

    suspend fun beginCheckpoint(workspaceId: String, runId: String? = null,
                                sessionId: String? = null): String = operations.withLock {
        if (selection.currentIdentity() != workspaceId) throw MirrorFailure("workspace_changed")
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        val ready = active.prepare()
        if (ready.dirty) throw MirrorFailure("sync_required")
        checkpoints(workspaceId).begin(ready.worktree, runId, sessionId)
    }

    suspend fun finishCheckpoint(workspaceId: String, id: String): Boolean = operations.withLock {
        if (selection.currentIdentity() != workspaceId) throw MirrorFailure("workspace_changed")
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        val ready = active.prepare()
        if (ready.dirty) throw MirrorFailure("sync_required")
        checkpoints(workspaceId).finish(id, ready.worktree)
    }

    suspend fun recordCheckpointPost(workspaceId: String, id: String) = operations.withLock {
        if (selection.currentIdentity() != workspaceId) throw MirrorFailure("workspace_changed")
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        val ready = active.prepare()
        if (ready.dirty) throw MirrorFailure("sync_required")
        checkpoints(workspaceId).recordPost(id, ready.worktree)
    }

    suspend fun checkpointMatchesPost(workspaceId: String, id: String): Boolean = operations.withLock {
        if (selection.currentIdentity() != workspaceId) throw MirrorFailure("workspace_changed")
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        val ready = active.prepare()
        if (ready.dirty) throw MirrorFailure("sync_required")
        checkpoints(workspaceId).matchesPost(id, ready.worktree)
    }

    internal suspend fun checkpointManifest(
        workspaceId: String,
        id: String,
        scanLive: Boolean = false,
    ): Map<String, CheckpointEntry>? {
        val store = checkpoints(workspaceId)
        if (scanLive) {
            val worktree = currentMirror()?.worktree?.takeIf { it.isDirectory }
            if (worktree != null) {
                try {
                    return store.scanManifest(worktree)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        val record = store.record(id) ?: return null
        return record.after ?: record.before
    }

    internal suspend fun readWorktreeFileBytes(
        workspaceId: String,
        relativePath: String,
        maxBytes: Int = 1024 * 1024,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (selection.currentIdentity() != workspaceId) return@withContext null
        val parsed = try {
            WorkspacePath.parse(relativePath)
        } catch (_: WorkspaceFailure) {
            return@withContext null
        }
        if (parsed.isRoot) return@withContext null
        val root = currentMirror()?.worktree?.takeIf { it.isDirectory } ?: return@withContext null
        val rootCanonical = try {
            root.canonicalFile
        } catch (_: Exception) {
            return@withContext null
        }
        val file = try {
            File(rootCanonical, parsed.value).canonicalFile
        } catch (_: Exception) {
            return@withContext null
        }
        if (!file.toPath().startsWith(rootCanonical.toPath()) || file == rootCanonical) {
            return@withContext null
        }
        if (!file.isFile || file.length() > maxBytes.toLong()) return@withContext null
        try {
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun beginRunCheckpoint(workspaceId: String, runId: String, sequence: Int,
                                   summary: String, allowDirty: Boolean = false): String? =
        operations.withLock {
            if (selection.currentIdentity() != workspaceId) throw MirrorFailure("workspace_changed")
            val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
            val ready = active.prepare()
            if (ready.dirty && !allowDirty) throw MirrorFailure("sync_required")
            runCheckpoints(workspaceId).begin(ready.worktree, runId, sequence, summary)
        }

    suspend fun finishRunCheckpoint(workspaceId: String, id: String) {
        operations.withLock {
            if (selection.currentIdentity() != workspaceId) return@withLock
            val active = currentMirror() ?: return@withLock
            // A completed run is expected to leave local edits; that is the checkpoint's
            // reason to exist. prepare() still gives the canonical worktree path.
            val ready = active.prepare()
            runCheckpoints(workspaceId).finish(id, ready.worktree)
        }
    }

    suspend fun runCheckpointHistory(limit: Int = 100): List<RunCheckpointInfo> = operations.withLock {
        val identity = selection.currentIdentity() ?: return@withLock emptyList()
        runCheckpoints(identity).history(limit)
    }

    suspend fun restoreRunCheckpoint(
        record: RunCheckpointInfo,
        undoRun: Boolean = false,
    ): RunCheckpointRestoreResult =
        operations.withLock {
            if (selection.currentIdentity() != record.workspace) throw MirrorFailure("workspace_changed")
            val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
            var ready = active.prepare()
            val store = runCheckpoints(record.workspace)
            val runRecords = if (undoRun) store.forRun(record.runId).ifEmpty { listOf(record) } else listOf(record)
            val stageOwner = runRecords.first()
            suspend fun stageTarget(worktree: File): RunCheckpointRestore =
                if (undoRun) store.stageRestoreRun(runRecords, worktree) else store.stageRestore(stageOwner, worktree)
            if (ready.dirty) {
                // Settle non-overlapping work first. If a same-path disagreement
                // remains, still run the checkpoint comparison so the panel can list
                // the files that stopped the restore instead of failing silently.
                val synced = active.sync()
                if (synced.state == MirrorSync.Conflict || synced.state == MirrorSync.Failed) {
                    val staged = stageTarget(ready.worktree)
                    if (staged.conflicts.isNotEmpty()) {
                        return@withLock RunCheckpointRestoreResult(emptyList(), staged.conflicts)
                    }
                    store.discard(stageOwner)
                    throw MirrorFailure("sync_required", synced.path)
                }
                ready = active.prepare()
                if (ready.dirty) throw MirrorFailure("sync_required")
            }
            val staged = stageTarget(ready.worktree)
            if (staged.conflicts.isNotEmpty()) {
                return@withLock RunCheckpointRestoreResult(emptyList(), staged.conflicts)
            }
            try {
                active.installCheckpoint(staged.stage)
                val synced = active.sync()
                if (synced.state == MirrorSync.Conflict || synced.state == MirrorSync.Failed) {
                    throw MirrorFailure("sync_failed", synced.path)
                }
                runRecords.forEach { store.markRestored(it) }
                RunCheckpointRestoreResult(staged.restored, emptyList())
            } catch (error: Exception) {
                store.discard(stageOwner)
                throw error
            }
        }

    suspend fun runCheckpointPruned(): Int = operations.withLock {
        val identity = selection.currentIdentity() ?: return@withLock 0
        runCheckpoints(identity).lastPruned
    }

    suspend fun checkpointHistory(limit: Int = 50): List<CheckpointRecord> = operations.withLock {
        val identity = selection.currentIdentity() ?: return@withLock emptyList()
        checkpoints(identity).history(limit)
    }

    suspend fun latestCheckpoint(): CheckpointRecord? = operations.withLock {
        val identity = selection.currentIdentity() ?: return@withLock null
        checkpoints(identity).latest()
    }

    suspend fun markUndoBarDismissed(id: String) = operations.withLock {
        val identity = selection.currentIdentity() ?: throw MirrorFailure("workspace_unavailable")
        checkpoints(identity).markBarDismissed(id)
    }

    suspend fun checkpointTurnChanges(record: CheckpointRecord): List<CheckpointChange> = operations.withLock {
        if (selection.currentIdentity() != record.workspace) throw MirrorFailure("workspace_changed")
        checkpoints(record.workspace).changes(record)
    }

    suspend fun undoLastCheckpoint(): MirrorSyncResult = operations.withLock {
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        val identity = selection.currentIdentity() ?: throw MirrorFailure("workspace_unavailable")
        val ready = active.prepare()
        if (ready.dirty) throw MirrorFailure("sync_required")
        val store = checkpoints(identity)
        val record = store.latest() ?: throw CheckpointFailure("undo_unavailable")
        val staged = store.stageUndo(record, ready.worktree)
        try { active.installCheckpoint(staged) }
        catch (failure: Exception) {
            try { withContext(NonCancellable) { store.discardStagedUndo(record.id) } }
            catch (cleanup: CancellationException) { throw cleanup }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        val sync = try { active.sync() }
        catch (e: CancellationException) { throw e }
        catch (failure: Exception) {
            // Rivet's copy is restored but the project still holds the newer files.
            // Stop offering this Undo instead of repeating the same half-applied turn.
            try { withContext(NonCancellable) { store.markDesynced(record.id) } }
            catch (cleanup: CancellationException) { throw cleanup }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw CheckpointFailure("undo_desync")
        }
        if (sync.state == MirrorSync.Ok || sync.state == MirrorSync.NoChanges) store.markUndone(record.id)
        sync
    }

    private fun checkpoints(identity: String) = TurnCheckpoint(File(app.filesDir, "checkpoints"), identity)

    private fun runCheckpoints(identity: String) =
        RunCheckpointStore(File(app.filesDir, "run-checkpoints"), identity)

    private suspend fun gitInspection(): GitInspection {
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        val ready = active.prepare()
        if (ready.dirty) throw MirrorFailure("sync_required")
        return GitInspection(ready.worktree)
    }

    suspend fun currentIdentity(): String? = selection.currentIdentity()

    suspend fun commandBlocker(): String? = operations.withLock {
        if (managedProcesses?.canStartCommand() == false) return@withLock "process_limit"
        currentMirror() ?: return@withLock "workspace_unavailable"
        // A pending mirror file is a warning, not a command gate. runCommand
        // settles what it can and runs in the current worktree.
        null
    }

    fun clearCommandOutput(workspace: String) = outputCache.clearForWorkspace(workspace)

    fun cachedCommandOutput(id: String, workspace: String): CommandOutputCache.CachedOutput? {
        val cached = outputCache.get(id) ?: return null
        if (cached.workspace != workspace) return null
        return cached
    }

    suspend fun backgroundBlocker(): String? = operations.withLock {
        if (managedProcesses?.canStartBackground() == false) return@withLock "process_limit"
        if (managedProcesses?.canStartCommand() == false) return@withLock "process_limit"
        commandGate(Long.MAX_VALUE, governor.readings())?.let { return@withLock it }
        currentMirror() ?: return@withLock "workspace_unavailable"
        null
    }

    suspend fun spawnProcess(
        command: String,
        cwd: String,
        readyWaitMs: Long,
        readyPattern: String?,
    ): BackgroundLaunch {
        if (command.isBlank() || '\u0000' in command ||
            command.toByteArray(Charsets.UTF_8).size > 8_192) {
            throw MirrorFailure("invalid_arguments")
        }
        if (readyWaitMs !in 0L..10_000L) throw MirrorFailure("invalid_arguments")
        val pattern = readyPattern?.takeIf { it.isNotBlank() }
        if (pattern != null && pattern.length > 256) throw MirrorFailure("invalid_arguments")
        val launch: BackgroundPlan = operations.withLock {
            val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
            val readings = governor.readings()
            managedProcesses?.setActiveLimit(effectiveSlots(readings.thermalStatus))
            commandGate(Long.MAX_VALUE, readings)?.let { throw MirrorFailure(it) }
            if (managedProcesses?.canStartBackground() == false) throw MirrorFailure("process_limit")
            var ready = active.prepare()
            if (ready.dirty) {
                try { active.sync() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
                ready = active.prepare()
            }
            toolchain.ensurePrepared()
            val credentialFile = if (toolchain.gitReady && GitCommandAuth.needsAuth(command)) {
                withContext(Dispatchers.IO) { gitHub.refresh() }
            } else null
            val effectiveCommand = GitCommandAuth.authenticated(command, credentialFile)
            val directory = runtimeDirectory(ready.worktree, cwd)
            val environment = environment(active, directory)
            val identity = mirrorIdentity ?: throw MirrorFailure("workspace_unavailable")
            BackgroundPlan(identity, directory.absolutePath, environment, effectiveCommand, credentialFile)
        }
        val identity = launch.identity
        val directory = launch.directory
        val environment = launch.environment
        val effectiveCommand = launch.command
        val credentialFile = launch.credentialFile
        var job: Job? = null
        val registry = managedProcesses ?: throw MirrorFailure("runtime_unavailable")
        val id = registry.registerBackground(command, identity, projectName()) {
            job?.cancel(CancellationException("Stopped from Processes"))
        } ?: throw MirrorFailure("process_limit")
        val ring = ProcessLogRing().also { backgroundRings[id] = it }
        val runningJob = backgroundScope.launch {
            var outcome: CommandOutput? = null
            try {
                outcome = CommandProcess().run(
                    effectiveCommand, directory, environment, timeoutMs = null,
                    onOutput = { _, _ ->
                        val output = ring.snapshot()
                        registry.updateBackgroundOutput(id, output.stdout, output.stderr)
                    },
                    onChunk = ring::append,
                    memoryCapBytes = governor.memoryCapBytes(),
                )
            } catch (e: CancellationException) {
                registry.finishBackground(id, ManagedProcessStatus.Stopped, null)
                throw e
            } catch (_: Exception) {
                registry.finishBackground(id, ManagedProcessStatus.Failed, null)
            } finally {
                if (credentialFile != null) {
                    withContext(NonCancellable + Dispatchers.IO) { gitHub.clearCredentialFile() }
                }
                backgroundRings.remove(id)
                drainedRings[id] = ring
                drainedOrder.add(id)
                while (drainedRings.size > 8) {
                    drainedOrder.poll()?.let(drainedRings::remove)
                }
                val output = ring.snapshot()
                registry.updateBackgroundOutput(id, output.stdout, output.stderr)
                outcome?.let {
                    if (it.memoryLimit) governor.recordStop("memory limit")
                    else if (it.tooManyChildren) governor.recordStop("too many child processes")
                    registry.finishBackground(
                        id,
                        if (it.exitCode != 0 || it.memoryLimit || it.tooManyChildren) ManagedProcessStatus.Failed
                        else ManagedProcessStatus.Completed,
                        it.exitCode,
                    )
                }
                withContext(NonCancellable) {
                    syncTerminal(identity)?.let { registry.setProjectSync(id, it) }
                }
            }
        }
        job = runningJob
        if (registry.info(id, identity)?.status == ManagedProcessStatus.Stopping) {
            runningJob.cancel(CancellationException("Stopped before start"))
        }
        registry.markBackgroundRunning(id)
        if (!registry.ensureForegroundService(app)) {
            runningJob.cancel()
            registry.finishBackground(id, ManagedProcessStatus.Failed, null)
            throw MirrorFailure("foreground_service_unavailable")
        }
        val guard = backgroundScope.launch {
            selection.awaitIdentityChange(identity)
            job?.cancel(CancellationException("Workspace changed"))
        }
        runningJob.invokeOnCompletion { guard.cancel() }
        val deadline = System.currentTimeMillis() + readyWaitMs
        var matched = false
        while (System.currentTimeMillis() < deadline) {
            val snap = ring.snapshot()
            if (pattern == null || pattern in snap.stdout || pattern in snap.stderr) {
                matched = pattern != null || snap.stdout.isNotEmpty() || snap.stderr.isNotEmpty()
                if (matched) break
            }
            if (!runningJob.isActive) break
            kotlinx.coroutines.delay(50)
        }
        val info = registry.info(id, identity)
        val snap = ring.snapshot()
        val status = when (info?.status) {
            ManagedProcessStatus.Running, ManagedProcessStatus.Starting -> "running"
            ManagedProcessStatus.Completed -> "completed"
            ManagedProcessStatus.Stopped -> "stopped"
            ManagedProcessStatus.Failed -> "failed"
            else -> "running"
        }
        return BackgroundLaunch(
            id, status,
            snap.stdout.takeLast(ProcessLogRing.MAX_OUTPUT_CHARS),
            snap.stderr.takeLast(ProcessLogRing.MAX_OUTPUT_CHARS),
            snap.stderrPresent,
            if (pattern != null) matched else null,
            info?.exitCode,
        )
    }

    suspend fun outputFor(processId: String, workspace: String): ProcessOutputSnapshot? {
        val info = managedProcesses?.info(processId, workspace) ?: return null
        if (info.kind != ManagedProcessKind.Background) return null
        val ring = backgroundRings[processId] ?: drainedRings[processId]
        val snap = ring?.snapshot()
        return ProcessOutputSnapshot(
            processId,
            info.status.name.lowercase(),
            info.exitCode,
            snap?.stdout ?: info.output,
            snap?.stderr.orEmpty(),
            snap?.stderrPresent ?: false,
            stdoutStart = snap?.out?.start ?: 0,
            stderrStart = snap?.err?.start ?: 0,
            stdoutEnd = snap?.out?.end,
            stderrEnd = snap?.err?.end,
            sync = info.projectSync,
        )
    }

    suspend fun startPreview(
        workspace: SafWorkspace,
        expectedWorkspace: String,
        projectLabel: String?,
        root: String,
        entry: String,
    ): PreviewLaunch {
        val registry = managedProcesses ?: throw MirrorFailure("runtime_unavailable")
        if (selection.currentIdentity() != expectedWorkspace || workspace.tree.toString() != expectedWorkspace) {
            throw MirrorFailure("workspace_changed")
        }
        val paths = try { PreviewPaths.parse(root, entry) }
            catch (_: WorkspaceFailure) { throw MirrorFailure("invalid_path") }
            catch (_: IllegalArgumentException) { throw MirrorFailure("invalid_path") }
        val rootPath = paths.root
        val entryPath = paths.entry
        try {
            if (!workspace.stat(rootPath).directory) throw MirrorFailure("not_directory")
            val item = workspace.stat(entryPath)
            if (item.directory) throw MirrorFailure("not_file")
        } catch (e: CancellationException) { throw e
        } catch (e: WorkspaceFailure) { throw MirrorFailure(e.reason.name.lowercase()) }

        registry.stopForOtherWorkspaces(expectedWorkspace)
        val existing = registry.previewFor(expectedWorkspace, rootPath.value)
        if (existing?.url == null) registry.stopPreviewsForWorkspace(expectedWorkspace)
        if (existing?.url != null) {
            if (existing.entryPath == entryPath.value) {
                return PreviewLaunch(existing.id, existing.url, entryPath.value, reused = true)
            }
            return PreviewLaunch(
                existing.id,
                previewEntryUrl(existing.url, rootPath, entryPath),
                entryPath.value,
                reused = true,
            )
        }

        val server = LocalPreviewServer(
            rootPath,
            entryPath,
            workspaceIsCurrent = { selection.currentIdentity() == expectedWorkspace },
            stat = workspace::stat,
            copyTo = { path, output, limit -> workspace.copyFileTo(path, output, limit).size },
        )
        val url = try { server.start() }
            catch (e: CancellationException) { throw e
            } catch (_: Exception) { throw MirrorFailure("preview_unavailable") }
        // The thermal throttle is refreshed before the preview claims a slot, matching
        // the command path; it never stops an existing preview.
        registry.setActiveLimit(effectiveSlots(governor.readings().thermalStatus))
        val id = registry.registerPreview(
            expectedWorkspace, projectLabel, entryPath.value, url,
            rootPath = rootPath.value, stop = server::close,
        ) ?: run { server.close(); throw MirrorFailure("process_limit") }
        server.onWorkspaceInvalidated { registry.stop(id) }
        try {
            if (!registry.ensureForegroundService(app)) throw MirrorFailure("foreground_service_unavailable")
            registry.markPreviewRunning(id)
            return PreviewLaunch(id, url, entryPath.value, reused = false)
        } catch (e: CancellationException) {
            registry.stop(id)
            throw e
        } catch (e: MirrorFailure) {
            registry.stop(id)
            throw e
        } catch (_: Exception) {
            registry.stop(id)
            throw MirrorFailure("foreground_service_unavailable")
        }
    }

    fun startTurn(onStop: () -> Unit = {}): String? =
        managedProcesses?.startTurn(app, onStop)

    fun finishTurn(id: String?) {
        if (id != null) managedProcesses?.finishTurn(id)
    }

    suspend fun retryPendingChanges(expectedWorkspace: String): MirrorSyncResult? = operations.withLock {
        if (selection.currentIdentity() != expectedWorkspace) return@withLock null
        currentMirror()?.sync()
    }

    suspend fun discardPendingChanges(expectedWorkspace: String): MirrorReady = operations.withLock {
        if (selection.currentIdentity() != expectedWorkspace) throw MirrorFailure("workspace_changed")
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        if (mirrorIdentity != expectedWorkspace || selection.currentIdentity() != expectedWorkspace) {
            throw MirrorFailure("workspace_changed")
        }
        active.discardPendingChanges()
    }

    suspend fun awaitIdentityChange(identity: String) = selection.awaitIdentityChange(identity)

    private suspend fun currentMirror(): WorkspaceMirror? {
        val selected = selection.restore()?.first ?: return null
        val identity = selected.tree.toString()
        if (selection.currentIdentity() != identity) throw MirrorFailure("workspace_changed")
        if (mirrorIdentity != identity || mirror == null) {
            mirrorIdentity = identity
            mirror = WorkspaceMirror(app, selected) { selection.currentIdentity() == identity }
        }
        return mirror
    }

    private fun environment(mirror: WorkspaceMirror, cwd: File): Array<String> {
        val home = mirror.home.apply { mkdirs() }
        val temporary = mirror.temporary.apply { mkdirs() }
        if (!home.isDirectory || !temporary.isDirectory) throw MirrorFailure("storage")
        return toolchain.commandEnvironment(
            baseEnvironment(home, toolchain.path(), temporary, cwd),
        ).toTypedArray()
    }

    private fun runtimeDirectory(worktree: File, cwd: String): File {
        val path = WorkspacePath.parse(cwd)
        val target = File(worktree, path.value).canonicalFile
        val root = worktree.canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) throw MirrorFailure("invalid_cwd")
        if (!target.isDirectory) throw MirrorFailure("invalid_cwd")
        return target
    }
}

internal const val SYSTEM_SHELL = "/system/bin/sh"

/** The BusyBox ash symlink when the farm is real, otherwise the platform shell. */
internal fun terminalShellPath(binDir: File?): String =
    binDir?.let { File(it, "ash") }?.takeIf { it.isFile }?.absolutePath ?: SYSTEM_SHELL

/** TERM, non-interactive pagers, and a folder-only, dollar prompt. */
internal fun terminalEnvironment(base: List<String>): List<String> {
    // No startup rc: Android's mksh rc can overwrite PS1 with its own login prompt.
    // Git defaults to a pager when stdout is a TTY; Android has no less(1), so pin cat.
    val kept = base.filterNot {
        it.startsWith("TERM=") || it.startsWith("PS1=") || it.startsWith("ENV=") ||
            it.startsWith("PAGER=") || it.startsWith("GIT_PAGER=") || it.startsWith("MANPAGER=")
    }
    return kept + "PAGER=cat" + "GIT_PAGER=cat" + "MANPAGER=cat" + "ENV=/dev/null" +
        "TERM=xterm-256color" + "PS1=${TerminalPrompt.PS1}"
}
