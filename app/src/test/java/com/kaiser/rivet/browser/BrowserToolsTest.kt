package com.kaiser.rivet.browser

import org.robolectric.RuntimeEnvironment
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BrowserToolsTest {
    private lateinit var tools: BrowserTools

    @Before fun create() {
        val app = RuntimeEnvironment.getApplication()
        tools = BrowserTools(BrowserSession(app, null))
    }

    @Test fun thereIsNoEvalTool() = runBlocking {
        assertNull(tools.prepare(AgentToolCall("1", "browser_eval", "{}")))
        assertTrue(BrowserTools.NAMES.none { it == "browser_eval" })
    }

    @Test fun theDefinitionsCarryEveryTypedAction() {
        assertEquals(setOf("browser_open", "browser_read", "browser_find", "browser_click",
            "browser_type", "browser_scroll", "browser_back", "browser_shot"),
            BrowserTools.definitions.map { it.name }.toSet())
    }

    @Test fun invalidArgumentsBecomeAFailureRatherThanACrash() = runBlocking {
        val prepared = tools.prepare(AgentToolCall("2", "browser_open", "{}"))!!
        val result = prepared.execute()
        assertTrue(result.error)
        assertTrue(result.content.contains("browser_invalid_url"))
    }
}
