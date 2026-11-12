package com.kaiser.rivet.ui.browser

/** Pure host inset rule: the layout reports the measured chrome, the host uses it. */
object BrowserChromeHeight {
    fun hostTopPaddingPx(measuredChromePx: Int): Int = measuredChromePx.coerceAtLeast(0)
}
