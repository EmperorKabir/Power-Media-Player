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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Immutable
import com.powermediaplayer.util.CoverArtColors
import com.powermediaplayer.util.PlayerTextColour
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
 * [frostedTitleBackground] + [rememberResolvedTextColour].
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

/** The user's player text colour choice (S3/S4), provided by the player screen from settings. */
@Immutable
data class PlayerTextColourConfig(
    val mode: Int = PlayerTextColour.MODE_DEFAULT,
    val customColour: Color? = null,
)

val LocalPlayerTextColour = staticCompositionLocalOf { PlayerTextColourConfig() }

/**
 * Resolve the pill text colour for the active mode. Custom needs no backdrop sample; Default and
 * Dynamic sample the real backdrop luminance behind [bounds] and hand it to [PlayerTextColour].
 * Dynamic also uses the artwork [palette] to pick a per-file colour.
 */
@Composable
fun rememberResolvedTextColour(
    frost: CoverFrost?,
    bounds: Rect?,
    mode: Int,
    customColour: Color?,
    palette: CoverArtColors?,
    dimAlpha: Float,
): Color {
    if (mode == PlayerTextColour.MODE_CUSTOM && customColour != null) return customColour
    var lum by remember { mutableStateOf(0f) }
    // Re-sample the backdrop luminance when the cover changes (palette) as well as when the block
    // bounds change. frost.captured latches true and never resets, so keying on it alone would keep
    // a stale luminance across a track/cover change whose title happens to be the same width — which
    // for Default mode (colour depends only on luminance) would leave the previous cover's decision.
    LaunchedEffect(frost?.captured, bounds, palette) {
        if (frost == null || bounds == null || !frost.captured) return@LaunchedEffect
        val sampled = runCatching {
            val bmp = frost.sharp.toImageBitmap()
            withContext(Dispatchers.Default) { averageLuminance(bmp, bounds, frost.coverOriginInRoot) }
        }.getOrNull()
        if (sampled != null) lum = sampled
    }
    return remember(mode, customColour, palette, lum, dimAlpha) {
        PlayerTextColour.resolve(mode, customColour, palette, lum, dimAlpha)
    }
}

/**
 * One piece of cover text (title/artist/…) with a per-VISUAL-LINE frosted pill. Each rendered line
 * gets its OWN rounded backdrop sized to that line's width — so a long title that wraps to two lines
 * shows two independent pills (different widths), not one rectangle with dead space. The backdrop is
 * the REAL blurred cover behind the glyphs (translated to pixel-align) + a dim, clipped to each
 * line's rounded rect; the text colour adapts to that region's luminance. When [enabled] is false
 * (video / no cover) it renders plain text in [baseColor]. API < 31 has no blur, so the pill shows a
 * sharp-but-dimmed cover slice — still legible, and the adaptive colour + shadow carry it.
 *
 * The element pads the text by ([padHorizontal], [padVertical]); the pills extend by the same amount
 * around each glyph line, so the padding and the pill geometry stay in lock-step (see the draw math).
 */
@Composable
fun FrostedTextLine(
    text: String,
    style: TextStyle,
    frost: CoverFrost?,
    enabled: Boolean,
    baseColor: Color,
    maxLines: Int,
    palette: CoverArtColors? = null,
    hasCover: Boolean = true,
    isVideo: Boolean = false,
    modifier: Modifier = Modifier,
    dimAlpha: Float = 0.36f,
    padHorizontal: Float = 12f,
    padVertical: Float = 5f,
) {
    val density = LocalDensity.current
    val padX = with(density) { padHorizontal.dp.toPx() }
    val padY = with(density) { padVertical.dp.toPx() }
    val radius = with(density) { 9.dp.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var region by remember { mutableStateOf<Rect?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val config = LocalPlayerTextColour.current
    val adaptive = rememberResolvedTextColour(
        frost, region, config.mode, config.customColour, palette, dimAlpha
    )
    val textColour = if (enabled) adaptive else baseColor
    // Crisp, not blurry: a dark halo only behind LIGHT text. Dark text reads cleanly on the dim
    // pill, and a dark shadow under it just smears the glyphs (the "blurry" look).
    val shadow = if (enabled && PlayerTextColour.luminance(textColour) > 0.5f)
        Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 3f) else null
    // Thin outline so the text stands out from the pill even when its colour is close to the
    // backdrop. Adaptive: a dark halo behind LIGHT text, a light halo behind DARK text. Width
    // scales with the font size (robust to the app font-scale) and with density (robust to
    // display type). No colour-logic change; this is a legibility outline only.
    val outlineColour = if (PlayerTextColour.luminance(textColour) > 0.5f)
        Color(0xE6000000) else Color(0xD9FFFFFF)
    val strokePx = with(density) {
        val fs = if (style.fontSize.isSpecified) style.fontSize.toPx() else 16.sp.toPx()
        (fs * 0.045f).coerceIn(1.2f, 5f)
    }
    Box(
        modifier = modifier
            .onGloballyPositioned { val b = it.boundsInRoot(); origin = b.topLeft; region = b }
            .drawBehind {
                val l = layout ?: return@drawBehind
                if (!enabled) return@drawBehind
                // drawBehind canvas = the OUTER (pre-padding) box; text content is inset by (padX,padY).
                // Pill extends padX/padY beyond each glyph line → equal gap all round (centred per line).
                val f = frost
                val useFrost = hasCover && f != null && f.captured
                for (i in 0 until l.lineCount) {
                    if (l.getLineRight(i) - l.getLineLeft(i) <= 0f) continue
                    val left = l.getLineLeft(i)
                    val top = l.getLineTop(i)
                    val right = l.getLineRight(i) + 2f * padX
                    val bottom = l.getLineBottom(i) + 2f * padY
                    val path = Path().apply {
                        addRoundRect(RoundRect(left, top, right, bottom, CornerRadius(radius, radius)))
                    }
                    clipPath(path) {
                        when {
                            useFrost && f != null -> {
                                // Cover present: frost the real backdrop (blur) plus a dark dim.
                                translate(f.coverOriginInRoot.x - origin.x, f.coverOriginInRoot.y - origin.y) {
                                    drawLayer(f.blurred)
                                }
                                drawRect(
                                    color = Color.Black.copy(alpha = dimAlpha),
                                    topLeft = Offset(left, top),
                                    size = Size(right - left, bottom - top)
                                )
                            }
                            isVideo -> {
                                // Over video: a dark scrim so the text reads over any frame
                                // (a light panel would vanish on a bright picture).
                                drawRect(
                                    color = Color.Black.copy(alpha = 0.42f),
                                    topLeft = Offset(left, top),
                                    size = Size(right - left, bottom - top)
                                )
                            }
                            else -> {
                                // No cover on a plain/dark background: a faint LIGHT panel so the
                                // pill stays visible (a dark dim would vanish into black).
                                drawRect(
                                    color = Color.White.copy(alpha = 0.10f),
                                    topLeft = Offset(left, top),
                                    size = Size(right - left, bottom - top)
                                )
                            }
                        }
                    }
                }
            }
            .padding(
                horizontal = if (enabled) padHorizontal.dp else 0.dp,
                vertical = if (enabled) padVertical.dp else 0.dp
            )
    ) {
        // Outline (stroked glyphs) drawn behind the fill; identical text/style/maxLines so it
        // lays out the same and registers exactly under the fill.
        if (enabled) {
            Text(
                text = text,
                style = style.copy(drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round)),
                color = outlineColour,
                textAlign = TextAlign.Center,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Fill glyphs on top; this Text drives the layout used by the per-line pill draw.
        Text(
            text = text,
            style = style.copy(shadow = shadow),
            color = textColour,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it }
        )
    }
}
