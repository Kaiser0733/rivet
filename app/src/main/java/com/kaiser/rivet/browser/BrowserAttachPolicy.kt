package com.kaiser.rivet.browser

enum class BrowserAttachAction { Attach, ReleaseFieldOnly }

/** Attach never owns destruction; the old view's onRelease stale branch does. */
object BrowserAttachPolicy {
    fun onAttach(oldAttached: Boolean): BrowserAttachAction =
        if (oldAttached) BrowserAttachAction.ReleaseFieldOnly else BrowserAttachAction.Attach
}
