package com.kaiser.rivet.browser

/**
 * The renderer does not expose PSS to the app, but its memory comes out of device
 * headroom. Take the larger of own-process growth and the drop in available RAM.
 */
object BrowserObservedCost {
    fun bytes(ownPssGrowthBytes: Long, baselineAvailMemBytes: Long,
              currentAvailMemBytes: Long): Long {
        val own = ownPssGrowthBytes.coerceAtLeast(0L)
        val headroomDrop = (baselineAvailMemBytes - currentAvailMemBytes).coerceAtLeast(0L)
        return maxOf(own, headroomDrop)
    }
}
