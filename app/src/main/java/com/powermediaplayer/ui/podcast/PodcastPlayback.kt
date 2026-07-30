package com.powermediaplayer.ui.podcast

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity

/**
 * Shared podcast → MediaItem builders. SINGLE SOURCE OF TRUTH for both the
 * Podcasts section (PodcastsViewModel) and the Library's downloaded-podcasts
 * section (LibraryViewModel, I6) so the mediaId / resume / Recents keying stays
 * byte-for-byte identical across the two entry points (a downloaded episode
 * played from the Library must share the same Recents row + resume position as
 * the same episode played from the Podcasts tab).
 */
object PodcastPlayback {
    /**
     * The downloaded copy's uri string if the episode has one AND it is still
     * readable; otherwise null. A stale localPath (file removed, or a revoked SAF
     * grant) self-heals: the row is cleared so the "downloaded" badge disappears
     * and playback falls back to the stream.
     */
    suspend fun resolvePlayableLocal(
        ctx: Context,
        dao: PodcastDao,
        episode: PodcastEpisodeEntity
    ): String? {
        val path = episode.localPath?.takeIf { it.isNotBlank() } ?: return null
        val readable = runCatching {
            val u = android.net.Uri.parse(path)
            when (u.scheme) {
                "content" ->
                    ctx.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
                "file", null ->
                    java.io.File(u.path ?: path).let { it.exists() && it.canRead() }
                else -> java.io.File(path).exists()
            }
        }.getOrDefault(false)
        if (!readable) {
            runCatching { dao.clearLocalPath(episode.guid) }
            return null
        }
        return path
    }

    /**
     * Build a MediaItem for one episode: downloaded file when present + readable,
     * else the stream; mediaId/requestMetadata kept as audioUrl so resume +
     * per-episode/show override + Recents dedup stay keyed to the episode. All
     * episodes of a show share its artwork (episode rows carry none).
     */
    suspend fun buildEpisodeItem(
        ctx: Context,
        dao: PodcastDao,
        episode: PodcastEpisodeEntity,
        showTitle: String?,
        artUri: String?
    ): MediaItem {
        val keyUri = android.net.Uri.parse(episode.audioUrl)
        val playUri = resolvePlayableLocal(ctx, dao, episode)
            ?.let { android.net.Uri.parse(it) } ?: keyUri
        return MediaItem.Builder()
            .setMediaId(episode.audioUrl)
            .setUri(playUri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(keyUri).build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(showTitle ?: "")
                    .apply { if (!artUri.isNullOrBlank()) setArtworkUri(android.net.Uri.parse(artUri)) }
                    .build()
            )
            .build()
    }
}
