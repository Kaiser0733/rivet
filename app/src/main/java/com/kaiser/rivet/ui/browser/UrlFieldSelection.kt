package com.kaiser.rivet.ui.browser

/** Pure selection helpers. Focus/tap handling lives in BrowserScreen's overlay. */
object UrlFieldSelection {
    data class Range(val start: Int, val end: Int) {
        val collapsed: Boolean get() = start == end
    }

    data class FocusedField(val text: String, val selection: Range)

    fun selection(text: String): Range = Range(0, text.length)

    fun focus(text: String): FocusedField = FocusedField(text, selection(text))

    fun selectAll(text: String): IntRange = selection(text).let { it.start..it.end }
}
