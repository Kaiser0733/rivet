package com.kaiser.rivet.runtime

import android.content.Context
import com.kaiser.rivet.workspace.WorkspacePath
import com.kaiser.rivet.workspace.WorkspaceSelection
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    val error: String? = null,
)

class RuntimeController(context: Context, private val selection: WorkspaceSelection = WorkspaceSelection(context.applicationContext)) {
    private val app = context.applicationContext
    private val operations = Mutex()
    private var mirrorIdentity: String? = null
    private var mirror: WorkspaceMirror? = null

    suspend fun runCommand(command: String, cwd: String, timeoutMs: Long): RuntimeCommandResult = operations.withLock {
        val active = currentMirror() ?: return@withLock RuntimeCommandResult(cwd = cwd, sync = "not_started", error = "workspace_unavailable")
        val ready = active.prepare()
        if (ready.dirty) {
            return@withLock RuntimeCommandResult(cwd = cwd, sync = "pending", error = "sync_required")
        }
        val directory = runtimeDirectory(ready.worktree, cwd)
        val environment = environment(active, directory)
        val result = CommandProcess().run(command, directory.absolutePath, environment, timeoutMs)
        val sync = try { active.sync() }
            catch (_: CancellationException) {
                // The command already ran. Return its exit status with an
                // interrupted sync so the correlated event can be persisted.
                return@withLock RuntimeCommandResult(result.exitCode, result.stdout, result.stderr,
                    result.stdoutTruncated, result.stderrTruncated, result.timedOut, cwd,
                    "interrupted")
            } catch (e: Exception) { MirrorSyncResult(MirrorSync.Failed) }
        RuntimeCommandResult(result.exitCode, result.stdout, result.stderr,
            result.stdoutTruncated, result.stderrTruncated, result.timedOut, cwd,
            sync.state.code, sync.path)
    }

    suspend fun requireSafCurrent() = operations.withLock {
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        if (active.hasLocalChanges()) throw MirrorFailure("sync_required")
    }

    suspend fun gitStatus(): RepositoryStatus = operations.withLock {
        gitInspection().status()
    }

    suspend fun gitDiff(path: String): RepositoryDiff = operations.withLock {
        gitInspection().diff(path)
    }

    suspend fun beginCheckpoint(workspaceId: String): String = operations.withLock {
        if (selection.currentIdentity() != workspaceId) throw MirrorFailure("workspace_changed")
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        val ready = active.prepare()
        if (ready.dirty) throw MirrorFailure("sync_required")
        checkpoints(workspaceId).begin(ready.worktree)
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

    suspend fun latestCheckpoint(): CheckpointRecord? = operations.withLock {
        val identity = selection.currentIdentity() ?: return@withLock null
        checkpoints(identity).latest()
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
        val sync = active.sync()
        if (sync.state == MirrorSync.Ok || sync.state == MirrorSync.NoChanges) store.markUndone(record.id)
        sync
    }

    private fun checkpoints(identity: String) = TurnCheckpoint(File(app.filesDir, "checkpoints"), identity)

    private suspend fun gitInspection(): GitInspection {
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        val ready = active.prepare()
        if (ready.dirty) throw MirrorFailure("sync_required")
        return GitInspection(ready.worktree)
    }

    suspend fun currentIdentity(): String? = selection.currentIdentity()

    suspend fun agentFailureState(): String = operations.withLock {
        val identity = selection.currentIdentity() ?: return@withLock ":workspace_unavailable"
        val dirty = try { currentMirror()?.hasLocalChanges() == true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        "$identity:${if (dirty) "sync_required" else "ready"}"
    }

    suspend fun commandBlocker(): String? = operations.withLock {
        val active = currentMirror() ?: return@withLock "workspace_unavailable"
        if (active.hasLocalChanges()) "sync_required" else null
    }

    suspend fun retryPendingChanges(expectedWorkspace: String): MirrorSyncResult? = operations.withLock {
        if (selection.currentIdentity() != expectedWorkspace) return@withLock null
        currentMirror()?.sync()
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
        return arrayOf(
            "HOME=${home.absolutePath}",
            "PATH=/system/bin:/system/xbin:/vendor/bin",
            "TMPDIR=${temporary.absolutePath}",
            "PWD=${cwd.absolutePath}",
            "LANG=C.UTF-8",
            "TERM=xterm-256color",
        )
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
