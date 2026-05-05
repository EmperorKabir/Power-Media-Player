package com.powermediaplayer.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Software AudioProcessor that supports two power-user effects on the
 * audio output path:
 *
 *  - Stereo flip: swaps left and right channels.
 *  - Mono mix:    averages both channels into a centred mono stream
 *                 (still output as stereo so downstream sinks don't
 *                 need to reconfigure).
 *
 * Both flags are read each call from a supplier so the user can
 * toggle them in Settings without restarting playback.
 *
 * Only 16-bit PCM stereo is transformed; other formats pass through
 * untouched. Atmos / 5.1 / 7.1 streams that ExoPlayer hands to a
 * passthrough decoder bypass this processor entirely (no buffer ever
 * arrives here), which is the desired behaviour — leave surround
 * formats alone.
 */
@OptIn(UnstableApi::class)
class StereoTransformProcessor(
    private val flipSupplier: () -> Boolean,
    private val monoSupplier: () -> Boolean
) : BaseAudioProcessor() {

    @Volatile
    var flipEnabled: Boolean = false
        private set
    @Volatile
    var monoEnabled: Boolean = false
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount == 2
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        flipEnabled = flipSupplier()
        monoEnabled = monoSupplier()

        val limit = inputBuffer.limit()
        val position = inputBuffer.position()
        val byteCount = limit - position
        if (byteCount <= 0) return

        val out = replaceOutputBuffer(byteCount).order(ByteOrder.LITTLE_ENDIAN)
        val src = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        src.position(position)

        if (!flipEnabled && !monoEnabled) {
            // Fast pass-through.
            out.put(src)
            inputBuffer.position(limit)
            out.flip()
            return
        }

        // Iterate by frame (4 bytes = 1 frame: int16 L + int16 R).
        // Avoid per-frame Pair<Int,Int> allocations: at 48 kHz/2ch
        // that would be ~96k Pair instances per second.
        val frames = byteCount / 4
        val mono = monoEnabled
        val flip = flipEnabled && !mono   // mono wins over flip
        for (i in 0 until frames) {
            val l = src.short.toInt()
            val r = src.short.toInt()
            val newL: Int
            val newR: Int
            when {
                mono -> {
                    val m = ((l + r) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    newL = m; newR = m
                }
                flip -> { newL = r; newR = l }
                else -> { newL = l; newR = r }
            }
            out.putShort(newL.toShort())
            out.putShort(newR.toShort())
        }
        inputBuffer.position(limit)
        out.flip()
    }
}
