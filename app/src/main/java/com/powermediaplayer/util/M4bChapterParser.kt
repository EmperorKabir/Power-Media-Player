package com.powermediaplayer.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle

/**
 * Utility to extract chapters from various media files (especially .m4b audiobooks).
 * Android's default MediaMetadataRetriever does not parse QuickTime chapter tracks.
 * This utilizes MediaExtractor to scan for Text/Chapter tracks.
 */
object M4bChapterParser {

    /**
     * Extracts chapters from the media URI and packages them into a Bundle
     * matching the format expected by PlaybackConnection.
     */
    fun extractChaptersAsBundle(context: Context, uri: Uri): Bundle {
        val bundle = Bundle()
        val chapters = extractChapters(context, uri)
        
        bundle.putInt("chapter_count", chapters.size)
        chapters.forEachIndexed { i, chapter ->
            bundle.putString("chapter_title_$i", chapter.title)
            bundle.putLong("chapter_start_$i", chapter.startTimeMs)
            bundle.putLong("chapter_end_$i", chapter.endTimeMs)
        }
        return bundle
    }

    private data class ChapterRaw(
        val title: String,
        val startTimeMs: Long,
        var endTimeMs: Long
    )

    private fun extractChapters(context: Context, uri: Uri): List<ChapterRaw> {
        val chapters = mutableListOf<ChapterRaw>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var textTrackIndex = -1
            var durationUs = 0L

            // 1. Identify Tracks
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                
                // M4B (MP4) QuickTime chapters are typically stored as a text track
                if (mime.startsWith("text/") || mime.contains("tx3g") || mime.contains("3gpp")) {
                    textTrackIndex = i
                }
                
                if (mime.startsWith("audio/") && format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                }
            }

            // 2. Extract Chapter Markers
            if (textTrackIndex != -1) {
                extractor.selectTrack(textTrackIndex)
                var index = 0
                val buffer = java.nio.ByteBuffer.allocate(1024 * 64) // 64kb max chapter title

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val sampleTimeMs = extractor.sampleTime / 1000L
                    val bytes = ByteArray(sampleSize)
                    buffer.get(bytes)
                    
                    var title = ""
                    // QuickTime text track samples (tx3g) usually begin with a 2-byte length prefix.
                    // If length matches the remaining sample size exactly, it's a prefix.
                    if (sampleSize > 2) {
                        val stringLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                        if (stringLength == sampleSize - 2) {
                            title = String(bytes, 2, stringLength, Charsets.UTF_8).trim()
                        } else {
                            // If it doesn't match, just decode the whole string
                            title = String(bytes, Charsets.UTF_8).trim()
                        }
                    } else {
                        title = String(bytes, Charsets.UTF_8).trim()
                    }

                    // Removing weird byte-order marks or null terminators if present
                    title = title.replace("\uFEFF", "").replace("\u0000", "")

                    if (title.isEmpty()) title = "Chapter ${index + 1}"

                    chapters.add(ChapterRaw(title, sampleTimeMs, -1L))
                    
                    extractor.advance()
                    buffer.clear()
                    index++
                }

                // 3. Set end times dynamically based on the start time of the next chapter or media duration
                for (i in chapters.indices) {
                    val nextTimeMs = if (i + 1 < chapters.size) {
                        chapters[i + 1].startTimeMs
                    } else {
                        if (durationUs > 0) durationUs / 1000L else chapters[i].startTimeMs + 300_000L // fallback 5 min
                    }
                    chapters[i].endTimeMs = nextTimeMs
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
        
        return chapters
    }
}
