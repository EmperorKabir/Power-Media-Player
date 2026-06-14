package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * In-chain 10-band graphic EQ — biquad peaking filters at the displayed
 * 31 Hz … 16 kHz centres — replacing the platform `audiofx.Equalizer`.
 *
 * Why: the platform EQ exposes only the DEVICE's hardware bands (almost
 * always 5) and the app mapped its 10 sliders onto them 1:1 BY INDEX. So
 * sliders 0–4 ("31"–"500 Hz") actually drove the device's full-spectrum
 * bands (boosting them clipped → the "farty/stuttery" low end) while
 * sliders 5–9 ("1 kHz"–"16 kHz") were never applied at all ("no effect on
 * the high end"). This processor implements all ten bands honestly at their
 * real frequencies, device-independent, with a soft-knee limiter so
 * cumulative boosts round off instead of hard-clipping.
 *
 * Levels are supplied in millibels (100 mB = 1 dB), read lazily per buffer.
 * Flat (every band 0) → exact byte-for-byte pass-through at zero cost, so
 * the rest of the chain (reverb etc.) is untouched unless the EQ is in use.
 * Placed AFTER the reverb processor in the sink chain so reverb's input —
 * and therefore its behaviour — is unchanged by this addition.
 */
@OptIn(UnstableApi::class)
class EqualizerAudioProcessor(
    private val bandLevelsMbSupplier: () -> List<Int>
) : BaseAudioProcessor() {

    private companion object {
        // Matches EqualizerViewModel.bandFrequencies labels.
        val CENTERS = doubleArrayOf(
            31.0, 63.0, 125.0, 250.0, 500.0,
            1000.0, 2000.0, 4000.0, 8000.0, 16000.0
        )
        const val Q = 1.41        // ~1 octave per band (graphic-EQ standard)
        const val N = 10
    }

    private var channels = 0
    private var sampleRate = 0

    // Per-band normalised biquad coefficients (a0 folded in).
    private val b0 = DoubleArray(N)
    private val b1 = DoubleArray(N)
    private val b2 = DoubleArray(N)
    private val a1 = DoubleArray(N)
    private val a2 = DoubleArray(N)
    private val bandActive = BooleanArray(N)

    // Per-band, per-channel filter state (Transposed Direct Form II).
    private var z1 = Array(N) { DoubleArray(0) }
    private var z2 = Array(N) { DoubleArray(0) }

    private var lastLevels: List<Int> = listOf(-1)   // force first recompute
    private var anyActive = false

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = input.channelCount
        sampleRate = input.sampleRate
        z1 = Array(N) { DoubleArray(channels) }
        z2 = Array(N) { DoubleArray(channels) }
        lastLevels = listOf(-1)
        recompute(bandLevelsMbSupplier())
        return input
    }

    private fun recompute(levels: List<Int>) {
        lastLevels = levels
        var active = false
        for (i in 0 until N) {
            val mb = levels.getOrElse(i) { 0 }
            val f0 = CENTERS[i]
            // Skip flat bands and any centre at/above Nyquist (low sample
            // rates) — a peaking filter there is undefined / unstable.
            if (mb == 0 || sampleRate <= 0 || f0 >= sampleRate * 0.45) {
                bandActive[i] = false
                continue
            }
            val gainDb = mb / 100.0
            val a = Math.pow(10.0, gainDb / 40.0)          // amplitude √(linear)
            val w0 = 2.0 * Math.PI * f0 / sampleRate
            val cosw0 = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha / a
            b0[i] = (1.0 + alpha * a) / a0
            b1[i] = (-2.0 * cosw0) / a0
            b2[i] = (1.0 - alpha * a) / a0
            a1[i] = (-2.0 * cosw0) / a0
            a2[i] = (1.0 - alpha / a) / a0
            bandActive[i] = true
            active = true
        }
        anyActive = active
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val levels = bandLevelsMbSupplier()
        if (levels != lastLevels) recompute(levels)

        if (!anyActive || channels == 0) {
            // Flat → exact pass-through; downstream sees identical bytes.
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }

        val out = replaceOutputBuffer(remaining)
        val samples = remaining / 2
        // Soft knee: linear up to the threshold, then compressed — cumulative
        // band boosts round off instead of squaring (the audible hard-clip).
        val knee = 26000f
        var ch = 0
        repeat(samples) {
            var x = inputBuffer.short.toDouble()
            for (i in 0 until N) {
                if (!bandActive[i]) continue
                val y = b0[i] * x + z1[i][ch]
                z1[i][ch] = b1[i] * x - a1[i] * y + z2[i][ch]
                z2[i][ch] = b2[i] * x - a2[i] * y
                x = y
            }
            var v = x.toFloat()
            val mag = kotlin.math.abs(v)
            if (mag > knee) {
                v = Math.signum(v) * (knee + (mag - knee) * 0.25f)
            }
            out.putShort(v.coerceIn(-32760f, 32760f).toInt().toShort())
            ch++
            if (ch >= channels) ch = 0
        }
        out.flip()
    }

    override fun onFlush() {
        // Clear filter memory on seek/flush so a stale tail can't click.
        for (i in 0 until N) {
            if (z1[i].isNotEmpty()) java.util.Arrays.fill(z1[i], 0.0)
            if (z2[i].isNotEmpty()) java.util.Arrays.fill(z2[i], 0.0)
        }
    }

    override fun onReset() {
        z1 = Array(N) { DoubleArray(0) }
        z2 = Array(N) { DoubleArray(0) }
        lastLevels = listOf(-1)
        anyActive = false
    }
}
