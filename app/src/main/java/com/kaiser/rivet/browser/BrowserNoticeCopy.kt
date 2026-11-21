package com.kaiser.rivet.browser

/**
 * The browser sheet shows these strings to the person holding the phone, so they
 * must never carry tool names or recovery calls. The agent keeps the precise
 * BrowserFailure wording; only this copy reaches the screen.
 */
internal data class BrowserNoticeCopy(val text: String, val retry: Boolean)

internal fun browserNoticeCopy(code: String?, message: String): BrowserNoticeCopy = when (code) {
    "browser_renderer_unavailable" -> BrowserNoticeCopy(
        "The page view didn't start. Try again.", retry = true)
    "browser_renderer_lost" -> BrowserNoticeCopy(
        "The browser closed this page to save memory. Try again.", retry = true)
    // Older failures carried their developer text straight through; never show a
    // recovery call even if one slips past a code we do not know.
    else -> if (looksLikeDeveloperText(message)) {
        BrowserNoticeCopy("The browser hit a problem with this page. Try again.", retry = true)
    } else BrowserNoticeCopy(message, retry = false)
}

private fun looksLikeDeveloperText(message: String): Boolean {
    val lowered = message.lowercase()
    return "browser_reset(" in lowered || "call browser_" in lowered ||
        "degenerate" in lowered || "renderer" in lowered
}

/** Why the first layout wait is still running, finished, or can never finish. */
internal enum class LayoutWait { Ready, Waiting, LostRenderer }

internal fun layoutWait(width: Int, height: Int, rendererLost: Boolean): LayoutWait = when {
    rendererLost -> LayoutWait.LostRenderer
    width > 1 && height > 1 -> LayoutWait.Ready
    else -> LayoutWait.Waiting
}
