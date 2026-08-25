package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Contract tests for [GainAudioProcessor] (PROJECT_RULES: every processor ships an
 * off=passthrough + configure→queue→flush test). Verifies the PCM16 gate, exact unity
 * pass-through, amplification when a gain is set, and the soft-knee ceiling.
 */
class GainAudioProcessorTest {
    private val SR = 48_000
    private val fmt = AudioProcessor.AudioFormat(SR, 2, C.ENCODING_PCM_16BIT)

    private fun buf(samples: ShortArray): ByteBuffer {
        val b = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) b.putShort(s)
        b.flip(); return b
    }

    private fun drainShorts(p: AudioProcessor): ShortArray {
        val out = p.output
        val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
        val arr = ShortArray(sb.remaining())
        sb.get(arr); return arr
    }

    @Test fun nonPcm16_returnsNotSet() {
        val p = GainAudioProcessor({ 0 })
        val r = p.configure(AudioProcessor.AudioFormat(SR, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, r)
    }

    @Test fun pcm16_configureReturnsInput() {
        val p = GainAudioProcessor({ 0 })
        assertEquals(fmt, p.configure(fmt))
    }

    @Test fun offIsExactPassThrough() {
        val p = GainAudioProcessor({ 0 })   // 0 mB → unity
        p.configure(fmt); p.flush()
        val input = buf(shortArrayOf(100, -200, 300, -400, 500, 12000, -12000, 0))
        val copy = ByteArray(input.remaining()); input.duplicate().get(copy)
        p.queueInput(input)
        val out = p.output
        val outArr = ByteArray(out.remaining()); out.get(outArr)
        assertArrayEquals("0 mB must be exact pass-through", copy, outArr)
    }

    @Test fun gainAmplifiesOverBuffer() {
        val p = GainAudioProcessor({ 2000 })  // 10^(2000/2000) = 10x target
        p.configure(fmt); p.flush()
        val n = 8000
        p.queueInput(buf(ShortArray(n) { 1000 }))  // constant amplitude 1000, below the knee
        val out = drainShorts(p)
        assertEquals(n, out.size)
        // curGain glides 1→10 across the buffer: first sample ~1000, last well above.
        assertTrue("first sample near input (${out.first()})", abs(out.first().toInt()) < 1200)
        assertTrue("late samples amplified (${out.last()})", abs(out.last().toInt()) > 5000)
    }

    @Test fun outputNeverExceedsCeiling() {
        // ~31.6x gain on loud input pushes past the soft knee; every output sample must
        // still stay within the ±32760 ceiling (soft-knee compression, no hard-clip overflow).
        val p = GainAudioProcessor({ 3000 })
        p.configure(fmt); p.flush()
        p.queueInput(buf(ShortArray(4000) { 20000 }))
        val out = drainShorts(p)
        assertTrue("all samples within ceiling", out.all { abs(it.toInt()) <= 32760 })
        assertTrue("gain took effect (some samples loud)", out.any { abs(it.toInt()) > 20000 })
    }
}
