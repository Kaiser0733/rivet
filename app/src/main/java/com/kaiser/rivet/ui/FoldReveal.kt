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
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext

/**
 * One fold body for every expandable row. The body is driven by the frame clock
 * directly, with the composition’s motion scale applied to its elapsed time.
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
        animateFoldValue(progress, target, duration) { progress = it }
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
        animateFoldValue(rotation, target, duration) { rotation = it }
    }
    return rotation
}


@Composable
internal fun foldOutcomeProgress(reveal: Boolean): Float {
    var progress by remember { mutableFloatStateOf(if (reveal) 0f else 1f) }
    LaunchedEffect(Unit) {
        animateFoldValue(progress, 1f, FoldMotion.EXPAND_MS) { progress = it }
    }
    return progress
}

private suspend fun animateFoldValue(from: Float, target: Float, duration: Int, update: (Float) -> Unit) {
    val motion = currentCoroutineContext()[MotionDurationScale]
    fun scale(): Float = motion?.scaleFactor ?: 1f
    if (from == target || scale() == 0f) {
        update(target)
        return
    }
    var previous = withFrameNanos { it }
    var elapsed = 0f
    while (elapsed < duration) {
        val now = withFrameNanos { it }
        val scale = scale()
        if (scale == 0f) break
        elapsed += (now - previous) / 1_000_000f / scale
        previous = now
        update(from + (target - from) * FoldMotion.ease(elapsed / duration))
    }
    update(target)
}
