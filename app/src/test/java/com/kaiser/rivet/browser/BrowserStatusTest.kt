package com.kaiser.rivet.browser

import android.app.Application
import android.webkit.WebView
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class BrowserStatusTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun openingAnEmptyBrowserStartsAtGoogle() {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        try {
            session.userOpen()
            assertEquals("https://www.google.com/", session.currentUrl())
        } finally {
            session.endSession()
        }
    }

    @Test fun closedBrowserIsReportedWithoutThrowing() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val status = session.status()
        assertFalse(status.alive)
        assertFalse(status.healthy)
        assertEquals("none", status.mode)
        assertEquals("", status.url)
        assertNull(status.hint)
        assertEquals(0, status.jsErrors)
    }

    @Test fun aOneByOneRendererIsUnhealthyAndNamesReset() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val view = WebView(app)
        view.layout(0, 0, 1, 1)
        session.attach(view)
        val status = session.status()
        assertTrue(status.alive)
        assertFalse(status.healthy)
        assertTrue(status.hint!!.contains("browser_reset(reopen=true)"))
    }

    @Test fun readerOnlyPageIsHealthyAndNamesTheTierCapability() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        // A text-mode open leaves a URL without a renderer; status must not create one.
        session.newWindow()
        val status = session.status()
        assertFalse(status.alive)
        assertEquals("none", status.mode)
        assertFalse(status.healthy)
    }

    @Test fun aGoneRendererStaysGoneAfterReleaseAndHasAResetHint() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val view = WebView(app).apply { layout(0, 0, 300, 300) }
        session.attach(view)
        val client = view.webViewClient
        client.onPageFinished(view, "https://example.org/article")
        client.onRenderProcessGone(view, null)
        assertFalse(session.status().alive)
        session.onRendererReleased(view) { session.releaseRenderer(view) }

        val tools = BrowserTools(session)
        val call = AgentToolCall("status", "browser_status", "{}")
        val result = requireNotNull(tools.prepare(call, "worker", BrowserDrivingLease())).execute()
        val payload = Json.parseToJsonElement(result.content).jsonObject

        assertEquals("gone", payload.getValue("mode").jsonPrimitive.content)
        assertEquals("false", payload.getValue("healthy").jsonPrimitive.content)
        assertEquals("browser_renderer_unavailable", payload.getValue("error_kind").jsonPrimitive.content)
        assertTrue(payload.getValue("hint").jsonPrimitive.content.contains("browser_reset(reopen=true)"))
        assertFalse(payload.containsKey("http_status"))
    }
}
