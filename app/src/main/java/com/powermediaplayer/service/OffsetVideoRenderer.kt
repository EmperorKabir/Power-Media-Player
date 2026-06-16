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
 * CRUCIAL: when the target offset is 0 (the non-BT default) the applied offset
 * stays 0, so the argument is passed to `super` UNCHANGED and the behaviour is
 * byte-for-byte the default — normal video playback (the common case) is
 * completely unaffected. Only a non-zero BT offset shifts anything.
 *
 * EASING: the supplier's value can change in one step — a BT-route gate flip
 * (connect/disconnect) or a slider scrub. Applying that step raw would freeze
 * the picture for `target` ms (stepping up) or fast-forward it (stepping down).
 * So the APPLIED offset eases toward the target by at most [RAMP_STEP_US] per
 * PROCESSED frame, spreading a typical ±250 ms change over ~1.7 s at 30 fps —
 * an imperceptible speed nudge instead of a jolt.
 *
 * @param offsetUsSupplier read live each frame, so the slider/gate take effect
 *        without rebuilding the player. Positive = delay the picture.
 */
class OffsetVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val offsetUsSupplier: () -> Long
) : MediaCodecVideoRenderer(builder) {

    /** The offset actually added to frame PTS, eased toward the supplier target
     *  (see EASING above). Starts at 0 so the first frames are default. */
    private var appliedOffsetUs: Long = 0L

    // Diagnostics: prove (a) THIS renderer is the one selected for video and
    // (b) the target/applied offset, on every target change + a heartbeat.
    private var lastLoggedTarget: Long = Long.MIN_VALUE
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
        val target = offsetUsSupplier()
        val processed = super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs + appliedOffsetUs, // 0 at steady non-BT → default
            isDecodeOnlyBuffer,
            isLastBuffer,
            format
        )
        // Advance the ramp ONCE per processed buffer — a held frame re-invokes
        // this returning false, and bumping then would over-ramp past the target.
        if (processed) {
            appliedOffsetUs += (target - appliedOffsetUs).coerceIn(-RAMP_STEP_US, RAMP_STEP_US)
            framesSinceLog++
        }
        if (target != lastLoggedTarget || framesSinceLog >= 150) {
            lastLoggedTarget = target
            framesSinceLog = 0
            Diag.i(
                "PMP_DIAG",
                "BTVID render target=${target}us applied=${appliedOffsetUs}us processed=$processed"
            )
        }
        return processed
    }

    private companion object {
        /** Max change to the applied offset per processed frame (µs). 5 ms/frame
         *  ≈ 150 ms/s at 30 fps → a ±250 ms transition eases in ~1.7 s. */
        const val RAMP_STEP_US = 5_000L
    }
}
