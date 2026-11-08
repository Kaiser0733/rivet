package com.kaiser.rivet.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// The right-hand slide. Material3 ships one drawer, and faking a second with a layout
// direction fights RTL, so this is the panel itself: a scrim that dims the chat and a
// surface that slides in from the end. The left slide is ModalNavigationDrawer.
// ponytail: one float drives both the slide and the scrim, so there is no animation
// state of its own to keep in sync; upgrade to AnimatedVisibility if more moves in here.
@Composable
internal fun RightSlidePanel(open: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    val scrim = remember { MutableInteractionSource() }
    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(durationMillis = if (open) 220 else 160),
    )
    Box(Modifier.fillMaxSize()) {
        if (progress > 0f) {
            Box(Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f * progress))
                .clickable(interactionSource = scrim, indication = null) { onClose() })
        }
        // The pane is composed only while it can be seen, so a closed panel's process
        // ticker does not run behind the chat.
        if (open || progress > 0f) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(0.88f)
                        .graphicsLayer { translationX = (1f - progress) * size.width },
                ) {
                    content()
                }
            }
        }
    }
}
