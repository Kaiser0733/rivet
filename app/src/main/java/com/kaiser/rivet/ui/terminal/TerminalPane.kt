package com.kaiser.rivet.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.chat.TerminalUiState

// The terminal is its own surface in both themes, like a terminal emulator window.
private val Pane = Color(0xFF151517)
private val PaneRaised = Color(0xFF1D1D20)
private val PaneLine = Color(0xFF2C2C30)
private val Ink = Color(0xFFE7E7E9)
private val Muted = Color(0xFF9A9AA2)
private val Glyph = Color(0xFF9FB4D0)

private data class TerminalRun(val command: String, val output: String, val status: String? = null)

/**
 * The user terminal: one command at a time in the session workspace, the same runtime the
 * agent's run_command uses. State lives in the view model, so this pane only renders it.
 *
 * This is a terminal transcript, not a shell: commands are echoed when a run starts, the
 * captured streams follow, and the only prompt glyph is a plain ">" — never a user, host,
 * or path prompt.
 */
@Composable
fun TerminalPane(
    state: TerminalUiState,
    workspaceName: String?,
    stateLine: String,
    onRun: (String) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var command by rememberSaveable { mutableStateOf(state.command) }
    val canRun = workspaceName != null && command.isNotBlank() && !state.running
    // A run starts from this field or from the keyboard; clear the draft either way so
    // the next command starts empty.
    LaunchedEffect(state.running) { if (state.running) command = "" }
    val runs = remember { mutableStateListOf<TerminalRun>() }
    var wasRunning by remember { mutableStateOf(false) }
    // Echo each run once. A run starts when running rises; a reopened pane seeds the
    // current (last) run from the view-model state instead of inventing history.
    LaunchedEffect(state.running, state.command) {
        if (runs.isEmpty() && state.command.isNotBlank()) {
            runs += TerminalRun(state.command, state.output,
                if (state.running) null else state.status)
        } else if (state.running && !wasRunning && state.command.isNotBlank()) {
            runs += TerminalRun(state.command, state.output)
        }
        if (!state.running && wasRunning && runs.isNotEmpty()) {
            runs[runs.lastIndex] = runs.last().copy(status = state.status)
        }
        wasRunning = state.running
    }
    LaunchedEffect(state.output) {
        if (runs.isNotEmpty() && runs.last().output != state.output) {
            runs[runs.lastIndex] = runs.last().copy(output = state.output)
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    LaunchedEffect(state.output) { scroll.scrollTo(scroll.maxValue) }
    val submit = {
        if (canRun) {
            onRun(command)
            command = ""
        }
    }

    Column(modifier.fillMaxSize().background(Pane)) {
        // Slim tab header: back, the one live state line as the tab, and a ghost Stop.
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_back), "Back to tools", tint = Muted)
            }
            Surface(
                modifier = Modifier.weight(1f).height(30.dp),
                color = PaneRaised,
                shape = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp),
                border = BorderStroke(1.dp, PaneLine),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(6.dp).background(
                        if (state.running) Glyph else Muted, CircleShape))
                    Text(
                        stateLine,
                        color = Ink,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.running) {
                TextButton(
                    onClick = onStop,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Stop", color = Muted,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace))
                }
            }
        }
        HorizontalDivider(color = PaneLine)

        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (workspaceName == null) {
                Text(
                    "No workspace is selected. Choose a project in the chat first.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            } else if (runs.isEmpty()) {
                Text(
                    "Terminal ready.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (run in runs) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(">", color = Muted,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace))
                            Spacer(Modifier.size(6.dp))
                            Text(
                                run.command,
                                modifier = Modifier.weight(1f),
                                color = Ink,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace),
                            )
                        }
                        if (run.output.isNotEmpty()) {
                            Text(
                                run.output.trimEnd('\n'),
                                color = Ink,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace),
                            )
                        } else if (run === runs.last() && state.running) {
                            Text(
                                "…",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace),
                            )
                        }
                        if (run.status != null) {
                            Text(
                                run.status,
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = PaneLine)
        Row(
            Modifier.fillMaxWidth().background(PaneRaised)
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(">", color = Glyph,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            Spacer(Modifier.size(8.dp))
            BasicTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                enabled = workspaceName != null,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = if (workspaceName != null) Ink else Muted,
                ),
                cursorBrush = SolidColor(Glyph),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { inner ->
                    Box {
                        if (command.isEmpty()) {
                            Text("Type a command", color = Muted,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace))
                        }
                        inner()
                    }
                },
            )
            IconButton(
                onClick = submit,
                enabled = canRun,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(painterResource(R.drawable.ic_send), "Run",
                    tint = if (canRun) Glyph else Muted.copy(alpha = 0.45f))
            }
        }
    }
}
