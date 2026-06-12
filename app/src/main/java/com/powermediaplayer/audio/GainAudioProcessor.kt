package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * In-chain volume boost with a soft-knee limiter.
 *
 * Replaces the platform LoudnessEnhancer, which applies its gain with a
 * hard clip — at high settings every transient crackles and the user
 * "should not have to put up with that". Here the gain glides per
 * sample (no zipper noise on slider drags) and anything pushed past the
 * knee is compressed instead of squared off, so +20 dB stays loud but
 * clean.
 *
 * Gain is supplied in millibels (0..3000) and read lazily per buffer.
 */
@OptIn(UnstableApi::class)
class GainAudioProcessor(
    private val gainMbSupplier: () -> Int
) : BaseAudioProcessor() {

    private var curGain = 1f
    // Audit 3.11 — pow() ran per buffer even at unity; the supplier read
    // stays per-buffer BY DESIGN (live toggling), the transcendental
    // recomputes only when the mB value changes.
    private var lastMb = Int.MIN_VALUE
    private var lastTargetGain = 1f

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return input
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val mb = gainMbSupplier().coerceIn(0, 3000)
        if (mb != lastMb) {
            lastMb = mb
            lastTargetGain = Math.pow(10.0, mb / 2000.0).toFloat()
        }
        val targetGain = lastTargetGain

        // Settled at unity → exact pass-through, zero cost.
        if (targetGain == 1f && kotlin.math.abs(curGain - 1f) < 0.0005f) {
            curGain = 1f
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }

        val out = replaceOutputBuffer(remaining)
        val samples = remaining / 2
        val glide = 0.002f
        // Soft knee: linear to the threshold, 4:1 above it, hard ceiling
        // only at the very top — transients round off instead of
        // squaring (the audible "crackle" of a hard clip).
        val knee = 26000f
        repeat(samples) {
            curGain += (targetGain - curGain) * glide
            var v = inputBuffer.short * curGain
            val a = kotlin.math.abs(v)
            if (a > knee) {
                val over = a - knee
                v = Math.signum(v) * (knee + over * 0.25f)
            }
            out.putShort(v.coerceIn(-32760f, 32760f).toInt().toShort())
        }
        out.flip()
    }
}
