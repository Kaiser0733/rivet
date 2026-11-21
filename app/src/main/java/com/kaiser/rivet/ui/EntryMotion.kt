package com.kaiser.rivet.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

@Composable
internal fun motionDisabled(): Boolean =
    rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f

@Composable
internal fun <T> rivetMotionSpec(duration: Int): FiniteAnimationSpec<T> =
    if (motionDisabled()) snap() else tween(durationMillis = duration, easing = RivetMotion.easing)

@Composable
internal fun entryProgress(token: Any?, animate: Boolean, duration: Int): Float {
    val static = motionDisabled()
    val progress = remember(token, animate) { Animatable(if (animate && !static) 0f else 1f) }
    LaunchedEffect(progress, static) {
        if (static) progress.snapTo(1f) else progress.animateTo(1f, tween(duration, easing = RivetMotion.easing))
    }
    return if (static) 1f else progress.value
}

internal fun Modifier.pageEntry(progress: Float, offset: Dp): Modifier = graphicsLayer {
    alpha = 0.65f + 0.35f * progress
    translationX = offset.toPx() * (1f - progress)
}

internal fun Modifier.messageEntry(progress: Float): Modifier = graphicsLayer {
    alpha = 0.5f + 0.5f * progress
    translationY = RivetMotion.messageRise.toPx() * (1f - progress)
}

internal fun Modifier.panelEntry(progress: Float): Modifier = graphicsLayer {
    alpha = progress
    translationY = RivetMotion.panelRise.toPx() * (1f - progress)
}
