package com.kaiser.rivet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RoseMuteTarget = Color(0xFFD2C2C5)

internal fun roseSurfaceColor(base: Color, intensity: Int): Color {
    val value = clampRoseIntensity(intensity)
    val fraction = (value - MIN_ROSE_INTENSITY).toFloat() /
        (MAX_ROSE_INTENSITY - MIN_ROSE_INTENSITY).toFloat()
    return lerp(RoseMuteTarget, base, fraction)
}

internal fun rivetColors(intensity: Int, themeMode: RivetThemeMode = RivetThemeMode.Rose): ColorScheme {
    if (themeMode == RivetThemeMode.Dark) return RivetDarkColors
    fun rose(color: Color) = roseSurfaceColor(color, intensity)
    return lightColorScheme(
        primary = Color(0xFF843B50),
        onPrimary = Color(0xFFFFF7F8),
        primaryContainer = rose(Color(0xFFD79AA6)),
        onPrimaryContainer = Color(0xFF28161B),
        secondary = Color(0xFF67474D),
        onSecondary = Color(0xFFFFF8F8),
        background = rose(Color(0xFFE6BBC3)),
        onBackground = Color(0xFF21171A),
        surface = rose(Color(0xFFF0D7DB)),
        onSurface = Color(0xFF21171A),
        surfaceVariant = rose(Color(0xFFE1BCC3)),
        onSurfaceVariant = Color(0xFF50373D),
        surfaceContainer = rose(Color(0xFFEBD0D5)),
        surfaceContainerLow = rose(Color(0xFFEDD2D7)),
        surfaceContainerHigh = rose(Color(0xFFF0D7DB)),
        outline = Color(0x99502D36),
        error = Color(0xFF8A2937),
        onError = Color(0xFFFFF7F7),
        errorContainer = rose(Color(0xFFF0C6CC)),
        onErrorContainer = Color(0xFF4A111A),
    )
}

private val RivetDarkColors = darkColorScheme(
    primary = Color(0xFFD08A9B),
    onPrimary = Color(0xFF321520),
    primaryContainer = Color(0xFF55313D),
    onPrimaryContainer = Color(0xFFF3DAE1),
    secondary = Color(0xFFC3A3AD),
    onSecondary = Color(0xFF302028),
    secondaryContainer = Color(0xFF44333C),
    onSecondaryContainer = Color(0xFFF3EAEC),
    tertiary = Color(0xFFC4A5B2),
    onTertiary = Color(0xFF302028),
    tertiaryContainer = Color(0xFF49313F),
    onTertiaryContainer = Color(0xFFF3EAEC),
    background = Color(0xFF171315),
    onBackground = Color(0xFFF3EAEC),
    surface = Color(0xFF211B1E),
    onSurface = Color(0xFFF3EAEC),
    surfaceVariant = Color(0xFF352A30),
    onSurfaceVariant = Color(0xFFD1BFC5),
    surfaceContainerLowest = Color(0xFF151214),
    surfaceContainerLow = Color(0xFF1E191C),
    surfaceContainer = Color(0xFF262024),
    surfaceContainerHigh = Color(0xFF2B2327),
    surfaceContainerHighest = Color(0xFF352A30),
    surfaceTint = Color(0xFFD08A9B),
    outline = Color(0xFF9F8C93),
    outlineVariant = Color(0xFF55434B),
    inverseSurface = Color(0xFFF0D7DB),
    inverseOnSurface = Color(0xFF21171A),
    inversePrimary = Color(0xFF843B50),
    error = Color(0xFFFFB1BE),
    onError = Color(0xFF4A111A),
    errorContainer = Color(0xFF5A2734),
    onErrorContainer = Color(0xFFFFD9DF),
)

private val RivetTypography = Typography().copy(
    displaySmall = TextStyle(fontFamily = FontFamily.Cursive, fontSize = 40.sp,
        lineHeight = 44.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontFamily = FontFamily.Cursive, fontSize = 28.sp,
        lineHeight = 34.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontFamily = FontFamily.Cursive, fontSize = 24.sp,
        lineHeight = 29.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp,
        fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp,
        fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium),
)

private val RivetShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
)

@Composable
fun RivetTheme(roseIntensity: Int = DEFAULT_ROSE_INTENSITY,
               themeMode: RivetThemeMode = RivetThemeMode.Rose, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = rivetColors(roseIntensity, themeMode),
        typography = RivetTypography,
        shapes = RivetShapes,
        content = content,
    )
}
