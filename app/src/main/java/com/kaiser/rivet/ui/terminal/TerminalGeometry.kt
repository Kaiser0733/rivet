package com.kaiser.rivet.ui.terminal

/** Grid dimensions for a terminal pane; the emulator requires at least 2x2. */
internal fun terminalGridSize(
    widthPx: Float,
    heightPx: Float,
    cellWidthPx: Float,
    cellHeightPx: Float,
): Pair<Int, Int> {
    val columns = if (cellWidthPx > 0f) (widthPx / cellWidthPx).toInt() else 80
    val rows = if (cellHeightPx > 0f) (heightPx / cellHeightPx).toInt() else 24
    return columns.coerceAtLeast(2) to rows.coerceAtLeast(2)
}
