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
        drive.map { it.driveFileId }.toSet() + pods.map { it.audioUrl }.toSet()
    }

    /** The Drive file id a URI maps to: a SAF content:// uri IS the id; an OAuth
     *  download url carries it as `.../files/{id}?...`. Null = not a Drive uri. */
    fun driveIdOf(uri: String): String? = when {
        uri.startsWith("content://") && uri.contains("document") -> uri
        else -> Regex("/files/([^?]+)").find(uri)?.groupValues?.getOrNull(1)
    }

    private fun isDriveUri(uri: String): Boolean =
        (uri.contains("/files/") && uri.contains("googleapis")) ||
            (uri.startsWith("content://") && uri.contains("document"))

    /**
     * Synchronous state from a URI + a reactive downloadedKeys snapshot (so it
     * composes into a StateFlow). [sourceHint] is "DRIVE"/"LOCAL"/"SPOTIFY" from
     * a Last Played row; null on the Player path (inferred from the URI).
     */
    fun stateOf(uri: String?, isSpotify: Boolean, downloaded: Set<String>, sourceHint: String? = null): OfflineState {
        if (uri.isNullOrBlank() || isSpotify || uri.startsWith("spotify:") || sourceHint == "SPOTIFY") {
            return OfflineState.NOT_APPLICABLE
        }
        val driveId = driveIdOf(uri)
        if ((driveId != null && driveId in downloaded) || uri in downloaded) return OfflineState.DOWNLOADED
        if (sourceHint == "DRIVE" || isDriveUri(uri)) return OfflineState.DOWNLOADABLE
        // Podcast: a LOCAL-source http uri from Last Played, or any non-Drive http
        // uri the Player is on — downloadable only if we actually know the episode.
        if (uri.startsWith("http") && (sourceHint == "LOCAL" || sourceHint == null)) {
            // Best-effort sync hint; the suspend download() re-checks authoritatively.
            return OfflineState.DOWNLOADABLE
        }
        return OfflineState.NOT_APPLICABLE
    }

    /** Download the media at [uri] for offline use. [title] names the stored file. */
    suspend fun download(uri: String, title: String): Result<Unit> = withContext(Dispatchers.IO) {
        // Podcast first: a non-Drive http uri with a known episode.
        if (uri.startsWith("http") && !isDriveUri(uri)) {
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
            return@withContext Result.success(Unit)
        }
        val pod = podcastDao.downloadedByAudioUrl(uri)
        if (pod != null) {
            pod.localPath?.takeIf { it.isNotBlank() }?.let { SafStorage.delete(context, Uri.parse(it)) }
            podcastDao.clearLocalPath(pod.guid)
            return@withContext Result.success(Unit)
        }
        Result.failure(IllegalStateException("Nothing to delete — not downloaded"))
    }

    private suspend fun downloadDrive(uri: String, title: String): Result<Unit> {
        val driveId = driveIdOf(uri)
            ?: return Result.failure(IllegalStateException("Not a downloadable item"))
        if (offlineCopyDao.get(driveId) != null) return Result.success(Unit)
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
            ?: return Result.failure(IllegalStateException("Download failed — try again on Wi-Fi"))
        val (path, size) = relocate(item, cache)
        settingsDataStore.upsertOfflineDrive(driveId, path)
        offlineCopyDao.upsert(
            OfflineCopyEntity(driveFileId = driveId, localPath = path, byteSize = size, displayName = title)
        )
        com.powermediaplayer.util.DownloadProgressBus.clear(driveId)
        return Result.success(Unit)
    }

    private suspend fun downloadPodcast(ep: com.powermediaplayer.data.db.entity.PodcastEpisodeEntity): Result<Unit> {
        val show = podcastDao.getShow(ep.feedUrl)
            ?: return Result.failure(IllegalStateException("Show not subscribed"))
        val global = settingsDataStore.podcastDownloadTreeUri.first().ifBlank { null }
        val saved = PodcastDownloader(context).download(show, ep, global)
            ?: return Result.failure(IllegalStateException("Download failed"))
        podcastDao.setLocalPath(ep.guid, saved.uri, saved.bytes, System.currentTimeMillis())
        return Result.success(Unit)
    }

    // ── Drive relocate into the user's single global offline folder (mirrors
    //    CloudViewModel.relocateDriveOffline) ─────────────────────────────────
    private suspend fun relocate(item: CloudMediaItem, cacheFile: java.io.File): Pair<String, Long> {
        val fallback = cacheFile.absolutePath to cacheFile.length()
        val tree = settingsDataStore.driveOfflineTreeUri.first().ifBlank { null }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return fallback
        if (!SafStorage.hasWriteAccess(context, tree)) return fallback
        val dir = SafStorage.resolveDir(context, tree, "PowerMediaPlayer", "drive") ?: return fallback
        val name = item.name.ifBlank { cacheFile.name }
        val child = SafStorage.createChild(dir, name, mimeForName(name)) ?: return fallback
        val bytes = runCatching {
            cacheFile.inputStream().use { SafStorage.writeStream(context, child.uri, it) }
        }.getOrDefault(0L)
        if (bytes <= 0L) {
            runCatching { child.delete() }
            return fallback
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
