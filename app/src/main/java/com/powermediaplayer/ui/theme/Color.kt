package com.powermediaplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

// §11.5 / B4 — TealAccent is the user-configurable accent. Backed by
// a Compose MutableState so every composable that reads it recomposes
// when the user changes the accent in Settings → Theme. ThemeAccent
// (in ThemeAccent.kt) hooks the DataStore key into the holder at app
// start. Default preserves the original Material teal so fresh
// installs look identical to v1.
private val _tealAccentState =
    androidx.compose.runtime.mutableStateOf(Color(0xFF00BFA5))
val TealAccent: Color
    get() = _tealAccentState.value
internal fun setTealAccentColor(c: Color) {
    _tealAccentState.value = c
}

// ── Teal Primary Palette ─────────────────────────────────────
// B4 fix — every shade is derived from the current TealAccent's H/S
// with a fixed L mapped to the Material 50→900 lightness ramp. So
// when the user picks a different accent, the entire palette tracks
// it. TealBright = the accent itself shifted to 60% lightness.
// Audit 3.11 — the get() properties allocated a FloatArray + two
// colour-space conversions PER READ, dozens of times per player
// recomposition. Cached per accent; reading the accent State inside
// shadeOfAccent keeps live-recolour recomposition semantics (the
// snapshot read subscribes the caller exactly as before).
private var shadeCacheAccent: Int = 1 // never a real ARGB → first read fills
private val shadeCache = HashMap<Int, Color>(16)
private fun shadeOfAccent(lightness: Float): Color {
    val accent = TealAccent.toArgb()   // State read — recolour still recomposes readers
    synchronized(shadeCache) {
        if (accent != shadeCacheAccent) {
            shadeCache.clear()
            shadeCacheAccent = accent
        }
        return shadeCache.getOrPut((lightness * 1000).toInt()) {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(accent, hsl)
            hsl[2] = lightness.coerceIn(0f, 1f)
            Color(ColorUtils.HSLToColor(hsl))
        }
    }
}
val Teal50: Color get() = shadeOfAccent(0.94f)
val Teal100: Color get() = shadeOfAccent(0.86f)
val Teal200: Color get() = shadeOfAccent(0.76f)
val Teal300: Color get() = shadeOfAccent(0.66f)
val Teal400: Color get() = shadeOfAccent(0.55f)
val Teal500: Color get() = shadeOfAccent(0.45f)
val Teal600: Color get() = shadeOfAccent(0.38f)
val Teal700: Color get() = shadeOfAccent(0.30f)
val Teal800: Color get() = shadeOfAccent(0.22f)
val Teal900: Color get() = shadeOfAccent(0.14f)
val TealBright: Color get() = shadeOfAccent(0.60f)

// ── OLED Black & Surfaces ────────────────────────────────────
val OledBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF0A0A0A)
val SurfaceElevated = Color(0xFF1A1A1A)
val SurfaceCard = Color(0xFF1E1E1E)
val SurfaceOverlay = Color(0x99000000) // 60% black overlay

// ── Disabled/Grey States ─────────────────────────────────────
// vc31 a11y: was 0xFF555555 = 2.82:1 on OledBlack (fails WCAG AA 3:1).
// 0xFF6E6E6E = ~4.1:1, clears the minimum with margin while still
// reading as a muted/disabled grey vs primary text.
val DisabledGrey = Color(0xFF6E6E6E)
val DisabledContent = Color(0xFF3A3A3A)

// ── Text Colors ──────────────────────────────────────────────
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB0B0B0)
val TextTertiary = Color(0xFF787878)

// ── Accents ──────────────────────────────────────────────────
val ErrorRed = Color(0xFFCF6679)
val WarningAmber = Color(0xFFFFB74D)
val SuccessGreen = Color(0xFF81C784)
