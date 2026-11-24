package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ObsidianDarkColorScheme = darkColorScheme(
    primary = NeuralCyan,
    onPrimary = ObsidianBg,
    primaryContainer = NeuralCyanContainer,
    onPrimaryContainer = Color(0xFFA5F3FC),
    secondary = EmeraldPulse,
    onSecondary = ObsidianBg,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = VioletAccent,
    onTertiary = Color.White,
    tertiaryContainer = VioletContainer,
    onTertiaryContainer = Color(0xFFDDD6FE),
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceElevated,
    surfaceContainerHigh = ObsidianSurfaceBright,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF1E293B),
    error = CoralAlert,
    onError = Color.White,
    errorContainer = CoralContainer,
    onErrorContainer = Color(0xFFFECDD3)
)

private val DaylightLightColorScheme = lightColorScheme(
    primary = LightCyanPrimary,
    onPrimary = Color.White,
    primaryContainer = LightCyanContainer,
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = LightEmeraldSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightEmeraldContainer,
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),
    background = LightBg,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = Color(0xFFE2E8F0),
    outline = LightOutline,
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

@Composable
fun PersonalAiTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colorScheme = if (isDark) ObsidianDarkColorScheme else DaylightLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
