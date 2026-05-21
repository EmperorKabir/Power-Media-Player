package com.powermediaplayer.subtitles

import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Wraps Media3's [DefaultSubtitleParserFactory] so every emitted
 * [CuesWithTiming] has its `startTimeUs` shifted by the user's current
 * "Subtitle delay" Setting.
 *
 * Why this exists
 * - Before this, [com.powermediaplayer.service.PlaybackConnection.setSubtitleDelayMs]
 *   was a logged-only no-op — the slider in Settings persisted but never
 *   reached the subtitle render path.
 * - Media3 1.4.0+ moved subtitle parsing from rendering into extraction
 *   (release-notes confirmed via Context7 against /androidx/media). The
 *   natural hook is therefore at the parser factory, not the renderer.
 * - The shift is applied at parse time. Live drag of the slider
 *   re-prepares the current MediaItem so the parser re-runs with the
 *   new value; the existing track-switch / reload paths already do this
 *   when the SubtitleConfiguration changes.
 *
 * Sign convention
 * - Positive ms  → subtitles delayed (appear later than file says)
 * - Negative ms  → subtitles brought forward (appear earlier)
 * - 0            → identity passthrough; no allocation beyond the wrap
 *
 * Thread safety
 * - [delayMsSupplier] is read on the extractor thread per parse. Backing
 *   field is `@Volatile Int` in PlaybackService — safe for cross-thread
 *   reads without locking.
 */
@UnstableApi
class ShiftingSubtitleParserFactory(
    private val delayMsSupplier: () -> Int
) : SubtitleParser.Factory {

    private val delegate = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean =
        delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val inner = delegate.create(format)
        return ShiftingSubtitleParser(inner, delayMsSupplier)
    }
}

@UnstableApi
private class ShiftingSubtitleParser(
    private val delegate: SubtitleParser,
    private val delayMsSupplier: () -> Int
) : SubtitleParser {

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>
    ) {
        val shiftUs = delayMsSupplier().toLong() * 1000L
        if (shiftUs == 0L) {
            delegate.parse(data, offset, length, outputOptions, output)
            return
        }
        val shifting = Consumer<CuesWithTiming> { cw ->
            // Coerce away from negative timestamps — a cue scheduled
            // before the file's zero is uninterpretable by the renderer.
            val newStart = (cw.startTimeUs + shiftUs).coerceAtLeast(0L)
            output.accept(
                CuesWithTiming(cw.cues, newStart, cw.durationUs)
            )
        }
        delegate.parse(data, offset, length, outputOptions, shifting)
    }

    override fun reset() = delegate.reset()

    override fun getCueReplacementBehavior(): Int =
        delegate.cueReplacementBehavior

    override fun parseToLegacySubtitle(
        data: ByteArray,
        offset: Int,
        length: Int
    ): androidx.media3.extractor.text.Subtitle =
        delegate.parseToLegacySubtitle(data, offset, length)
}
