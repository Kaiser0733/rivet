package com.kaiser.rivet.ui.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kaiser.rivet.browser.BrowserUiState
import com.kaiser.rivet.browser.BrowserWindowSummary
import com.kaiser.rivet.ui.RivetPageHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiser.rivet.R
import com.kaiser.rivet.browser.BrowserDriving
import com.kaiser.rivet.browser.BrowserFailure
import com.kaiser.rivet.browser.BrowserSession
import com.kaiser.rivet.browser.ReaderBlock
import com.kaiser.rivet.browser.ReaderBlockKind
import com.kaiser.rivet.browser.ReaderBlocks
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowserScreen(session: BrowserSession, onClose: () -> Unit,
                  onCloseSession: () -> Unit = { session.endSession() },
                  modifier: Modifier = Modifier,
                  hostContent: @Composable (Modifier, () -> Unit) -> Unit = { hostModifier, onPageInteraction ->
                      BrowserHost(session, hostModifier, onPageInteraction)
                  }) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var isUrlFocused by remember { mutableStateOf(false) }
    var address by remember {
        mutableStateOf(TextFieldValue(state.url, selection = TextRange(state.url.length)))
    }
    LaunchedEffect(state.url) {
        address = TextFieldValue(state.url, selection = TextRange(state.url.length))
    }
    LaunchedEffect(state.visible) {
        if (!state.visible) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
    val clearUrlFocus: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val submitUrl: () -> Unit = {
        clearUrlFocus()
        if (address.text.isNotBlank()) scope.launch {
            try {
                session.openVisual(address.text, userInitiated = true)
            } catch (error: BrowserFailure) {
                session.showError(error)
            }
        }
    }
    var pageOptions by remember { mutableStateOf(false) }
    BackHandler(enabled = state.visible && pageOptions) { pageOptions = false }
    val context = LocalContext.current

    Column(modifier.fillMaxSize().then(
        if (state.visible) Modifier.background(MaterialTheme.colorScheme.surface).navigationBarsPadding()
        else Modifier)) {
        if (state.visible && !pageOptions) {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                BrowserTabStrip(
                    windows = state.windows,
                    onSelect = session::selectWindow,
                    onClose = session::closeWindow,
                    onNewTab = session::newWindow,
                    onBack = onClose,
                )
                BrowserToolbar(
                    address = address,
                    focused = isUrlFocused,
                    onAddressChange = { address = it },
                    onFocusChange = { isUrlFocused = it },
                    focusRequester = focusRequester,
                    onSelectAddress = {
                        address = address.copy(selection = TextRange(0, address.text.length), composition = null)
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    },
                    onSubmit = submitUrl,
                    onBack = { scope.launch { runCatching { session.back() } } },
                    onForward = { scope.launch { runCatching { session.forward() } } },
                    onReload = { scope.launch { runCatching { session.reload() } } },
                    onCloseTab = state.activeWindowId?.let { id -> { session.closeWindow(id) } },
                    onShare = state.url.takeUnless { it.isBlank() || it == "about:blank" }?.let { url ->
                        {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, url)
                            runCatching { context.startActivity(Intent.createChooser(send, null)) }
                            Unit
                        }
                    },
                    onOptions = { clearUrlFocus(); pageOptions = true },
                )
                state.handoff?.let {
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
                                    if (state.errorRetry) {
                                        TextButton(onClick = {
                                            val retryUrl = state.url.ifBlank { session.currentUrl() }
                                            if (retryUrl.isNotBlank()) scope.launch {
                                                try { session.openVisual(retryUrl, userInitiated = true) }
                                                catch (failure: BrowserFailure) { session.showError(failure) }
                                            }
                                        }) { Text("Try again") }
                                    }
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
            hostContent(Modifier.fillMaxSize(), clearUrlFocus)
            if (state.visible && !pageOptions) {
                if (state.readerMode) {
                    ReaderPane(state.readerText, Modifier.fillMaxSize()) { url ->
                        scope.launch { runCatching { session.openVisual(url, userInitiated = true) } }
                    }
                } else if (state.url.isBlank() || state.url == "about:blank") {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center) {
                        Text("Enter a web address to get started.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (state.rendererVisible && !state.pageReady) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (state.visible && pageOptions) {
                BrowserOptions(state, session, { pageOptions = false }, onClose, onCloseSession)
            }
        }

    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BrowserToolbar(
    address: TextFieldValue,
    focused: Boolean,
    onAddressChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    onSelectAddress: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onCloseTab: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onOptions: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_back), "Page back")
        }
        IconButton(onClick = onForward, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_forward), "Page forward")
        }
        IconButton(onClick = onReload, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_reload), "Reload page")
        }
        Surface(Modifier.weight(1f), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            BasicTextField(
                value = address,
                onValueChange = onAddressChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    .onFocusChanged { onFocusChange(it.isFocused) }
                    .semantics { contentDescription = "Address" },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 46.dp), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.padding(horizontal = 12.dp).alpha(if (focused) 1f else 0f)) { field() }
                        if (!focused) {
                            val display = BrowserTabs.addressLabel(address.text)
                            Text(display, Modifier.padding(horizontal = 12.dp), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Box(Modifier.matchParentSize().clearAndSetSemantics {}
                                .pointerInput(onSelectAddress) { detectTapGestures { onSelectAddress() } })
                        }
                    }
                },
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_more), "Browser menu")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Share link") }, enabled = onShare != null, onClick = {
                    menuOpen = false
                    onShare?.invoke()
                })
                DropdownMenuItem(text = { Text("Close tab") }, enabled = onCloseTab != null, onClick = {
                    menuOpen = false
                    onCloseTab?.invoke()
                })
                DropdownMenuItem(text = { Text("Page options") }, onClick = {
                    menuOpen = false
                    onOptions()
                })
            }
        }
    }
}

@Composable
private fun BrowserTabStrip(windows: List<BrowserWindowSummary>, onSelect: (String) -> Unit,
                            onClose: (String) -> Unit, onNewTab: () -> Unit, onBack: () -> Unit) {
    val position = rememberLazyListState()
    val activeIndex = windows.indexOfFirst { it.active }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && position.layoutInfo.visibleItemsInfo.none { it.index == activeIndex }) {
            position.scrollToItem(activeIndex)
        }
    }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_back), "Back to Rivet")
        }
        LazyRow(Modifier.weight(1f), state = position,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(windows, key = { it.id }) { window ->
                val title = BrowserTabs.tabTitle(window.title, window.url)
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (window.active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                ) {
                    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.widthIn(min = 76.dp, max = 132.dp).clickable { onSelect(window.id) }
                            .padding(start = 9.dp, top = 10.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val favicon = window.favicon
                            if (favicon != null) Image(favicon.asImageBitmap(), null, Modifier.size(16.dp))
                            else Icon(painterResource(R.drawable.ic_browser), null, Modifier.size(16.dp))
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { onClose(window.id) }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_close), "Close ${title}", Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
        IconButton(onClick = onNewTab, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_add), "New tab")
        }
    }
}

@Composable
private fun BrowserOptions(state: BrowserUiState, session: BrowserSession, onBack: () -> Unit,
                           onClose: () -> Unit, onCloseSession: () -> Unit) {
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            RivetPageHeader("Page options", onBack)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                if (state.agentRunActive && state.agentVisualActive) {
                    TextButton(onClick = {
                        if (state.driving == BrowserDriving.Agent) session.takeControl() else session.handBack()
                        onBack()
                    }) { Text(if (state.driving == BrowserDriving.Agent) "Take control" else "Hand back") }
                }
                TextButton(onClick = {
                    scope.launch {
                        if (state.readerMode) session.hideReader() else try {
                            session.showReader()
                        } catch (error: BrowserFailure) {
                            session.showError(error)
                        }
                        onBack()
                    }
                }) { Text(if (state.readerMode) "Page view" else "Reader view") }
                TextButton(enabled = state.url.isNotBlank(), onClick = { session.openExternal(state.url) }) {
                    Text("Open in my browser")
                }
                TextButton(enabled = state.url.isNotBlank(), onClick = {
                    scope.launch { runCatching { session.bookmark() }; onBack() }
                }) { Text("Save link") }
                BrowserSwitch("Allow JavaScript", state.allowJs, session::setAllowJs)
                BrowserSwitch("Block third-party cookies", state.blockThirdParty, session::setBlockThirdParty)
                BrowserSwitch("Full page (diet off)", state.fullPage, session::setFullPage)
                BrowserSwitch("Desktop layout", state.desktopMode, session::setDesktopMode)
                TextButton(onClick = session::clearBrowserData) { Text("Clear browser data") }
                Text("Sites this session", modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleSmall)
                if (state.domains.isEmpty()) Text("No sites yet.")
                state.domains.takeLast(20).forEach { visit ->
                    Text("${visit.host}${if (visit.projectPreview) " · project preview" else ""}",
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onClose) { Text("Back to Rivet") }
                TextButton(onClick = onCloseSession) { Text("Close browser") }
            }
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

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
