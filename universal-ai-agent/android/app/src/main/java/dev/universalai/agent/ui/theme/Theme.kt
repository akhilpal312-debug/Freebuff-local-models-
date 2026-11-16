package dev.universalai.agent.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Pearl-white palette: warm neutrals, graphite ink, one restrained clay accent. */
object Palette {
    val Pearl = Color(0xFFFCFBF9)
    val WarmWhite = Color(0xFFF7F4EF)
    val SoftGrey = Color(0xFFEFECE6)
    val Mist = Color(0xFFF2F0EC)

    val Ink = Color(0xFF1C1B19)
    val Graphite = Color(0xFF3A3733)
    val Muted = Color(0xFF7A756D)
    val Faint = Color(0xFFA8A29A)

    val Hairline = Color(0x1A1C1B19)
    val HairlineStrong = Color(0x2E1C1B19)

    val Clay = Color(0xFF9A6A4F)
    val ClaySoft = Color(0xFFF1E4DB)
    val Sage = Color(0xFF5C6B5A)
    val SageSoft = Color(0xFFE6EBE3)
    val Amber = Color(0xFFB0813A)
    val AmberSoft = Color(0xFFF6ECD9)
    val Rose = Color(0xFF9A4A47)
    val RoseSoft = Color(0xFFF5E2E0)

    val CodeBackground = Color(0xFFF4F2EE)
    val CodeBorder = Color(0xFFE3DFD7)
}

private val PearlColors = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Palette.Pearl,
    primaryContainer = Palette.SoftGrey,
    onPrimaryContainer = Palette.Ink,
    secondary = Palette.Clay,
    onSecondary = Palette.Pearl,
    secondaryContainer = Palette.ClaySoft,
    onSecondaryContainer = Palette.Graphite,
    tertiary = Palette.Sage,
    onTertiary = Palette.Pearl,
    background = Palette.Pearl,
    onBackground = Palette.Ink,
    surface = Palette.Pearl,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Mist,
    onSurfaceVariant = Palette.Graphite,
    outline = Palette.HairlineStrong,
    outlineVariant = Palette.Hairline,
    error = Palette.Rose,
    onError = Palette.Pearl,
    errorContainer = Palette.RoseSoft,
    onErrorContainer = Palette.Rose,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

val MonoFont = FontFamily.Monospace

@Composable
fun UniversalAgentTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The design language is intentionally light-only: a pearl workspace with soft
    // warm neutrals. A dark theme would need its own contrast pass, so the app does
    // not pretend to offer one.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    MaterialTheme(
        colorScheme = PearlColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
