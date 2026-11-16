package com.kaiser.rivet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One fold body for every expandable row. The animation owns the height change;
 * callers only publish the open/closed state.
 */
@Composable
internal fun FoldReveal(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(animationSpec = tween(durationMillis = 180)) +
            fadeIn(animationSpec = tween(durationMillis = 120)),
        exit = shrinkVertically(animationSpec = tween(durationMillis = 150)) +
            fadeOut(animationSpec = tween(durationMillis = 90)),
    ) {
        content()
    }
}
