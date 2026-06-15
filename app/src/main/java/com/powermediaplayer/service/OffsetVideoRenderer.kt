@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.powermediaplayer.service

import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
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
 * CRUCIAL: at offset 0 the argument is passed to `super` UNCHANGED, so the
 * behaviour is byte-for-byte the default — normal video playback (the common
 * case) is completely unaffected. Only a non-zero BT offset shifts anything.
 *
 * @param offsetUsSupplier read live each frame, so the slider takes effect
 *        without rebuilding the player. Positive = delay the picture.
 */
class OffsetVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val offsetUsSupplier: () -> Long
) : MediaCodecVideoRenderer(builder) {

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
        val off = offsetUsSupplier()
        return super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs + off, // offset 0 → unchanged → default behaviour
            isDecodeOnlyBuffer,
            isLastBuffer,
            format
        )
    }
}
