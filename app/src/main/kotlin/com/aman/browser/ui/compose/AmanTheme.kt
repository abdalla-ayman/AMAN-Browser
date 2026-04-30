package com.aman.browser.ui.compose

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.aman.browser.R

/**
 * Brand palette aligned with the 2026 Aman product design (g50 → g800 mint scale,
 * neutral surfaces #f8f9fa / #f1f3f4, soft #e0e0e0 borders).
 */
object AmanPalette {
    val G50 = Color(0xFFF0FAF5)
    val G100 = Color(0xFFC6EDD8)
    val G300 = Color(0xFF5CC99A)
    val G500 = Color(0xFF1A8C5B)
    val G600 = Color(0xFF166E47)
    val G800 = Color(0xFF0D4129)

    val Surface = Color(0xFFFFFFFF)
    val SurfaceDim = Color(0xFFF8F9FA)
    val SurfaceVariant = Color(0xFFF1F3F4)
    val Border = Color(0xFFE0E0E0)
    val BorderSoft = Color(0xFFEDEFF1)

    val Text = Color(0xFF202124)
    val Text2 = Color(0xFF5F6368)
    val Text3 = Color(0xFF80868B)

    // Backwards-compatibility aliases used elsewhere in the codebase.
    val Primary = G500
    val OnPrimary = Color.White
    val PrimaryContainer = G50
    val OnPrimaryContainer = G800
}

/**
 * Accent tints for feature-row icon bubbles (green / blue / amber triad).
 */
data class AmanAccentColors(
    val greenBg: Color,
    val greenBorder: Color,
    val greenIcon: Color,
    val blueBg: Color,
    val blueBorder: Color,
    val blueIcon: Color,
    val amberBg: Color,
    val amberBorder: Color,
    val amberIcon: Color,
)

private val LightAccents = AmanAccentColors(
    greenBg = Color(0xFFF0FAF5), greenBorder = Color(0xFFB8E8CF), greenIcon = AmanPalette.G500,
    blueBg = Color(0xFFE8F0FE), blueBorder = Color(0xFFC5D9FC), blueIcon = Color(0xFF185FA5),
    amberBg = Color(0xFFFEF7E0), amberBorder = Color(0xFFFCE69A), amberIcon = Color(0xFFBA7517),
)

private val DarkAccents = AmanAccentColors(
    greenBg = Color(0xFF0F2A1E), greenBorder = Color(0xFF1F4A35), greenIcon = Color(0xFF7DE0AF),
    blueBg = Color(0xFF111E33), blueBorder = Color(0xFF22426B), blueIcon = Color(0xFF8FB3F0),
    amberBg = Color(0xFF2A210B), amberBorder = Color(0xFF5A4416), amberIcon = Color(0xFFE8B964),
)

val LocalAmanAccents = staticCompositionLocalOf { LightAccents }

private val LightColors = lightColorScheme(
    primary = AmanPalette.G500,
    onPrimary = Color.White,
    primaryContainer = AmanPalette.G50,
    onPrimaryContainer = AmanPalette.G800,
    secondary = AmanPalette.G600,
    onSecondary = Color.White,
    secondaryContainer = AmanPalette.G100,
    onSecondaryContainer = AmanPalette.G800,
    tertiary = Color(0xFF1F4E60),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8F0F7),
    onTertiaryContainer = Color(0xFF071F27),
    background = AmanPalette.SurfaceVariant,
    onBackground = AmanPalette.Text,
    surface = AmanPalette.Surface,
    onSurface = AmanPalette.Text,
    surfaceVariant = AmanPalette.SurfaceDim,
    onSurfaceVariant = AmanPalette.Text2,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = AmanPalette.SurfaceDim,
    surfaceContainer = AmanPalette.SurfaceVariant,
    surfaceContainerHigh = Color(0xFFEAECEE),
    surfaceContainerHighest = Color(0xFFE3E5E8),
    outline = AmanPalette.Border,
    outlineVariant = AmanPalette.BorderSoft,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = AmanPalette.SurfaceDim,
    inversePrimary = AmanPalette.G300,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DE0AF),
    onPrimary = Color(0xFF003820),
    primaryContainer = Color(0xFF0F4A30),
    onPrimaryContainer = Color(0xFFC6EDD8),
    secondary = Color(0xFFB7D5C4),
    onSecondary = Color(0xFF203529),
    secondaryContainer = Color(0xFF1F3A2C),
    onSecondaryContainer = Color(0xFFD3EFDF),
    tertiary = Color(0xFFA9D4E2),
    onTertiary = Color(0xFF0B3441),
    tertiaryContainer = Color(0xFF214B59),
    onTertiaryContainer = Color(0xFFC6F0FF),
    background = Color(0xFF0B0F0D),
    onBackground = Color(0xFFE2ECE6),
    surface = Color(0xFF111613),
    onSurface = Color(0xFFE2ECE6),
    surfaceVariant = Color(0xFF1A201C),
    onSurfaceVariant = Color(0xFFB8C8BE),
    surfaceContainerLowest = Color(0xFF080B09),
    surfaceContainerLow = Color(0xFF111613),
    surfaceContainer = Color(0xFF161B18),
    surfaceContainerHigh = Color(0xFF1B201D),
    surfaceContainerHighest = Color(0xFF202622),
    outline = Color(0xFF38423C),
    outlineVariant = Color(0xFF242B27),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Brand typography:
 *   - Headlines / titles / labels: IBM Plex Sans Arabic (also covers Latin
 *     gracefully and gives the Arabic UI strings a refined, editorial feel).
 *   - Body copy: DM Sans (variable, single asset) for compact, neutral copy.
 *
 * IBM Plex Sans Arabic ships in 4 static weights (400/500/600/700) under
 * res/font/ibm_plex_sans_arabic_*.ttf. DM Sans is bundled as a single
 * variable font (res/font/dm_sans_variable.ttf) referenced through the
 * res/font/aman_sans.xml family.
 */
private val ArabicSans = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
)

private val DmSans = FontFamily(
    Font(R.font.dm_sans_variable, FontWeight.Normal),
    Font(R.font.dm_sans_variable, FontWeight.Medium),
    Font(R.font.dm_sans_variable, FontWeight.SemiBold),
    Font(R.font.dm_sans_variable, FontWeight.Bold),
)

/** Compact, modern type scale (DM-Sans-style proportions). */
private val AmanTypography = Typography(
    displayLarge = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-0.02).sp),
    displayMedium = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.02).sp),
    displaySmall = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.01).sp),
    headlineLarge = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01).sp),
    headlineMedium = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = DmSans, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = DmSans, fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontFamily = DmSans, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = ArabicSans, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

/** Shape scale: 8 / 12 / 16 / 20 / 24 dp. */
private val AmanShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun AmanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val accents = if (darkTheme) DarkAccents else LightAccents
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Edge-to-edge already enabled by the activity; just flip the
            // status / nav bar icon tint so glyphs stay legible.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalAmanAccents provides accents) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AmanTypography,
            shapes = AmanShapes,
            content = content,
        )
    }
}
