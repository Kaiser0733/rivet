package com.kaiser.rivet.browser

/** Agent coordinates are 0..1000 in both axes; the WebView gets real pixels. */
object BrowserCoordinates {
    fun pixel(normalized: Int, size: Int): Float {
        val n = normalized.coerceIn(0, 1000)
        return (n / 1000f) * size.coerceAtLeast(1)
    }
}
