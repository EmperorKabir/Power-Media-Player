package com.powermediaplayer.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * Art model for a LOCAL audio row that resolves the cover STRICTLY per
 * track, in the same order the player does:
 *
 *   1. the file's OWN embedded picture (ID3 APIC / MP4 covr) — the only
 *      truly per-file source, and
 *   2. a [fallbackArtUri] (the track's MediaStore album-art) ONLY when that
 *      cover is legitimate — borrowed buckets are already nulled upstream by
 *      the library scan, so this never re-introduces a neighbour's cover.
 *
 * Why both: a generic-tag bucket (album="Music") shares one MediaStore
 * albumart URI across unrelated files. Some members (e.g. "Hey cuddle
 * monkey") carry their OWN embedded cover — MediaStore even derived the
 * bucket thumbnail from it — while others (e.g. "San Diego Super Chargers")
 * have none and merely borrow it. Embedded-first shows the real owner its
 * art and leaves the borrower blank; the cleaned-URI fallback preserves
 * covers for legitimate albums that store art outside the file (audiobooks,
 * folder art) like "The Lost World".
 */
data class LocalTrackArt(
    val mediaUri: String,
    val fallbackArtUri: String?,
)

class LocalTrackArtFetcher(
    private val data: LocalTrackArt,
    private val options: Options,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        // 1) Per-file embedded picture.
        if (data.mediaUri !in noEmbedded) {
            val embedded = runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, Uri.parse(data.mediaUri))
                    mmr.embeddedPicture
                } finally {
                    runCatching { mmr.release() }
                }
            }.getOrNull()
            if (embedded != null && embedded.isNotEmpty()) {
                return@withContext sourceResult(embedded)
            }
            noEmbedded.add(data.mediaUri)
        }

        // 2) Legitimate (non-borrowed) album-art URI fallback.
        val fb = data.fallbackArtUri
        if (!fb.isNullOrBlank()) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(Uri.parse(fb))?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                return@withContext sourceResult(bytes)
            }
        }

        // 3) VIDEO fallback: a SCALED representative frame ~10% in (downscaled to the row's target
        //    size so a 4K frame doesn't hand Coil a ~33 MB bitmap). Only rows that are DEFINITIVELY
        //    audio (HAS_VIDEO probe succeeded and returned not-"yes") are negative-cached — a
        //    transient decode failure or a frame miss on a real video must NOT permanently blacklist
        //    the row. Artwork priority (steps 1-2) is preserved; this only rescues LOCAL video rows.
        if (data.mediaUri !in noFrame) {
            var hasVideo: Boolean? = null
            val frame = runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, Uri.parse(data.mediaUri))
                    val hv = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                    hasVideo = hv
                    if (hv) {
                        val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                        val atUs = (durMs * 1000L / 10L).coerceAtLeast(0L)
                        val tw = (options.size.width as? coil3.size.Dimension.Pixels)?.px ?: 512
                        val th = (options.size.height as? coil3.size.Dimension.Pixels)?.px ?: 512
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1)
                            mmr.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
                        else
                            mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } else null
                } finally {
                    runCatching { mmr.release() }
                }
            }.getOrNull()
            if (frame != null) {
                return@withContext ImageFetchResult(
                    frame.asImage(), isSampled = true, dataSource = DataSource.DISK
                )
            }
            // Blacklist ONLY confirmed-audio rows; a video that failed to yield a frame this time
            // (decoder contention, transient I/O, or a swallowed error → hasVideo stays null) stays
            // re-probable on the next bind.
            if (hasVideo == false) noFrame.add(data.mediaUri)
        }

        throw NoTrackArtException(data.mediaUri)
    }

    private fun sourceResult(bytes: ByteArray): FetchResult = SourceFetchResult(
        source = ImageSource(
            source = Buffer().apply { write(bytes) },
            fileSystem = options.fileSystem,
        ),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    class NoTrackArtException(mediaUri: String) :
        RuntimeException("No per-track art for $mediaUri")

    class Factory(context: Context) : Fetcher.Factory<LocalTrackArt> {
        private val appContext = context.applicationContext
        override fun create(
            data: LocalTrackArt,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = LocalTrackArtFetcher(data, options, appContext)
    }

    /** Stable memory/disk cache key so the row art is cached per track. */
    class ArtKeyer : Keyer<LocalTrackArt> {
        override fun key(data: LocalTrackArt, options: Options): String =
            "localart:${data.mediaUri}|${data.fallbackArtUri ?: ""}"
    }

    private companion object {
        // Process-lifetime negative cache of mediaUris with no embedded
        // picture, so a no-embedded-art row doesn't re-open the (expensive)
        // retriever on every rebind. The URI fallback still runs.
        val noEmbedded: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

        // URIs with no video frame either (audio rows) — skip re-probing the retriever on rebind.
        val noFrame: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
