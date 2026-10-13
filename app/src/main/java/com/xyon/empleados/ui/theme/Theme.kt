package com.xyon.empleados.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────── Raw palette (Xyon blue stays the anchor, everything else is flat + saturated) ─────────
val Primary50 = Color(0xFFEFF6FF)
val Primary300 = Color(0xFF93C5FD)
val Primary400 = Color(0xFF60A5FA)
val Primary500 = Color(0xFF3B82F6)
val Primary600 = Color(0xFF2563EB)
val Primary700 = Color(0xFF1D4ED8)
val Primary900 = Color(0xFF1E3A8A)
val Primary950 = Color(0xFF172554)

val ElectricBlue = Color(0xFF2563EB)
val DeepBlue = Color(0xFF1B3FD0)
val Lime = Color(0xFFC8F53C)
val Lilac = Color(0xFFC9B6FF)
val Coral = Color(0xFFFF6B4A)
val Sun = Color(0xFFFFD84A)
val Paper = Color(0xFFF4F2EA)
val InkBlack = Color(0xFF0C0C12)

val Success = Color(0xFF22C55E)
val Warning = Color(0xFFF59E0B)
val Danger = Color(0xFFEF4444)
val Violet = Color(0xFF7C3AED)
val Cyan = Color(0xFF22D3EE)

val Ink950 = Color(0xFF0A0A10)
val Ink900 = Color(0xFF111118)
val Ink800 = Color(0xFF1A1A24)
// Mid greys chosen to stay legible on both the paper and the near-black surfaces.
val Slate300 = Color(0xFFB4BCCB)
val Slate400 = Color(0xFF7D8699)
val Slate500 = Color(0xFF6B7385)

val GlassFill = Color(0x14FFFFFF)
val GlassFillStrong = Color(0x24FFFFFF)
val GlassStroke = Color(0x26FFFFFF)

val BrandGradient = Brush.linearGradient(listOf(Primary600, Primary600))
val SuccessGradient = Brush.linearGradient(listOf(Success, Success))
val DangerGradient = Brush.linearGradient(listOf(Coral, Coral))

/** Semantic, theme-aware colors for the bold flat design language. */
data class XyonColors(
    val dark: Boolean,
    val bg: Color,          // app canvas
    val card: Color,        // cards / fields
    val cardAlt: Color,     // quieter card
    val ink: Color,         // primary text
    val inkSoft: Color,     // secondary text
    val outline: Color,     // thick card borders
    val blue: Color,        // brand field
    val onBlue: Color,
    val lime: Color,
    val lilac: Color,
    val capsule: Color,     // floating nav
    val onCapsule: Color,
    val shape: Color,       // decorative shape tint on bg
)

private val LightXyon = XyonColors(
    dark = false, bg = Paper, card = Color.White, cardAlt = Color(0xFFEAE7DC), ink = InkBlack, inkSoft = Color(0xFF5B5E6B),
    outline = InkBlack, blue = ElectricBlue, onBlue = Color.White, lime = Lime, lilac = Lilac,
    capsule = InkBlack, onCapsule = Color.White, shape = Color(0xFF2563EB),
)
private val DarkXyon = XyonColors(
    dark = true, bg = Ink950, card = Ink800, cardAlt = Color(0xFF14141C), ink = Color(0xFFF5F3EC), inkSoft = Color(0xFFA2A7B8),
    outline = Color(0xFF34344A), blue = Color(0xFF2F6BFF), onBlue = Color.White, lime = Lime, lilac = Lilac,
    capsule = Color(0xFF1E1E2A), onCapsule = Color.White, shape = Color(0xFF2F6BFF),
)

val LocalXyon = staticCompositionLocalOf { LightXyon }

object Xyon {
    val colors: XyonColors @Composable @ReadOnlyComposable get() = LocalXyon.current
}

private val LightScheme = lightColorScheme(
    primary = ElectricBlue, onPrimary = Color.White, primaryContainer = Lilac, onPrimaryContainer = InkBlack,
    secondary = Lime, onSecondary = InkBlack, tertiary = Violet,
    background = Paper, onBackground = InkBlack,
    surface = Paper, onSurface = InkBlack, surfaceVariant = Color(0xFFEAE7DC), onSurfaceVariant = Color(0xFF5B5E6B),
    outline = InkBlack, outlineVariant = Color(0xFFD6D2C4),
    error = Danger, onError = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFEAE7DC),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6C93FF), onPrimary = InkBlack, primaryContainer = Color(0xFF2A2350), onPrimaryContainer = Lilac,
    secondary = Lime, onSecondary = InkBlack, tertiary = Lilac,
    background = Ink950, onBackground = Color(0xFFF5F3EC),
    surface = Ink950, onSurface = Color(0xFFF5F3EC), surfaceVariant = Ink800, onSurfaceVariant = Color(0xFFA2A7B8),
    outline = Color(0xFF34344A), outlineVariant = Color(0xFF26263A),
    error = Danger, onError = Color.White,
    surfaceContainer = Ink800, surfaceContainerHigh = Color(0xFF20202D), surfaceContainerHighest = Color(0xFF282838),
)

private val Sans = FontFamily.SansSerif
private val Type = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 56.sp, lineHeight = 52.sp, letterSpacing = (-2.5).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 42.sp, letterSpacing = (-2).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-1.4).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 30.sp, letterSpacing = (-1).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.2.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp), extraLarge = RoundedCornerShape(40.dp),
)

/** Bold flat theme; follows the system light/dark setting. */
@Composable
fun XyonTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val x = if (dark) DarkXyon else LightXyon
    CompositionLocalProvider(LocalXyon provides x) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = Type, shapes = AppShapes) {
            Surface(color = x.bg, contentColor = x.ink, content = content)
        }
    }
}
