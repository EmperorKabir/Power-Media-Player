package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceBoostAudioProcessorTest {
    private val SR = 48_000
    private val format = AudioProcessor.AudioFormat(SR, 2, C.ENCODING_PCM_16BIT)

    private fun sineStereo(frames: Int, freqHz: Double, amp: Double): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        for (n in 0 until frames) {
            val s = (amp * sin(2.0 * PI * freqHz * n / SR)).toInt().toShort()
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

    private fun goertzelMag(x: DoubleArray, off: Int, N: Int, k: Int): Double {
        val w = 2.0 * PI * k / N
        val cw = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (n in 0 until N) { val s0 = x[off + n] + cw * s1 - s2; s2 = s1; s1 = s0 }
        val real = s1 - s2 * cos(w); val imag = s2 * sin(w)
        return sqrt(real * real + imag * imag)
    }

    private fun magAt(enabled: Boolean, freqHz: Double): Double {
        val p = VoiceBoostAudioProcessor({ enabled })
        p.configure(format); p.flush()
        p.queueInput(sineStereo(19_200, freqHz, 12_000.0))
        val x = leftChannel(drain(p))
        return goertzelMag(x, 9_600, 9_600, (freqHz * 9_600 / SR).toInt())
    }

    @Test fun offIsExactPassThrough() {
        val p = VoiceBoostAudioProcessor({ false })
        p.configure(format); p.flush()
        val input = sineStereo(4_096, 1_000.0, 10_000.0)
        val copy = ByteArray(input.remaining()); input.duplicate().get(copy)
        p.queueInput(input)
        assertArrayEquals(copy, drain(p))
    }

    @Test fun presenceBandBoostedRelativeToLow() {
        // 2.7 kHz (presence) gains relative to 300 Hz (low) when enabled.
        val pres = magAt(true, 2_700.0) / magAt(false, 2_700.0)
        val low = magAt(true, 300.0) / magAt(false, 300.0)
        println("VB-DIAG presenceRatio=$pres lowRatio=$low")
        assertTrue("presence ($pres) should exceed low ($low) by >=1.3x", pres > low * 1.3)
    }
}
