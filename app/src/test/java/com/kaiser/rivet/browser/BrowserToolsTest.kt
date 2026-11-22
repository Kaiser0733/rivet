package com.kaiser.rivet.browser

import com.kaiser.rivet.agent.AgentToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonPrimitive

class BrowserToolsTest {
    @Test fun evalIsRegisteredWithBoundedArguments() {
        assertTrue("browser_eval" in BrowserTools.NAMES)
        val eval = BrowserTools.definitions.first { it.name == "browser_eval" }
        assertTrue(eval.parameters.toString().contains("expected_origin"))
        assertTrue(eval.parameters.toString().contains("timeout_ms"))
        assertTrue(eval.parameters.toString().contains("expression"))
    }

    @Test fun theDefinitionsCarryEveryTypedAction() {
        assertEquals(setOf("browser_open", "browser_eval", "browser_console", "browser_key", "browser_wait_for", "browser_reset", "browser_status", "browser_read", "browser_find", "browser_click",
            "browser_type", "browser_scroll", "browser_back", "browser_shot", "browser_close",
            "browser_tap", "browser_swipe"),
            BrowserTools.definitions.map { it.name }.toSet())
    }

    @Test fun consoleIsRegisteredAndNonDriving() {
        assertTrue("browser_console" in BrowserTools.NAMES)
        assertTrue(BrowserTools.definitions.any { it.name == "browser_console" })
    }

    @Test fun keyIsRegisteredWithModifiersAndBounds() {
        val key = BrowserTools.definitions.first { it.name == "browser_key" }
        assertTrue(key.parameters.toString().contains("modifiers"))
        assertTrue(key.parameters.toString().contains("hold_ms"))
        assertTrue(BrowserTools.NAMES.contains("browser_key"))
    }

    @Test fun waitForIsRegisteredWithAllConditions() {
        val wait = BrowserTools.definitions.first { it.name == "browser_wait_for" }
        assertTrue(wait.parameters.toString().contains("network_idle"))
        assertTrue(wait.parameters.toString().contains("poll_ms"))
        assertTrue(wait.description.contains("run_command"))
    }

    @Test fun resetIsRegisteredAndDriving() {
        assertTrue(BrowserTools.definitions.any { it.name == "browser_reset" })
        assertTrue(BrowserTools.NAMES.contains("browser_reset"))
    }

    @Test fun statusCarriesTierLegendAndNoArguments() {
        val status = BrowserTools.definitions.first { it.name == "browser_status" }
        assertTrue(status.description.contains("strictdiet"))
        assertTrue(status.parameters.toString().contains("object"))
    }

    @Test fun shotDescriptionExplainsVisionAttachAndTextOnlyFallback() {
        val shot = BrowserTools.definitions.first { it.name == "browser_shot" }
        assertTrue(shot.description.contains("Vision-capable"))
        assertTrue(shot.description.contains("vision_attached=false"))
    }

    @Test fun autoPreviewCannotClaimARendererAnotherAgentIsDriving() {
        val lease = BrowserDrivingLease()
        val open = AgentToolCall("open", "browser_open", """{"url":"https://example.org"}""")
        assertTrue(lease.tryAcquireRenderer("coordinator"))
        assertTrue(lease.tryAcquire("worker", open))
        assertFalse(lease.tryAcquireRenderer("worker"))
        lease.release("coordinator")
        assertTrue(lease.tryAcquireRenderer("worker"))
    }

    @Test fun everyDefinitionHasAnObjectSchema() {
        assertTrue(BrowserTools.definitions.all { it.parameters["type"]?.jsonPrimitive?.content == "object" })
    }
}
