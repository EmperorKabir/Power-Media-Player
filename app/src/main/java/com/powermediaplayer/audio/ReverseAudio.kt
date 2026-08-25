package com.powermediaplayer.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.powermediaplayer.diag.DiagLog
import java.io.File
import java.io.RandomAccessFile

/**
 * Reversed playback for local audio ("Reverse audio" in Settings).
 *
 * Audio cannot be played backwards with a flag — the file is decoded
 * once, its PCM streamed to a temp file, then written back as a
 * frame-reversed WAV which the normal player plays. Everything is
 * disk-streamed (no whole-file buffers), results are cached per source
 * under cacheDir/reverse-cache, and a 60-minute guard keeps multi-GB
 * audiobooks from eating the disk.
 */
object ReverseAudio {

    private const val MAX_DURATION_MS = 60 * 60_000L

    /**
     * Remote (Drive) cap by COMPRESSED size — the cost there is the
     * download, and duration is a poor proxy (bitrate varies 5x).
     * 50 MB ≈ 5 s on a typical ~10 MB/s Wi-Fi connection,
     * ≈ 17 s on the slowest measured network, ≈ 50 min of 128 kbps
     * audio. One download ever per file (result cached).
     */
    private const val MAX_REMOTE_BYTES = 50L * 1024 * 1024

    /**
     * Total-size LRU cap for the reverse-cache (audit 2026-08-25). Reversed WAVs are
     * uncompressed (a 1-hour file ≈ 600 MB), and one is kept per reversed source with no
     * cap — reversing several files bloats cacheDir until the OS evicts unpredictably
     * (and an OS eviction can delete the file mid-playback). Self-trim to this budget,
     * oldest-first, always sparing the just-built file. ~1 GB ≈ two hour-long files.
     */
    private const val MAX_CACHE_BYTES = 1024L * 1024 * 1024

    private fun isRemote(uri: Uri) = uri.scheme == "http" || uri.scheme == "https"

    /** Compressed size of [uri], or -1 when unknowable. */
    private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        when (uri.scheme) {
            "http", "https" -> {
                val c = java.net.URL(uri.toString())
                    .openConnection() as java.net.HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 4000
                c.readTimeout = 4000
                try { c.contentLengthLong } finally { c.disconnect() }
            }
            "file" -> File(uri.path!!).length()
            else -> context.contentResolver
                .openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }
    }.getOrDefault(-1L)

    /** Cached-or-built reversed WAV for [uri]. Call on Dispatchers.IO. */
    fun ensureReversedWav(context: Context, uri: Uri): Result<File> = runCatching {
        val dir = File(context.cacheDir, "reverse-cache").apply { mkdirs() }
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val wav = File(dir, "$key.wav")
        if (wav.isFile && wav.length() > 44) {
            DiagLog.perf("reverse.cacheHit", 0, "bytes=${wav.length()}")
            return@runCatching wav
        }
        if (isRemote(uri)) {
            val size = sizeOf(context, uri)
            require(size in 1..MAX_REMOTE_BYTES) {
                "Drive file too large for reverse mode " +
                    "(${size / (1024 * 1024)} MB > 50 MB limit)"
            }
        }
        val pcm = File(dir, "$key.pcm.tmp")
        try {
            val t0 = DiagLog.now()
            val fmt = decodeToPcm(context, uri, pcm)
            DiagLog.perf("reverse.decode", DiagLog.now() - t0,
                "bytes=${pcm.length()} sr=${fmt.sampleRate} ch=${fmt.channels}")
            val t1 = DiagLog.now()
            writeReversedWav(pcm, wav, fmt.sampleRate, fmt.channels)
            DiagLog.perf("reverse.write", DiagLog.now() - t1, "bytes=${wav.length()}")
            // LRU-trim the cache to its budget, sparing the file we just built.
            val freed = trimCacheToCap(dir, MAX_CACHE_BYTES, wav)
            if (freed > 0) DiagLog.perf("reverse.trim", 0, "freed=$freed")
            wav
        } finally {
            pcm.delete()
        }
    }

    /**
     * Trim the reverse-cache to [capBytes] total, deleting least-recently-modified `.wav`
     * files first, never deleting [keep]. Pure file I/O — unit-tested. Returns bytes freed.
     */
    internal fun trimCacheToCap(dir: File, capBytes: Long, keep: File?): Long {
        val wavs = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.toMutableList() ?: return 0L
        var total = wavs.sumOf { it.length() }
        if (total <= capBytes) return 0L
        wavs.sortBy { it.lastModified() }          // oldest first = LRU
        val keepPath = keep?.absolutePath
        var freed = 0L
        for (f in wavs) {
            if (total <= capBytes) break
            if (f.absolutePath == keepPath) continue
            val len = f.length()
            if (f.delete()) { total -= len; freed += len }
        }
        return freed
    }

    internal data class PcmFormat(val sampleRate: Int, val channels: Int)

    /** Decode the audio track of [uri] to 16-bit PCM, streamed to [out]. */
    private fun decodeToPcm(context: Context, uri: Uri, out: File): PcmFormat {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var inFmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i; inFmt = f; break
                }
            }
            require(track >= 0 && inFmt != null) { "no audio track" }
            val durationUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION))
                inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
            require(durationUs / 1000 <= MAX_DURATION_MS) {
                "file too long for reverse mode (limit 60 min)"
            }
            extractor.selectTrack(track)
            val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFmt, null, null, 0)
            codec.start()

            var sampleRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            out.outputStream().buffered(1 shl 16).use { os ->
                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIx = codec.dequeueInputBuffer(10_000)
                        if (inIx >= 0) {
                            val buf = codec.getInputBuffer(inIx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(
                                    inIx, 0, size, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                    val outIx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIx >= 0 -> {
                            val ob = codec.getOutputBuffer(outIx)!!
                            if (info.size > 0) {
                                if (floatPcm) {
                                    // Convert float PCM to 16-bit.
                                    val fb = ob.order(java.nio.ByteOrder.nativeOrder())
                                        .asFloatBuffer()
                                    val shorts = ByteArray(fb.remaining() * 2)
                                    var w = 0
                                    while (fb.hasRemaining()) {
                                        val v = (fb.get() * 32767f)
                                            .coerceIn(-32768f, 32767f).toInt()
                                        shorts[w++] = (v and 0xFF).toByte()
                                        shorts[w++] = ((v shr 8) and 0xFF).toByte()
                                    }
                                    os.write(shorts)
                                } else {
                                    val bytes = ByteArray(info.size)
                                    ob.position(info.offset)
                                    ob.get(bytes, 0, info.size)
                                    os.write(bytes)
                                }
                            }
                            codec.releaseOutputBuffer(outIx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                        outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                }
            }
            return PcmFormat(sampleRate, channels)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Write [pcm] (16-bit interleaved) into [wav] with its FRAMES in
     * reverse order. Pure file I/O — unit-tested. Chunked from the tail
     * so memory stays flat regardless of file size.
     */
    internal fun writeReversedWav(pcm: File, wav: File, sampleRate: Int, channels: Int) {
        val frameBytes = 2 * channels
        val dataLen = (pcm.length() / frameBytes) * frameBytes
        wav.outputStream().buffered(1 shl 16).use { os ->
            os.write(wavHeader(dataLen, sampleRate, channels))
            RandomAccessFile(pcm, "r").use { raf ->
                val chunkFrames = 1 shl 14
                val chunk = ByteArray(chunkFrames * frameBytes)
                val rev = ByteArray(chunkFrames * frameBytes)
                var pos = dataLen
                while (pos > 0) {
                    val take = minOf(chunk.size.toLong(), pos).toInt()
                    pos -= take
                    raf.seek(pos)
                    raf.readFully(chunk, 0, take)
                    // Reverse frame order within the chunk.
                    val frames = take / frameBytes
                    for (f in 0 until frames) {
                        System.arraycopy(
                            chunk, (frames - 1 - f) * frameBytes,
                            rev, f * frameBytes, frameBytes
                        )
                    }
                    os.write(rev, 0, take)
                }
            }
        }
    }

    private fun wavHeader(dataLen: Long, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val b = java.nio.ByteBuffer.allocate(44)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1) // PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(byteRate)
        b.putShort((channels * 2).toShort()) // block align
        b.putShort(16) // bits per sample
        b.put("data".toByteArray())
        b.putInt(dataLen.toInt())
        return b.array()
    }
}
