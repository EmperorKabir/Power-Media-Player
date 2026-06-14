package com.powermediaplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.powermediaplayer.ui.settings.SettingsViewModel

/**
 * Power Media Player Material 3 dark theme.
 * Teal primary with pure OLED black backgrounds to disable pixels.
 *
 * Built per-composition (keyed on the live [TealAccent]) rather than as a
 * static val — otherwise `colorScheme.primary` froze at the accent's value
 * at class-load, so any component using the DEFAULT Material colours (a
 * Slider/Switch without an explicit `colors =`) stayed teal forever while
 * the user's chosen accent only reached the call-sites that read TealAccent
 * directly. Keying on TealAccent recolours the whole Material surface.
 */
private fun powerDarkColorScheme() = darkColorScheme(
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

/**
 * Hybrid font/hitbox scale set by the user in Settings → Display →
 * Font size. Read this CompositionLocal from any Composable that
 * needs to scale touch-target dimensions (button size, row height,
 * padding) while leaving icon glyphs at their design size. Text
 * automatically scales via the LocalDensity.fontScale override below.
 * Default 1.0f when nobody provides it (preview / unit-test contexts).
 */
val LocalPmpScale = compositionLocalOf { 1.0f }

@Composable
fun PowerMediaPlayerTheme(
    content: @Composable () -> Unit
) {
    // §11.5 — installs the user's chosen accent into TealAccent's
    // MutableState holder so every composable that reads TealAccent
    // recomposes when the user changes it in Settings → Theme. No
    // per-call-site rewrite required.
    InstallThemeAccent()

    // Hybrid font scale: read the user's value via the same Settings
    // ViewModel pattern used by InstallThemeAccent. Apply it as a
    // multiplier on LocalDensity.fontScale (this is what makes every
    // sp-based TextStyle grow) AND publish it via LocalPmpScale so
    // composables can scale their touch targets to match.
    val vm: SettingsViewModel = hiltViewModel()
    val scale by vm.fontSizeScaleFlow.collectAsStateWithLifecycle()
    val userScale = scale.coerceIn(0.85f, 2.0f)
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, userScale) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * userScale
        )
    }

    // Rebuilt whenever the accent changes so every Material default-coloured
    // surface (sliders, switches, etc.) recolours, not just TealAccent
    // call-sites.
    val colorScheme = remember(TealAccent) { powerDarkColorScheme() }

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalPmpScale provides userScale
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PowerTypography,
            content = content
        )
    }
}
