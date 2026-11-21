package com.kaiser.rivet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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

private val ProtectedDarkColors = darkColorScheme(
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

private val ProtectedLightColors = lightColorScheme(
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

private val CanvasLight = Color(0xFFF4F5F3)
private val PaperLight = Color(0xFFFBFCFA)
private val InkLight = Color(0xFF182321)
private val MutedLight = Color(0xFF495C57)
private val SignalLight = Color(0xFF225F6B)
private val LayerLight = Color(0xFFE9EDEB)
private val RaisedLight = Color(0xFFE1E7E4)
private val TopLight = Color(0xFFD6E0DC)

private val CanvasDark = Color(0xFF1A2020)
private val PaperDark = Color(0xFF212A29)
private val InkDark = Color(0xFFF0F5F3)
private val MutedDark = Color(0xFFBCD0CA)
private val SignalDark = Color(0xFF91CBD2)
private val LayerDark = Color(0xFF283230)
private val RaisedDark = Color(0xFF303D3A)
private val TopDark = Color(0xFF3A4945)

private val RivetLightColors = ProtectedLightColors.copy(
    primary = SignalLight,
    onPrimary = PaperLight,
    primaryContainer = LayerLight,
    onPrimaryContainer = InkLight,
    secondary = MutedLight,
    onSecondary = PaperLight,
    secondaryContainer = RaisedLight,
    onSecondaryContainer = InkLight,
    tertiary = MutedLight,
    onTertiary = PaperLight,
    tertiaryContainer = TopLight,
    onTertiaryContainer = InkLight,
    background = CanvasLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = RaisedLight,
    onSurfaceVariant = MutedLight,
    surfaceContainerLowest = CanvasLight,
    surfaceContainerLow = PaperLight,
    surfaceContainer = LayerLight,
    surfaceContainerHigh = RaisedLight,
    surfaceContainerHighest = TopLight,
    surfaceTint = SignalLight,
    outline = Color(0xFF677E77),
    outlineVariant = Color(0xFFCCD7D2),
    inverseSurface = InkLight,
    inverseOnSurface = CanvasLight,
    inversePrimary = SignalDark,
    errorContainer = Color(0xFFF7CDD4),
    onErrorContainer = Color(0xFF4A111A),
)

private val RivetDarkColors = ProtectedDarkColors.copy(
    primary = SignalDark,
    onPrimary = CanvasDark,
    primaryContainer = LayerDark,
    onPrimaryContainer = InkDark,
    secondary = MutedDark,
    onSecondary = CanvasDark,
    secondaryContainer = RaisedDark,
    onSecondaryContainer = InkDark,
    tertiary = MutedDark,
    onTertiary = CanvasDark,
    tertiaryContainer = TopDark,
    onTertiaryContainer = InkDark,
    background = CanvasDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = RaisedDark,
    onSurfaceVariant = MutedDark,
    surfaceContainerLowest = CanvasDark,
    surfaceContainerLow = PaperDark,
    surfaceContainer = LayerDark,
    surfaceContainerHigh = RaisedDark,
    surfaceContainerHighest = TopDark,
    surfaceTint = SignalDark,
    outline = Color(0xFF95ADA6),
    outlineVariant = Color(0xFF3E504B),
    inverseSurface = InkDark,
    inverseOnSurface = CanvasDark,
    inversePrimary = SignalLight,
    errorContainer = Color(0xFF5A2734),
    onErrorContainer = Color(0xFFFFD9DF),
)

internal fun rivetColors(themeMode: RivetThemeMode, dark: Boolean): ColorScheme =
    if (themeMode == RivetThemeMode.Light || (themeMode == RivetThemeMode.System && !dark)) {
        ProtectedLightColors
    } else {
        ProtectedDarkColors
    }

private val ProtectedTypography = Typography(
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

private val ProtectedShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

private val RivetTypography = ProtectedTypography.copy(
    headlineMedium = ProtectedTypography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.55).sp),
    headlineSmall = ProtectedTypography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 31.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.35).sp),
    titleLarge = ProtectedTypography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleSmall = ProtectedTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
)

private val RivetShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(5.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(19.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(25.dp),
)

internal object RivetLayout {
    val pageGutter = 20.dp
    val columnBreakpoint = 690.dp
    val utilityMaxWidth = 1_120.dp
    val contextInlineWidth = 410.dp
    val compactComposerWidth = 300.dp
    val sectionGap = 16.dp
    val controlSize = 48.dp
    val drawerMaxWidth = 380.dp
    const val drawerWidthFraction = 0.84f
}

private val LocalProtectedColors = staticCompositionLocalOf<ColorScheme?> { null }

@Composable
internal fun ProtectedControls(content: @Composable () -> Unit) {
    val palette = LocalProtectedColors.current
    if (palette == null) content() else MaterialTheme(
        colorScheme = palette,
        typography = ProtectedTypography,
        shapes = ProtectedShapes,
        content = content,
    )
}

@Composable
fun RivetTheme(themeMode: RivetThemeMode = RivetThemeMode.Dark,
               content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val legacy = if (themeMode == RivetThemeMode.Light ||
        (themeMode == RivetThemeMode.System && !dark)) ProtectedLightColors else ProtectedDarkColors
    CompositionLocalProvider(LocalProtectedColors provides legacy) {
        MaterialTheme(colorScheme = rivetColors(themeMode, dark), typography = RivetTypography,
            shapes = RivetShapes, content = content)
    }
}

internal object RivetMotion {
    const val PAGE_MS = 240
    const val MESSAGE_MS = 190
    const val FOCUS_MS = 160
    const val DISCLOSURE_MS = 200
    const val CHAT_FOLD_EXPAND_MS = 180
    const val CHAT_FOLD_COLLAPSE_MS = 150
    const val COMPACT_DOT_MS = 300
    const val DRAWER_MS = DISCLOSURE_MS
    const val COMPLETE_MS = 660
    val pageShift = 36.dp
    val panelRise = 18.dp
    val paneTravel = 32.dp
    val pageBackShift = (-16).dp
    val messageRise = 10.dp
    val completionStroke = 3.dp
    val completionLength = 78.dp
    const val EFFORT_MS = 420
    val effortSignalWidth = 55.dp
    val effortSignalHeight = 18.dp
    val effortSignalStroke = 2.dp
    const val drawerScrim = 0.46f
    const val pressAlpha = 0.16f
    const val hoverAlpha = 0.08f
    val easing = androidx.compose.animation.core.FastOutSlowInEasing
}
