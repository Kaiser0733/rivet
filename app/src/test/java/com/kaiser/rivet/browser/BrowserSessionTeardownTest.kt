package com.kaiser.rivet.browser

import android.app.Application
import android.app.ActivityManager
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserSessionTeardownTest {
    private val scheduler = TestCoroutineScheduler()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun staleReleaseCannotTerminateTheReplacementRenderer() {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val old = WebView(app)
        val replacement = WebView(app)
        val sharedProcess = Any()
        var terminated = 0
        session.rendererTerminateSupported = { true }
        session.renderProcessHandleProvider = { sharedProcess to { terminated++; true } }
        session.attach(old)
        session.attach(replacement)

        session.onRendererReleased(old) { session.releaseRenderer(old) }

        assertEquals(0, terminated)
        assertTrue(session.state.value.rendererVisible)
        assertFalse(session.state.value.teardownRequested)
    }

    @Test fun teardownStaysPendingUntilTheDepartingRendererIsGone() {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val view = WebView(app)
        val process = Any()
        var terminated = 0
        session.rendererTerminateSupported = { true }
        session.renderProcessHandleProvider = { process to { terminated++; true } }
        session.attach(view)
        session.requestRendererTeardown()
        session.onRendererReleased(view) { session.releaseRenderer(view) }

        assertEquals(1, terminated)
        assertTrue(session.state.value.teardownRequested)
        view.webViewClient.onRenderProcessGone(view, null)
        assertFalse(session.state.value.teardownRequested)
        assertFalse(session.state.value.rendererVisible)
    }

    @Test fun aNewNavigationCancelsTheScheduledPaintProbe() {
        val app = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val view = WebView(app).apply { layout(0, 0, 300, 300) }
        session.attach(view)
        session.watch()
        var probes = 0
        session.paintJsEvaluator = { _, _ -> probes++; "100" }
        view.webViewClient.onPageFinished(view, "https://example.org/first")
        view.webViewClient.onPageStarted(view, "https://example.org/second", null)

        scheduler.advanceTimeBy(1_600)

        assertEquals(0, probes)
        assertFalse(session.state.value.pageReady)
        session.endSession()
    }

    @Test fun rendererGetsOneRestartAndThenOffersReload() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(ActivityManager::class.java)
        Shadow.extract<ShadowActivityManager>(manager).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            availMem = 1024L * 1024 * 1024
        })
        val session = BrowserSession(app, models = null)
        val first = WebView(app)
        session.attach(first)
        session.watch()
        val client = first.webViewClient
        val url = "https://example.org/article"
        client.onPageFinished(first, url)

        client.onRenderProcessGone(first, null)
        assertTrue(session.state.value.teardownRequested)
        session.onRendererReleased(first) { session.releaseRenderer(first) }
        assertTrue(session.state.value.rendererVisible)

        val replacement = WebView(app).apply { layout(0, 0, 300, 300) }
        session.attach(replacement)
        client.onRenderProcessGone(first, null)
        assertFalse(session.state.value.teardownRequested)
        replacement.webViewClient.onRenderProcessGone(replacement, null)
        session.onRendererReleased(replacement) { session.releaseRenderer(replacement) }
        assertFalse(session.state.value.rendererVisible)
        assertTrue(session.state.value.errorRetry)

        val reload = CoroutineScope(Dispatchers.Main).launch { session.reloadFromError() }
        assertTrue(session.state.value.rendererVisible)
        var loadedUrl: String? = null
        val reloaded = object : WebView(app) {
            override fun loadUrl(url: String) {
                loadedUrl = url
                super.loadUrl(url)
            }

            override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                resultCallback?.onReceiveValue("null")
            }
        }.apply { right = 300; bottom = 300 }
        session.attach(reloaded)
        assertEquals(url, loadedUrl)
        reloaded.webViewClient.onPageFinished(reloaded, url)
        assertNull(session.state.value.error)
        assertFalse(reload.isActive)
        session.endSession()
    }
}
