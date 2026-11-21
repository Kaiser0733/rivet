package com.kaiser.rivet.replay

/**
 * The 9:16 frame uses the same gutter on both sides, so the text column must not
 * sit in the left half of the canvas. Values are pre-scale (1080x1920) pixels.
 */
internal const val FRAME_PANEL_LEFT = 72f
internal const val FRAME_PANEL_RIGHT = 1008f
internal const val FRAME_CONTENT_INSET = 40f

internal val FRAME_TEXT_LEFT: Float = FRAME_PANEL_LEFT + FRAME_CONTENT_INSET

internal fun frameTextWidth(): Int =
    (FRAME_PANEL_RIGHT - FRAME_TEXT_LEFT - FRAME_CONTENT_INSET).toInt()

/** Right gutter left between the widest text line and the panel edge. */
internal fun frameTextRight(): Float = FRAME_TEXT_LEFT + frameTextWidth()
