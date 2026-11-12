package com.kaiser.rivet.ui.browser

/** A focused URL field selects its whole current value so typing replaces it. */
object UrlFieldSelection {
    data class Range(val start: Int, val end: Int) {
        val collapsed: Boolean get() = start == end
    }

    fun selection(text: String): Range = Range(0, text.length)

    fun selectAll(text: String): IntRange = selection(text).let { it.start..it.end }
}
