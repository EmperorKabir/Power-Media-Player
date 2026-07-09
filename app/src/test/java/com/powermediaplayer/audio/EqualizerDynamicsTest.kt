package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Item 6 (2026-07-09) — the EQ output-stage redesign, cross-examined by an
 * adversarial DSP review. Proves the two user-reported defects are gone at
 * the processor surface:
 *  (a) "big volume loss when boosting" — a boosted band must now play at its
 *      TRUE gain (the old worst-case pre-attenuation is removed);
 *  (b) "brief fart when adjusting" — gain changes must ramp (no step
 *      discontinuity), detected via the second difference of a low-frequency
 *      sine, where the old instant coefficient swap was worst.
 * Plus the limiter contract: sustained boosted material parks at a CONSTANT
 * gain (hold covers the longest band period) so it stays distortion-clean,
 * and output never exceeds full scale.
 */
class EqualizerDynamicsTest {

    private val SR = 48_000
    private val format = AudioProcessor.AudioFormat(SR, 2, C.ENCODING_PCM_16BIT)

    private fun sineStereo(frames: Int, freqHz: Double, amp: Double, phase0Frame: Int = 0): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (n in 0 until frames) {
            val s = (amp * sin(2.0 * PI * freqHz * (phase0Frame + n) / SR)).toInt()
                .coerceIn(-32768, 32767).toShort()
            b.putShort(s); b.putShort(s)
        }
        b.flip(); return b
    }

    private fun drain(p: AudioProcessor): ByteArray {
        val out = p.output
        val arr = ByteArray(out.remaining()); out.get(arr); return arr
    }

    private fun leftChannel(bytes: ByteArray): DoubleArray {
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = sb.remaining() / 2
        val out = DoubleArray(frames)
        for (i in 0 until frames) { out[i] = sb.get().toDouble(); sb.get() }
        return out
    }

    /** Goertzel amplitude of [freqHz] over the window [off, off+N). */
    private fun amplitude(x: DoubleArray, off: Int, N: Int, freqHz: Double): Double {
        val k = (freqHz * N / SR).toInt()
        val w = 2.0 * PI * k / N
        val cw = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (n in 0 until N) {
            val s0 = x[off + n] + cw * s1 - s2
            s2 = s1; s1 = s0
        }
        val real = s1 - s2 * cos(w)
        val imag = s2 * sin(w)
        return 2.0 * sqrt(real * real + imag * imag) / N
    }

    /** (a) A +6 dB band boost must deliver ~+6 dB at the band centre for quiet
     *  material — the old design delivered ~0 dB net (boost eaten by the
     *  global pre-attenuation). */
    @Test fun boostDeliversTrueGain() {
        val p = EqualizerAudioProcessor({ MutableList(10) { 0 }.also { it[5] = 600 } })
        p.configure(format); p.flush()
        val amp = 3277.0 // ~-20 dBFS: limiter provably inactive
        p.queueInput(sineStereo(19_200, 1000.0, amp))
        val x = leftChannel(drain(p))
        val outAmp = amplitude(x, 9_600, 9_600, 1000.0)
        val ratio = outAmp / amp
        println("DYN-DIAG boost ratio=$ratio (want ~1.995)")
        assertTrue("boost must arrive at ~+6 dB, got ratio=$ratio", ratio in 1.85..2.15)
    }

    /** (a) Off-centre material must NOT be globally attenuated when another
     *  band is boosted (the old preGain attenuated everything). */
    @Test fun neighbourBandNotAttenuated() {
        val p = EqualizerAudioProcessor({ MutableList(10) { 0 }.also { it[5] = 600 } })
        p.configure(format); p.flush()
        val amp = 3277.0
        p.queueInput(sineStereo(19_200, 125.0, amp)) // 3+ octaves below the boost
        val x = leftChannel(drain(p))
        val ratio = amplitude(x, 9_600, 9_600, 125.0) / amp
        println("DYN-DIAG neighbour ratio=$ratio (want ~1.0)")
        assertTrue("distant band must stay ~unity, got $ratio", ratio in 0.85..1.2)
    }

    /** (b) Changing every band mid-stream must produce NO step discontinuity.
     *  Second difference of a smooth 31 Hz sine is tiny; an instant 12 dB
     *  coefficient swap injects a spike an order of magnitude larger than the
     *  ramped block edges. */
    @Test fun gainChangeIsZipperFree() {
        var levels = List(10) { 0 }
        val p = EqualizerAudioProcessor({ levels })
        p.configure(format); p.flush()
        val amp = 2000.0
        val collected = ArrayList<ByteArray>()
        var frameBase = 0
        // 1024-frame buffers; flip the preset a third of the way through.
        repeat(30) { buf ->
            if (buf == 10) levels = List(10) { 1200 }
            p.queueInput(sineStereo(1024, 30.0, amp, phase0Frame = frameBase))
            frameBase += 1024
            collected.add(drain(p))
        }
        val all = leftChannel(collected.reduce { a, b -> a + b })
        // Scan the transition region (buffers 10..30) for step discontinuities.
        var maxSecondDiff = 0.0
        for (n in (10 * 1024 + 2) until all.size) {
            val d2 = abs(all[n] - 2.0 * all[n - 1] + all[n - 2])
            if (d2 > maxSecondDiff) maxSecondDiff = d2
        }
        println("DYN-DIAG maxSecondDiff=$maxSecondDiff (instant swap would be >4000)")
        assertTrue("gain change must ramp, not step: d2=$maxSecondDiff", maxSecondDiff < 2000.0)
        // And it must actually CONVERGE to the boosted response. All ten bands
        // at +12 dB: the 31 Hz band contributes ~x4 at 30 Hz and the 63/125 Hz
        // skirts stack on top -> composite ~x5.5 (measured 5.46).
        val settled = amplitude(all, all.size - 9_600, 9_600, 30.0) / amp
        println("DYN-DIAG settled ratio=$settled (want ~5.5 composite)")
        assertTrue("ramp must converge to the composite target, got $settled", settled in 4.5..6.5)
    }

    /** Limiter contract: sustained boosted-past-ceiling material parks at a
     *  constant gain (hold >= one 31 Hz cycle) — clean, and never over FS. */
    @Test fun sustainedLimitingStaysCleanAndBounded() {
        val p = EqualizerAudioProcessor({ MutableList(10) { 0 }.also { it[5] = 600 } })
        p.configure(format); p.flush()
        p.queueInput(sineStereo(19_200, 1000.0, 30_000.0)) // ~2x over ceiling post-boost
        val x = leftChannel(drain(p))
        var peak = 0.0
        for (v in x) peak = maxOf(peak, abs(v))
        assertTrue("output must stay within full scale, peak=$peak", peak <= 32767.0)
        // THD in the settled window: constant limiter gain -> pure sine.
        val fund = amplitude(x, 9_600, 9_600, 1000.0)
        var total = 0.0
        for (n in 9_600 until 19_200) total += x[n] * x[n]
        total /= 9_600
        val harm = (total - fund * fund / 2.0).coerceAtLeast(0.0)
        val thd = if (fund > 0) sqrt(harm / (fund * fund / 2.0)) else 0.0
        println("DYN-DIAG limited THD=$thd peak=$peak")
        assertTrue("sustained limiting must stay clean, THD=$thd", thd < 0.02)
    }
}
