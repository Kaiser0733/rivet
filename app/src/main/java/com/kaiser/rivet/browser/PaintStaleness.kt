package com.kaiser.rivet.browser

/** A frame sample taken before and after a one-pixel DOM scroll nudge. */
data class FrameProbe(val scrollY: Int, val hash: String)

object PaintStaleness {
    /**
     * The DOM moved but the presented pixels did not: the compositor is showing an
     * older frame. A page that cannot scroll is never called stale by this check.
     */
    fun isStale(before: FrameProbe, after: FrameProbe): Boolean =
        after.scrollY != before.scrollY && before.hash == after.hash
}
