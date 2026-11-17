package com.kaiser.rivet.ui.browser

import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kaiser.rivet.browser.BrowserSession

private class HeldWebView {
    var view: WebView? = null
}

/**
 * The only WebView in the app. Composition removes the view before release; only
 * then does the host stop and destroy it, and only then does the session clear the
 * renderer card/visibility state.
 */
@Composable
fun BrowserHost(
    session: BrowserSession,
    modifier: Modifier = Modifier,
    onPageInteraction: () -> Unit = {},
) {
    val state by session.state.collectAsState()
    if (!state.rendererVisible || state.teardownRequested) return
    val held = remember { HeldWebView() }
    AndroidView(
        factory = { context ->
            val frame = FrameLayout(context)
            val web = WebView(context)
            held.view = web
            web.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onPageInteraction()
                false
            }
            web.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onPageInteraction()
            }
            frame.addView(web, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            session.attach(web)
            frame
        },
        modifier = modifier,
        onRelease = { frame ->
            val web = held.view ?: (0 until frame.childCount)
                .map { frame.getChildAt(it) }
                .filterIsInstance<WebView>()
                .firstOrNull()
            held.view = null
            if (web != null) {
                session.onRendererReleased(web) { session.releaseRenderer(web) }
            }
        },
    )
}
