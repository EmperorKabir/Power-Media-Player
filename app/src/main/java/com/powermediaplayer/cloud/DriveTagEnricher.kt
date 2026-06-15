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
    private val lastPlayedRepo: LastPlayedRepository
) {
    /** Tags already extracted this process, keyed by Drive file id — a re-open
     *  (cast-return, re-tap) restores them instantly with no re-download. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, LocalMetadataOverride>()

    fun cached(id: String): LocalMetadataOverride? = cache[id]

    /**
     * @param stableKey the player mediaId / Last Played mediaUri (== item
     *        .downloadUrl): the key cold-start's `cachedOnly` and the history
     *        row are looked up by, so persistence lands where resume reads it.
     */
    fun enrich(scope: CoroutineScope, item: CloudMediaItem, stableKey: String) {
        scope.launch(Dispatchers.IO) {
            playbackConnection.setCloudFetchInProgress(true)
            val isSaf = item.id.startsWith("content://")
            var found = false
            var temp = try {
                if (isSaf) driveProvider.downloadToCache(item)
                else driveOAuthProvider.downloadToCache(item)
            } catch (_: Throwable) { null }
            if (temp != null) {
                found = parseAndApply(item, temp, stableKey)
                runCatching { temp.delete() }
            }
            if (!found) {
                temp = try {
                    if (isSaf) driveProvider.downloadFullToCache(item)
                    else driveOAuthProvider.downloadFullToCache(item)
                } catch (_: Throwable) { null }
                if (temp != null) {
                    parseAndApply(item, temp, stableKey)
                    runCatching { temp.delete() }
                }
            }
            playbackConnection.setCloudFetchInProgress(false)
        }
    }

    private suspend fun parseAndApply(
        item: CloudMediaItem,
        tempFile: java.io.File,
        stableKey: String
    ): Boolean {
        var found = false
        val tempUri = android.net.Uri.fromFile(tempFile)
        com.powermediaplayer.util.Diag.i(
            "PowerMediaPlayer",
            "DriveTagEnricher: file=${tempFile.absolutePath} bytes=${tempFile.length()}"
        )
        runCatching {
            android.media.MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, tempUri)
                val title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                val album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
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
                    playbackConnection.setLocalMetadata(override)
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
                playbackConnection.setLocalChapters(chapters)
                found = true
            }
        }
        return found
    }
}
