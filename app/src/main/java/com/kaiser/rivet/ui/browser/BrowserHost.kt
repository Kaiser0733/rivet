package com.kaiser.rivet.ui.browser

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kaiser.rivet.browser.BrowserSession

/**
 * The only WebView in the app. It lives under the visual screen when open and
 * behind the chat when not, so a watch card can show a live session and a
 * screenshot never sees a 1dp placeholder.
 */
@Composable
fun BrowserHost(session: BrowserSession, modifier: Modifier = Modifier) {
    val state by session.state.collectAsState()
    if (!state.rendererVisible) return
    AndroidView(
        factory = { context -> WebView(context).also { session.attach(it) } },
        modifier = modifier,
    )
    DisposableEffect(Unit) { onDispose { session.detach(null) } }
}
