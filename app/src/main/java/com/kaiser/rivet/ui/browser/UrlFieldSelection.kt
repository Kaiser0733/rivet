package com.kaiser.rivet.ui.browser

/** A focused URL field selects its whole current value so typing replaces it. */
object UrlFieldSelection {
    fun selectAll(text: String): IntRange = 0..text.length
}
