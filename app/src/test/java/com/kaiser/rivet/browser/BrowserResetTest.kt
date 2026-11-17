package com.kaiser.rivet.browser

import android.app.Application
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
class BrowserResetTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun resetPreservesProjectAndReportsNoPreviousUrl() = runTest {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        session.projectKey = "project-a"
        val result = session.reset(reopen = true, clearStorage = false)
        assertTrue(result.reset)
        assertTrue(result.rendererKilled)
        assertFalse(result.reopened)
        assertEquals("project-a", session.projectKey)
        assertEquals("There was no previous URL to reopen.", result.detail)
    }

    @Test fun resetAwaitLayoutRejectsADegenerateViewport() = runTest {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val view = WebView(app)
        view.layout(0, 0, 1, 1)
        val failure = runCatching { session.awaitLaidOut(view, timeoutMs = 30) }.exceptionOrNull()
        assertTrue(failure is BrowserFailure)
        assertTrue((failure as BrowserFailure).message.contains("browser_reset(reopen=true)"))
    }
}
