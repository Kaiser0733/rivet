package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserIdleArmingDeadlineTest {
    @Test fun backgroundingStoresTheDeadlineAndForegroundingEnforcesIt() {
        val arming = BrowserIdleArming()
        val now = 10_000L
        assertTrue(arming.onBackground(rendererAlive = true, now = now))
        assertEquals(now + BrowserIdleTimer.IDLE_MS, arming.deadline)
        assertTrue(arming.onForeground(now + BrowserIdleTimer.IDLE_MS))
        assertFalse(arming.armed)
    }

    @Test fun returningBeforeTheDeadlineCancelsWithoutTeardown() {
        val arming = BrowserIdleArming()
        arming.onBackground(rendererAlive = true, now = 10_000L)
        assertFalse(arming.onForeground(10_000L + BrowserIdleTimer.IDLE_MS - 1))
        assertFalse(arming.armed)
    }
    @Test fun aHiddenBrowserKeepsItsOriginalDeadlineAcrossAnEarlyForeground() {
        val arming = BrowserIdleArming()
        arming.onBackground(rendererAlive = true, now = 10_000L)
        val deadline = arming.deadline
        val now = 10_000L + BrowserIdleTimer.IDLE_MS / 2
        assertTrue(BrowserIdleTimer.shouldRearmHiddenBrowser(
            visible = false, wasArmed = arming.armed, deadline = deadline, now = now))
        arming.onForeground(now)
        arming.armUntil(deadline)
        assertTrue(arming.armed)
        assertEquals(deadline, arming.deadline)
        assertEquals(BrowserIdleTimer.IDLE_MS / 2, BrowserIdleTimer.remainingMs(10_000L, now))
    }

    @Test fun aVisibleBrowserDoesNotNeedAHiddenRearmAndAnExpiredOneDoesNotRearm() {
        assertFalse(BrowserIdleTimer.shouldRearmHiddenBrowser(
            visible = true, wasArmed = true, deadline = 20_000L, now = 10_000L))
        assertFalse(BrowserIdleTimer.shouldRearmHiddenBrowser(
            visible = false, wasArmed = true, deadline = 10_000L, now = 10_000L))
        assertFalse(BrowserIdleTimer.shouldRearmHiddenBrowser(
            visible = false, wasArmed = false, deadline = 20_000L, now = 10_000L))
    }

    @Test fun onBackgroundDoesNotExtendAnAlreadyArmedDeadline() {
        val arming = BrowserIdleArming()
        val firstDeadline = arming.onHidden(now = 10_000L)
        assertTrue(arming.onBackground(rendererAlive = true, now = 120_000L))
        assertEquals(firstDeadline, arming.deadline)
    }

}
