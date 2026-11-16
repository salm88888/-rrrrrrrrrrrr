package com.miyahna.water.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val SkyPrimary = Color(0xFF0369A1)
val SkyPrimaryDark = Color(0xFF0C4A6E)
val SkyContainer = Color(0xFFE0F2FE)
val EmeraldAccent = Color(0xFF059669)
val EmeraldContainer = Color(0xFFD1FAE5)
val AmberWarning = Color(0xFFD97706)
val AmberContainer = Color(0xFFFEF3C7)
val RoseError = Color(0xFFE11D48)
val RoseContainer = Color(0xFFFFE4E6)

val SlateBgLight = Color(0xFFF8FAFC)
val SlateSurfaceLight = Color(0xFFFFFFFF)
val SlateSurfaceVariantLight = Color(0xFFF1F5F9)
val SlateOutlineLight = Color(0xFFE2E8F0)
val SlateTextPrimary = Color(0xFF0F172A)
val SlateTextSecondary = Color(0xFF475569)

val DriverSlateBg = Color(0xFF0F172A)
val DriverSlateCard = Color(0xFF1E293B)
val DriverSlateElevated = Color(0xFF334155)

private val MiyahnaLightColors = lightColorScheme(
    primary = SkyPrimary,
    onPrimary = Color.White,
    primaryContainer = SkyContainer,
    onPrimaryContainer = SkyPrimaryDark,
    secondary = EmeraldAccent,
    onSecondary = Color.White,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = AmberWarning,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFF78350F),
    error = RoseError,
    onError = Color.White,
    errorContainer = RoseContainer,
    onErrorContainer = Color(0xFF881337),
    background = SlateBgLight,
    onBackground = SlateTextPrimary,
    surface = SlateSurfaceLight,
    onSurface = SlateTextPrimary,
    surfaceVariant = SlateSurfaceVariantLight,
    onSurfaceVariant = SlateTextSecondary,
    outline = SlateOutlineLight
)

private val MiyahnaDriverDarkColors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    primaryContainer = SkyPrimaryDark,
    onPrimaryContainer = SkyContainer,
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF064E3B),
    secondaryContainer = Color(0xFF065F46),
    onSecondaryContainer = EmeraldContainer,
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = AmberContainer,
    error = Color(0xFFFB7185),
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF881337),
    onErrorContainer = RoseContainer,
    background = DriverSlateBg,
    onBackground = Color(0xFFF8FAFC),
    surface = DriverSlateCard,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = DriverSlateElevated,
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF334155)
)

val MiyahnaTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 34.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

val MiyahnaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

val NumericTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum",
    fontWeight = FontWeight.Bold
)

@Composable
fun MiyahnaTheme(
    darkDriverTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = if (darkDriverTheme) MiyahnaDriverDarkColors else MiyahnaLightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MiyahnaTypography,
        shapes = MiyahnaShapes,
        content = content
    )
}
