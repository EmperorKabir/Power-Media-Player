package com.powermediaplayer.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.powermediaplayer.util.Diag
import java.io.File
import java.nio.ByteBuffer

/**
 * Extracts JUST the audio track of a (large) local video into a small temp
 * `.m4a`, so it can be cast to an AUDIO-ONLY device cheaply.
 *
 * WHY: casting the full 4K video to a speaker makes the receiver buffer
 * ~20-30s of the high-bitrate VIDEO stream before any sound. The phone only
 * needs to send the audio. Remuxing the audio track (no re-encode — just a
 * container copy) gives a tiny file the receiver buffers in ~1-2s, while the
 * phone keeps showing the picture locally.
 *
 * This is a copy (demux→remux), not a transcode, so it's fast and lossless.
 * Returns null when there's no audio track or the container can't be remuxed
 * (e.g. an exotic codec the MPEG-4 muxer rejects) — the caller then falls
 * back to casting the video.
 */
object CastAudioExtractor {

    /** Caller should delete the returned file when the cast ends. */
    fun extractAudio(context: Context, source: Uri): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var out: File? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(source, "r") ?: return null
            pfd.use { extractor.setDataSource(it.fileDescriptor) }

            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) {
                Diag.w("PMP_DIAG", "CastAudioExtractor: no audio track in $source")
                return null
            }
            extractor.selectTrack(audioTrack)

            val dir = File(context.cacheDir, "castaudio").apply { mkdirs() }
            // One temp at a time; the caller deletes the previous on cast end.
            out = File(dir, "cast_audio_${System.nanoTime()}.m4a")
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()

            val maxInput = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(0)
                .coerceAtLeast(1 shl 18) // ≥256 KB
            val buffer = ByteBuffer.allocate(maxInput)
            val info = MediaCodec.BufferInfo()
            var samples = 0L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags =
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outTrack, buffer, info)
                samples++
                if (!extractor.advance()) break
            }
            muxer.stop()
            Diag.i(
                "PMP_DIAG",
                "CastAudioExtractor: wrote ${out.length()} bytes ($samples samples) → ${out.name}"
            )
            return out
        } catch (t: Throwable) {
            Diag.w("PMP_DIAG", "CastAudioExtractor failed for $source", t)
            runCatching { out?.delete() }
            return null
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
        }
    }
}
