package com.kaiser.rivet.ui.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
 * Full-screen visual browser chrome. The page itself is BrowserHost beneath this
 * overlay, so opening/closing the screen does not recreate the WebView.
 */
@Composable
fun BrowserScreen(session: BrowserSession, onClose: () -> Unit,
                  onCloseSession: () -> Unit = { session.endSession() },
                  onChromeHeight: (Int) -> Unit = {},
                  modifier: Modifier = Modifier) {
    val state by session.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember(state.url) {
        mutableStateOf(TextFieldValue(state.url, selection = TextRange(state.url.length)))
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

    Box(modifier.fillMaxSize().then(
        if (!state.rendererVisible || state.readerMode) Modifier.background(MaterialTheme.colorScheme.surface)
        else Modifier)) {
        if (state.readerMode) {
            ReaderPane(state.readerText, Modifier.fillMaxSize().padding(
                top = BrowserChrome.topPadding(0.dp), bottom = BrowserChrome.bottomPadding(0.dp))) { url ->
                scope.launch { runCatching { session.openVisual(url, userInitiated = true) } }
            }
        } else if (state.url.isBlank() && !state.rendererVisible) {
            Box(Modifier.fillMaxSize().padding(top = 80.dp, bottom = 64.dp),
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

        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding()
            .onSizeChanged { onChromeHeight(it.height) }) {
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
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                            .onFocusChanged { focus ->
                                if (focus.isFocused) {
                                    address = address.copy(
                                        selection = TextRange(UrlFieldSelection.selectAll(address.text).last))
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
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_settings), "Browser menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = {
                                Text(if (state.readerMode) "Page view" else "Reader view")
                            }, onClick = {
                                menuOpen = false
                                scope.launch {
                                    if (state.readerMode) session.hideReader()
                                    else runCatching { session.showReader() }
                                }
                            })
                            DropdownMenuItem(text = { Text("Sites") }, onClick = {
                                menuOpen = false; domainsOpen = true
                            })
                            DropdownMenuItem(text = { Text("Windows (${state.windows.size})") }, onClick = {
                                menuOpen = false; windowsOpen = true
                            })
                            DropdownMenuItem(text = { Text("Open in my browser") }, onClick = {
                                menuOpen = false
                                if (state.url.isNotBlank()) session.openExternal(state.url)
                            })
                            DropdownMenuItem(text = { Text("Save link") }, onClick = {
                                menuOpen = false
                                scope.launch { runCatching { session.bookmark() } }
                            })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = {
                                menuOpen = false; settingsOpen = true
                            })
                        }
                    }
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
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCloseSession) { Text("Close browser") }
                }
            }
            state.notice?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.budgetCloseAction) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCloseSession) { Text("Close browser") }
                }
            }
        }

        Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding(),
            tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { runCatching { session.back() } } }) {
                    Icon(painterResource(R.drawable.ic_back), "Page back")
                }
                TextButton(onClick = {
                    scope.launch {
                        if (state.readerMode) session.hideReader()
                        else runCatching { session.showReader() }
                    }
                }) { Text(if (state.readerMode) "Page" else "Reader") }
                TextButton(onClick = { domainsOpen = true }) {
                    Text("Sites (${state.domains.map { it.host }.distinct().size})")
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
            }
        }
    }

    if (settingsOpen) {
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
    if (domainsOpen) {
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
    if (windowsOpen) {
        AlertDialog(
            onDismissRequest = { windowsOpen = false },
            title = { Text("Windows") },
            text = {
                Column {
                    state.windows.forEach { window ->
                        TextButton(onClick = {
                            session.selectWindow(window.id)
                            windowsOpen = false
                        }) { Text("${if (window.active) "• " else ""}${window.title.take(48)}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { windowsOpen = false }) { Text("Close") } },
        )
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
