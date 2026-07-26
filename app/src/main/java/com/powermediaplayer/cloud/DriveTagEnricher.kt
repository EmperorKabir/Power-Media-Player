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
        /** Tail window spliced in for the moov-at-end sparse pass. The moov
         *  (cover+tags+chapters) + its chapter-text samples sit at the very end of
         *  Audible/iTunes .m4b; 64 MB covers them with generous margin. */
        const val TAIL_META_BYTES = 64L * 1024 * 1024
        /** Only attempt the head+tail splice when the full download it avoids is
         *  meaningfully bigger than head+tail (else just download the whole thing). */
        const val SPARSE_MIN_BYTES = (HEAD_WINDOW_BYTES + TAIL_META_BYTES) * 3 / 2 // 144 MB
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
                    // NB: DON'T delete `temp` yet — the tail-sparse pass reuses the head.
                }
                // TAIL-SPARSE metadata pass (Drive moov-at-end .m4b). The moov —
                // cover, tags AND chapters — lives at the END of Audible/iTunes M4Bs,
                // so the 32 MB head found nothing. Rather than downloading the whole
                // 1-2 GB file (the historical "stuck on Updating" cost), splice the
                // head + a 64 MB tail into a SPARSE file at their REAL byte offsets:
                // the moov + its chapter-text samples land at the exact positions the
                // parsers expect, the audio mdat in between is a zero-filled hole MMR
                // never reads for metadata, and disk use is only head+tail. This is
                // what the abandoned 2026-05-02 tail-ONLY attempt couldn't do ("MMR
                // needs a complete MP4 structure"). Marked COMPLETE only when it yields
                // valid CHAPTERS (proof the moov+samples were inside the window); any
                // other outcome falls through to the unchanged full download.
                // MP4-family only: the sparse-moov trick relies on an ISO-BMFF moov
                // box (m4b/m4a/mp4/m4v/mov). Other containers store metadata
                // differently (mp3 ID3, FLAC/Ogg headers up front, Matroska/AVI), so
                // a splice can't be parsed — those go straight to the full download
                // (no wasted tail fetch). moov-at-front MP4s already got everything
                // from the head, so `!found` here means it's a moov-at-end candidate.
                val ext = item.name.substringAfterLast('.', "").lowercase()
                val mp4Family = ext in setOf("m4b", "m4a", "mp4", "m4v", "mov")
                if (!found && !completeParsed && !meteredBlocked && !isSaf &&
                    mp4Family && temp != null && item.size > SPARSE_MIN_BYTES) {
                    val sparse = runCatching { buildHeadTailSparse(item, temp!!) }.getOrNull()
                    if (sparse != null) {
                        found = parseAndApply(item, android.net.Uri.fromFile(sparse), stableKey)
                        if (chaptersValid(stableKey)) {
                            completeParsed = true
                            com.powermediaplayer.util.Diag.i(
                                "PowerMediaPlayer",
                                "DriveTagEnricher: tail-sparse metadata complete " +
                                    "(cover+tags+chapters from ${TAIL_META_BYTES / 1024 / 1024}MB tail, " +
                                    "skipped full download of ${item.size / 1024 / 1024}MB)"
                            )
                        }
                        runCatching { sparse.delete() }
                    }
                }
                runCatching { temp?.delete() }
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
     * Splice the 32 MB head + a [TAIL_META_BYTES] tail of [item] into a SPARSE cache
     * file the size of the whole file, at their real byte offsets. The gap between is
     * a filesystem hole (reads as zeros, costs no disk). For a moov-at-end .m4b this
     * hands MediaMetadataRetriever / M4bChapterParser a complete-enough MP4 (ftyp +
     * mdat header in the head so the box-walk reaches the moov; moov + chapter-text
     * samples in the tail at their correct offsets). Returns the sparse file, or null
     * on any failure (caller then falls back to the full download). Reuses the
     * already-fetched [headFile] so only the tail is an extra transfer.
     */
    private suspend fun buildHeadTailSparse(
        item: CloudMediaItem,
        headFile: java.io.File
    ): java.io.File? {
        val size = item.size
        if (size <= SPARSE_MIN_BYTES) return null
        val tailLen = minOf(TAIL_META_BYTES, size)
        val tailStart = size - tailLen
        val tailFile = driveOAuthProvider.downloadRangeToCache(
            item, tailStart, size - 1, "metatail"
        ) ?: return null
        return try {
            val out = java.io.File(context.cacheDir, "drive_${item.id.hashCode()}_sparse")
            java.io.RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(size) // sparse: the middle stays an unallocated hole
                raf.seek(0)
                spliceInto(raf, headFile)
                raf.seek(tailStart)
                spliceInto(raf, tailFile)
            }
            // Confirm the middle really is a HOLE (st_blocks = actual disk, not the
            // logical length): the file must never cost more than head+tail on disk.
            runCatching {
                val diskBytes = android.system.Os.stat(out.path).st_blocks * 512L
                com.powermediaplayer.util.Diag.i(
                    "PowerMediaPlayer",
                    "buildHeadTailSparse: logical=${out.length()} disk=$diskBytes " +
                        "(hole spared ${(size - diskBytes) / 1024 / 1024}MB)"
                )
            }
            out
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { tailFile.delete() }
        }
    }

    /** Stream [src] into [raf] at its current position in 64 KB chunks (never holds a
     *  whole 32/64 MB range in RAM). */
    private fun spliceInto(raf: java.io.RandomAccessFile, src: java.io.File) {
        src.inputStream().buffered().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                raf.write(buf, 0, n)
            }
        }
    }

    /** True when the chapters cached under [stableKey] exist AND every title is
     *  non-blank — proof the tail window actually held the moov + chapter samples (a
     *  truncated/partial parse yields 0 or blank-titled chapters). Only then may the
     *  tail-sparse pass be treated as COMPLETE and the full download skipped. */
    private fun chaptersValid(stableKey: String): Boolean {
        val b = runCatching { ChapterCache.shared.get(stableKey, "?") }.getOrNull() ?: return false
        val n = b.getInt("chapter_count", 0)
        if (n <= 0) return false
        for (i in 0 until n) if (b.getString("chapter_title_$i").isNullOrBlank()) return false
        return true
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
