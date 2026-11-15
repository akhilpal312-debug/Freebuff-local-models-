package dev.akhil.freebuff.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Soft neutral palette: warm paper in light mode, near-black graphite in dark mode. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6B5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3EDE5),
    onPrimaryContainer = Color(0xFF062A24),
    secondary = Color(0xFF4C635A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E7E2),
    onSecondaryContainer = Color(0xFF111F1A),
    tertiary = Color(0xFF7C5A3E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E3D3),
    onTertiaryContainer = Color(0xFF2C1B0D),
    background = Color(0xFFFBFAF8),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFF0F0EC),
    onSurfaceVariant = Color(0xFF5A615D),
    surfaceTint = Color(0xFF1E6B5C),
    outline = Color(0xFFD8DAD5),
    outlineVariant = Color(0xFFE8E9E5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF2E3130),
    inverseOnSurface = Color(0xFFF1F1EE),
    inversePrimary = Color(0xFF8FD8C4),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86D6C0),
    onPrimary = Color(0xFF04211B),
    primaryContainer = Color(0xFF1B4F44),
    onPrimaryContainer = Color(0xFFC6EEE3),
    secondary = Color(0xFFB4CBC1),
    onSecondary = Color(0xFF1F2B26),
    secondaryContainer = Color(0xFF2B3733),
    onSecondaryContainer = Color(0xFFD0E0D8),
    tertiary = Color(0xFFE4C3A2),
    onTertiary = Color(0xFF3A2716),
    tertiaryContainer = Color(0xFF4C3520),
    onTertiaryContainer = Color(0xFFF6E1CC),
    background = Color(0xFF0F1010),
    onBackground = Color(0xFFECEEEC),
    surface = Color(0xFF171818),
    onSurface = Color(0xFFECEEEC),
    surfaceVariant = Color(0xFF222423),
    onSurfaceVariant = Color(0xFFA9B1AD),
    surfaceTint = Color(0xFF86D6C0),
    outline = Color(0xFF343736),
    outlineVariant = Color(0xFF262928),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE7E9E7),
    inverseOnSurface = Color(0xFF1B1D1C),
    inversePrimary = Color(0xFF1E6B5C),
    scrim = Color(0xFF000000),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** Extra semantic colors that Material does not model. */
data class ExtendedColors(
    val codeBackground: Color,
    val codeBorder: Color,
    val success: Color,
    val warning: Color,
    val mutedFill: Color,
    val userBubble: Color,
    val onUserBubble: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        codeBackground = Color(0xFFF3F3EF),
        codeBorder = Color(0xFFE4E5E1),
        success = Color(0xFF2E7D53),
        warning = Color(0xFF9A6A15),
        mutedFill = Color(0xFFF4F4F0),
        userBubble = Color(0xFFEDF2EF),
        onUserBubble = Color(0xFF14201B),
    )
}

private val LightExtended = ExtendedColors(
    codeBackground = Color(0xFFF3F3EF),
    codeBorder = Color(0xFFE4E5E1),
    success = Color(0xFF2E7D53),
    warning = Color(0xFF9A6A15),
    mutedFill = Color(0xFFF4F4F0),
    userBubble = Color(0xFFEDF2EF),
    onUserBubble = Color(0xFF14201B),
)

private val DarkExtended = ExtendedColors(
    codeBackground = Color(0xFF141616),
    codeBorder = Color(0xFF2A2D2C),
    success = Color(0xFF7FD3A5),
    warning = Color(0xFFE3B761),
    mutedFill = Color(0xFF1D1F1F),
    userBubble = Color(0xFF1F2A26),
    onUserBubble = Color(0xFFDDEAE4),
)

@Composable
fun FreebuffTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtended else LightExtended,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
