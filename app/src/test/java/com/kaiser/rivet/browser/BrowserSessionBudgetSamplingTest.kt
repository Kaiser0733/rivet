package com.kaiser.rivet.browser

import android.app.Application
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
class BrowserSessionBudgetSamplingTest {
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun onPageFinishedSamplesMemoryAndRaisesNoticeWithoutWaitingFiveSeconds() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val pssBytes = AtomicLong(40L * 1024 * 1024)
        val samples = AtomicInteger(0)
        session.memoryProbe = BrowserMemoryProbe(
            ownPss = {
                samples.incrementAndGet()
                pssBytes.get()
            },
            rendererPss = { null },
        )
        session.userOpen()
        val webView = WebView(app)
        session.attach(webView)
        withTimeout(2_000) {
            while (samples.get() < 1) delay(20)
        }

        val samplesBeforeFinish = samples.get()
        pssBytes.set(40L * 1024 * 1024 + 150L * 1024 * 1024)
        webView.webViewClient.onPageFinished(webView, "https://example.com/heavy")

        val tightened = withTimeout(2_500) {
            session.state.first {
                it.tier == BrowserTier.StrictDiet && it.notice?.contains("diet tightened") == true
            }
        }
        assertTrue(samples.get() > samplesBeforeFinish)
        assertEquals(BrowserTier.StrictDiet, tightened.tier)
        session.endSession()
    }

    @Test fun postLoadSpikeSurfacesWithinPostLoadFollowUpWindow() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val pssBytes = AtomicLong(40L * 1024 * 1024)
        val samples = AtomicInteger(0)
        session.memoryProbe = BrowserMemoryProbe(
            ownPss = {
                samples.incrementAndGet()
                pssBytes.get()
            },
            rendererPss = { null },
        )
        session.userOpen()
        val webView = WebView(app)
        session.attach(webView)
        withTimeout(2_000) {
            while (samples.get() < 1) delay(20)
        }

        webView.webViewClient.onPageFinished(webView, "https://example.com/async-decode")
        withTimeout(2_000) {
            while (samples.get() < 2) delay(20)
        }
        pssBytes.set(40L * 1024 * 1024 + 150L * 1024 * 1024)

        val tightened = withTimeout(3_500) {
            session.state.first {
                it.tier == BrowserTier.StrictDiet && it.notice?.contains("diet tightened") == true
            }
        }
        assertEquals(BrowserTier.StrictDiet, tightened.tier)
        session.endSession()
    }
}
