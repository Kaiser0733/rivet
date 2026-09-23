package com.kaiser.rivet.runtime

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TerminalUiState(
    val session: TerminalSession? = null,
    val preparing: Boolean = false,
    val active: Boolean = false,
    val dirty: Boolean = false,
    val render: Long = 0,
    val notice: String? = null,
)

class TerminalViewModel(application: Application) : AndroidViewModel(application), TerminalSessionClient {
    val controller = RuntimeController(application)
    private val mutable = MutableStateFlow(TerminalUiState())
    val state = mutable.asStateFlow()
    private var watchWorkspace: Job? = null

    init {
        viewModelScope.launch {
            try { if (controller.terminalDirty()) mutable.update { it.copy(dirty = true) } }
            catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutable.update { it.copy(dirty = true) } }
        }
    }

    fun start() {
        if (mutable.value.active || mutable.value.preparing) return
        mutable.update { it.copy(preparing = true, notice = null) }
        viewModelScope.launch {
            try {
                val identity = controller.currentIdentity()
                    ?: throw MirrorFailure("workspace_unavailable")
                val session = controller.startTerminal(this@TerminalViewModel)
                val dirty = controller.terminalDirty()
                mutable.update { it.copy(session = session, preparing = false, active = true, dirty = dirty) }
                watchWorkspace?.cancel()
                watchWorkspace = viewModelScope.launch {
                    controller.awaitIdentityChange(identity)
                    controller.invalidateTerminal()
                    mutable.update { it.copy(notice = "Workspace changed. The shell was stopped.") }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutable.update { it.copy(preparing = false, notice = terminalError(e)) }
            }
        }
    }

    fun stop() {
        val session = mutable.value.session ?: return
        if (session.isRunning) controller.stopTerminal()
        else {
            controller.terminalFinished(session)
            watchWorkspace?.cancel()
            mutable.update { it.copy(session = null, active = false, notice = "Shell stopped before startup.") }
        }
    }

    fun startupFailed(session: TerminalSession, error: Exception) {
        controller.stopTerminal()
        controller.terminalFinished(session)
        watchWorkspace?.cancel()
        mutable.update { it.copy(session = null, active = false, notice = terminalError(error)) }
    }

    fun controlC() {
        mutable.value.session?.takeIf { mutable.value.active }?.write(byteArrayOf(3), 0, 1)
    }

    fun markInput() { mutable.update { it.copy(dirty = true) } }

    fun sync() {
        if (mutable.value.active || mutable.value.preparing) {
            mutable.update { it.copy(notice = "Stop the shell before syncing workspace changes.") }
            return
        }
        mutable.update { it.copy(preparing = true, notice = null) }
        viewModelScope.launch {
            try {
                val result = controller.syncTerminal()
                mutable.update { it.copy(preparing = false,
                    dirty = result.state != MirrorSync.Ok && result.state != MirrorSync.NoChanges,
                    notice = when (result.state) {
                        MirrorSync.Ok -> "Workspace changes synced."
                        MirrorSync.NoChanges -> "No workspace changes to sync."
                        MirrorSync.Conflict -> "Workspace changed outside Rivet. Mirror changes are preserved; review ${result.path ?: "the workspace"}."
                        MirrorSync.Failed -> "Sync failed. Mirror changes are preserved; review ${result.path ?: "the workspace"}."
                    }) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutable.update { it.copy(preparing = false, dirty = true, notice = terminalError(e)) }
            }
        }
    }

    private fun terminalError(error: Exception): String = when (error) {
        is MirrorFailure -> when (error.code) {
            "workspace_unavailable" -> "Select a workspace in Files before opening Terminal."
            "workspace_changed" -> "Workspace changed. Open Terminal again for the selected project."
            "mirror_dirty" -> "Unsynced mirror changes were preserved. Sync before refreshing this workspace."
            else -> "Runtime workspace unavailable (${error.code})."
        }
        else -> "Terminal could not start (${error.javaClass.simpleName})."
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        mutable.update { it.copy(render = it.render + 1) }
    }
    override fun onTitleChanged(changedSession: TerminalSession) {
        mutable.update { it.copy(render = it.render + 1) }
    }
    override fun onSessionFinished(finishedSession: TerminalSession) {
        controller.terminalFinished(finishedSession)
        watchWorkspace?.cancel()
        mutable.update { it.copy(active = false, render = it.render + 1, notice = it.notice ?: "Shell exited. Sync workspace changes before starting another shell.") }
        viewModelScope.launch {
            try { if (controller.terminalDirty()) mutable.update { it.copy(dirty = true) } }
            catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutable.update { it.copy(dirty = true) } }
        }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(getApplication())?.toString() ?: return
        session?.write(text)
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) { onTextChanged(session) }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, error: Exception) = Unit
    override fun logStackTrace(tag: String, error: Exception) = Unit

    override fun onCleared() {
        watchWorkspace?.cancel()
        controller.stopTerminal()
        super.onCleared()
    }
}
