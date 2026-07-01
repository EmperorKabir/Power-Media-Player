package com.powermediaplayer.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options

/**
 * Coil model for a downloaded local media file whose list thumbnail should be its own content,
 * not a generic icon. Wrapped in its own type so [MediaThumbnailFetcher] only handles these and
 * never intercepts ordinary image URLs.
 */
data class MediaThumbnailRequest(val uri: Uri)

/**
 * Builds a thumbnail for a downloaded media file: embedded cover art first (audio and video, via
 * [MediaMetadataRetriever.getEmbeddedPicture]); failing that, a frame about a tenth of the way
 * into a video (skips a black first frame); failing both, returns null so the caller's icon shows.
 * Artwork therefore takes priority over the video frame, exactly as requested.
 */
class MediaThumbnailFetcher(
    private val context: Context,
    private val request: MediaThumbnailRequest,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val mmr = MediaMetadataRetriever()
        return try {
            when (request.uri.scheme?.lowercase()) {
                "content" -> mmr.setDataSource(context, request.uri)
                else -> mmr.setDataSource(request.uri.path ?: return null)
            }
            // 1. Embedded cover art (audio and video) has priority.
            val art = mmr.embeddedPicture
            if (art != null) {
                val bmp = BitmapFactory.decodeByteArray(art, 0, art.size)
                if (bmp != null) {
                    return ImageFetchResult(bmp.asImage(), isSampled = false, dataSource = DataSource.DISK)
                }
            }
            // 2. A representative video frame, about 10% in.
            if (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
                val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val atUs = (durMs * 1000L / 10L).coerceAtLeast(0L)
                // Scale the frame to the row's target size so a 4K video doesn't hand Coil a ~33 MB
                // full-res bitmap for a list thumbnail.
                val tw = (options.size.width as? coil3.size.Dimension.Pixels)?.px ?: 512
                val th = (options.size.height as? coil3.size.Dimension.Pixels)?.px ?: 512
                val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1)
                    mmr.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
                else
                    mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    return ImageFetchResult(frame.asImage(), isSampled = true, dataSource = DataSource.DISK)
                }
            }
            null
        } catch (e: Exception) {
            com.powermediaplayer.util.Diag.w(
                "PMP_DIAG", "MediaThumbnailFetcher FAIL uri=${request.uri}: ${e.message}"
            )
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<MediaThumbnailRequest> {
        override fun create(
            data: MediaThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = MediaThumbnailFetcher(context, data, options)
    }

    /** Memory-cache key for the custom data type. */
    class Key : Keyer<MediaThumbnailRequest> {
        override fun key(data: MediaThumbnailRequest, options: Options): String =
            "media-thumb:" + data.uri.toString()
    }
}
