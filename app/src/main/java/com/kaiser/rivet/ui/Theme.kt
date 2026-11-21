package com.kaiser.rivet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TextPrimaryDark = Color(0xFFF3F6F9)
private val TextSecondaryDark = Color(0xFFB8C3CE)
private val TextPrimaryLight = Color(0xFF17212B)
private val TextSecondaryLight = Color(0xFF536271)
private val AccentLight = Color(0xFF3567A7)
private val AccentDark = Color(0xFF89B5F2)
private val BaseLight = Color(0xFFFFFFFF)
private val BaseDark = Color(0xFF15191F)
private val SurfaceLight = Color(0xFFF8FAFC)
private val SurfaceDark = Color(0xFF1E252D)
private val ContainerLight = Color(0xFFF0F4F7)
private val ContainerDark = Color(0xFF252E38)
private val HighLight = Color(0xFFE8EEF3)
private val HighDark = Color(0xFF2D3844)
private val HighestLight = Color(0xFFE1E8EE)
private val HighestDark = Color(0xFF35424E)

private val RivetDarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = BaseDark,
    primaryContainer = HighestDark,
    onPrimaryContainer = TextPrimaryDark,
    secondary = TextSecondaryDark,
    onSecondary = BaseDark,
    secondaryContainer = HighDark,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = TextSecondaryDark,
    onTertiary = BaseDark,
    tertiaryContainer = ContainerDark,
    onTertiaryContainer = TextPrimaryDark,
    background = BaseDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = HighDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = BaseDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = ContainerDark,
    surfaceContainerHigh = HighDark,
    surfaceContainerHighest = HighestDark,
    surfaceTint = AccentDark,
    outline = Color(0xFF8998A7),
    outlineVariant = Color(0xFF39444F),
    inverseSurface = TextPrimaryDark,
    inverseOnSurface = BaseDark,
    inversePrimary = AccentLight,
    error = Color(0xFFF25A5A),
    onError = BaseDark,
    errorContainer = Color(0xFF5A2734),
    onErrorContainer = Color(0xFFFFD9DF),
)

private val RivetLightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = BaseLight,
    primaryContainer = HighLight,
    onPrimaryContainer = TextPrimaryLight,
    secondary = TextSecondaryLight,
    onSecondary = BaseLight,
    secondaryContainer = ContainerLight,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = TextSecondaryLight,
    onTertiary = BaseLight,
    tertiaryContainer = HighestLight,
    onTertiaryContainer = TextPrimaryLight,
    background = BaseLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = HighLight,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = BaseLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainer = ContainerLight,
    surfaceContainerHigh = HighLight,
    surfaceContainerHighest = HighestLight,
    surfaceTint = AccentLight,
    outline = Color(0xFF6C7A88),
    outlineVariant = Color(0xFFDCE3E9),
    inverseSurface = TextPrimaryLight,
    inverseOnSurface = BaseLight,
    inversePrimary = AccentDark,
    error = Color(0xFFEC1313),
    onError = BaseLight,
    errorContainer = Color(0xFFF7CDD4),
    onErrorContainer = Color(0xFF4A111A),
)

internal fun rivetColors(themeMode: RivetThemeMode, dark: Boolean): ColorScheme =
    if (themeMode == RivetThemeMode.Light || (themeMode == RivetThemeMode.System && !dark)) {
        RivetLightColors
    } else {
        RivetDarkColors
    }

// Spec type scale: system sans only (the cursive identity retires with S1), body 15-16sp
// with generous line heights; mono is reserved for code/command surfaces.
private val RivetTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 40.sp,
        lineHeight = 48.sp, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp,
        lineHeight = 42.sp, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp,
        lineHeight = 36.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 26.sp,
        lineHeight = 34.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 23.sp,
        lineHeight = 31.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp,
        lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 19.sp,
        lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp,
        lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp,
        lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp,
        lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp,
        lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp,
        lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
        lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp,
        lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp,
        lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val RivetShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun RivetTheme(themeMode: RivetThemeMode = RivetThemeMode.Dark,
               content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = rivetColors(themeMode, isSystemInDarkTheme()),
        typography = RivetTypography,
        shapes = RivetShapes,
        content = content,
    )
}

internal object RivetMotion {
    const val PAGE_MS = 240
    const val MESSAGE_MS = 190
    const val FOCUS_MS = 160
    const val PROCESS_MS = 200
    const val COMPLETE_MS = 660
    val pageShift = 24.dp
    val messageRise = 10.dp
    val completionStroke = 3.dp
    val completionLength = 78.dp
    const val drawerScrim = 0.46f
    val easing = androidx.compose.animation.core.FastOutSlowInEasing
}
