package com.kaiser.rivet.browser

/** One idle clock, two triggers: screen hidden and app backgrounded. */
class BrowserIdleArming {
    var armed: Boolean = false
        private set
    var deadline: Long = 0L
        private set

    fun onHidden(now: Long = System.currentTimeMillis()): Long {
        armed = true
        deadline = now + BrowserIdleTimer.IDLE_MS
        return deadline
    }

    fun onBackground(rendererAlive: Boolean,
                     now: Long = System.currentTimeMillis()): Boolean {
        if (rendererAlive) onHidden(now)
        return armed
    }

    /** True means the deadline passed while backgrounded and teardown must run now. */
    fun onForeground(now: Long = System.currentTimeMillis()): Boolean {
        val expired = armed && BrowserIdleTimer.shouldDestroy(deadline - BrowserIdleTimer.IDLE_MS, now)
        armed = false
        deadline = 0L
        return expired
    }

    fun onActivity(): Boolean {
        armed = false
        deadline = 0L
        return false
    }
}
