package com.powermediaplayer.offline

import android.content.Context
import android.net.Uri
import com.powermediaplayer.cloud.CloudMediaItem
import com.powermediaplayer.cloud.CloudProviderType
import com.powermediaplayer.cloud.DriveOAuthProvider
import com.powermediaplayer.cloud.GoogleDriveProvider
import com.powermediaplayer.data.db.dao.OfflineCopyDao
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.db.entity.OfflineCopyEntity
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.podcast.PodcastDownloader
import com.powermediaplayer.util.SafStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Whether the currently-resolved media can be taken offline / is already local. */
enum class OfflineState { NOT_APPLICABLE, DOWNLOADABLE, DOWNLOADED }

/** OAuth Drive download url → file id. Hoisted so [OfflineMediaManager.driveIdOf]
 *  (called per offline-state evaluation) does not recompile the pattern each call. */
private val DRIVE_FILE_ID_RE = Regex("/files/([^?]+)")

/**
 * Single entry point for "download for offline use" / "delete from local
 * storage" of a Drive file or a podcast episode, identified by its play URI.
 * Shared by the Last Played overflow menu and the Player button so both reuse
 * one proven path (mirrors CloudViewModel.saveDriveOffline + PodcastDownloader).
 * Spotify (DRM) and plain local-library files are NOT_APPLICABLE.
 */
@Singleton
class OfflineMediaManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val offlineCopyDao: OfflineCopyDao,
    private val podcastDao: PodcastDao,
    private val driveProvider: GoogleDriveProvider,
    private val driveOAuthProvider: DriveOAuthProvider,
    private val settingsDataStore: SettingsDataStore
) {
    /** Reactive set of keys that are currently downloaded: Drive file ids +
     *  podcast audio urls. UIs combine this with the current URI to show the
     *  right action (download vs delete) without a per-frame DB query. */
    val downloadedKeys: Flow<Set<String>> = combine(
        offlineCopyDao.observeAll(),
        podcastDao.observeDownloaded()
    ) { drive, pods ->
        // Every key a play URI might equal: podcast audioUrls; the Drive id AS
        // STORED (SAF content:// uri or bare OAuth id); PLUS the OAuth
        // `files/{id}?alt=media` url that recordCloudPlay stores as a Last Played
        // row's mediaUri — so a downloaded OAuth row badges "Offline" by direct
        // equality too, not only via driveIdOf. Additive superset: driveIdOf-based
        // consumers (Player/Library) are unaffected by the extra alias entries.
        val s = HashSet<String>()
        pods.forEach { s.add(it.audioUrl) }
        drive.forEach { d ->
            s.add(d.driveFileId)
            if (!d.driveFileId.startsWith("content://")) {
                s.add("https://www.googleapis.com/drive/v3/files/${d.driveFileId}?alt=media")
            }
        }
        s
    }

    /** The Drive file id a URI maps to: a SAF content:// uri IS the id; an OAuth
     *  download url carries it as `.../files/{id}?...`. Null = not a Drive uri. */
    fun driveIdOf(uri: String): String? = when {
        uri.startsWith("content://") && uri.contains("document") -> uri
        else -> DRIVE_FILE_ID_RE.find(uri)?.groupValues?.getOrNull(1)
    }

    /** True ONLY for an unambiguous OAuth Drive download url. A SAF content://
     *  uri is deliberately NOT inferred as Drive — a locally-picked file has the
     *  identical shape, so Drive-via-SAF is offered only when an explicit "DRIVE"
     *  source hint says so (Last Played), never inferred on the Player path. */
    private fun isOAuthDriveUri(uri: String): Boolean =
        uri.contains("/files/") && uri.contains("googleapis")

    /**
     * Synchronous state from a URI + a reactive downloadedKeys snapshot (so it
     * composes into a StateFlow). [sourceHint] is "DRIVE"/"LOCAL"/"SPOTIFY" from
     * a Last Played row; null on the Player path (inferred from the URI).
     */
    fun stateOf(uri: String?, isSpotify: Boolean, downloaded: Set<String>, sourceHint: String? = null): OfflineState {
        if (uri.isNullOrBlank() || isSpotify || uri.startsWith("spotify:") || sourceHint == "SPOTIFY") {
            return OfflineState.NOT_APPLICABLE
        }
        // DOWNLOADED is unambiguous: only real offline copies are in `downloaded`,
        // so a plain local file (never in the DAO) can't false-positive here.
        val driveId = driveIdOf(uri)
        if ((driveId != null && driveId in downloaded) || uri in downloaded) return OfflineState.DOWNLOADED
        // DOWNLOADABLE — an explicit hint (Last Played), or an unambiguous OAuth
        // Drive url. A SAF content:// uri is NEVER inferred as Drive (it looks
        // identical to a locally-picked file → would copy a local file + write a
        // bogus Drive-offline row), so SAF Drive is offered only on a DRIVE hint.
        if (sourceHint == "DRIVE") return OfflineState.DOWNLOADABLE
        if (isOAuthDriveUri(uri)) return OfflineState.DOWNLOADABLE
        // Podcast: a LOCAL-source http uri from a Last Played row (those rows are
        // genuinely podcast episodes). Not inferred on the Player path, where a
        // bare http uri isn't necessarily a subscribed episode.
        if (sourceHint == "LOCAL" && uri.startsWith("http")) return OfflineState.DOWNLOADABLE
        return OfflineState.NOT_APPLICABLE
    }

    /**
     * Player-path state (no source hint): the sync [stateOf] PLUS an authoritative
     * podcast check — a bare http uri that is a GENUINE subscribed episode (in the
     * DB) is downloadable, while arbitrary http (radio/remote streams) is not. This
     * is why the player offline button now also appears for podcasts, not only Drive.
     */
    suspend fun stateOfAsync(uri: String?, isSpotify: Boolean, downloaded: Set<String>): OfflineState {
        val sync = stateOf(uri, isSpotify, downloaded, null)
        if (sync != OfflineState.NOT_APPLICABLE) return sync
        if (!uri.isNullOrBlank() && !isSpotify && uri.startsWith("http") && !isOAuthDriveUri(uri)) {
            if (podcastDao.episodeByAudioUrl(uri) != null) return OfflineState.DOWNLOADABLE
        }
        return OfflineState.NOT_APPLICABLE
    }

    /** The DownloadProgressBus key for [uri] — a Drive file id OR a podcast episode
     *  guid — so the player button can show progress + STOP for BOTH download types. */
    suspend fun progressIdFor(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        if (isOAuthDriveUri(uri)) return driveIdOf(uri)
        if (uri.startsWith("content://")) return driveIdOf(uri)
        if (uri.startsWith("http")) return podcastDao.episodeByAudioUrl(uri)?.guid
        return null
    }

    /** Download the media at [uri] for offline use. [title] names the stored file. */
    suspend fun download(uri: String, title: String): Result<Unit> = withContext(Dispatchers.IO) {
        // The failure message is surfaced verbatim by the Player/Last Played
        // offlineStatus toasts, so the user learns WHY nothing downloaded.
        if (com.powermediaplayer.util.MobileDataPolicy.downloadsBlocked(context, settingsDataStore)) {
            return@withContext Result.failure(
                IllegalStateException(com.powermediaplayer.util.MobileDataPolicy.BLOCKED_MESSAGE)
            )
        }
        // Podcast first: a non-Drive http uri with a known episode.
        if (uri.startsWith("http") && !isOAuthDriveUri(uri)) {
            val ep = podcastDao.episodeByAudioUrl(uri)
            if (ep != null) return@withContext downloadPodcast(ep)
        }
        downloadDrive(uri, title)
    }

    /** Remove the local copy for [uri] (Drive offline row or podcast download). */
    suspend fun deleteLocal(uri: String): Result<Unit> = withContext(Dispatchers.IO) {
        val driveId = driveIdOf(uri)
        val drive = driveId?.let { offlineCopyDao.get(it) }
        if (drive != null) {
            deleteOfflinePath(drive.localPath)
            offlineCopyDao.delete(driveId)
            settingsDataStore.removeOfflineDrive(driveId)
            evictOrphanCaches(uri)
            return@withContext Result.success(Unit)
        }
        val pod = podcastDao.downloadedByAudioUrl(uri)
        if (pod != null) {
            pod.localPath?.takeIf { it.isNotBlank() }?.let { SafStorage.delete(context, Uri.parse(it)) }
            podcastDao.clearLocalPath(pod.guid)
            evictOrphanCaches(uri)
            return@withContext Result.success(Unit)
        }
        Result.failure(IllegalStateException("Nothing to delete, not downloaded"))
    }

    /** #3 — the durable file + DB row are gone; also evict the cover-art (600 KB-
     *  1 MB, uncapped) + chapter-cache entries keyed by this uri, which deleteLocal
     *  previously orphaned in cacheDir until OS eviction. Best-effort. */
    private fun evictOrphanCaches(uri: String) {
        com.powermediaplayer.util.ArtworkCache.evict(context, uri)
        com.powermediaplayer.util.ChapterCache.shared.evict(context.cacheDir, uri)
    }

    private suspend fun downloadDrive(uri: String, title: String): Result<Unit> {
        val driveId = driveIdOf(uri)
            ?: return Result.failure(IllegalStateException("Not a downloadable item"))
        if (offlineCopyDao.get(driveId) != null) return Result.success(Unit)
        com.powermediaplayer.util.DownloadProgressBus.label(driveId, title)
        // finally → ALWAYS clear the progress entry (success, failure OR a STOP
        // cancel). Without this the Player STOP button stuck on the spinner and
        // a failed download left a permanent "Downloading" row in Manage Downloads.
        try {
            val item = CloudMediaItem(
                id = driveId,
                name = title,
                mimeType = mimeForName(title),
                size = 0L,
                downloadUrl = uri,
                sourceProvider = CloudProviderType.GOOGLE_DRIVE
            )
            val cache = try {
                if (uri.startsWith("content://")) driveProvider.downloadFullToCache(item, progressId = driveId)
                else driveOAuthProvider.downloadFullToCache(item, progressId = driveId)
            } catch (_: Throwable) { null }
                ?: return Result.failure(IllegalStateException("Download failed, try again on Wi-Fi"))
            val (path, size) = relocate(item, cache)
            settingsDataStore.upsertOfflineDrive(driveId, path)
            offlineCopyDao.upsert(
                OfflineCopyEntity(driveFileId = driveId, localPath = path, byteSize = size, displayName = title)
            )
            return Result.success(Unit)
        } finally {
            com.powermediaplayer.util.DownloadProgressBus.clear(driveId)
        }
    }

    private suspend fun downloadPodcast(ep: com.powermediaplayer.data.db.entity.PodcastEpisodeEntity): Result<Unit> {
        val show = podcastDao.getShow(ep.feedUrl)
            ?: return Result.failure(IllegalStateException("Show not subscribed"))
        val global = settingsDataStore.podcastDownloadTreeUri.first().ifBlank { null }
        com.powermediaplayer.util.DownloadProgressBus.label(ep.guid, ep.title)
        try {
            val saved = PodcastDownloader(context).download(show, ep, global)
                ?: return Result.failure(IllegalStateException("Download failed"))
            podcastDao.setLocalPath(ep.guid, saved.uri, saved.bytes, System.currentTimeMillis())
            return Result.success(Unit)
        } finally {
            com.powermediaplayer.util.DownloadProgressBus.clear(ep.guid)
        }
    }

    // ── Drive relocate into the user's single global offline folder (mirrors
    //    CloudViewModel.relocateDriveOffline) ─────────────────────────────────
    private suspend fun relocate(item: CloudMediaItem, cacheFile: java.io.File): Pair<String, Long> {
        // No SAF folder (or the move fails) → DON'T leave the copy in cacheDir: the
        // OS evicts it AND the enricher's same-named temp (drive_<hash>_full) deletes
        // it, so a cache-stored "offline" file silently vanishes → slow re-streaming.
        // Move it into persistent filesDir/offline instead.
        suspend fun durable() = com.powermediaplayer.util.OfflineStorage.toDurable(context, cacheFile, item.id, item.name)
        val tree = settingsDataStore.driveOfflineTreeUri.first().ifBlank { null }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return durable()
        if (!SafStorage.hasWriteAccess(context, tree)) return durable()
        val dir = SafStorage.resolveDir(context, tree, "PowerMediaPlayer", "drive") ?: return durable()
        val name = item.name.ifBlank { cacheFile.name }
        val child = SafStorage.createChild(dir, name, mimeForName(name)) ?: return durable()
        val bytes = runCatching {
            cacheFile.inputStream().use { SafStorage.writeStream(context, child.uri, it) }
        }.getOrDefault(0L)
        if (bytes <= 0L) {
            runCatching { child.delete() }
            return durable()
        }
        runCatching { cacheFile.delete() }
        return child.uri.toString() to bytes
    }

    private fun deleteOfflinePath(path: String) {
        if (path.startsWith("content://")) SafStorage.delete(context, Uri.parse(path))
        else runCatching { java.io.File(path).delete() }
    }

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav", "wave" -> "audio/wav"
        else -> "audio/*"
    }
}
