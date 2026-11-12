package com.kaiser.rivet.ui.browser

/** A focused URL field selects its whole current value so typing replaces it. */
object UrlFieldSelection {
    data class Range(val start: Int, val end: Int) {
        val collapsed: Boolean get() = start == end
    }

    data class FocusedField(val text: String, val selection: Range)

    fun selection(text: String): Range = Range(0, text.length)

    /** The exact state the composable installs when the URL field gains focus. */
    fun focus(text: String): FocusedField = FocusedField(text, selection(text))

    fun selectAll(text: String): IntRange = selection(text).let { it.start..it.end }
}
