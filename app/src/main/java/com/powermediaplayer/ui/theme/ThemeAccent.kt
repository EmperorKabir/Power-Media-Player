package com.powermediaplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.powermediaplayer.ui.settings.SettingsViewModel

/**
 * §11.5 — installs the user's chosen accent colour into [TealAccent].
 *
 * Called once from the root [com.powermediaplayer.ui.theme.PowerMediaPlayerTheme]
 * composable. Reads the DataStore key `THEME_ACCENT_HEX` via
 * SettingsViewModel and pushes the parsed [Color] into the holder
 * inside [TealAccent]. Every composable that reads `TealAccent`
 * recomposes when the holder changes — no per-call-site rewrite.
 *
 * Background and text colours are intentionally non-configurable per
 * the locked spec (OLED-power discipline; contrast guarantee).
 */
@Composable
fun InstallThemeAccent() {
    val vm: SettingsViewModel = hiltViewModel()
    val state by vm.uiState.collectAsState()
    LaunchedEffect(state.themeAccentHex) {
        val hex = state.themeAccentHex
        if (hex.isBlank()) return@LaunchedEffect
        val parsed = parseHexColor(hex) ?: return@LaunchedEffect
        setTealAccentColor(parsed)
    }
}

/**
 * Parse "#RRGGBB" / "#RRGGBBAA" / "#AARRGGBB" / "0xRRGGBB" into a
 * [Color]. Returns null on any parse failure so callers can fall
 * through to the default teal.
 */
fun parseHexColor(input: String): Color? {
    val trimmed = input.trim()
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    return runCatching {
        val v = trimmed.toLong(16)
        when (trimmed.length) {
            6 -> Color(0xFF000000 or v)
            8 -> Color(v)
            else -> null
        }
    }.getOrNull()
}
