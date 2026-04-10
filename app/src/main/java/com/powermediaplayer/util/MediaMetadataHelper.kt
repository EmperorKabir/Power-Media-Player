package com.powermediaplayer.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import wseemann.media.FFmpegMediaMetadataRetriever

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
        return title == other.title && artist == other.artist && uri == other.uri
    }

    override fun hashCode(): Int = title.hashCode() * 31 + artist.hashCode()
    private val uri: String = ""
}

/**
 * Helper for extracting metadata from media files.
 * Supports two modes:
 * - Standard: Uses Android's built-in MediaMetadataRetriever (fast)
 * - Deep Scan: Uses FFmpegMediaMetadataRetriever for rare file types (thorough)
 */
object MediaMetadataHelper {

    /**
     * Extract metadata from a media URI.
     * @param deepScan If true, uses FFmpegMediaMetadataRetriever for more thorough extraction.
     */
    fun extractMetadata(context: Context, uri: Uri, deepScan: Boolean): MediaInfo {
        return if (deepScan) {
            extractWithFFmpeg(context, uri)
        } else {
            extractWithStandard(context, uri)
        }
    }

    /**
     * Standard extraction using Android's MediaMetadataRetriever.
     * Fast but may miss tags in rare file formats.
     */
    private fun extractWithStandard(context: Context, uri: Uri): MediaInfo {
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
     * Deep Scan extraction using FFmpegMediaMetadataRetriever.
     * Reads the entire file header for missing tags in rare file types.
     * Slower but significantly more thorough.
     */
    private fun extractWithFFmpeg(context: Context, uri: Uri): MediaInfo {
        val retriever = FFmpegMediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            MediaInfo(
                title = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_TITLE) ?: "",
                artist = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
                album = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
                genre = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_GENRE) ?: "",
                duration = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                trackNumber = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_TRACK) ?: "",
                year = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_DATE) ?: "",
                composer = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: "",
                codec = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_AUDIO_CODEC) ?: "",
                sampleRate = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_AUDIO_SAMPLE_RATE) ?: "",
                channels = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_AUDIO_CHANNEL_COUNT) ?: "",
                artworkBytes = retriever.embeddedPicture
            )
        } catch (e: Exception) {
            // Fall back to standard extraction if FFmpeg fails
            extractWithStandard(context, uri)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
