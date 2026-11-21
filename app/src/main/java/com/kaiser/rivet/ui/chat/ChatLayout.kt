package com.kaiser.rivet.ui.chat

internal const val CHAT_CONTENT_MAX_WIDTH_DP = 748f
internal const val USER_BUBBLE_MAX_WIDTH_RATIO = 0.702f
internal const val USER_BUBBLE_MAX_PARENT_FRACTION = 0.82f

internal fun userBubbleMaxWidth(availableWidthDp: Float): Float {
    val width = availableWidthDp.coerceAtLeast(0f)
    return minOf(CHAT_CONTENT_MAX_WIDTH_DP * USER_BUBBLE_MAX_WIDTH_RATIO, width * USER_BUBBLE_MAX_PARENT_FRACTION)
}
