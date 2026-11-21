package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.ui.RivetMotion

internal fun effortGraduations(level: ReasoningLevel): Int = when (level) {
    ReasoningLevel.Default -> 0
    ReasoningLevel.Low -> 1
    ReasoningLevel.Medium -> 2
    ReasoningLevel.High -> 3
    ReasoningLevel.XHigh -> 4
    ReasoningLevel.Max -> 5
    ReasoningLevel.Ultra -> 6
}

@Composable
internal fun ReasoningSignal(level: ReasoningLevel, progress: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val count = effortGraduations(level)
    Canvas(modifier) {
        val stroke = RivetMotion.effortSignalStroke.toPx()
        if (size.width <= 2 * stroke || size.height <= 2 * stroke) return@Canvas
        val inset = stroke
        val step = (size.width - 2 * inset) / 5f
        val fade = 1f - ((progress - 0.74f) / 0.26f).coerceIn(0f, 1f)
        repeat(count) { index ->
            val reach = ((progress - index * 0.06f) * 2.8f).coerceIn(0f, 1f)
            val x = inset + index * step
            val bottom = size.height - inset
            val height = (size.height - 2 * inset) * (0.45f + index * 0.11f)
            drawLine(color.copy(alpha = (0.45f + count * 0.08f) * reach * fade),
                start = Offset(x, bottom), end = Offset(x, bottom - height * reach),
                strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
