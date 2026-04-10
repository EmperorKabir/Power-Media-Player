package com.powermediaplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Power Media Player Material 3 dark theme.
 * Teal primary with pure OLED black backgrounds to disable pixels.
 */
private val PowerDarkColorScheme = darkColorScheme(
    primary = TealAccent,
    onPrimary = OledBlack,
    primaryContainer = Teal800,
    onPrimaryContainer = Teal100,
    secondary = TealBright,
    onSecondary = OledBlack,
    secondaryContainer = Teal900,
    onSecondaryContainer = Teal200,
    tertiary = Teal300,
    onTertiary = OledBlack,
    tertiaryContainer = Teal700,
    onTertiaryContainer = Teal100,
    error = ErrorRed,
    onError = OledBlack,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = OledBlack,
    onBackground = TextPrimary,
    surface = OledBlack,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DisabledGrey,
    outlineVariant = DisabledContent,
    inverseSurface = Teal50,
    inverseOnSurface = OledBlack,
    inversePrimary = Teal700,
    surfaceTint = TealAccent
)

@Composable
fun PowerMediaPlayerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PowerDarkColorScheme,
        typography = PowerTypography,
        content = content
    )
}
