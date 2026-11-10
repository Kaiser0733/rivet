package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonPrimitive

class BrowserToolsTest {
    @Test fun thereIsNoEvalTool() {
        assertTrue(BrowserTools.NAMES.none { it == "browser_eval" })
    }

    @Test fun theDefinitionsCarryEveryTypedAction() {
        assertEquals(setOf("browser_open", "browser_read", "browser_find", "browser_click",
            "browser_type", "browser_scroll", "browser_back", "browser_shot"),
            BrowserTools.definitions.map { it.name }.toSet())
    }

    @Test fun everyDefinitionHasAnObjectSchema() {
        assertTrue(BrowserTools.definitions.all { it.parameters["type"]?.jsonPrimitive?.content == "object" })
    }
}
