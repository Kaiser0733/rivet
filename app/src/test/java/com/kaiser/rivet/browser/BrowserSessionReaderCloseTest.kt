package com.kaiser.rivet.browser

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserSessionReaderCloseTest {
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun closingActiveTabInReaderModeLoadsSurvivingPageOnANewRenderer() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(ActivityManager::class.java)
        Shadow.extract<ShadowActivityManager>(manager).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            availMem = 1024L * 1024 * 1024
        })
        val snapshotDir = File(app.filesDir, "browser-snapshots")
        snapshotDir.deleteRecursively()
        BrowserSnapshotStore(snapshotDir).save(
            BrowserSnapshot(
                id = "win-surviving",
                title = "Surviving",
                historyUrls = listOf("https://first.example/page"),
                historyIndex = 0,
                formFields = mapOf("q" to "rivet"),
            ),
        )
        val session = BrowserSession(app, models = null)
        val evaluatedScripts = CopyOnWriteArrayList<String>()
        val loadedUrls = CopyOnWriteArrayList<String>()
        val webView = object : WebView(app) {
            override fun loadUrl(url: String) {
                loadedUrls += url
                super.loadUrl(url)
            }

            override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                evaluatedScripts += script
                when {
                    script.contains("outerHTML") -> {
                        val html = "<html><body><article><h1>Article</h1><p>Readable body text long enough to exceed the extractor threshold for offline reader tests.</p></article></body></html>"
                        resultCallback?.onReceiveValue(JSONObject.quote(html))
                    }
                    script.contains("innerText") -> {
                        resultCallback?.onReceiveValue(JSONObject.quote("Readable body text"))
                    }
                    else -> super.evaluateJavascript(script, resultCallback)
                }
            }
        }
        // loadSnapshotState posts the restore onto the WebView. An unattached View
        // queues that post until attach, so mount it in an Activity as BrowserHost does.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(webView)
        session.attach(webView)
        session.newWindow()
        val closingId = session.state.value.activeWindowId!!
        webView.webViewClient.onPageFinished(webView, "https://second.example/article")

        session.showReader()
        assertTrue(session.state.value.teardownRequested)
        assertTrue(session.state.value.readerMode)
        session.onRendererReleased(webView) { session.releaseRenderer(webView) }
        assertFalse(session.state.value.rendererVisible)

        val replacement = object : WebView(app) {
            override fun loadUrl(url: String) {
                loadedUrls += url
                super.loadUrl(url)
            }

            override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                evaluatedScripts += script
                resultCallback?.onReceiveValue("null")
            }
        }
        activity.setContentView(replacement)
        replacement.layout(0, 0, 300, 300)
        loadedUrls.clear()
        evaluatedScripts.clear()
        session.closeWindow(closingId)
        session.attach(replacement)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(session.state.value.readerMode)
        assertEquals("win-surviving", session.state.value.activeWindowId)
        assertEquals("https://first.example/page", session.state.value.url)
        assertTrue(loadedUrls.contains("https://first.example/page"))
        assertFalse(session.state.value.pageReady)
        assertTrue(
            "Snapshot restore JS must wait for onPageFinished instead of racing before load",
            evaluatedScripts.none { it.contains("querySelectorAll") },
        )

        replacement.webViewClient.onPageFinished(replacement, "https://first.example/page")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.state.value.pageReady)
        assertTrue(
            "Deferred snapshot restore must run once onPageFinished completes",
            evaluatedScripts.any { it.contains("querySelectorAll") },
        )
        session.endSession()
    }
}
