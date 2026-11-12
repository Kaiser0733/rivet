package com.kaiser.rivet.browser

/** Two-beat rotation restore: never accept a scrollTo clamped by a short page. */
class ScrollRestore {
    var target: Int = 0
        private set
    var attempts: Int = 0
        private set

    fun capture(value: Int) {
        target = value.coerceAtLeast(0)
        attempts = 0
    }

    fun clear() {
        target = 0
        attempts = 0
    }

    /** Returns the offset to scroll to, or null when the layout is still too short. */
    fun attempt(contentHeight: Int): Int? {
        if (target == 0) return 0
        attempts++
        return if (contentHeight >= target) target else null
    }
}
