package com.kaiser.rivet.browser

import android.app.Application
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
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
class BrowserWaitForTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun waitRequiresAtLeastOneConditionAndNamesThem() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val tools = BrowserTools(BrowserSession(app, models = null))
        val prepared = tools.prepare(AgentToolCall("w", "browser_wait_for", "{}"))!!
        val result = prepared.execute()
        assertTrue(result.error)
        assertTrue(result.content.contains("invalid_arguments"))
        assertTrue(result.content.contains("selector"))
        assertTrue(result.content.contains("network_idle"))
    }
}
