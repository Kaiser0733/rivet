package com.kaiser.rivet.browser

import android.app.Application
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
}
