package com.powermediaplayer.ui.player.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover-text legibility (user 2026-06-30): the title/author over a high-contrast cover (e.g.
 * Jurassic Park's red/white) can be illegible. The fix captures the ACTUAL rendered cover into a
 * [GraphicsLayer] so the title block can (a) frost the real backdrop region behind it and
 * (b) pick a light/dark text colour from that region's luminance — robust to Fit/Crop/zoom/fold/
 * display because it reads what was truly drawn, not the source bitmap + a transform guess.
 *
 * The player content Box provides a [CoverFrost] via [LocalCoverFrost]; [CoverArtBackground] is
 * tagged with [captureCoverFrost]; the title block (TrackInfoSection) consumes it via
 * [frostedTitleBackground] + [rememberAdaptiveTextColor].
 */
@Stable
class CoverFrost(
    /** the cover, sharp — used both as the on-screen background source and for luminance sampling. */
    val sharp: GraphicsLayer,
    /** the same cover with a blur [androidx.compose.ui.graphics.RenderEffect] — drawn behind the text. */
    val blurred: GraphicsLayer,
) {
    /** top-left of the captured cover in root coordinates (maps a title region into layer space). */
    var coverOriginInRoot by mutableStateOf(Offset.Zero)

    /** true once the cover has actually recorded a frame (so we don't frost over nothing). */
    var captured by mutableStateOf(false)

    /** the title block's bounds in root coords — set by [frostedTitleBackground], read by its draw. */
    var lastTitleBounds by mutableStateOf<Rect?>(null)
}

val LocalCoverFrost = staticCompositionLocalOf<CoverFrost?> { null }

/** Blur is only real on API 31+; below that the frost falls back to a plain dim (see [frostedTitleBackground]). */
val coverBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

@Composable
fun rememberCoverFrost(): CoverFrost {
    val sharp = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val frost = remember(sharp, blurred) { CoverFrost(sharp, blurred) }
    if (coverBlurSupported) {
        SideEffect { blurred.renderEffect = BlurEffect(38f, 38f, TileMode.Decal) }
    }
    return frost
}

/** Tag the cover composable: record its draw into both layers each frame. */
fun Modifier.captureCoverFrost(frost: CoverFrost?): Modifier =
    if (frost == null) this else this
        .onGloballyPositioned { frost.coverOriginInRoot = it.boundsInRoot().topLeft }
        .drawWithContent {
            frost.sharp.record { this@drawWithContent.drawContent() }
            frost.blurred.record { drawLayer(frost.sharp) }
            frost.captured = true
            drawLayer(frost.sharp)
        }

/**
 * Frosted-glass backdrop behind ONLY the title block: draws the blurred cover region that is
 * truly behind this element (translated so it pixel-aligns with the background) + a dim. On
 * API < 31 the blur is a no-op, so it shows a sharp-but-dimmed slice — still readable, and the
 * adaptive text colour carries the legibility. Reports the block's bounds for colour sampling.
 */
fun Modifier.frostedTitleBackground(
    frost: CoverFrost?,
    enabled: Boolean,
    onBounds: (Rect) -> Unit,
    dimAlpha: Float = 0.34f,
): Modifier =
    if (frost == null || !enabled) this else this
        .onGloballyPositioned { val r = it.boundsInRoot(); frost.lastTitleBounds = r; onBounds(r) }
        .drawBehind {
            if (!frost.captured) return@drawBehind
            val b = frost.lastTitleBounds ?: return@drawBehind
            // translate so the layer pixel at the block's top-left lands at this canvas origin
            translate(frost.coverOriginInRoot.x - b.left, frost.coverOriginInRoot.y - b.top) {
                drawLayer(frost.blurred)
            }
            drawRect(color = Color.Black.copy(alpha = dimAlpha))
        }

/** Average perceived luminance (0..1) of [bounds] (root coords) within the captured cover. */
private fun averageLuminance(bmp: ImageBitmap, bounds: Rect, coverOrigin: Offset): Float? {
    val src = bmp.asAndroidBitmap()
    // GraphicsLayer bitmaps can be HARDWARE — copy to software so getPixel works.
    val ab = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
    if (ab == null || ab.width == 0 || ab.height == 0) return null
    val left = (bounds.left - coverOrigin.x).toInt().coerceIn(0, ab.width - 1)
    val top = (bounds.top - coverOrigin.y).toInt().coerceIn(0, ab.height - 1)
    val right = (bounds.right - coverOrigin.x).toInt().coerceIn(left + 1, ab.width)
    val bottom = (bounds.bottom - coverOrigin.y).toInt().coerceIn(top + 1, ab.height)
    val stepX = ((right - left) / 10).coerceAtLeast(1)
    val stepY = ((bottom - top) / 10).coerceAtLeast(1)
    var sum = 0.0
    var n = 0
    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            val px = ab.getPixel(x, y)
            val r = ((px shr 16) and 0xFF) / 255.0
            val g = ((px shr 8) and 0xFF) / 255.0
            val bl = (px and 0xFF) / 255.0
            sum += 0.2126 * r + 0.7152 * g + 0.0722 * bl
            n++
            x += stepX
        }
        y += stepY
    }
    return if (n > 0) (sum / n).toFloat() else null
}

/**
 * Light/dark text colour chosen from the REAL backdrop luminance behind [bounds] (after the
 * frost dim is accounted for). Recomputes only when the cover or the block bounds change.
 */
@Composable
fun rememberAdaptiveTextColor(frost: CoverFrost?, bounds: Rect?, dimAlpha: Float = 0.34f): Color {
    var color by remember { mutableStateOf(Color.White) }
    LaunchedEffect(frost?.captured, bounds) {
        if (frost == null || bounds == null || !frost.captured) return@LaunchedEffect
        val lum = runCatching {
            val bmp = frost.sharp.toImageBitmap()
            withContext(Dispatchers.Default) { averageLuminance(bmp, bounds, frost.coverOriginInRoot) }
        }.getOrNull() ?: return@LaunchedEffect
        // effective luminance behind the text = backdrop dimmed by the black frost
        val effective = lum * (1f - dimAlpha)
        color = if (effective > 0.5f) Color(0xFF0E0E0E) else Color.White
    }
    return color
}
