package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHangValveTest {
    @Test fun aFrameGapAtTheThresholdTripsTheValve() {
        val valve = BrowserHangValve(thresholdMs = 2_000)
        assertFalse(valve.onFrame(1_000))
        assertFalse(valve.onFrame(2_500))
        assertTrue(valve.onFrame(4_500))
    }

    @Test fun aResetForgetsTheHangBaseline() {
        val valve = BrowserHangValve(thresholdMs = 2_000)
        valve.onFrame(1_000)
        valve.reset()
        assertFalse(valve.onFrame(9_000))
    }
}
