package com.kaiser.rivet.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

/**
 * One fold body for every expandable row. The body is driven by the frame clock
 * directly so a zero system motion scale cannot turn the reveal into a one-frame jump.
 */
@Composable
internal fun FoldReveal(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(if (expanded) 1f else 0f) }
    var composed by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) composed = true
        val target = if (expanded) 1f else 0f
        val duration = if (expanded) FoldMotion.EXPAND_MS else FoldMotion.COLLAPSE_MS
        val from = progress
        if (from != target) {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val fraction = ((now - start) / 1_000_000f / duration).coerceIn(0f, 1f)
                progress = from + (target - from) * FoldMotion.ease(fraction)
                if (fraction >= 1f) break
            }
        }
        progress = target
        if (!expanded) composed = false
    }
    Layout(
        content = { if (composed) content() },
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val fullHeight = placeables.maxOfOrNull { it.height } ?: 0
        val fullWidth = placeables.maxOfOrNull { it.width } ?: 0
        val height = (fullHeight * progress).roundToInt().coerceIn(0, fullHeight)
        layout(fullWidth, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/** Shared fold timing and a frame-clock-driven chevron rotation. */
internal object FoldMotion {
    const val EXPAND_MS = 180
    const val COLLAPSE_MS = 150

    fun ease(fraction: Float): Float = FastOutSlowInEasing.transform(fraction.coerceIn(0f, 1f))
}

@Composable
internal fun foldChevronRotation(open: Boolean): Float {
    var rotation by remember { mutableFloatStateOf(if (open) 180f else 0f) }
    LaunchedEffect(open) {
        val target = if (open) 180f else 0f
        val duration = if (open) FoldMotion.EXPAND_MS else FoldMotion.COLLAPSE_MS
        val from = rotation
        if (from != target) {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val fraction = ((now - start) / 1_000_000f / duration).coerceIn(0f, 1f)
                rotation = from + (target - from) * FoldMotion.ease(fraction)
                if (fraction >= 1f) break
            }
        }
        rotation = target
    }
    return rotation
}
