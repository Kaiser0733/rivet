package com.kaiser.rivet.ui.terminal

internal object TerminalScrollPolicy {
    fun cursorScrollTargetRow(
        follow: Boolean,
        transcriptRows: Int,
        totalRows: Int,
        cursorIndex: Int,
        visibleRows: Int,
    ): Int? {
        if (!follow || totalRows <= 0) return null
        if (transcriptRows > 0) return totalRows - 1
        if (visibleRows in 1..cursorIndex) return cursorIndex.coerceAtMost(totalRows - 1)
        return null
    }
}
