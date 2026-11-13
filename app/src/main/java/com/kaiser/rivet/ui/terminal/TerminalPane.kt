package com.kaiser.rivet.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiser.rivet.R
import com.kaiser.rivet.runtime.InteractiveTerminalSession
import com.kaiser.rivet.runtime.InteractiveTerminalState
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle as TerminalTextStyle
import kotlin.math.roundToInt

private val Pane = Color(0xFF0B0B0D)
private val PaneRaised = Color(0xFF17171A)
private val PaneLine = Color(0xFF2A2A2E)
private val Ink = Color(0xFFE8E8EA)
private val Muted = Color(0xFF9A9AA2)
private val Glyph = Color(0xFF9FB4D0)
private val Good = Color(0xFF86C9A4)

private val TerminalFontSize = 13.sp
private val TerminalLineHeight = 17.sp

/**
 * The interactive shell. It has exactly one input: the terminal surface and its hidden
 * IME anchor. The only prompt characters are the shell's own PS1 ("<folder> $ ") and the
 * extra-key bar's labels; Rivet never prints a login or absolute-path prompt.
 */
@Composable
internal fun TerminalPane(
    session: InteractiveTerminalSession,
    stateLine: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()
    Column(modifier.fillMaxSize().background(Pane).navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_back), "Back to chat", tint = Muted)
            }
            Surface(
                modifier = Modifier.weight(1f).height(30.dp),
                color = PaneRaised,
                shape = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PaneLine),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(6.dp).background(
                        if (state.running) Good else Muted, CircleShape))
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
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(painterResource(R.drawable.ic_close), "Hide terminal", tint = Muted)
            }
        }
        HorizontalDivider(color = PaneLine)
        if (state.ended) {
            EndedTerminal(session, state, Modifier.weight(1f))
        } else {
            TerminalArea(session, state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EndedTerminal(
    session: InteractiveTerminalSession,
    state: InteractiveTerminalState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            terminalErrorMessage(state.error) ?: "Terminal ended.",
            color = Ink,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        state.exitCode?.let {
            Spacer(Modifier.size(6.dp))
            Text("Exit $it", color = Muted,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
        Spacer(Modifier.size(14.dp))
        TextButton(onClick = session::restart) {
            Text("Restart", color = Glyph,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

private fun terminalErrorMessage(code: String?): String? = when (code) {
    null -> null
    "workspace_unavailable" -> "No workspace is selected. Choose a project in the chat first."
    "process_limit" -> "No process slot is free. Stop a running process and try again."
    "sync_required" -> "The workspace has conflicting changes. Resolve them before opening a terminal."
    "sync_timeout" -> "The workspace took too long to sync. The terminal is usable again; try once more."
    "sync_failed" -> "The workspace couldn't finish syncing. The terminal is usable again; try once more."
    "thermal_blocked" -> "The phone is too hot. Wait for it to cool before opening a terminal."
    "battery_low" -> "Battery is low — terminal paused for long jobs. Charge the phone or try again."
    else -> "The terminal could not start. Try again."
}

@OptIn(ExperimentalTextApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun TerminalArea(
    session: InteractiveTerminalSession,
    state: InteractiveTerminalState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val terminalStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = TerminalFontSize,
            lineHeight = TerminalLineHeight,
            color = Ink,
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val cellWidthPx = remember(textMeasurer, terminalStyle, density) {
        textMeasurer.measure("M", terminalStyle).size.width.toFloat().coerceAtLeast(1f)
    }
    val cellHeightPx = remember(terminalStyle, density) {
        with(density) { TerminalLineHeight.toPx() }.coerceAtLeast(1f)
    }
    var ctrl by rememberSaveable { mutableStateOf(false) }
    var alt by rememberSaveable { mutableStateOf(false) }
    var imeText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun focusInput() {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(state.running) { if (state.running) focusInput() }
    Column(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val (columns, rows) = terminalGridSize(widthPx, heightPx,
                cellWidthPx, cellHeightPx)
            LaunchedEffect(columns, rows, cellWidthPx, cellHeightPx) {
                // A restored Tools slide may outlive a process recreation. Opening here
                // also covers the effect-order case where the card already set the flag;
                // a running session treats open() as a no-op.
                if (!state.ended) session.open()
                session.ensureStarted(columns, rows,
                    cellWidthPx.roundToInt().coerceAtLeast(1),
                    cellHeightPx.roundToInt().coerceAtLeast(1))
            }
            val emulator = session.emulator
            if (emulator == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Starting terminal…", color = Muted,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace))
                }
            } else {
                TerminalGrid(
                    session = session,
                    emulator = emulator,
                    requestedColumns = columns,
                    visibleRows = rows,
                    terminalStyle = terminalStyle,
                    imeText = imeText,
                    onImeText = { imeText = it },
                    ctrl = ctrl,
                    alt = alt,
                    onCtrl = { ctrl = it },
                    onAlt = { alt = it },
                    focusRequester = focusRequester,
                    keyboard = keyboard,
                )
            }
        }
        if (session.emulator != null) {
            HorizontalDivider(color = PaneLine)
            ExtraKeysBar(
                session = session,
                ctrl = ctrl,
                alt = alt,
                onToggleCtrl = { ctrl = !ctrl },
                onToggleAlt = { alt = !alt },
                onConsumeModifiers = { ctrl = false; alt = false },
                focusRequester = focusRequester,
                keyboard = keyboard,
                onClearInput = { imeText = "" },
            )
        }
    }
}

@OptIn(ExperimentalTextApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun TerminalGrid(
    session: InteractiveTerminalSession,
    emulator: TerminalEmulator,
    requestedColumns: Int,
    visibleRows: Int,
    terminalStyle: TextStyle,
    imeText: String,
    onImeText: (String) -> Unit,
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: (Boolean) -> Unit,
    onAlt: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
) {
    val revision by session.revision.collectAsState()
    val buffer = emulator.screen
    val columns = requestedColumns.coerceAtMost(emulator.columns).coerceAtLeast(1)
    val transcriptRows = buffer.activeTranscriptRows
    val totalRows = buffer.activeRows
    val cursorIndex = transcriptRows + emulator.cursorRow
    val colors = emulator.mColors.mCurrentColors
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    fun focusInput() {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    fun applyIme(new: String) {
        val old = imeText
        val common = old.commonPrefixWith(new).length
        repeat((old.length - common).coerceAtLeast(0)) { session.write("\u007f") }
        val added = new.substring(common)
        val usedModifier = ctrl || alt
        if (added.isNotEmpty()) {
            session.write(terminalText(added, ctrl, alt))
            if (usedModifier) {
                onCtrl(false)
                onAlt(false)
            }
        }
        val submitted = added.any { it == '\n' || it == '\r' }
        onImeText(if (submitted || usedModifier) "" else new)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling) follow = !canForward
                else if (!canForward) follow = true
            }
    }
    LaunchedEffect(revision, totalRows, cursorIndex, visibleRows) {
        // The screen already fits the viewport unless the IME shrinks visibleRows below cursorIndex.
        TerminalScrollPolicy.cursorScrollTargetRow(
            follow = follow,
            transcriptRows = transcriptRows,
            totalRows = totalRows,
            cursorIndex = cursorIndex,
            visibleRows = visibleRows,
        )?.let { target ->
            listState.scrollToItem(target)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focusInput() },
        ) {
            items(count = totalRows, key = { it }) { index ->
                TerminalLine(
                    buffer = buffer,
                    externalRow = index - transcriptRows,
                    columns = columns,
                    cursorColumn = if (index == cursorIndex) emulator.cursorCol else -1,
                    colors = colors,
                    terminalStyle = terminalStyle,
                    revision = revision,
                )
            }
        }
        BasicTextField(
            value = imeText,
            onValueChange = { new -> applyIme(new) },
            modifier = Modifier.align(Alignment.BottomStart)
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val native = event.nativeKeyEvent
                    val ctrlNow = ctrl || native.isCtrlPressed
                    val altNow = alt || native.isAltPressed
                    val keyMode = (if (ctrlNow) KeyHandler.KEYMOD_CTRL else 0) or
                        (if (altNow) KeyHandler.KEYMOD_ALT else 0) or
                        (if (native.isShiftPressed) KeyHandler.KEYMOD_SHIFT else 0)
                    val special = KeyHandler.getCode(native.keyCode, keyMode,
                        emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
                    if (special != null) {
                        session.write(special)
                        onCtrl(false)
                        onAlt(false)
                        onImeText("")
                        return@onPreviewKeyEvent true
                    }
                    val printable = native.unicodeChar.takeIf { it != 0 }?.toChar()
                        ?: printableKey(native.keyCode)
                    if ((ctrlNow || altNow) && printable != null) {
                        session.write(terminalText(printable.toString(), ctrlNow, altNow))
                        onCtrl(false)
                        onAlt(false)
                        onImeText("")
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            textStyle = terminalStyle.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(autoCorrect = false,
                imeAction = androidx.compose.ui.text.input.ImeAction.Default),
        )
    }
}

@Composable
private fun TerminalLine(
    buffer: TerminalBuffer,
    externalRow: Int,
    columns: Int,
    cursorColumn: Int,
    colors: IntArray,
    terminalStyle: TextStyle,
    revision: Long,
) {
    val line = remember(revision, externalRow, columns, cursorColumn) {
        buildTerminalLine(buffer, externalRow, columns, cursorColumn, colors)
    }
    Text(
        line,
        modifier = Modifier.fillMaxWidth(),
        style = terminalStyle,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

private fun buildTerminalLine(
    buffer: TerminalBuffer,
    externalRow: Int,
    columns: Int,
    cursorColumn: Int,
    colors: IntArray,
): AnnotatedString {
    val firstRow = -buffer.activeTranscriptRows
    val lastRow = buffer.activeRows - buffer.activeTranscriptRows - 1
    if (lastRow < firstRow) return AnnotatedString("")
    val row = buffer.getLine(externalRow.coerceIn(firstRow, lastRow))
    val defaultForeground = terminalColor(
        TerminalTextStyle.COLOR_INDEX_FOREGROUND, colors)
    val defaultBackground = terminalColor(
        TerminalTextStyle.COLOR_INDEX_BACKGROUND, colors)
    val cursorColor = terminalColor(TerminalTextStyle.COLOR_INDEX_CURSOR, colors)
    return buildAnnotatedString {
        for (column in 0 until columns) {
            val text = row.getCellText(column)
            if (text.isEmpty()) {
                if (column == cursorColumn) {
                    withStyle(SpanStyle(color = defaultBackground, background = cursorColor)) {
                        append(' ')
                    }
                }
                continue
            }
            val style = row.getStyle(column)
            val effect = TerminalTextStyle.decodeEffect(style)
            val inverse = effect and TerminalTextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0
            var foreground = terminalColor(
                TerminalTextStyle.decodeForeColor(style), colors, defaultForeground)
            var background = terminalColor(
                TerminalTextStyle.decodeBackColor(style), colors, defaultBackground)
            if (inverse) {
                val swap = foreground
                foreground = background
                background = swap
            }
            if (column == cursorColumn) {
                foreground = background
                background = cursorColor
            }
            if (effect and TerminalTextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0) {
                foreground = background
            }
            if (effect and TerminalTextStyle.CHARACTER_ATTRIBUTE_DIM != 0) {
                foreground = foreground.copy(alpha = 0.65f)
            }
            var span = SpanStyle(color = foreground)
            if (column == cursorColumn || background != defaultBackground) {
                span = span.copy(background = background)
            }
            if (effect and TerminalTextStyle.CHARACTER_ATTRIBUTE_BOLD != 0) {
                span = span.copy(fontWeight = FontWeight.Bold)
            }
            if (effect and TerminalTextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) {
                span = span.copy(fontStyle = FontStyle.Italic)
            }
            val decorations = buildList {
                if (effect and TerminalTextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0) {
                    add(TextDecoration.Underline)
                }
                if (effect and TerminalTextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0) {
                    add(TextDecoration.LineThrough)
                }
            }
            if (decorations.isNotEmpty()) {
                span = span.copy(textDecoration = TextDecoration.combine(decorations))
            }
            withStyle(span) { append(if (text.isEmpty()) " " else text) }
        }
    }
}

private fun terminalColor(value: Int, colors: IntArray,
                          fallback: Color = Color.White): Color {
    if (value < 0) return Color(value) // truecolor has the alpha bit set
    return colors.getOrNull(value)?.let { Color(it) } ?: fallback
}

private fun printableKey(keyCode: Int): Char? = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
        ('a'.code + keyCode - KeyEvent.KEYCODE_A).toChar()
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
        ('0'.code + keyCode - KeyEvent.KEYCODE_0).toChar()
    KeyEvent.KEYCODE_SPACE -> ' '
    else -> null
}

@Composable
private fun ExtraKeysBar(
    session: InteractiveTerminalSession,
    ctrl: Boolean,
    alt: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onConsumeModifiers: () -> Unit,
    focusRequester: FocusRequester,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onClearInput: () -> Unit,
) {
    val scroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    fun focus() {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    fun writeKey(keyCode: Int) {
        val keyMode = (if (ctrl) KeyHandler.KEYMOD_CTRL else 0) or
            (if (alt) KeyHandler.KEYMOD_ALT else 0)
        KeyHandler.getCode(keyCode, keyMode,
            session.emulator?.isCursorKeysApplicationMode == true,
            session.emulator?.isKeypadApplicationMode == true)
            ?.let(session::write)
        onConsumeModifiers()
        onClearInput()
        focus()
    }
    Row(
        Modifier.fillMaxWidth().background(PaneRaised)
            .horizontalScroll(scroll)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraKey("ESC") { writeKey(KeyEvent.KEYCODE_ESCAPE) }
        ExtraKey("TAB") { writeKey(KeyEvent.KEYCODE_TAB) }
        ExtraKey("CTRL", active = ctrl) { onToggleCtrl(); focus() }
        ExtraKey("ALT", active = alt) { onToggleAlt(); focus() }
        ExtraKey("HOME") { writeKey(KeyEvent.KEYCODE_MOVE_HOME) }
        ExtraKey("↑") { writeKey(KeyEvent.KEYCODE_DPAD_UP) }
        ExtraKey("↓") { writeKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        ExtraKey("←") { writeKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        ExtraKey("→") { writeKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        ExtraKey("END") { writeKey(KeyEvent.KEYCODE_MOVE_END) }
        ExtraKey("PGUP") { writeKey(KeyEvent.KEYCODE_PAGE_UP) }
        ExtraKey("PGDN") { writeKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        ExtraKey("PASTE") {
            clipboard.getText()?.text?.let {
                session.write(terminalText(it, ctrl = false, alt = false))
            }
            onConsumeModifiers()
            onClearInput()
            focus()
        }
    }
}

@Composable
private fun ExtraKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = if (active) Glyph else Pane,
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp,
            if (active) Glyph else PaneLine),
        modifier = Modifier.widthIn(min = 34.dp).height(30.dp)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) Pane else Ink,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace))
        }
    }
}
