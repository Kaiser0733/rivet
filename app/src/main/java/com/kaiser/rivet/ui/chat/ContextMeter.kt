package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.chat.ContextMeterReading

@Composable
internal fun ConversationContextMeter(reading: ContextMeterReading, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.primary
    val label = "${reading.percent}%"
    val window = if (reading.estimatedWindow) "estimated context window" else "context window"
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = "$label of $window used. About ${reading.usedTokens} of ${reading.contextWindow} tokens."
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val line = 1.6.dp.toPx()
            val bounds = Size(size.width - line, size.height - line)
            val inset = Offset(line / 2, line / 2)
            drawOval(track, topLeft = inset, size = bounds, style = Stroke(line))
            if (reading.usedTokens > 0) {
                drawArc(ink, startAngle = -90f,
                    sweepAngle = (reading.usedTokens.toDouble() / reading.contextWindow).coerceIn(0.0, 1.0).toFloat() * 360f,
                    useCenter = false, topLeft = inset, size = bounds, style = Stroke(line, cap = StrokeCap.Round))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
