package com.powermediaplayer.cloud

import android.content.Context
import com.powermediaplayer.data.repository.LastPlayedRepository
import com.powermediaplayer.service.ChapterInfo
import com.powermediaplayer.service.LocalMetadataOverride
import com.powermediaplayer.service.PlaybackConnection
import com.powermediaplayer.util.ArtworkCache
import com.powermediaplayer.util.ChapterCache
import com.powermediaplayer.util.M4bChapterParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shared Drive metadata/chapter enricher. Embedded tags + M4B chapters live in
 * the moov box (often at the END of the file), which MediaMetadataRetriever /
 * the chapter parser cannot read over an authenticated HTTPS URL — they need a
 * complete local MP4. So: download to cache once, extract, push to the player,
 * and PERSIST (chapters under the stable remote key + the proper title/author
 * onto the Last Played row) so a later AUTO-RESUME shows them without a refetch.
 *
 * Single source of truth used by BOTH the Cloud tab and Last Played taps, so a
 * tap from either place loads the full metadata consistently. Auto-resume never
 * calls this (it only reads what a prior tap persisted) — matching the user's
 * "fetch only when I tap it" choice.
 */
@Singleton
class DriveTagEnricher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val driveProvider: GoogleDriveProvider,
    private val driveOAuthProvider: DriveOAuthProvider,
    private val playbackConnection: PlaybackConnection,
    private val lastPlayedRepo: LastPlayedRepository,
    private val offlineCopyDao: com.powermediaplayer.data.db.dao.OfflineCopyDao,
    private val enrichmentCacheDao: com.powermediaplayer.data.db.dao.EnrichmentCacheDao,
    private val settingsDataStore: com.powermediaplayer.data.preferences.SettingsDataStore
) {
    /** Tags already extracted this process, keyed by Drive file id — a re-open
     *  (cast-return, re-tap) restores them instantly with no re-download. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, LocalMetadataOverride>()

    private companion object {
        /** Size of the head-range attempt (mirrors downloadToCache): a file at or
         *  under this was parsed IN FULL by the head attempt. */
        const val HEAD_WINDOW_BYTES = 32L * 1024 * 1024
    }

    fun cached(id: String): LocalMetadataOverride? = cache[id]

    /**
     * @param stableKey the player mediaId / Last Played mediaUri (== item
     *        .downloadUrl): the key cold-start's `cachedOnly` and the history
     *        row are looked up by, so persistence lands where resume reads it.
     */
    /**
     * @param silent when true (a look-ahead prefetch of a NOT-yet-playing track)
     *        the global "cloud fetch in progress" spinner is left alone — the
     *        current track is already loaded, so a background prefetch must not
     *        flash a loading state. Tags still land in [cache] for an instant tap.
     */
    fun enrich(
        scope: CoroutineScope,
        item: CloudMediaItem,
        stableKey: String,
        silent: Boolean = false,
        // #16 — persist the extracted tags to enrichment_cache so a favourited-
        // but-never-played item is searchable by author/series before first play.
        writeSearchCache: Boolean = false,
        // #16 — called exactly once when this enrich attempt finishes (even if it
        // bailed on the in-flight dedup) so the caller can clear its own guard/hint.
        onDone: (() -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
          try {
            // Dedup: if the same file is already being enriched (e.g. tapped from
            // the Cloud tab AND Last Played near-simultaneously), don't start a
            // second hundreds-of-MB download — let the in-flight one finish. If the
            // caller wanted the search-cache row and a PRIOR enrich already filled
            // the in-process cache, still write the row (a favourite tapped while
            // its first play is loading must not silently lose its cover row).
            if (!ChapterCache.shared.markFilling(stableKey)) {
                // PEEK grade: this bail cannot know whether the prior parse was
                // complete; the in-flight enrich writes its own graded row.
                if (writeSearchCache) {
                    cache[item.id]?.let { ov -> writeSearchRow(item.id, ov, complete = false) }
                }
                return@launch
            }
            // setCloudFetchInProgress INSIDE the try so the finally's
            // unmarkFilling is unreachable only if markFilling itself threw —
            // i.e. the fill flag can never get permanently stuck for the process.
            try {
                if (!silent) playbackConnection.setCloudFetchInProgress(true)
                val isSaf = item.id.startsWith("content://")
                var found = false
                // Evidence grade: the durable PROVIDER_DRIVE_FULL / NO_ART verdict
                // may be written ONLY after a COMPLETE copy was parsed (offline
                // copy, full download, or a file that fits inside the 32 MB head).
                // A partial parse or failed transfer is NOT evidence of artlessness
                // — those write PEEK-grade art or nothing, staying retryable.
                var completeParsed = false
                // §C28 — REUSE an existing offline copy first: no re-download, works
                // with no network, and avoids a second copy of a file already saved.
                val offlinePath = runCatching { offlineCopyDao.get(item.id)?.localPath }.getOrNull()
                if (!offlinePath.isNullOrBlank()) {
                    val offUri = android.net.Uri.parse(offlinePath)
                    val readable = runCatching {
                        when (offUri.scheme) {
                            "content" ->
                                context.contentResolver.openFileDescriptor(offUri, "r")?.use { true } ?: false
                            else -> java.io.File(offUri.path ?: offlinePath).exists()
                        }
                    }.getOrDefault(false)
                    if (readable) {
                        found = parseAndApply(item, offUri, stableKey)
                        // A readable offline copy IS the complete file: its parse is
                        // final evidence even when artless — never re-download what
                        // already sits on disk.
                        completeParsed = true
                    }
                }
                // Settings, Cloud, "Download files on mobile data": when off on a
                // metered network, skip the NETWORK transfers entirely (the offline
                // copy reuse above needs none). Nothing durable is written for a
                // skipped item, so it stays retryable and self-heals on Wi-Fi via
                // the sweep, the next favourite, or the next play. Streaming is
                // untouched: the player still parses basic tags from the stream.
                val meteredBlocked = !completeParsed &&
                    com.powermediaplayer.util.MobileDataPolicy
                        .downloadsBlocked(context, settingsDataStore)
                if (meteredBlocked) {
                    com.powermediaplayer.util.Diag.i(
                        "PowerMediaPlayer",
                        "DriveTagEnricher: skipped (downloads on mobile data disabled)"
                    )
                }
                var temp = if (completeParsed || meteredBlocked) null else try {
                    if (isSaf) driveProvider.downloadToCache(item)
                    else driveOAuthProvider.downloadToCache(item)
                } catch (_: Throwable) { null }
                if (temp != null) {
                    found = parseAndApply(item, android.net.Uri.fromFile(temp), stableKey)
                    // The head window IS the whole file for anything at or under it.
                    if (item.size in 1..HEAD_WINDOW_BYTES) completeParsed = true
                    runCatching { temp.delete() }
                }
                if (!found && !completeParsed && !meteredBlocked) {
                    temp = try {
                        if (isSaf) driveProvider.downloadFullToCache(item)
                        else driveOAuthProvider.downloadFullToCache(item)
                    } catch (_: Throwable) { null }
                    if (temp != null) {
                        completeParsed = true
                        parseAndApply(item, android.net.Uri.fromFile(temp), stableKey)
                        runCatching { temp.delete() }
                    } else if (!isSaf) {
                        // Full download unavailable (>4 GB cap or transfer failure)
                        // — a bounded tail Range fetch can still recover the COVER
                        // (moov/…/covr sits in the tail for moov-at-end files). The
                        // resulting row stays PEEK-grade (completeParsed = false),
                        // so tags/chapters are retried by a later favourite/play.
                        tailArtFallback(item, stableKey)
                    }
                }
                // #16 — store extracted tags in enrichment_cache (favourite-enrich)
                // so a never-played item is searchable by author/series and its
                // cover shows in the Cloud list. Grading: a COMPLETE parse writes
                // the durable FULL row (art or the NO_ART verdict); a partial parse
                // writes PEEK-grade art only; failed transfers write NOTHING so the
                // sweep / next favourite / next play retries.
                if (writeSearchCache) {
                    val ov = cache[item.id]
                    when {
                        ov != null -> writeSearchRow(item.id, ov, complete = completeParsed)
                        completeParsed ->
                            writeSearchRow(item.id, LocalMetadataOverride(), complete = true)
                    }
                }
            } finally {
                // finally so a cancel mid-download doesn't stick the spinner.
                if (!silent) playbackConnection.setCloudFetchInProgress(false)
                ChapterCache.shared.unmarkFilling(stableKey)
            }
          } finally {
            onDone?.invoke()
          }
        }
    }

    /**
     * Upsert the enrichment_cache row for [id] from [ov], graded by evidence:
     * [complete] = a COMPLETE copy was parsed, so a missing cover is the durable
     * NO_ART verdict and the row is PROVIDER_DRIVE_FULL (never re-parsed). A
     * partial parse ([complete] = false) may only contribute PEEK-grade art —
     * with no art there is nothing durable to say, so no row is written and the
     * item stays retryable (a transient failure must never masquerade as
     * "confirmed artless").
     */
    private suspend fun writeSearchRow(id: String, ov: LocalMetadataOverride, complete: Boolean) {
        val art = ov.artworkUri?.toString()
        if (!complete && art == null) return
        runCatching {
            enrichmentCacheDao.put(
                com.powermediaplayer.data.db.entity.EnrichmentCacheEntity(
                    cacheKey = id,
                    provider = if (complete)
                        com.powermediaplayer.data.db.entity
                            .EnrichmentCacheEntity.PROVIDER_DRIVE_FULL
                    else
                        com.powermediaplayer.data.db.entity
                            .EnrichmentCacheEntity.PROVIDER_DRIVE_PEEK,
                    title = ov.title, artist = ov.artist, album = ov.album,
                    year = null, genre = null,
                    artworkUrl = art
                        ?: com.powermediaplayer.data.db.entity.EnrichmentCacheEntity.NO_ART,
                    fetchedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    /** Cover-only recovery when the FULL download is unavailable (>4 GB cap or a
     *  failed transfer): fetch the last 8 MB by HTTP Range and pull the iTunes covr
     *  image straight out of the tail bytes. MP4-family only (that's where a
     *  moov-at-end cover lives); other formats carry art at the file START, which
     *  the 32 MB head attempt already covered. */
    private suspend fun tailArtFallback(item: CloudMediaItem, stableKey: String) {
        val ext = item.name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("m4b", "m4a", "mp4", "m4v", "mov")) return
        if (item.size <= 0L) return
        val tailBytes = 8L * 1024 * 1024
        val start = (item.size - tailBytes).coerceAtLeast(0L)
        val temp = runCatching {
            driveOAuthProvider.downloadRangeToCache(item, start, item.size - 1, "tailart")
        }.getOrNull() ?: return
        try {
            val art = runCatching {
                com.powermediaplayer.util.Mp4CoverTailParser.extractCoverFromBuffer(temp.readBytes())
            }.getOrNull() ?: return
            val artUri = ArtworkCache.write(context, stableKey, art) ?: return
            val override = LocalMetadataOverride(artworkUri = artUri, artworkBytes = art)
            cache[item.id] = override
            playbackConnection.setLocalMetadataIfCurrent(override, stableKey)
            runCatching { lastPlayedRepo.updateArtworkByUri(stableKey, artUri.toString()) }
            com.powermediaplayer.util.Diag.i(
                "PowerMediaPlayer",
                "DriveTagEnricher tailArtFallback: cover ${art.size}B from tail range"
            )
        } finally {
            runCatching { temp.delete() }
        }
    }

    private suspend fun parseAndApply(
        item: CloudMediaItem,
        tempUri: android.net.Uri,
        stableKey: String
    ): Boolean {
        var found = false
        com.powermediaplayer.util.Diag.i(
            "PowerMediaPlayer",
            "DriveTagEnricher: uri=$tempUri"
        )
        runCatching {
            android.media.MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, tempUri)
                // Repair mojibake AT THE SOURCE: MediaMetadataRetriever decodes a
                // UTF-8 ©nam/©ART tag as Windows-1252 for some M4Bs, so "’" (E2 80
                // 99) comes back as "â€™" ("Philosopherâ€™s"). Without this the
                // corrupted title was persisted to the Recents row + senderMetadata
                // + the system-notification — everything downstream, not just one
                // screen. TextNormalizer.fixMojibake is idempotent (no-op on clean
                // text) and keeps the proper curly apostrophe.
                val title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.let { com.powermediaplayer.util.TextNormalizer.fixMojibake(it) }
                val artist = (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                    ?.let { com.powermediaplayer.util.TextNormalizer.fixMojibake(it) }
                val album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.let { com.powermediaplayer.util.TextNormalizer.fixMojibake(it) }
                val artBytes = mmr.embeddedPicture
                val artUri = (artBytes?.let {
                    ArtworkCache.write(context, stableKey, it)
                }) ?: item.thumbnailUri
                if (!title.isNullOrBlank() || !artist.isNullOrBlank() ||
                    !album.isNullOrBlank() || artBytes != null
                ) {
                    val override = LocalMetadataOverride(
                        // Pass null (not the filename) when the embedded title
                        // tag is missing — a filename override outranks the real
                        // title ExoPlayer parses from the stream. See CloudViewModel.
                        title = title,
                        artist = artist,
                        album = album,
                        artworkUri = artUri,
                        artworkBytes = artBytes
                    )
                    // Guarded: only paint onto the player if this item is still
                    // current (a switch during the download must not put A's tags
                    // on B). The caches below are keyed by stableKey = always safe.
                    playbackConnection.setLocalMetadataIfCurrent(override, stableKey)
                    // DURABLE: write the enriched tags into senderMetadataByMediaId
                    // (used by the local resolution AND the rebuilt cast item) so
                    // the title/cover survive the transient override being wiped on
                    // a reload/cast swap. See CloudViewModel for the full rationale.
                    runCatching {
                        val meta = androidx.media3.common.MediaMetadata.Builder().apply {
                            if (!title.isNullOrBlank()) setTitle(title)
                            if (!artist.isNullOrBlank()) setArtist(artist)
                            if (!album.isNullOrBlank()) setAlbumTitle(album)
                            artUri?.let { setArtworkUri(it) }
                            artBytes?.let {
                                setArtworkData(it, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            }
                        }.build()
                        com.powermediaplayer.service.PlaybackService
                            .senderMetadataByMediaId[stableKey] = meta
                    }
                    cache[item.id] = override
                    runCatching {
                        if (!title.isNullOrBlank())
                            lastPlayedRepo.updateDisplayByUri(stableKey, title, artist ?: "")
                        if (artBytes != null && artUri != null)
                            lastPlayedRepo.updateArtworkByUri(stableKey, artUri.toString())
                    }
                    if (artBytes != null) found = true
                }
                com.powermediaplayer.util.Diag.i(
                    "PowerMediaPlayer",
                    "DriveTagEnricher MMR: title=$title artist=$artist album=$album artBytes=${artBytes?.size ?: 0}"
                )
            }
        }
        runCatching {
            val bundle = M4bChapterParser.extractChaptersAsBundle(context, tempUri)
            val count = bundle.getInt("chapter_count", 0)
            com.powermediaplayer.util.Diag.i("PowerMediaPlayer", "DriveTagEnricher chapters=$count")
            runCatching {
                ChapterCache.shared.attachDiskStore(context.cacheDir)
                ChapterCache.shared.put(stableKey, "?", bundle)
            }
            if (count > 0) {
                val chapters = (0 until count).mapNotNull { i ->
                    val t = bundle.getString("chapter_title_$i") ?: "Chapter ${i + 1}"
                    val s = bundle.getLong("chapter_start_$i", -1)
                    val e = bundle.getLong("chapter_end_$i", -1)
                    if (s >= 0) ChapterInfo(t, s, e, i) else null
                }
                playbackConnection.setLocalChaptersIfCurrent(chapters, stableKey)
                found = true
            }
        }
        return found
    }
}
