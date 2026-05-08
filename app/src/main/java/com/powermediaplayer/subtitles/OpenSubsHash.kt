package com.powermediaplayer.subtitles

import java.io.RandomAccessFile

/**
 * §C9 A2.3 — OpenSubtitles "moviehash" implementation.
 *
 * The hash is a 64-bit checksum derived from the file size and
 * 64 KiB of bytes at the start and 64 KiB at the end of the file.
 * Reference: https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes
 *
 * Returns the lower-case 16-char hex string OpenSubtitles expects,
 * or null if the file is shorter than 128 KiB or unreadable.
 */
object OpenSubsHash {
    private const val CHUNK = 64 * 1024L

    fun compute(path: String): String? {
        return runCatching {
            RandomAccessFile(path, "r").use { f ->
                val size = f.length()
                if (size < CHUNK * 2) return@use null
                var hash = size
                val buf = ByteArray(CHUNK.toInt())
                f.seek(0L)
                f.readFully(buf)
                hash += sumLongs(buf)
                f.seek(size - CHUNK)
                f.readFully(buf)
                hash += sumLongs(buf)
                "%016x".format(hash)
            }
        }.getOrNull()
    }

    private fun sumLongs(buf: ByteArray): Long {
        var acc = 0L
        var i = 0
        while (i + 8 <= buf.size) {
            var v = 0L
            for (b in 0 until 8) {
                v = v or ((buf[i + b].toLong() and 0xFFL) shl (b * 8))
            }
            acc += v
            i += 8
        }
        return acc
    }
}
