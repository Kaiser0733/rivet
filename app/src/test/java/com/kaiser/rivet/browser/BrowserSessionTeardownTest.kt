package com.kaiser.rivet.browser

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserSessionTeardownTest {
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun closeAfterWindowUnloadAndNullRendererStillTerminatesAllTrackedProcesses() {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val p1 = Any()
        val p2 = Any()
        val activeProcess = AtomicReference<Any>(p1)
        val p1Terminations = AtomicInteger(0)
        val p2Terminations = AtomicInteger(0)
        session.rendererTerminateSupported = { true }
        session.renderProcessHandleProvider = {
            val current = activeProcess.get()
            current to { target ->
                when {
                    target === p1 -> { p1Terminations.incrementAndGet(); true }
                    target === p2 -> { p2Terminations.incrementAndGet(); true }
                    else -> false
                }
            }
        }

        session.newWindow()
        session.newWindow()
        val webView = WebView(app)
        session.attach(webView)

        // Simulate Chromium spawning a secondary process after an error transition.
        activeProcess.set(p2)
        val httpReq = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://example.com/404")
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        webView.webViewClient.onReceivedHttpError(
            webView,
            httpReq,
            WebResourceResponse("text/html", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))),
        )

        // Simulate renderer already released/null when Close arrives (hidden teardown / post-unload).
        session.onRendererReleased(webView) { null }
        assertFalse(session.state.value.rendererVisible)

        session.endSession()
        assertEquals("Primary tracked renderer process must be terminated on Close", 1, p1Terminations.get())
        assertEquals("Secondary tracked error-page process must be terminated on Close", 1, p2Terminations.get())
    }
}
