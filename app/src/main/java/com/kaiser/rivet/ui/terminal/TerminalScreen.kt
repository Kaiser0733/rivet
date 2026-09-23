package com.kaiser.rivet.ui.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.runtime.TerminalViewModel
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

@Composable
fun TerminalScreen(viewModel: TerminalViewModel) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.start() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.active) {
                OutlinedButton(onClick = viewModel::stop) { Text("Stop shell") }
                OutlinedButton(onClick = viewModel::controlC) { Text("Ctrl-C") }
            } else {
                Button(onClick = viewModel::start, enabled = !state.preparing) { Text("Start shell") }
                OutlinedButton(onClick = viewModel::sync, enabled = !state.preparing) { Text("Sync") }
            }
        }
        Text(
            if (state.dirty) "Workspace mirror may have unsynced changes. Stop the shell, then Sync."
            else "Terminal works in Rivet's private workspace mirror. Sync writes changes to Files.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val session = state.session
        if (session != null) {
            AndroidView(
                factory = { context ->
                    TerminalView(context, null).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTerminalViewClient(TerminalInputClient(this, viewModel::markInput))
                        setTextSize(15)
                        try { attachSession(session) }
                        catch (error: Exception) { viewModel.startupFailed(session, error) }
                        requestFocus()
                    }
                },
                update = { view ->
                    if (view.mTermSession !== session) {
                        try { view.attachSession(session) }
                        catch (error: Exception) { viewModel.startupFailed(session, error) }
                    }
                    // Capture the screen revision so AndroidView redraws when
                    // the session client reports output without replacing the view.
                    view.tag = state.render
                    view.onScreenUpdated()
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

private class TerminalInputClient(
    private val view: TerminalView,
    private val input: () -> Unit,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(event: MotionEvent) {
        view.requestFocus()
        val keyboard = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession): Boolean {
        input()
        return false
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        input()
        return false
    }
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, error: Exception) = Unit
    override fun logStackTrace(tag: String, error: Exception) = Unit
}
