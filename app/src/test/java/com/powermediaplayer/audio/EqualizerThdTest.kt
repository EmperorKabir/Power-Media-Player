package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
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
 * #7 — the in-chain EQ sounded "robotic / low-bitrate". The biquad math is
 * correct; the artefact was a hard soft-knee waveshaper firing at ~-2 dBFS on
 * boosted peaks with NO input headroom and NO oversampling → high-order
 * harmonics that alias back in-band. This harness measures Total Harmonic
 * Distortion (THD) offline via a Goertzel bin at the fundamental, with coherent
 * sampling (1 kHz @ 48 kHz → 48-sample period; a 9600-sample window = exactly
 * 200 periods, bin k=200) so there is no spectral leakage to confound the
 * measurement. FAILS on the pre-fix processor (THD ~11 %), PASSES after the
 * headroom + tanh-limiter fix. The flat path is pinned byte-exact so the fix
 * cannot regress the zero-cost pass-through.
 */
class EqualizerThdTest {

    private val SR = 48_000
    private val format = AudioProcessor.AudioFormat(SR, 2, C.ENCODING_PCM_16BIT)

    /** One continuous stereo sine, phase 0 at frame 0 (so any window starting
     *  at a frame that is a whole number of periods in is coherent). */
    private fun sineStereo(frames: Int, freqHz: Double, amp: Double): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (n in 0 until frames) {
            val s = (amp * sin(2.0 * PI * freqHz * n / SR)).toInt()
                .coerceIn(-32768, 32767).toShort()
            b.putShort(s); b.putShort(s)
        }
        b.flip(); return b
    }

    private fun drain(p: AudioProcessor): ByteArray {
        val out = p.output
        val arr = ByteArray(out.remaining()); out.get(arr); return arr
    }

    /** Left-channel samples (one per frame). */
    private fun leftChannel(bytes: ByteArray): DoubleArray {
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = sb.remaining() / 2
        val out = DoubleArray(frames)
        for (i in 0 until frames) { out[i] = sb.get().toDouble(); sb.get() }
        return out
    }

    /** Goertzel magnitude of integer bin k over the window [off, off+N). */
    private fun goertzelMag(x: DoubleArray, off: Int, N: Int, k: Int): Double {
        val w = 2.0 * PI * k / N
        val cw = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (n in 0 until N) {
            val s0 = x[off + n] + cw * s1 - s2
            s2 = s1; s1 = s0
        }
        val real = s1 - s2 * cos(w)
        val imag = s2 * sin(w)
        return sqrt(real * real + imag * imag)
    }

    /** THD = sqrt(non-fundamental power / fundamental power) over a coherent
     *  window. fundamental amplitude A = 2*|X(k)|/N, fund power = A^2/2. */
    private fun thd(x: DoubleArray, off: Int, N: Int, freqHz: Double): Double {
        val k = (freqHz * N / SR).toInt()
        val aF = 2.0 * goertzelMag(x, off, N, k) / N
        val fundPower = aF * aF / 2.0
        var total = 0.0
        for (n in 0 until N) total += x[off + n] * x[off + n]
        total /= N
        val harm = (total - fundPower).coerceAtLeast(0.0)
        return if (fundPower <= 0.0) 0.0 else sqrt(harm / fundPower)
    }

    private fun processSine(levels: List<Int>, freqHz: Double, amp: Double): DoubleArray {
        val p = EqualizerAudioProcessor({ levels })
        p.configure(format); p.flush()
        // 19200 frames = two 9600 windows; analyse the SECOND (filter settled).
        p.queueInput(sineStereo(19_200, freqHz, amp))
        return leftChannel(drain(p))
    }

    @Test fun oneBandBoostStaysClean() {
        val levels = MutableList(10) { 0 }.also { it[5] = 600 } // 1 kHz +6 dB
        val x = processSine(levels, 1000.0, 20_000.0)
        val t = thd(x, 9_600, 9_600, 1000.0)
        println("THD-DIAG oneBand=$t")
        assertTrue("THD too high (oneBand)=$t", t < 0.02)
    }

    @Test fun allBandsBoostStaysClean() {
        val levels = MutableList(10) { 600 } // every band +6 dB
        val x = processSine(levels, 1000.0, 20_000.0)
        val t = thd(x, 9_600, 9_600, 1000.0)
        println("THD-DIAG allBands=$t")
        assertTrue("THD too high (allBands)=$t", t < 0.05)
    }

    @Test fun loudBoostPeakBounded() {
        val levels = MutableList(10) { 0 }.also { it[5] = 600 }
        val x = processSine(levels, 1000.0, 30_000.0)
        var peak = 0.0
        for (v in x) peak = maxOf(peak, abs(v))
        println("THD-DIAG peak=$peak")
        assertTrue("peak must stay within full scale =$peak", peak <= 32767.0)
    }

    @Test fun flatIsExactPassThrough() {
        val p = EqualizerAudioProcessor({ List(10) { 0 } })
        p.configure(format); p.flush()
        val frames = 1024
        val expected = ByteArray(frames * 4)
        java.util.Random(42).nextBytes(expected)
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        input.put(expected); input.flip()
        p.queueInput(input)
        assertArrayEquals("flat must be byte-exact pass-through", expected, drain(p))
    }
}
