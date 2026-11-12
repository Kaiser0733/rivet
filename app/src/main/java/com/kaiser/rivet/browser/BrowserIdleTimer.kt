package com.kaiser.rivet.browser

/** Session-hide starts the visual renderer's idle clock; any user/agent wake cancels it. */
object BrowserIdleTimer {
    const val IDLE_MS = 3 * 60_000L

    fun shouldDestroy(hiddenAtMillis: Long, nowMillis: Long): Boolean =
        hiddenAtMillis > 0L && nowMillis - hiddenAtMillis >= IDLE_MS

    fun shouldEndOnTimer(visible: Boolean, appBackgrounded: Boolean): Boolean =
        !visible || appBackgrounded

    fun remainingMs(hiddenAtMillis: Long, nowMillis: Long): Long =
        if (hiddenAtMillis <= 0L) IDLE_MS else (IDLE_MS - (nowMillis - hiddenAtMillis)).coerceAtLeast(0L)
}
