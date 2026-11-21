package com.kaiser.rivet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    val instant = foldMotionDisabled()
    val target = if (expanded) 1f else 0f
    var progress by remember { mutableFloatStateOf(target) }
    var composed by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded, instant) {
        if (expanded) composed = true
        val duration = if (expanded) FoldMotion.EXPAND_MS else FoldMotion.COLLAPSE_MS
        animateFoldValue(progress, target, duration) { progress = it }
        if (!expanded) composed = false
    }
    Layout(
        content = { if (if (instant) expanded else composed) content() },
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val fullHeight = placeables.maxOfOrNull { it.height } ?: 0
        val fullWidth = placeables.maxOfOrNull { it.width } ?: 0
        val height = (fullHeight * (if (instant) target else progress)).roundToInt().coerceIn(0, fullHeight)
        layout(fullWidth, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/** Shared fold timing and a frame-clock-driven chevron rotation. */
internal object FoldMotion {
    const val EXPAND_MS = RivetMotion.CHAT_FOLD_EXPAND_MS
    const val COLLAPSE_MS = RivetMotion.CHAT_FOLD_COLLAPSE_MS

    fun ease(fraction: Float): Float = RivetMotion.easing.transform(fraction.coerceIn(0f, 1f))
}

@Composable
internal fun foldChevronRotation(open: Boolean): Float {
    val instant = foldMotionDisabled()
    val target = if (open) 180f else 0f
    var rotation by remember { mutableFloatStateOf(target) }
    LaunchedEffect(open, instant) {
        val duration = if (open) FoldMotion.EXPAND_MS else FoldMotion.COLLAPSE_MS
        animateFoldValue(rotation, target, duration) { rotation = it }
    }
    return if (instant) target else rotation
}

@Composable
internal fun foldOutcomeProgress(reveal: Boolean): Float {
    val instant = foldMotionDisabled()
    var progress by remember { mutableFloatStateOf(if (reveal && !instant) 0f else 1f) }
    LaunchedEffect(instant) {
        animateFoldValue(progress, 1f, FoldMotion.EXPAND_MS) { progress = it }
    }
    return if (instant) 1f else progress
}

@Composable
private fun foldMotionDisabled(): Boolean =
    rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f

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
