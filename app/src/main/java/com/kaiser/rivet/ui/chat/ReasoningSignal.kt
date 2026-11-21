package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
internal fun ReasoningSignal(effect: EffortMotion, progress: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val length = min(size.width * 0.27f, 76.dp.toPx())
        val center = Offset(size.width - length * 0.68f, size.height * 0.50f)
        val advance = (progress * 1.55f).coerceAtMost(1f)
        val opacity = effect.strength * (1f - ((progress - 0.68f) / 0.32f).coerceIn(0f, 1f))
        val ink = color.copy(alpha = opacity)
        val stroke = 1.6.dp.toPx()
        fun segment(x: Float, y: Float, endX: Float, endY: Float) {
            val start = center + Offset(x * length, y * length)
            val end = center + Offset((x + (endX - x) * advance) * length,
                (y + (endY - y) * advance) * length)
            drawLine(ink, start, end, stroke, StrokeCap.Round)
        }
        when (effect.pattern) {
            EffortPattern.Line -> segment(-0.45f, 0f, 0.38f, 0f)
            EffortPattern.Fork -> {
                segment(-0.48f, 0f, 0.02f, 0f)
                segment(0.02f, 0f, 0.38f, -0.36f)
                segment(0.02f, 0f, 0.38f, 0.36f)
            }
            EffortPattern.Steps -> repeat(4) {
                val x = -0.48f + it * 0.19f
                val y = 0.38f - it * 0.21f
                val sweep = (progress * 1.75f - it * 0.13f).coerceIn(0f, 1f)
                drawCircle(ink.copy(alpha = opacity * sweep), 3.2.dp.toPx() * sweep,
                    center + Offset((x + sweep * 0.15f) * length, y * length))
                segment(x - 0.12f, y, x, y)
            }
            EffortPattern.Cross -> repeat(3) {
                val y = (it - 1) * 0.18f
                segment(-0.51f, y - 0.4f, 0.4f, y + 0.36f)
                segment(-0.51f, y + 0.4f, 0.4f, y - 0.36f)
            }
            EffortPattern.Rings -> repeat(4) {
                val ring = (progress * 1.8f - it * 0.16f).coerceIn(0f, 1f)
                drawCircle(ink.copy(alpha = opacity * ring),
                    length * (0.13f + it * 0.09f) * ring, center, style = Stroke(stroke))
            }
            EffortPattern.Lattice -> repeat(6) {
                val y = (it - 2.5f) * 0.14f
                segment(-0.58f, y - 0.34f, 0.54f, y + 0.34f)
                segment(-0.58f, y + 0.34f, 0.54f, y - 0.34f)
                val knot = center + Offset((it - 2.5f) * 0.17f * length, y * length * 0.5f)
                drawCircle(ink, 2.9.dp.toPx() * advance, knot)
            }
        }
    }
}
