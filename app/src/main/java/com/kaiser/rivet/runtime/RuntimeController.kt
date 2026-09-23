package com.kaiser.rivet.runtime

import android.content.Context
import com.kaiser.rivet.workspace.WorkspacePath
import com.kaiser.rivet.workspace.WorkspaceSelection
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

class RuntimeController(context: Context) {
    private val app = context.applicationContext
    private val selection = WorkspaceSelection(app)
    private val operations = Mutex()
    private var mirrorIdentity: String? = null
    private var mirror: WorkspaceMirror? = null
    @Volatile private var terminal: TerminalSession? = null

    suspend fun runCommand(command: String, cwd: String, timeoutMs: Long): RuntimeCommandResult = operations.withLock {
        if (terminal != null) return@withLock RuntimeCommandResult(cwd = cwd, sync = "not_started", error = "terminal_active")
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

    suspend fun startTerminal(client: TerminalSessionClient): TerminalSession = operations.withLock {
        terminal?.let { return@withLock it }
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        val ready = active.prepare()
        val root = ready.worktree
        val session = TerminalSession("/system/bin/sh", root.absolutePath,
            arrayOf("/system/bin/sh", "-l"), environment(active, root), 2000, client)
        terminal = session
        session
    }

    suspend fun syncTerminal(): MirrorSyncResult = operations.withLock {
        if (terminal != null) throw MirrorFailure("terminal_active")
        val active = currentMirror() ?: throw MirrorFailure("workspace_unavailable")
        active.sync()
    }

    suspend fun terminalDirty(): Boolean = operations.withLock {
        val active = currentMirror() ?: return@withLock false
        active.hasLocalChanges()
    }

    suspend fun requireSafCurrent() = operations.withLock {
        val active = currentMirror() ?: return@withLock
        if (active.hasLocalChanges()) throw MirrorFailure("sync_required")
    }

    fun stopTerminal() { terminal?.finishIfRunning() }

    fun terminalFinished(session: TerminalSession) {
        if (terminal === session) terminal = null
    }

    fun invalidateTerminal() { stopTerminal() }

    suspend fun currentIdentity(): String? = selection.currentIdentity()

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
