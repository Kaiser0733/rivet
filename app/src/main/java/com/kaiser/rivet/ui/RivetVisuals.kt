package com.kaiser.rivet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R

@Composable
fun RivetBackdrop(content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), content = content)
    }
}

@Composable
fun RivetChatBackground(
    modifier: Modifier = Modifier,
    intensity: Int = DEFAULT_ROSE_INTENSITY,
    themeMode: RivetThemeMode = RivetThemeMode.Rose,
) {
    Image(
        painter = painterResource(R.drawable.chat_background),
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        alignment = Alignment.Center,
        contentScale = ContentScale.Crop,
        alpha = if (themeMode == RivetThemeMode.Dark) 0.045f
            else 0.46f * clampRoseIntensity(intensity) / MAX_ROSE_INTENSITY,
    )
}

@Composable
fun RivetOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onBackground,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(
            alpha = if (enabled) 0.78f else 0.32f)),
        content = content,
    )
}

enum class RivetDoodle { Project, Provider, Ready }

@Composable
fun RivetDoodleMark(kind: RivetDoodle, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(132.dp, 102.dp)) {
        val pen = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            RivetDoodle.Project -> {
                val folder = Path().apply {
                    moveTo(size.width * 0.19f, size.height * 0.37f)
                    lineTo(size.width * 0.43f, size.height * 0.37f)
                    lineTo(size.width * 0.51f, size.height * 0.47f)
                    lineTo(size.width * 0.80f, size.height * 0.47f)
                    lineTo(size.width * 0.75f, size.height * 0.77f)
                    quadraticTo(size.width * 0.74f, size.height * 0.81f,
                        size.width * 0.68f, size.height * 0.81f)
                    lineTo(size.width * 0.22f, size.height * 0.80f)
                    quadraticTo(size.width * 0.17f, size.height * 0.79f,
                        size.width * 0.18f, size.height * 0.73f)
                    close()
                }
                drawPath(folder, ink, style = pen)
                drawLine(ink, androidx.compose.ui.geometry.Offset(size.width * 0.28f, size.height * 0.58f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.69f, size.height * 0.58f),
                    strokeWidth = 1.3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(accent, androidx.compose.ui.geometry.Offset(size.width * 0.25f, size.height * 0.88f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.73f, size.height * 0.89f),
                    strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
            }
            RivetDoodle.Provider -> {
                drawLine(ink, androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.25f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.12f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(ink, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.1f),
                    style = pen)
                drawRoundRect(ink, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.26f, size.height * 0.3f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.48f, size.height * 0.42f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()), style = pen)
                drawLine(ink, androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.42f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.26f, size.height * 0.42f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(ink, androidx.compose.ui.geometry.Offset(size.width * 0.74f, size.height * 0.42f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.42f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(ink, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * 0.41f, size.height * 0.49f))
                drawCircle(ink, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * 0.59f, size.height * 0.49f))
                drawLine(accent, androidx.compose.ui.geometry.Offset(size.width * 0.39f, size.height * 0.63f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.61f, size.height * 0.63f),
                    strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                sketchSpark(ink, size.width * 0.13f, size.height * 0.27f, 6.dp.toPx())
                sketchSpark(ink, size.width * 0.87f, size.height * 0.26f, 5.dp.toPx())
            }
            RivetDoodle.Ready -> {
                sketchSpark(ink, size.width * 0.50f, size.height * 0.34f, 8.dp.toPx())
                sketchSpark(ink, size.width * 0.37f, size.height * 0.55f, 4.dp.toPx())
                sketchSpark(accent, size.width * 0.64f, size.height * 0.56f, 5.dp.toPx())
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sketchSpark(
    color: androidx.compose.ui.graphics.Color,
    x: Float,
    y: Float,
    radius: Float,
) {
    drawLine(color, androidx.compose.ui.geometry.Offset(x - radius, y),
        androidx.compose.ui.geometry.Offset(x + radius, y), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, androidx.compose.ui.geometry.Offset(x, y - radius),
        androidx.compose.ui.geometry.Offset(x, y + radius), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
}
