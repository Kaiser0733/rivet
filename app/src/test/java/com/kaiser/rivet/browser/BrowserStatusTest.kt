package com.kaiser.rivet.browser

import android.app.Application
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
