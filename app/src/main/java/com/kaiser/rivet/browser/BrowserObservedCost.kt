package com.kaiser.rivet.browser

/**
 * The renderer does not expose PSS to the app, but its memory comes out of device
 * headroom. Take the largest of own-process growth, total app-PSS growth, and the
 * drop in available RAM. The app-PSS term is the one that cannot under-read when the
 * renderer's own PID is invisible.
 */
object BrowserObservedCost {
    fun bytes(ownPssGrowthBytes: Long, baselineAvailMemBytes: Long,
              currentAvailMemBytes: Long, appPssGrowthBytes: Long = 0L): Long {
        val own = ownPssGrowthBytes.coerceAtLeast(0L)
        val headroomDrop = (baselineAvailMemBytes - currentAvailMemBytes).coerceAtLeast(0L)
        val app = appPssGrowthBytes.coerceAtLeast(0L)
        return maxOf(own, headroomDrop, app)
    }
}
