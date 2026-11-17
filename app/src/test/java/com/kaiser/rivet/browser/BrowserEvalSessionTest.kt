package com.kaiser.rivet.browser

import android.app.Application
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
class BrowserEvalSessionTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun readySession(url: String = "http://127.0.0.1:45179/"): Pair<BrowserSession, WebView> {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val view = WebView(app)
        view.layout(0, 0, 720, 1480)
        session.attach(view)
        view.webViewClient.onPageFinished(view, url)
        session.rendererLayoutTimeoutMs = 100
        session.loadInteraction = { }
        return session to view
    }

    @Test fun evalReturnsBoundedSuccessAndOrigin() = runBlocking {
        val (session, _) = readySession()
        session.evalScript = { _, _ -> JSONObject.quote("""{"ok":true,"type":"number","value":42}""") }
        val result = session.eval("42", expectedOrigin = "http://127.0.0.1:45179")
        assertTrue(result.content.contains("\"ok\":true"))
        assertTrue(result.content.contains("\"value\":42"))
        assertEquals("http://127.0.0.1:45179", result.origin)
    }

    @Test fun evalTimeoutAndExpectedOriginMismatchAreNamed() = runBlocking {
        val (session, _) = readySession()
        session.evalScript = { _, _ -> delay(300); "{}" }
        val timeout = runCatching { session.eval("slow", timeoutMs = 100) }.exceptionOrNull()
        assertTrue(timeout is BrowserFailure)
        assertEquals("browser_eval_timeout", (timeout as BrowserFailure).code)
        assertTrue(timeout.message.contains("browser_reset(reopen=true)"))

        session.evalScript = { _, _ -> """{"ok":true,"value":"x"}""" }
        val mismatch = runCatching {
            session.eval("x", expectedOrigin = "http://127.0.0.1:9999")
        }.exceptionOrNull()
        assertTrue(mismatch is BrowserFailure)
        assertEquals("browser_origin_mismatch", (mismatch as BrowserFailure).code)
    }

    @Test fun evalRefusesAnIdentityHandoff() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        session.openText("https://accounts.google.com/signin")
        val failure = runCatching { session.eval("1") }.exceptionOrNull()
        assertTrue(failure is BrowserFailure)
        assertEquals("browser_handoff", (failure as BrowserFailure).code)
    }
}
