package com.kaiser.rivet.browser

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserSessionPaintSamplingTest {
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun attachSizedWebView(app: Application, session: BrowserSession) {
        val webView = WebView(app)
        webView.layout(0, 0, 128, 128)
        session.attach(webView)
        // Lay out after attach as the host does; samplePaint must see a non-zero size.
        webView.layout(0, 0, 128, 128)
    }

    private fun writeStripedShot(dir: File, name: String, offset: Int): BrowserShot {
        dir.mkdirs()
        val file = File(dir, name)
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) {
            val color = if ((y + offset) % 2 == 0) Color.BLUE else Color.YELLOW
            for (x in 0 until 128) {
                bmp.setPixel(x, y, color)
            }
        }
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        return BrowserShot(file.absolutePath, 128, 128)
    }

    @Test fun healthyScrollablePageSamplesBeforeRestoringScrollAndReturnsPainted() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        attachSizedWebView(app, session)

        val scrollY = AtomicInteger(0)
        val shotCounter = AtomicInteger(0)
        val shotsDir = File(app.cacheDir, "test-paint-shots-${System.nanoTime()}")
        val capturedAtScroll = mutableListOf<Int>()

        session.paintJsEvaluator = { _, script ->
            when {
                script.contains("innerText.length") -> "120"
                script == "window.scrollY" -> scrollY.get().toString()
                script.startsWith("window.scrollBy(") -> {
                    val match = Regex("""window\.scrollBy\(0,\s*(-?\d+)\)""").find(script)
                    val delta = match?.groupValues?.get(1)?.toInt() ?: 0
                    scrollY.set((scrollY.get() + delta).coerceAtLeast(0))
                    "null"
                }
                else -> "null"
            }
        }
        session.paintCapture = { _ ->
            val y = scrollY.get()
            capturedAtScroll += y
            writeStripedShot(shotsDir, "shot-${shotCounter.incrementAndGet()}.png", offset = y)
        }

        val verdict = session.samplePaint("https://example.com/scrollable")
        assertEquals(PaintVerdict.Painted, verdict)
        assertEquals(listOf(0, 1), capturedAtScroll)
        assertEquals(0, scrollY.get())

        shotsDir.deleteRecursively()
        session.endSession()
    }

    @Test fun wedgedScrollablePageWithIdenticalPixelsReportsStale() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        attachSizedWebView(app, session)

        val scrollY = AtomicInteger(0)
        val shotCounter = AtomicInteger(0)
        val shotsDir = File(app.cacheDir, "test-paint-stale-${System.nanoTime()}")

        session.paintJsEvaluator = { _, script ->
            when {
                script.contains("innerText.length") -> "120"
                script == "window.scrollY" -> scrollY.get().toString()
                script.startsWith("window.scrollBy(") -> {
                    val match = Regex("""window\.scrollBy\(0,\s*(-?\d+)\)""").find(script)
                    val delta = match?.groupValues?.get(1)?.toInt() ?: 0
                    scrollY.set((scrollY.get() + delta).coerceAtLeast(0))
                    "null"
                }
                else -> "null"
            }
        }
        // Always emit identical pixels regardless of scrollY to simulate a wedged surface.
        session.paintCapture = { _ ->
            writeStripedShot(shotsDir, "shot-${shotCounter.incrementAndGet()}.png", offset = 0)
        }

        val verdict = session.samplePaint("https://example.com/wedged")
        assertEquals(PaintVerdict.Stale, verdict)
        assertEquals(0, scrollY.get())

        shotsDir.deleteRecursively()
        session.endSession()
    }
}
