package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserIdleTimerTest {
    @Test fun aHiddenSessionDiesAfterThreeMinutes() {
        val hidden = 10_000L
        assertFalse(BrowserIdleTimer.shouldDestroy(hidden, hidden + BrowserIdleTimer.IDLE_MS - 1))
        assertTrue(BrowserIdleTimer.shouldDestroy(hidden, hidden + BrowserIdleTimer.IDLE_MS))
        assertEquals(0L, BrowserIdleTimer.remainingMs(hidden, hidden + BrowserIdleTimer.IDLE_MS + 1))
    }

    @Test fun neverHiddenDoesNotExpire() {
        assertFalse(BrowserIdleTimer.shouldDestroy(0L, Long.MAX_VALUE))
    }
}
