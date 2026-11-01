package com.kaiser.rivet.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A provider can send hundreds of deltas a second; the screen can show a few dozen.
class PreviewWindowTest {
    private val millis = 1_000_000L

    @Test fun theFirstDeltaAlwaysPublishes() {
        assertTrue(PreviewWindow().shouldPublish(nowNanos = 5_000 * millis))
    }

    @Test fun deltasInsideTheWindowAreDropped() {
        val window = PreviewWindow()
        assertTrue(window.shouldPublish(1_000 * millis))
        assertFalse(window.shouldPublish(1_010 * millis))
        assertFalse(window.shouldPublish(1_079 * millis))
    }

    @Test fun theFirstDeltaAfterTheWindowPublishes() {
        val window = PreviewWindow()
        assertTrue(window.shouldPublish(1_000 * millis))
        assertTrue(window.shouldPublish(1_080 * millis))
        assertTrue(window.shouldPublish(1_400 * millis))
    }

    @Test fun aClockThatStepsBackwardsCannotWedgeThePreview() {
        val window = PreviewWindow()
        assertTrue(window.shouldPublish(2_000 * millis))
        assertTrue("a backwards step must not silence later deltas", window.shouldPublish(1_000 * millis))
        assertFalse(window.shouldPublish(1_010 * millis))
    }

    @Test fun aResetLetsTheNextDeltaThrough() {
        val window = PreviewWindow()
        assertTrue(window.shouldPublish(1_000 * millis))
        window.reset()
        assertTrue(window.shouldPublish(1_001 * millis))
    }
}
