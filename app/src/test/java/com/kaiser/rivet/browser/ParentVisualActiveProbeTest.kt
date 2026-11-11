package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Test

/** Parent-input probe: a quiet escalation leaves both visible gates false today. */
class ParentVisualActiveProbeTest {
    @Test fun aQuietEscalatedRendererIsInvisibleToTheOldPolicy() {
        assertFalse(BrowserDrivingPolicy.bannerVisible(
            agentRunActive = true,
            agentVisualActive = false,
            driving = BrowserDriving.Agent,
        ))
        assertFalse(BrowserDrivingPolicy.cardVisible(
            agentRunActive = true,
            agentVisualActive = false,
            watchVisible = true,
            screenVisible = false,
        ))
    }
}
