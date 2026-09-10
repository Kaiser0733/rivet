package com.kaiser.rivet.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Dark-only by design: Rivet is a coding tool and the shell is built
// dark-first. Revisit only if a light theme becomes a real request.
private val RivetColors = darkColorScheme(
    background = Color(0xFF14161A),
    onBackground = Color(0xFFD6DAE0),
    surface = Color(0xFF1B1E24),
    onSurface = Color(0xFFD6DAE0),
    surfaceContainer = Color(0xFF22262D),
    primary = Color(0xFFE8A33D),
    onPrimary = Color(0xFF14161A),
    primaryContainer = Color(0xFF3D2F14),
    onPrimaryContainer = Color(0xFFF0C67F),
    secondary = Color(0xFF8FA1B8),
    onSecondary = Color(0xFF14161A),
    secondaryContainer = Color(0xFF27303C),
    onSecondaryContainer = Color(0xFFB9C7DA),
)

// One small radius everywhere; no pill shapes.
private val RivetShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
)

@Composable
fun RivetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RivetColors,
        shapes = RivetShapes,
        content = content,
    )
}
