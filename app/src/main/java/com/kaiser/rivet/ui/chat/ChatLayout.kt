package com.kaiser.rivet.ui.chat

internal const val CHAT_CONTENT_MAX_WIDTH_DP = 748f
internal const val USER_BUBBLE_MAX_WIDTH_RATIO = 0.702f
internal const val USER_BUBBLE_MAX_PARENT_FRACTION = 0.82f

internal fun userBubbleMaxWidth(availableWidthDp: Float): Float {
    val width = availableWidthDp.coerceAtLeast(0f)
    return minOf(CHAT_CONTENT_MAX_WIDTH_DP * USER_BUBBLE_MAX_WIDTH_RATIO, width * USER_BUBBLE_MAX_PARENT_FRACTION)
}


internal fun conversationWidth(availableWidthDp: Float): Float =
    availableWidthDp.coerceIn(0f, 1_040f)

internal fun chatPaneFraction(availableWidthDp: Float, requested: Float): Float {
    val usable = (availableWidthDp - 14f).coerceAtLeast(0f)
    if (usable < 540f) return requested.coerceIn(0.42f, 0.58f)
    val minimum = 270f / usable
    return requested.coerceIn(maxOf(0.32f, minimum), minOf(0.72f, 1f - minimum))
}
