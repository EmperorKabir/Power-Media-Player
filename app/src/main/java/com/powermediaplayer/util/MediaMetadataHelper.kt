package com.powermediaplayer.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Extracted metadata from a media file.
 */
data class MediaInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val duration: Long = 0L,
    val trackNumber: String = "",
    val year: String = "",
    val composer: String = "",
    val bitrate: String = "",
    val sampleRate: String = "",
    val channels: String = "",
    val codec: String = "",
    val artworkBytes: ByteArray? = null,
    val hasChapters: Boolean = false,
    val chapterCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MediaInfo
        return title == other.title && artist == other.artist
    }

    override fun hashCode(): Int = title.hashCode() * 31 + artist.hashCode()
}

/**
 * Helper for extracting metadata from media files.
 * Supports two modes:
 * - Standard: Uses Android's built-in MediaMetadataRetriever (fast)
 * - Deep Scan: Performs extended extraction with all available metadata keys (thorough)
 *
 * NOTE: When FFmpegMediaMetadataRetriever is available (via local AAR),
 * Deep Scan will use it for rare file types. Currently falls back to
 * Android's built-in retriever with extended key extraction.
 */
object MediaMetadataHelper {

    /**
     * Extract metadata from a media URI.
     * @param deepScan If true, performs extended metadata extraction.
     */
    fun extractMetadata(context: Context, uri: Uri, deepScan: Boolean): MediaInfo {
        return if (deepScan) {
            extractDeepScan(context, uri)
        } else {
            extractStandard(context, uri)
        }
    }

    /**
     * Standard extraction using Android's MediaMetadataRetriever.
     * Fast but may miss tags in rare file formats.
     */
    private fun extractStandard(context: Context, uri: Uri): MediaInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            MediaInfo(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "",
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "",
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) ?: "",
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: "",
                composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: "",
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE) ?: "",
                artworkBytes = retriever.embeddedPicture
            )
        } catch (e: Exception) {
            MediaInfo()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Deep Scan: Extended metadata extraction.
     * Reads additional keys that standard mode skips, including
     * sample rate, channel count, MIME type, and codec info.
     * This is more thorough for rare/unusual file formats.
     */
    private fun extractDeepScan(context: Context, uri: Uri): MediaInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            MediaInfo(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "",
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: "",
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "",
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER) ?: "",
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: "",
                composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR) ?: "",
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE) ?: "",
                sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) ?: "",
                channels = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS) ?: "",
                codec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "",
                artworkBytes = retriever.embeddedPicture
            )
        } catch (e: Exception) {
            // Fall back to standard extraction if deep scan fails
            extractStandard(context, uri)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
