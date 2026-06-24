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

        // #7 — kill the "robotic / low-bitrate" artefact. The biquads are
        // correct; the artefact was a HARD soft-knee firing at ~-2 dBFS on
        // boosted peaks with no headroom and no oversampling → high-order
        // harmonics that alias. Fix = reserve input headroom so boosts no
        // longer slam a low threshold (the EQ becomes headroom-managed /
        // relative — a boosted band sits near unity while the rest sits a
        // touch lower, the standard clean graphic-EQ behaviour), and replace
        // the hard knee with a C-infinity-smooth tanh limiter as a last-resort
        // ceiling whose harmonic spill rolls off fast.
        const val FULL_SCALE = 32767f
        // Below LIMIT_LINEAR*FS the transfer is exactly unity (no colour);
        // above it the tanh knee bends smoothly to the FULL_SCALE asymptote.
        const val LIMIT_LINEAR = 0.85f
        // Floor on the reserved-headroom attenuation (-24 dB) so a pathological
        // preset can't drive the signal to silence; the tanh limiter mops up the
        // tiny residual beyond this.
        const val MIN_PREGAIN = 0.063
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

    // #7 — input attenuation reserved for the active preset's worst-case boost.
    // 1.0 when flat; < 1.0 once any band is boosted, so a boosted resonant peak
    // no longer crosses a low hard-knee threshold.
    private var preGain = 1.0

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
        // #7 — reserve headroom equal to the cascade's ACTUAL worst-case peak
        // linear gain, so a boosted band sits at unity (never clips) while the
        // rest sits proportionally lower. We evaluate |H(f)| of the active
        // cascade at each band centre and reserve the maximum. This keeps the
        // per-sample tanh limiter from engaging on normal programme at all — so
        // there are NO harmonics to alias (the +12 dB-all-bands extreme that a
        // crude dB-sum headroom left at ~9 % THD now measures ~0 %), making
        // polyphase oversampling unnecessary. A cut-only preset reserves nothing
        // (maxGain<=1 → preGain=1, no level loss). There is deliberately no
        // make-up restore: restoring would push the peak back over full scale and
        // re-introduce the clipping that caused the artefact — the headroom IS
        // the fix; tanh only mops up any tiny between-centre overshoot.
        var maxGain = 1.0
        for (fIdx in 0 until N) {
            if (!bandActive[fIdx]) continue
            val f = CENTERS[fIdx]
            if (sampleRate <= 0 || f >= sampleRate * 0.45) continue
            val w = 2.0 * Math.PI * f / sampleRate
            val cw = Math.cos(w); val sw = Math.sin(w)
            val c2 = Math.cos(2.0 * w); val s2 = Math.sin(2.0 * w)
            var g = 1.0
            for (i in 0 until N) {
                if (!bandActive[i]) continue
                val nre = b0[i] + b1[i] * cw + b2[i] * c2
                val nim = -(b1[i] * sw + b2[i] * s2)
                val dre = 1.0 + a1[i] * cw + a2[i] * c2
                val dim = -(a1[i] * sw + a2[i] * s2)
                g *= Math.sqrt((nre * nre + nim * nim) / (dre * dre + dim * dim))
            }
            if (g > maxGain) maxGain = g
        }
        preGain = (1.0 / maxGain).coerceIn(MIN_PREGAIN, 1.0)
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
        val threshold = LIMIT_LINEAR * FULL_SCALE
        val span = (1f - LIMIT_LINEAR) * FULL_SCALE
        var ch = 0
        repeat(samples) {
            // Pre-attenuate into the cascade by the reserved headroom (#7).
            var x = inputBuffer.short.toDouble() * preGain
            for (i in 0 until N) {
                if (!bandActive[i]) continue
                val y = b0[i] * x + z1[i][ch]
                z1[i][ch] = b1[i] * x - a1[i] * y + z2[i][ch]
                z2[i][ch] = b2[i] * x - a2[i] * y
                x = y
            }
            var v = x.toFloat()
            val a = kotlin.math.abs(v)
            if (a > threshold) {
                // Smooth tanh knee from `threshold` up to the FULL_SCALE
                // asymptote — C-infinity, so its harmonic spill rolls off far
                // faster than the old hard knee (which aliased).
                val over = (a - threshold) / span
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
        preGain = 1.0
    }
}
