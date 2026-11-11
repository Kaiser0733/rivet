package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserIdleArmingTest {
    @Test fun backgroundingArmsOnlyWhenARendererExists() {
        val arming = BrowserIdleArming()
        assertFalse(arming.onBackground(rendererAlive = false))
        assertTrue(arming.onBackground(rendererAlive = true))
        assertTrue(arming.armed)
    }

    @Test fun foregroundingCancelsTheBackgroundClock() {
        val arming = BrowserIdleArming()
        arming.onBackground(rendererAlive = true)
        assertFalse(arming.onForeground())
        assertFalse(arming.armed)
    }

    @Test fun userActivityCancelsTheBackgroundClock() {
        val arming = BrowserIdleArming()
        arming.onBackground(rendererAlive = true)
        assertFalse(arming.onActivity())
        assertFalse(arming.armed)
    }
}
