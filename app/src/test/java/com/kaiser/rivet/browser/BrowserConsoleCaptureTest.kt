package com.kaiser.rivet.browser

import android.app.Application
import android.webkit.ConsoleMessage
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserConsoleCaptureTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun webChromeMessagesFeedTheBoundedConsoleQuery() {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val webView = WebView(app)
        session.attach(webView)
        val client = webView.webChromeClient!!
        assertFalse(client.onConsoleMessage(ConsoleMessage(
            "Uncaught TypeError: nope", "game.js", 42, "http://127.0.0.1:45179/",
            ConsoleMessage.MessageLevel.ERROR)))
        val snapshot = session.console("error", 0, 50, clear = false)
        assertEquals(1, snapshot.result.entries.size)
        assertEquals(BrowserConsoleLevel.Error, snapshot.result.entries.single().level)
        assertTrue(snapshot.result.entries.single().message.contains("nope"))
        assertEquals(42, snapshot.result.entries.single().line)
    }

    @Test fun httpErrorsBecomeNetworkErrorEntriesAndClearOnlyWhenAsked() {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val webView = WebView(app)
        session.attach(webView)
        val request = object : android.webkit.WebResourceRequest {
            override fun getUrl() = android.net.Uri.parse("http://127.0.0.1:45179/missing.js")
            override fun isForMainFrame() = false
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        webView.webViewClient.onReceivedHttpError(webView, request,
            android.webkit.WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))))
        val result = session.console("all", 0, 50, clear = true)
        assertEquals(BrowserConsoleLevel.NetworkError, result.result.entries.single().level)
        assertEquals(0, session.console("all", 0, 50, false).result.entries.size)
    }
}
