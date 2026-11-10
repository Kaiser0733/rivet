package com.kaiser.rivet.ui.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.browser.customtabs.CustomTabsIntent
import com.kaiser.rivet.R
import com.kaiser.rivet.browser.BrowserDriving
import com.kaiser.rivet.browser.BrowserMode
import com.kaiser.rivet.browser.BrowserSession
import com.kaiser.rivet.browser.BrowserTier
import kotlinx.coroutines.launch

@Composable
fun BrowserSheet(session: BrowserSession, onDismiss: () -> Unit) {
    val state by session.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember(state.url) { mutableStateOf(state.url) }
    var showDomains by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        session.customTabOpener = { url ->
            val activity = context.findActivity()
            runCatching {
                if (activity != null) {
                    CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
    LaunchedEffect(state.visible) { if (!state.visible) onDismiss() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { runCatching { session.back() } } }) {
                Icon(painterResource(R.drawable.ic_back), "Back")
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                singleLine = true,
                label = { Text("URL") },
            )
            TextButton(onClick = {
                scope.launch { runCatching { session.open(address) } }
            }) { Text("Go") }
            IconButton(onClick = { session.openExternal(state.url) }) {
                Icon(painterResource(R.drawable.ic_visibility), "Open in my browser")
            }
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_close), "Close browser")
            }
        }
        Text(state.budgetLine, Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.windows.size > 1) {
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.windows, key = { it.id }) { window ->
                    AssistChip(onClick = { session.selectWindow(window.id) }, label = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            window.thumbnailFile?.let { path ->
                                val bitmap = remember(path) {
                                    runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull()
                                }
                                if (bitmap != null) {
                                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                                        modifier = Modifier.size(32.dp))
                                }
                            }
                            Text("${window.title.take(18)}${if (window.active) " •" else ""}")
                        }
                    })
                }
            }
        }
        state.error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it, Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.handoff?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Continue in your browser", style = MaterialTheme.typography.titleMedium)
                    Text("Signing in there does not sign in Rivet's browser. Share the page back to continue.",
                        style = MaterialTheme.typography.bodySmall)
                    Row {
                        Button(onClick = { session.continueInBrowser() }) { Text("Open in browser") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { session.close() }) { Text("Not now") }
                    }
                }
            }
        }
        state.downloadPrompt?.let { prompt ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Download ${prompt.fileName}? ${prompt.sizeBytes / (1024 * 1024)}MB on a metered network.",
                        style = MaterialTheme.typography.bodySmall)
                    Row {
                        Button(onClick = { session.confirmDownload() }) { Text("Download") }
                        TextButton(onClick = { session.dismissDownload() }) { Text("Cancel") }
                    }
                }
            }
        }
        if (state.driving == BrowserDriving.User) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("You're driving. Rivet is paused.", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { session.handBack() }) { Text("Hand back") }
                }
            }
        }
        if (state.rendererVisible) {
            AndroidView(
                factory = { ctx -> WebView(ctx).also { view -> session.attach(view) } },
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.pressed }) session.setDriving(true)
                            }
                        }
                    },
            )
            DisposableEffect(Unit) { onDispose { session.detach(null) } }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                item {
                    if (state.mode == BrowserMode.Reader) {
                        SelectionContainer {
                            Text(state.readerText.ifBlank {
                                if (state.needsRenderer) "This page needs the renderer for readable text. Use an interaction tool or open it for viewing."
                                else "Open a page to read it."
                            })
                        }
                    } else Text("Opening the renderer…")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.tier) {
                    BrowserTier.Normal -> "Normal"
                    BrowserTier.StrictDiet -> "Strict diet"
                    BrowserTier.ReaderFirst -> "Reader first"
                    BrowserTier.NoRenderer -> "Reader only"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.width(8.dp))
            state.fidelity?.let { AssistChip(onClick = {}, label = { Text(it.name.lowercase()) }) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showDomains = true }) {
                Text("Sites (${state.domains.map { it.host }.distinct().size})")
            }
            TextButton(onClick = { showSettings = !showSettings }) { Text("Settings") }
            TextButton(onClick = { scope.launch { runCatching { session.bookmark() } } }) {
                Text("Save link")
            }
        }
        if (showSettings) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow JavaScript", Modifier.weight(1f))
                    Switch(checked = state.allowJs, onCheckedChange = session::setAllowJs)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Block third-party cookies", Modifier.weight(1f))
                    Switch(checked = state.blockThirdParty, onCheckedChange = session::setBlockThirdParty)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Full page (diet off)", Modifier.weight(1f))
                    Switch(checked = state.fullPage, onCheckedChange = session::setFullPage)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Desktop layout", Modifier.weight(1f))
                    Switch(checked = state.desktopMode, onCheckedChange = session::setDesktopMode)
                }
                TextButton(onClick = session::clearBrowserData) { Text("Clear browser data") }
            }
        }
    }
    if (showDomains) {
        AlertDialog(
            onDismissRequest = { showDomains = false },
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
            confirmButton = { TextButton(onClick = { showDomains = false }) { Text("Close") } },
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
