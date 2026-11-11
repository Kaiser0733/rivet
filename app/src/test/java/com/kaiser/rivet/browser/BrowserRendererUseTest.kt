package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRendererUseTest {
    @Test fun aQuietReadEscalationStillCountsAsVisualActivity() {
        assertTrue(BrowserDrivingPolicy.visualActive(
            agentRunActive = true,
            agentRendererUse = true,
            rendererAlive = true,
        ))
    }

    @Test fun aManualOpenWithNoRunIsNotVisualActivity() {
        assertFalse(BrowserDrivingPolicy.visualActive(
            agentRunActive = false,
            agentRendererUse = false,
            rendererAlive = true,
        ))
    }

    @Test fun endingTheRunOrLosingTheRendererDropsVisualActivity() {
        assertFalse(BrowserDrivingPolicy.visualActive(true, true, false))
        assertFalse(BrowserDrivingPolicy.visualActive(false, true, true))
    }
}
