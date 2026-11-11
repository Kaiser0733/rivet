package com.kaiser.rivet.browser

/**
 * Main-thread frame-gap valve for loopback previews, where the memory cap is
 * intentionally disabled. A frame callback that arrives far later than the previous
 * one means the UI thread (and the page) is hanging, not merely using memory.
 */
class BrowserHangValve(private val thresholdMs: Long = 2_000L) {
    private var lastFrameMs: Long? = null

    fun reset() {
        lastFrameMs = null
    }

    fun onFrame(nowMs: Long): Boolean {
        val previous = lastFrameMs
        lastFrameMs = nowMs
        return previous != null && nowMs - previous >= thresholdMs
    }
}
