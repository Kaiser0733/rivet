package com.kaiser.rivet.browser

/** A frame sample taken before and after a one-pixel DOM scroll nudge. */
data class FrameProbe(val scrollY: Int, val hash: String)

/** A post-load frame digest associated with the loaded URL. */
data class NavigationFrame(val url: String, val hash: String)

object PaintStaleness {
    /**
     * The DOM moved but the presented pixels did not: the compositor is showing an
     * older frame. A page that cannot scroll is never called stale by this check.
     */
    fun isStale(before: FrameProbe, after: FrameProbe): Boolean =
        after.scrollY != before.scrollY && before.hash == after.hash

    /**
     * Navigating to a different URL must produce different rendered pixels even on
     * short non-scrollable pages where DOM scroll probes cannot move.
     */
    fun isNavigationStale(previous: NavigationFrame?, current: NavigationFrame): Boolean =
        previous != null &&
            previous.url.isNotBlank() &&
            current.url.isNotBlank() &&
            previous.url != current.url &&
            previous.hash.isNotEmpty() &&
            previous.hash == current.hash
}
