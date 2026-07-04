package com.powermediaplayer.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Delays the PCM-16-bit audio output by N ms (0–[MAX_DELAY_MS]). Used
 * to compensate for video latency on certain Bluetooth / wireless
 * audio paths where audio reaches the listener earlier than the
 * matching video frames.
 *
 * Implementation: a frame-aligned ring buffer sized to the maximum
 * delay at the configured rate × channels. Latency is set per input
 * buffer from [delayMsSupplier] so the user can scrub the slider in
 * real-time without restart.
 *
 * Range: only positive delay (audio plays later than video) is
 * supported by this processor — buffering audio. To shift the other
 * direction (audio earlier than video) you'd have to delay the video
 * output instead, which is out of scope here. Negative slider values
 * collapse to 0 ms (pass-through).
 *
 * Format compatibility: rejects encodings other than ENCODING_PCM_16BIT
 * via [onConfigure], so surround / float / passthrough streams bypass
 * this processor entirely.
 */
@OptIn(UnstableApi::class)
class AudioDelayProcessor(
    private val delayMsSupplier: () -> Int
) : BaseAudioProcessor() {

    companion object {
        const val MAX_DELAY_MS = 2000
    }

    private var bytesPerSecond: Int = 0
    private var frameBytes: Int = 0
    private var ring: ByteArray = ByteArray(0)
    private var writePos: Int = 0
    private var readPos: Int = 0
    private var filled: Int = 0
    private var maxRingBytes: Int = 0
    // vc29.26 — reused scratch buffer for inputBuffer.get(). Was
    // ByteArray(inBytes) per call = ~50 allocs/sec at typical 20 ms
    // ExoPlayer buffers. Grown on demand if the input ever exceeds
    // the previous high-water mark.
    private var copyScratch: ByteArray = ByteArray(0)

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        frameBytes = input.channelCount * 2
        bytesPerSecond = input.sampleRate * frameBytes
        // Ring sized for max delay + a small frame-headroom so the
        // wrap math always lands on a frame boundary.
        val raw = bytesPerSecond * MAX_DELAY_MS / 1000
        maxRingBytes = (raw - raw % frameBytes).coerceAtLeast(frameBytes)
        ring = ByteArray(maxRingBytes + frameBytes)
        writePos = 0; readPos = 0; filled = 0
        // vc29.27 — release any stale high-water scratch from a
        // previous format. Re-grown lazily on first queueInput.
        copyScratch = ByteArray(0)
        return input
    }

    // Audit B-7 (PROJECT_RULES §4 contract): the base flush() clears only the
    // OUTPUT buffer — subclass ring state must be dropped here or a seek/track
    // change replays up to MAX_DELAY_MS of pre-seek audio (and a delay→0
    // transition drains the stale tail). Mirrors Equalizer/Reverb/VoiceBoost.
    override fun onFlush() {
        writePos = 0; readPos = 0; filled = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inBytes = inputBuffer.remaining()
        if (inBytes == 0) return

        val delayMs = delayMsSupplier().coerceIn(0, MAX_DELAY_MS)
        val rawDelayBytes = bytesPerSecond * delayMs / 1000
        val delayBytes = (rawDelayBytes - rawDelayBytes % frameBytes)
            .coerceIn(0, maxRingBytes)

        // Fast pass-through when delay is zero AND nothing is buffered.
        if (delayBytes == 0 && filled == 0) {
            val out = replaceOutputBuffer(inBytes)
            out.put(inputBuffer)
            out.flip()
            return
        }

        // vc29.26 — reuse copyScratch; only resize if input grew.
        if (copyScratch.size < inBytes) copyScratch = ByteArray(inBytes)
        inputBuffer.get(copyScratch, 0, inBytes)

        // Write into ring (two-segment if it wraps).
        val tail = ring.size - writePos
        if (inBytes <= tail) {
            System.arraycopy(copyScratch, 0, ring, writePos, inBytes)
        } else {
            System.arraycopy(copyScratch, 0, ring, writePos, tail)
            System.arraycopy(copyScratch, tail, ring, 0, inBytes - tail)
        }
        writePos = (writePos + inBytes) % ring.size
        filled += inBytes

        // We can output anything older than `delayBytes` worth.
        var emit = (filled - delayBytes).coerceAtLeast(0)
        emit -= emit % frameBytes
        if (emit <= 0) {
            // Still warming up the buffer — output an empty slice so
            // the processor reports work but doesn't break the chain.
            replaceOutputBuffer(0).flip()
            return
        }

        val out = replaceOutputBuffer(emit)
        val rTail = ring.size - readPos
        if (emit <= rTail) {
            out.put(ring, readPos, emit)
        } else {
            out.put(ring, readPos, rTail)
            out.put(ring, 0, emit - rTail)
        }
        readPos = (readPos + emit) % ring.size
        filled -= emit
        out.flip()
    }
}
