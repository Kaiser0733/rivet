package com.kaiser.rivet.ui.browser

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Page content never goes under browser chrome. Insets are added to the bar bands. */
object BrowserChrome {
    const val TOP_BAR_DP = 64
    const val BOTTOM_BAR_DP = 52

    fun topPadding(statusBar: Dp): Dp = TOP_BAR_DP.dp + statusBar

    /** The host uses the chrome column's measured height, not a second constant. */
    fun hostTopPaddingPx(measuredChromePx: Int): Int =
        BrowserChromeHeight.hostTopPaddingPx(measuredChromePx)
    fun bottomPadding(navigationBar: Dp): Dp = BOTTOM_BAR_DP.dp + navigationBar
}
