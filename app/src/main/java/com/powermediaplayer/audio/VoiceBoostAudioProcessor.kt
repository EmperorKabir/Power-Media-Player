package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Voice boost / speech clarity — a single presence-band peaking biquad (~2.7 kHz,
 * the consonant/intelligibility band) lifting speech out of a mix. Mirrors
 * [EqualizerAudioProcessor]'s structure (BaseAudioProcessor, media3 1.6.0,
 * 16-bit PCM, per-channel Transposed-Direct-Form-II state). Headroom is reserved
 * for the boost peak so it can't clip, with a tanh knee as the last-resort
 * ceiling; OFF → exact byte pass-through.
 *
 * Local pipeline only — N/A on Spotify Connect / Cast (audio is remote). Pure
 * DSP → unit-tested (VoiceBoostAudioProcessorTest).
 */
class VoiceBoostAudioProcessor(
    private val enabledSupplier: () -> Boolean
) : BaseAudioProcessor() {

    private companion object {
        const val F0 = 2700.0      // presence centre (Hz)
        const val BOOST_DB = 5.0   // fixed lift
        const val Q = 1.0          // ~1.5-octave bell
        const val FULL_SCALE = 32767f
        const val LIMIT_LINEAR = 0.85f
        const val MIN_PREGAIN = 0.2
    }

    private var channels = 0
    private var sampleRate = 0

    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)
    private var preGain = 1.0
    private var active = false
    private var lastEnabled = false

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = input.channelCount
        sampleRate = input.sampleRate
        z1 = DoubleArray(channels)
        z2 = DoubleArray(channels)
        recompute(enabledSupplier())
        return input
    }

    private fun recompute(enabled: Boolean) {
        lastEnabled = enabled
        if (!enabled || sampleRate <= 0 || F0 >= sampleRate * 0.45) {
            active = false
            return
        }
        val a = Math.pow(10.0, BOOST_DB / 40.0)        // amplitude √(linear)
        val w0 = 2.0 * Math.PI * F0 / sampleRate
        val cosw0 = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * Q)
        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosw0) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosw0) / a0
        a2 = (1.0 - alpha / a) / a0
        // Reserve headroom = peak |H(f)| at the centre (the boost) so the lift
        // can't clip; tanh mops up any tiny between-sample overshoot.
        val peak = Math.pow(10.0, BOOST_DB / 20.0)
        preGain = (1.0 / peak).coerceIn(MIN_PREGAIN, 1.0)
        active = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val enabled = enabledSupplier()
        if (enabled != lastEnabled) recompute(enabled)

        if (!active || channels == 0) {
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }
        val out = replaceOutputBuffer(remaining)
        val samples = remaining / 2
        val threshold = LIMIT_LINEAR * FULL_SCALE
        var ch = 0
        repeat(samples) {
            var x = inputBuffer.short.toDouble() * preGain
            val y = b0 * x + z1[ch]
            z1[ch] = b1 * x - a1 * y + z2[ch]
            z2[ch] = b2 * x - a2 * y
            var v = y.toFloat()
            val mag = kotlin.math.abs(v)
            if (mag > threshold) {
                val over = (mag - threshold) / ((1f - LIMIT_LINEAR) * FULL_SCALE)
                v = Math.signum(v) * FULL_SCALE *
                    (LIMIT_LINEAR + (1f - LIMIT_LINEAR) *
                        kotlin.math.tanh(over.toDouble()).toFloat())
            }
            out.putShort(v.coerceIn(-FULL_SCALE, FULL_SCALE).toInt().toShort())
            ch++
            if (ch >= channels) ch = 0
        }
        out.flip()
    }

    override fun onFlush() {
        if (z1.isNotEmpty()) java.util.Arrays.fill(z1, 0.0)
        if (z2.isNotEmpty()) java.util.Arrays.fill(z2, 0.0)
    }
}
