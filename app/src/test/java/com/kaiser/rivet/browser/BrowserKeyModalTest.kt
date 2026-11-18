package com.kaiser.rivet.browser

import android.app.Application
import android.content.Context
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebView
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class BrowserKeyModalTest {
    private val url = "http://127.0.0.1:45179/"
    private var session: BrowserSession? = null

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() {
        session?.endSession()
        session = null
        Dispatchers.resetMain()
    }

    private fun readySession(): Pair<BrowserSession, WebView> {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null).also { this.session = it }
        val view = EvaluatingWebView(app)
        view.layout(0, 0, 720, 1480)
        session.attach(view)
        view.right = 720
        view.bottom = 1480
        view.loadUrl(url)
        view.webViewClient.onPageFinished(view, url)
        session.rendererLayoutTimeoutMs = 100
        return session to view
    }

    private fun keyCall() = AgentToolCall("key", "browser_key", """{"key":"ArrowDown"}""")

    @Test fun browserKeyIsBlockedWhileAlertIsShowing() = runTest {
        val (session, view) = readySession()
        view.webChromeClient!!.onJsAlert(view, url, "Game Over! Score: 0", jsResult())

        val prepared = BrowserTools(session).prepare(keyCall())
        assertNotNull(prepared)
        val result = prepared!!.execute()

        assertTrue(result.error)
        assertTrue(result.content.contains("page_modal_blocked"))
        assertTrue(result.content.contains("browser_reset"))
    }

    @Test fun browserKeyStillSucceedsWithoutAModal() = runTest {
        val (session, _) = readySession()

        val prepared = BrowserTools(session).prepare(keyCall())
        assertNotNull(prepared)
        val result = prepared!!.execute()

        assertFalse(result.content, result.error)
        assertTrue(result.content.contains("\"key\":\"ArrowDown\""))
    }

    @Test fun navigationClearsTheModalBlock() = runTest {
        val (session, view) = readySession()
        view.webChromeClient!!.onJsAlert(view, url, "Game Over! Score: 0", jsResult())
        view.webViewClient.onPageStarted(view, url, null)

        val prepared = BrowserTools(session).prepare(keyCall())
        assertNotNull(prepared)
        val result = prepared!!.execute()

        assertFalse(result.content, result.error)
    }

    /** Robolectric's WebView never invokes evaluateJavascript; return a key target directly. */
    private class EvaluatingWebView(context: Context) : WebView(context) {
        override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) {
            callback?.onReceiveValue(
                """{"found":true,"sensitive":false,"risky":false,"description":"body"}""")
        }
    }

    /**
     * The platform jar exposes JsResult's no-arg constructor as package-private;
     * Robolectric's android-all supplies the concrete implementation at runtime.
     */
    private fun jsResult(): JsResult {
        val constructor = JsResult::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance()
    }
}
