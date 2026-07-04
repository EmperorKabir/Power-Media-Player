package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audit B-7 — PROJECT_RULES §4 contract: flush() must drop ring state so a
 * seek/track change cannot replay pre-seek audio out of the delay ring.
 * Exercises configure → queueInput → flush → queueInput and asserts no stale
 * bytes cross the flush boundary.
 */
class AudioDelayProcessorFlushTest {

    private val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun buf(size: Int, fill: Byte): ByteBuffer =
        ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).apply {
            repeat(size) { put(fill) }
            flip()
        }

    private fun drain(p: AudioDelayProcessor): ByteArray {
        val out = p.output
        val bytes = ByteArray(out.remaining())
        out.get(bytes)
        return bytes
    }

    @Test
    fun flushDropsBufferedRingAudio() {
        val delayMs = 500
        val p = AudioDelayProcessor { delayMs }
        p.configure(format)
        p.flush() // apply the pending configuration (Media3 contract)

        val bytesPerSecond = 44100 * 2 * 2
        val delayBytes = bytesPerSecond * delayMs / 1000 // frame-aligned already

        // Fill one second of 0x11: emits (1s - delay) of 0x11, ring holds delay's worth.
        p.queueInput(buf(bytesPerSecond, 0x11))
        val first = drain(p)
        assertTrue("pre-flush output should carry the early input", first.isNotEmpty())
        assertTrue(first.all { it == 0x11.toByte() })

        // Seek: the sink flushes the chain. Ring must reset.
        p.flush()

        // Post-flush input of 0x22 slightly beyond the delay window: everything
        // emitted must be 0x22. Without onFlush the ring still held ~delayBytes
        // of pre-seek 0x11, which would lead the output.
        val extra = 3528 // 20 ms, frame-aligned
        p.queueInput(buf(delayBytes + extra, 0x22))
        val after = drain(p)
        assertEquals("emitted size should be exactly the beyond-delay excess", extra, after.size)
        assertTrue(
            "no pre-flush bytes may survive the flush",
            after.all { it == 0x22.toByte() }
        )
    }

    @Test
    fun flushKeepsZeroDelayPassThrough() {
        val p = AudioDelayProcessor { 0 }
        p.configure(format)
        p.flush()
        p.queueInput(buf(1764, 0x33))
        assertTrue(drain(p).all { it == 0x33.toByte() })
        p.flush()
        p.queueInput(buf(1764, 0x44))
        assertTrue(drain(p).all { it == 0x44.toByte() })
    }
}
