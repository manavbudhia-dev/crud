package com.papervault.scanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand palette taken from the PaperVault design mockup. */
object Brand {
    val Blue = Color(0xFF1560FF)
    val BlueDark = Color(0xFF0A4FE0)
    val BlueSoft = Color(0xFFE6EEFF)
    val BlueBlob = Color(0xFFDCE5FD)
    val Ink = Color(0xFF0B1024)
    val InkSoft = Color(0xFF5B6275)
    val Background = Color(0xFFF6F8FD)
    val Card = Color(0xFFFFFFFF)
    val ChipGray = Color(0xFFE8ECFA)
    val Badge = Color(0xFF585D70)
    val Star = Color(0xFFFFA40A)

    // Tool / file-type accents (foreground, soft background)
    val Red = Color(0xFFFD4646);       val RedSoft = Color(0xFFFFEDEF)
    val Green = Color(0xFF00B089);     val GreenSoft = Color(0xFFEAFAF5)
    val Purple = Color(0xFF7B6CF6);    val PurpleSoft = Color(0xFFF2EFFE)
    val Orange = Color(0xFFFF9A1F);    val OrangeSoft = Color(0xFFFEF5EC)
    val Teal = Color(0xFF14B8C4);      val TealSoft = Color(0xFFE6F8FA)
    val Pink = Color(0xFFE6488A);      val PinkSoft = Color(0xFFFDEEF4)
    val PremiumSoft = Color(0xFFFCF0E6)
}

/** Colours that Material's scheme doesn't model, switched for dark mode. */
@Immutable
data class ExtraColors(
    val background: Color,
    val card: Color,
    val chip: Color,
    val blob: Color,
    val ink: Color,
    val inkSoft: Color,
    val premium: Color,
    val iconSoft: Color,
)

private val LightExtra = ExtraColors(Brand.Background, Brand.Card, Brand.ChipGray, Brand.BlueBlob, Brand.Ink, Brand.InkSoft, Brand.PremiumSoft, Brand.BlueSoft)
private val DarkExtra = ExtraColors(
    Color(0xFF0E1220), Color(0xFF181D2E), Color(0xFF242A3E), Color(0xFF1A2443),
    Color(0xFFF2F4FA), Color(0xFFA3AABD), Color(0xFF33291F), Color(0xFF1E2A4A),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

private val LightColors = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Brand.BlueSoft,
    onPrimaryContainer = Brand.Blue,
    secondary = Brand.InkSoft,
    background = Brand.Background,
    onBackground = Brand.Ink,
    surface = Brand.Background,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.ChipGray,
    onSurfaceVariant = Brand.InkSoft,
    surfaceContainer = Brand.Card,
    surfaceContainerLow = Brand.Card,
    surfaceContainerHigh = Brand.Card,
    surfaceContainerHighest = Brand.ChipGray,
    outlineVariant = Color(0xFFE6E9F2),
    error = Color(0xFFE5383B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5B8DFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E2A4A),
    onPrimaryContainer = Color(0xFF9DBBFF),
    background = DarkExtra.background,
    onBackground = DarkExtra.ink,
    surface = DarkExtra.background,
    onSurface = DarkExtra.ink,
    surfaceVariant = DarkExtra.chip,
    onSurfaceVariant = DarkExtra.inkSoft,
    surfaceContainer = DarkExtra.card,
    surfaceContainerLow = DarkExtra.card,
    surfaceContainerHigh = DarkExtra.card,
    surfaceContainerHighest = DarkExtra.chip,
    outlineVariant = Color(0xFF2A3045),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()
private val AppTypography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
)

val MaterialTheme.extra: ExtraColors
    @Composable get() = LocalExtraColors.current

@Composable
fun PaperVaultTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
