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

// Both ladders are dsh's product tokens: label-primary / label-secondary / label-tertiary
// from @deepseek-ai/dsh-client-ui-theme/lib/client.js (bluish-50/300/400 dark,
// bluish-1000/700/600 light). Dark and light differ, so the ladders are separate.
private val TextPrimaryDark = Color(0xFFF9FAFB)
private val TextSecondaryDark = Color(0xFFCFD3D6)
private val TextMetaDark = Color(0xFFADB2B8)
private val TextPrimaryLight = Color(0xFF0F1115)
private val TextSecondaryLight = Color(0xFF61666B)
private val TextMetaLight = Color(0xFF81858C)

// dsh's one accent is its link blue: deepseek-500 light, deepseek-400 dark. Its
// brand-primary is the monochrome contrast fill (near-black/near-white), which would leave
// Rivet without the spec's single accent, so primary takes the link value instead.
private val AccentLight = Color(0xFF4176E6)
private val AccentDark = Color(0xFF7AAAFF)

// bg-base: bluish-00 light, bluish-950 dark.
private val BaseLight = Color(0xFFFFFFFF)
private val BaseDark = Color(0xFF151517)

// border-l1: the product's hairline in each theme.
private val HairlineLight = Color(0x0A000000)
private val HairlineDark = Color(0x0FFFFFFF)

private val RivetDarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = BaseDark,
    // No product "primary container"; the raised step it uses for selected fills.
    primaryContainer = Color(0xFF43454A),
    onPrimaryContainer = TextPrimaryDark,
    secondary = TextSecondaryDark,
    onSecondary = BaseDark,
    // No product secondary container; the raised step below layer-3.
    secondaryContainer = Color(0xFF353638),
    onSecondaryContainer = TextPrimaryDark,
    tertiary = TextMetaDark,
    onTertiary = BaseDark,
    tertiaryContainer = Color(0xFF2C2C2E),
    onTertiaryContainer = TextPrimaryDark,
    background = BaseDark,
    onBackground = TextPrimaryDark,
    // bg-layer-1.
    surface = Color(0xFF232324),
    onSurface = TextPrimaryDark,
    surfaceVariant = Color(0xFF353638),
    onSurfaceVariant = TextSecondaryDark,
    // bg-base, bg-layer-1..3, then the next ramp step.
    surfaceContainerLowest = BaseDark,
    surfaceContainerLow = Color(0xFF232324),
    surfaceContainer = Color(0xFF2C2C2E),
    surfaceContainerHigh = Color(0xFF353638),
    surfaceContainerHighest = Color(0xFF43454A),
    surfaceTint = AccentDark,
    // The product's borders are alphas; M3 wants an opaque outline, so its mid ramp step
    // (bluish-500) is used, and border-l1 for the hairline.
    outline = Color(0xFF979DA6),
    outlineVariant = HairlineDark,
    inverseSurface = TextPrimaryDark,
    inverseOnSurface = BaseDark,
    inversePrimary = AccentDark,
    // state-error-primary (red-400). The product has no error container, so the S1
    // pair is kept for the container roles.
    error = Color(0xFFF25A5A),
    onError = BaseDark,
    errorContainer = Color(0xFF5A2734),
    onErrorContainer = Color(0xFFFFD9DF),
)

private val RivetLightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = BaseLight,
    primaryContainer = Color(0xFFEBEEF2),
    onPrimaryContainer = TextPrimaryLight,
    secondary = TextSecondaryLight,
    onSecondary = BaseLight,
    secondaryContainer = Color(0xFFF1F3F5),
    onSecondaryContainer = TextPrimaryLight,
    tertiary = TextMetaLight,
    onTertiary = BaseLight,
    tertiaryContainer = Color(0xFFE9ECF2),
    onTertiaryContainer = TextPrimaryLight,
    background = BaseLight,
    onBackground = TextPrimaryLight,
    // bg-layer-2 is pure white in the product's light theme, as are layers 1 and 3.
    surface = BaseLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFEBEEF2),
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = BaseLight,
    // The quiet fills the product uses above white (bluish-60/75/100/150).
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFEBEEF2),
    surfaceContainerHighest = Color(0xFFE9ECF2),
    surfaceTint = AccentLight,
    outline = Color(0xFFADB2B8),
    outlineVariant = HairlineLight,
    inverseSurface = TextPrimaryLight,
    inverseOnSurface = BaseLight,
    inversePrimary = AccentLight,
    // state-error-primary (red-600), with the S1 container pair kept.
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
