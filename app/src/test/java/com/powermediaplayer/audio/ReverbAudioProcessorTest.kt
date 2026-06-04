package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * vc32: the platform reverb proved unusable on hardware, so reverb is
 * rendered in-chain. These tests pin the two behaviours that matter:
 * a preset produces an audible tail after the dry signal stops, and
 * preset 0 is an EXACT pass-through.
 */
class ReverbAudioProcessorTest {

    private val format =
        AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    private fun stereoBuffer(frames: Int, sample: Short = 0): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { b.putShort(sample); b.putShort(sample) }
        b.flip()
        return b
    }

    private fun drain(p: AudioProcessor): ByteArray {
        val out = p.output
        val arr = ByteArray(out.remaining())
        out.get(arr)
        return arr
    }

    @Test
    fun presetProducesAudibleTailAfterInputGoesSilent() {
        var preset = 5 // Cave — longest tail
        val p = ReverbAudioProcessor({ preset }, { 1f })
        p.configure(format)
        p.flush()

        // A loud burst, then silence. The glide needs a few buffers to
        // open the wet path, so feed several loud buffers first.
        repeat(10) {
            p.queueInput(stereoBuffer(4096, 20_000))
            drain(p)
        }
        var tailEnergy = 0L
        repeat(5) {
            p.queueInput(stereoBuffer(4096, 0))
            val bytes = drain(p)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
            while (sb.hasRemaining()) {
                val v = sb.get().toLong()
                tailEnergy += v * v
            }
        }
        assertTrue(
            "silent input must still carry a reverb tail (energy=$tailEnergy)",
            tailEnergy > 0L
        )
    }

    @Test
    fun presetZeroIsExactPassThrough() {
        val p = ReverbAudioProcessor({ 0 }, { 1f })
        p.configure(format)
        p.flush()

        val frames = 1024
        val input = stereoBuffer(frames)
        val expected = ByteArray(frames * 4)
        val rnd = java.util.Random(42)
        for (i in expected.indices) expected[i] = rnd.nextInt().toByte()
        input.clear(); input.put(expected); input.flip()

        p.queueInput(input)
        val out = drain(p)
        assertArrayEquals("bypass must not alter samples", expected, out)
    }
}
