package com.kaiser.rivet.browser

/** Visual-browser desktop profile. The text reader never changes its UA. */
object BrowserDesktopMode {
    const val DEFAULT_DESKTOP = true
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"
}
