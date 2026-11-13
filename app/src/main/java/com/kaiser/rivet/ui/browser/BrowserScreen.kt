package com.kaiser.rivet.ui.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiser.rivet.R
import com.kaiser.rivet.browser.BrowserDriving
import com.kaiser.rivet.browser.BrowserFailure
import com.kaiser.rivet.browser.BrowserSession
import com.kaiser.rivet.browser.BrowserTier
import com.kaiser.rivet.browser.ReaderBlock
import com.kaiser.rivet.browser.ReaderBlockKind
import com.kaiser.rivet.browser.ReaderBlocks
import kotlinx.coroutines.launch

/**
 * Full-screen visual browser shell. Top chrome, banners, page surface, and bottom controls live
 * in a single Column so banners never overlap the WebView or bleed through to chat.
 */
@Composable
fun BrowserScreen(session: BrowserSession, onClose: () -> Unit,
                  onCloseSession: () -> Unit = { session.endSession() },
                  modifier: Modifier = Modifier,
                  hostContent: @Composable (Modifier) -> Unit = { hostModifier ->
                      BrowserHost(session, hostModifier)
                  }) {
    val state by session.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val urlField = remember { UrlFieldSelection.Controller(state.url) }
    var address by remember {
        mutableStateOf(TextFieldValue(state.url, selection = TextRange(state.url.length)))
    }
    var selectAllLatch by remember { mutableStateOf(false) }
    LaunchedEffect(state.url) {
        val synced = urlField.syncExternalUrl(state.url)
        selectAllLatch = false
        address = TextFieldValue(synced.text, TextRange(synced.selection.start, synced.selection.end))
    }
    LaunchedEffect(selectAllLatch) {
        if (selectAllLatch) {
            kotlinx.coroutines.delay(150)
            urlField.onFocusSettled()
            selectAllLatch = false
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var domainsOpen by remember { mutableStateOf(false) }
    var windowsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        session.customTabOpener = { url ->
            val activity = context.findActivity()
            runCatching {
                if (activity != null) CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
                else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    Column(modifier.fillMaxSize().then(
        if (state.visible) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)) {
        if (state.visible) {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            BrowserTabStrip(
                windows = state.windows,
                onSelect = session::selectWindow,
                onClose = session::closeWindow,
                onOpenWindows = { windowsOpen = true },
            )
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_back), "Back to chat")
                    }
                    IconButton(onClick = onCloseSession) {
                        Icon(painterResource(R.drawable.ic_close), "Close browser")
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { incoming ->
                            val result = urlField.onValueChange(
                                incoming.text,
                                UrlFieldSelection.Range(incoming.selection.start, incoming.selection.end),
                            )
                            selectAllLatch = urlField.pendingSelectAllOnTap
                            address = if (result.keepFullSelection) {
                                incoming.copy(
                                    selection = TextRange(
                                        result.field.selection.start,
                                        result.field.selection.end,
                                    ),
                                    composition = null,
                                )
                            } else {
                                incoming
                            }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                            .onFocusChanged { focus ->
                                val focused = urlField.onFocusChanged(
                                    isFocused = focus.isFocused,
                                    currentText = address.text,
                                    currentSelection = UrlFieldSelection.Range(
                                        address.selection.start,
                                        address.selection.end,
                                    ),
                                )
                                selectAllLatch = urlField.pendingSelectAllOnTap
                                if (focused != null) {
                                    address = address.copy(
                                        selection = TextRange(focused.selection.start, focused.selection.end),
                                        composition = null,
                                    )
                                }
                            },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        label = { Text("URL") },
                    )
                    TextButton(onClick = {
                        if (address.text.isNotBlank()) scope.launch {
                            try {
                                session.openVisual(address.text, userInitiated = true)
                            } catch (error: BrowserFailure) {
                                session.showError(error.message)
                            }
                        }
                    }) { Text("Go") }
                }
            }
            state.handoff?.let { handoff ->
                BrowserNotice(MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Continue in your browser", style = MaterialTheme.typography.titleSmall)
                        Text("Signing in there does not sign in Rivet's browser. Share the page back to continue.",
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            Button(onClick = { session.continueInBrowser() }) { Text("Open in browser") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onClose) { Text("Not now") }
                        }
                    }
                }
            }
            state.downloadPrompt?.let { prompt ->
                BrowserNotice(MaterialTheme.colorScheme.tertiaryContainer) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Download ${prompt.fileName}? ${prompt.sizeBytes / (1024 * 1024)}MB on a metered network.",
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            Button(onClick = { session.confirmDownload() }) { Text("Download") }
                            TextButton(onClick = { session.dismissDownload() }) { Text("Cancel") }
                        }
                    }
                }
            }
            if (com.kaiser.rivet.browser.BrowserDrivingPolicy.bannerVisible(
                    state.agentRunActive, state.agentVisualActive, state.driving)) {
                BrowserNotice(MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("You're driving. Rivet is paused.", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { session.handBack() }) { Text("Hand back") }
                    }
                }
            }
            if (state.error != null || state.notice != null || state.budgetCloseAction) {
                BrowserNotice(
                    if (state.error != null) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        state.error?.let { error ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(error, Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onCloseSession) { Text("Close browser") }
                            }
                        }
                        state.notice?.let {
                            Text(it, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.budgetCloseAction && state.error == null) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onCloseSession) { Text("Close browser") }
                            }
                        }
                    }
                }
            }
        }
        }

        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            hostContent(Modifier.fillMaxSize())
            if (state.visible) {
                if (state.readerMode) {
                    ReaderPane(state.readerText, Modifier.fillMaxSize()) { url ->
                        scope.launch { runCatching { session.openVisual(url, userInitiated = true) } }
                    }
                } else if (state.url.isBlank() && (!state.rendererVisible || state.activeWindowId == null)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center) {
                        Text("Enter a URL to open the visual browser.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (state.rendererVisible && !state.pageReady) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        if (state.visible) {
        Surface(Modifier.fillMaxWidth().navigationBarsPadding(),
            tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { runCatching { session.back() } } }) {
                    Icon(painterResource(R.drawable.ic_back), "Page back")
                }
                IconButton(onClick = { scope.launch { runCatching { session.forward() } } }) {
                    Icon(painterResource(R.drawable.ic_forward), "Page forward")
                }
                IconButton(onClick = { scope.launch { runCatching { session.reload() } } }) {
                    Icon(painterResource(R.drawable.ic_reload), "Reload page")
                }
                IconButton(onClick = {
                    scope.launch {
                        if (state.readerMode) session.hideReader()
                        else try {
                            session.showReader()
                        } catch (error: BrowserFailure) {
                            session.showError(error.message)
                        }
                    }
                }) {
                    Icon(painterResource(if (state.readerMode) R.drawable.ic_visibility_off
                        else R.drawable.ic_visibility),
                        if (state.readerMode) "Page view" else "Reader view")
                }
                if (state.agentRunActive && state.agentVisualActive &&
                    state.driving == BrowserDriving.Agent) {
                    TextButton(onClick = { session.takeControl() }) { Text("Take control") }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    when (state.tier) {
                        BrowserTier.Normal -> "Normal"
                        BrowserTier.StrictDiet -> "Strict diet"
                        BrowserTier.ReaderFirst -> "Reader first"
                        BrowserTier.NoRenderer -> "Reader only"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more), "Browser menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (state.readerMode) "Page view" else "Reader view") },
                            leadingIcon = { Icon(painterResource(
                                if (state.readerMode) R.drawable.ic_visibility_off
                                else R.drawable.ic_visibility), null) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    if (state.readerMode) session.hideReader()
                                    else try {
                                        session.showReader()
                                    } catch (error: BrowserFailure) {
                                        session.showError(error.message)
                                    }
                                }
                            })
                        DropdownMenuItem(
                            text = { Text("Sites") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_browser), null) },
                            onClick = { menuOpen = false; domainsOpen = true })
                        DropdownMenuItem(
                            text = { Text("Windows (${state.windows.size})") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_processes), null) },
                            onClick = { menuOpen = false; windowsOpen = true })
                        DropdownMenuItem(
                            text = { Text("Open in my browser") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_forward), null) },
                            onClick = {
                                menuOpen = false
                                if (state.url.isNotBlank()) session.openExternal(state.url)
                            })
                        DropdownMenuItem(
                            text = { Text("Save link") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_pin), null) },
                            onClick = {
                                menuOpen = false
                                scope.launch { runCatching { session.bookmark() } }
                            })
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_settings), null) },
                            onClick = { menuOpen = false; settingsOpen = true })
                    }
                }
            }
        }
        }
    }

    if (state.visible && settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Browser settings") },
            text = {
                Column {
                    BrowserSwitch("Allow JavaScript", state.allowJs, session::setAllowJs)
                    BrowserSwitch("Block third-party cookies", state.blockThirdParty,
                        session::setBlockThirdParty)
                    BrowserSwitch("Full page (diet off)", state.fullPage, session::setFullPage)
                    BrowserSwitch("Desktop layout", state.desktopMode, session::setDesktopMode)
                    TextButton(onClick = session::clearBrowserData) { Text("Clear browser data") }
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Close") } },
        )
    }
    if (state.visible && domainsOpen) {
        AlertDialog(
            onDismissRequest = { domainsOpen = false },
            title = { Text("Sites this session") },
            text = {
                Column {
                    if (state.domains.isEmpty()) Text("No sites yet.")
                    state.domains.takeLast(20).forEach { visit ->
                        Text("${visit.host}${if (visit.projectPreview) " · project preview" else ""}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { domainsOpen = false }) { Text("Close") } },
        )
    }
    if (state.visible && windowsOpen) {
        AlertDialog(
            onDismissRequest = { windowsOpen = false },
            title = { Text("Windows") },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(state.windows, key = { it.id }) { window ->
                        BrowserWindowCard(
                            window = window,
                            onSelect = {
                                session.selectWindow(window.id)
                                windowsOpen = false
                            },
                            onClose = { session.closeWindow(window.id) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { windowsOpen = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun BrowserTabStrip(
    windows: List<com.kaiser.rivet.browser.BrowserWindowSummary>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onOpenWindows: () -> Unit,
) {
    val chips = remember(windows) {
        BrowserTabs.chips(windows.map {
            BrowserWindowInfo(it.id, it.title, it.url, it.active)
        })
    }
    if (chips.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        items(chips, key = { it.id }) { chip ->
            Surface(
                color = if (chip.active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                border = if (chip.active) BorderStroke(1.dp,
                    MaterialTheme.colorScheme.outlineVariant) else null,
                modifier = Modifier.height(36.dp).widthIn(min = 96.dp, max = 200.dp)
                    .clickable { onSelect(chip.id) },
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_browser), null,
                        Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(chip.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (chip.active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 132.dp))
                    if (chip.active) {
                        IconButton(onClick = { onClose(chip.id) },
                            modifier = Modifier.size(26.dp)) {
                            Icon(painterResource(R.drawable.ic_close), "Close tab",
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Surface(
                color = Color.Transparent,
                modifier = Modifier.height(36.dp).clickable { onOpenWindows() },
            ) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_processes), "Windows",
                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("${chips.size}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BrowserWindowCard(
    window: com.kaiser.rivet.browser.BrowserWindowSummary,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = if (window.active) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Column(Modifier.padding(start = 10.dp, top = 8.dp, end = 2.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_browser), null,
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(BrowserTabs.title(window.title, window.url), maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(painterResource(R.drawable.ic_close), "Close window",
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(window.url.substringAfter("://", window.url).substringBefore('/'),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp))
        }
    }
}

@Composable
private fun BrowserNotice(color: Color, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = color) { content() }
}

@Composable
private fun BrowserSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderPane(text: String, modifier: Modifier = Modifier, onLink: (String) -> Unit) {
    val blocks = remember(text) { ReaderBlocks.parse(text) }
    Surface(modifier.background(MaterialTheme.colorScheme.surface)) {
        if (blocks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No readable text on this page.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                items(blocks) { block ->
                    when (block.kind) {
                        ReaderBlockKind.Heading -> Text(
                            spansText(block),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = when (block.level) {
                                    1 -> 28.sp; 2 -> 23.sp; 3 -> 20.sp; else -> 18.sp
                                },
                                fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        ReaderBlockKind.Bullet -> Row(Modifier.padding(vertical = 2.dp)) {
                            Text("• ", style = MaterialTheme.typography.bodyLarge)
                            InlineSpans(block, onLink)
                        }
                        ReaderBlockKind.Code -> Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Text(spansText(block), Modifier.padding(10.dp),
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                        }
                        ReaderBlockKind.Paragraph -> Box(Modifier.padding(vertical = 3.dp)) {
                            InlineSpans(block, onLink)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineSpans(block: ReaderBlock, onLink: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.Start) {
        block.spans.forEach { span ->
            val url = span.url
            if (url != null) {
                Text(
                    span.text,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clickable { onLink(url) },
                )
            } else {
                Text(span.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun spansText(block: ReaderBlock): String = block.spans.joinToString(" ") { it.text }

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
