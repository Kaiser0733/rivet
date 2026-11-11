package com.kaiser.rivet.browser

/** One idle clock, two triggers: screen hidden and app backgrounded. */
class BrowserIdleArming {
    var armed: Boolean = false
        private set

    fun onHidden(): Boolean {
        armed = true
        return true
    }

    fun onBackground(rendererAlive: Boolean): Boolean {
        if (rendererAlive) armed = true
        return armed
    }

    fun onForeground(): Boolean {
        armed = false
        return false
    }

    fun onActivity(): Boolean {
        armed = false
        return false
    }
}
