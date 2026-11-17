package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
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
        assertEquals(setOf("browser_open", "browser_eval", "browser_console", "browser_key", "browser_read", "browser_find", "browser_click",
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

    @Test fun everyDefinitionHasAnObjectSchema() {
        assertTrue(BrowserTools.definitions.all { it.parameters["type"]?.jsonPrimitive?.content == "object" })
    }
}
