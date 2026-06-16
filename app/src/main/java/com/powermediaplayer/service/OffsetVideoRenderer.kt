@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.powermediaplayer.service

import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.powermediaplayer.util.Diag
import java.nio.ByteBuffer

/**
 * A [MediaCodecVideoRenderer] that DELAYS video frames by a live offset (µs),
 * so the on-screen picture can be held back to match a late Bluetooth-speaker
 * audio output (the audio can't be advanced, so the video is delayed instead).
 *
 * It adds the offset to each frame's presentation time before the default
 * frame-release logic runs (Media3 1.6.0 keeps that decision in
 * `processOutputBuffer` → `VideoFrameReleaseControl`). A larger presentation
 * time makes the frame "not due yet" → it's held → the picture lags.
 *
 * TWO INDEPENDENT INPUTS — this is the crux:
 *  - [offsetUsSupplier] is the SLIDER value (µs, ≥0). It is applied IMMEDIATELY
 *    so the user sees lip-sync change as they drag — a tuning control must be
 *    responsive, never ramped.
 *  - [gateSupplier] is whether Bluetooth is genuinely the live output. ONLY this
 *    on/off transition is eased (a 0→1 scale over [GATE_RAMP_STEP] per frame),
 *    so a BT connect/disconnect mid-video fades the offset in/out instead of
 *    freezing the picture for `offset` ms (connect) or jumping it (disconnect).
 *
 * applied = offset × gateScale. When the gate is off the scale settles to 0 →
 * applied 0 → the argument is passed to `super` UNCHANGED → normal (non-BT)
 * video playback is byte-for-byte the default, completely unaffected.
 */
class OffsetVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val offsetUsSupplier: () -> Long,
    private val gateSupplier: () -> Boolean
) : MediaCodecVideoRenderer(builder) {

    /** Eased 0→1 gate scale (BT off→on). Only the GATE is ramped; the slider
     *  value is applied immediately. Starts at 0 so first frames are default. */
    private var gateScale: Double = 0.0

    // Diagnostics: prove THIS renderer draws the video + the values applied.
    private var lastLoggedOffset: Long = Long.MIN_VALUE
    private var framesSinceLog: Int = 0

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format
    ): Boolean {
        val offsetUs = offsetUsSupplier()
        val gateOn = gateSupplier()
        // Slider value applies immediately; only the gate scale eases.
        val appliedUs = (offsetUs * gateScale).toLong()
        val processed = super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs + appliedUs, // 0 when gate settled off → default
            isDecodeOnlyBuffer,
            isLastBuffer,
            format
        )
        // Ease the GATE scale once per processed buffer (a held frame re-invokes
        // this returning false; bumping then would over-ramp the scale).
        if (processed) {
            val targetScale = if (gateOn) 1.0 else 0.0
            gateScale += (targetScale - gateScale).coerceIn(-GATE_RAMP_STEP, GATE_RAMP_STEP)
            framesSinceLog++
        }
        if (offsetUs != lastLoggedOffset || framesSinceLog >= 150) {
            lastLoggedOffset = offsetUs
            framesSinceLog = 0
            Diag.i(
                "PMP_DIAG",
                "BTVID render offset=${offsetUs}us gateOn=$gateOn scale=${"%.2f".format(gateScale)} " +
                    "applied=${appliedUs}us processed=$processed"
            )
        }
        return processed
    }

    private companion object {
        /** Gate-scale change per processed frame: 0→1 in 20 frames (~0.67 s at
         *  30 fps, ~0.33 s at 60 fps). Smooths a BT connect/disconnect without
         *  the slider value itself ever being throttled. */
        const val GATE_RAMP_STEP = 0.05
    }
}
