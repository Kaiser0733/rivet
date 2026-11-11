package com.kaiser.rivet.ui.browser

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kaiser.rivet.browser.BrowserSession

/**
 * The only WebView in the app. Composition removes the view before release; only
 * then does the host stop and destroy it, and only then does the session clear the
 * renderer card/visibility state.
 */
@Composable
fun BrowserHost(session: BrowserSession, modifier: Modifier = Modifier) {
    val state by session.state.collectAsState()
    if (!state.rendererVisible || state.teardownRequested) return
    AndroidView(
        factory = { context -> WebView(context).also { session.attach(it) } },
        modifier = modifier,
        onRelease = { view ->
            session.onRendererReleased(view) {
                try {
                    view.stopLoading()
                    view.destroy()
                    null
                } catch (error: Throwable) {
                    error
                }
            }
        },
    )
}
