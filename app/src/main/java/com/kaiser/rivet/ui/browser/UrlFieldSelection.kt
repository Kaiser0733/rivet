package com.kaiser.rivet.ui.browser

/** A focused URL field selects its whole current value so typing replaces it. */
object UrlFieldSelection {
    data class Range(val start: Int, val end: Int) {
        val collapsed: Boolean get() = start == end
    }

    data class FocusedField(val text: String, val selection: Range)

    data class ValueChangeResult(
        val field: FocusedField,
        val keepFullSelection: Boolean,
    )

    /**
     * Tracks focus vs Compose tap-caret placement (`b/209348464`): `onFocusChanged` fires
     * before `onValueChange(tapCaret)`, so a one-shot latch preserves the initial select-all
     * against the immediate tap-offset overwrite while still allowing subsequent taps/edits.
     */
    class Controller(initialUrl: String = "") {
        var wasFocused: Boolean = false
            private set
        var pendingSelectAllOnTap: Boolean = false
            private set
        var field: FocusedField = FocusedField(initialUrl, Range(initialUrl.length, initialUrl.length))
            private set

        fun syncExternalUrl(url: String): FocusedField {
            pendingSelectAllOnTap = false
            field = FocusedField(url, Range(url.length, url.length))
            return field
        }

        fun onFocusChanged(
            isFocused: Boolean,
            currentText: String,
            currentSelection: Range,
        ): FocusedField? {
            if (!wasFocused && isFocused) {
                wasFocused = true
                pendingSelectAllOnTap = true
                field = focus(currentText)
                return field
            }
            if (wasFocused && !isFocused) {
                wasFocused = false
                pendingSelectAllOnTap = false
                field = FocusedField(currentText, currentSelection)
                return null
            }
            wasFocused = isFocused
            return null
        }

        fun onValueChange(incomingText: String, incomingSelection: Range): ValueChangeResult {
            val overrideSelection = pendingSelectAllOnTap && incomingText == field.text
            pendingSelectAllOnTap = false
            if (overrideSelection) {
                field = focus(incomingText)
                return ValueChangeResult(field, keepFullSelection = true)
            }
            field = FocusedField(incomingText, incomingSelection)
            return ValueChangeResult(field, keepFullSelection = false)
        }

        fun onFocusSettled() {
            pendingSelectAllOnTap = false
        }
    }

    fun selection(text: String): Range = Range(0, text.length)

    /** The exact state the composable installs when the URL field gains focus. */
    fun focus(text: String): FocusedField = FocusedField(text, selection(text))

    fun selectAll(text: String): IntRange = selection(text).let { it.start..it.end }
}
