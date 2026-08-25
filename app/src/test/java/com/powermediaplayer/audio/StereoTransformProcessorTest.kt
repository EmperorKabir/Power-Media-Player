package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Contract tests for [StereoTransformProcessor] (PROJECT_RULES: off=passthrough +
 * configure→queue→flush). Covers the PCM16-stereo gate, exact pass-through, stereo flip,
 * mono mix, and mono-wins-over-flip.
 */
class StereoTransformProcessorTest {
    private val SR = 48_000
    private val fmt = AudioProcessor.AudioFormat(SR, 2, C.ENCODING_PCM_16BIT)

    private fun frames(vararg lr: Pair<Int, Int>): ByteBuffer {
        val b = ByteBuffer.allocate(lr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for ((l, r) in lr) { b.putShort(l.toShort()); b.putShort(r.toShort()) }
        b.flip(); return b
    }

    private fun readFrames(p: AudioProcessor): List<Pair<Int, Int>> {
        val sb = p.output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = ArrayList<Pair<Int, Int>>()
        while (sb.remaining() >= 2) out.add(sb.get().toInt() to sb.get().toInt())
        return out
    }

    @Test fun nonStereo_returnsNotSet() {
        val p = StereoTransformProcessor({ false }, { false })
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            p.configure(AudioProcessor.AudioFormat(SR, 1, C.ENCODING_PCM_16BIT))
        )
    }

    @Test fun nonPcm16_returnsNotSet() {
        val p = StereoTransformProcessor({ false }, { false })
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            p.configure(AudioProcessor.AudioFormat(SR, 2, C.ENCODING_PCM_FLOAT))
        )
    }

    @Test fun stereoPcm16_configureReturnsInput() {
        val p = StereoTransformProcessor({ false }, { false })
        assertEquals(fmt, p.configure(fmt))
    }

    @Test fun offIsExactPassThrough() {
        val p = StereoTransformProcessor({ false }, { false })
        p.configure(fmt); p.flush()
        val input = frames(100 to 200, -300 to 400, 12000 to -12000)
        val copy = ByteArray(input.remaining()); input.duplicate().get(copy)
        p.queueInput(input)
        val out = p.output; val outArr = ByteArray(out.remaining()); out.get(outArr)
        assertArrayEquals(copy, outArr)
    }

    @Test fun flipSwapsChannels() {
        val p = StereoTransformProcessor({ true }, { false })
        p.configure(fmt); p.flush()
        p.queueInput(frames(100 to 200, -300 to 400))
        assertEquals(listOf(200 to 100, 400 to -300), readFrames(p))
    }

    @Test fun monoAveragesChannels() {
        val p = StereoTransformProcessor({ false }, { true })
        p.configure(fmt); p.flush()
        p.queueInput(frames(100 to 200, 1000 to -1000))
        assertEquals(listOf(150 to 150, 0 to 0), readFrames(p))
    }

    @Test fun monoWinsOverFlip() {
        val p = StereoTransformProcessor({ true }, { true })
        p.configure(fmt); p.flush()
        p.queueInput(frames(100 to 200))
        assertEquals("mono applied, not flip", listOf(150 to 150), readFrames(p))
    }
}
