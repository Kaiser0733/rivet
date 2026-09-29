package com.kaiser.rivet.ui.chat

import android.content.res.Configuration

internal fun showsHistoryPane(orientation: Int): Boolean =
    orientation == Configuration.ORIENTATION_LANDSCAPE

internal fun landscapeSidebarWidth(availableWidthDp: Float): Float {
    val width = availableWidthDp.coerceAtLeast(0f)
    val maximum = minOf(320f, width * 0.4f)
    return (width * 0.36f).coerceIn(minOf(220f, maximum), maximum)
}
