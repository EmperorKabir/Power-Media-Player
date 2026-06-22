package com.powermediaplayer.podcast

import android.content.Context
import android.net.Uri
import com.powermediaplayer.data.db.dao.PodcastDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §C10 — maps a podcast episode's STREAM url to its downloaded local file so
 * EVERY play path can use the offline copy, not only the podcast-list tap.
 *
 * The Recents row + cold-start restore key on the stream url (stable for resume
 * position); this resolver looks up whether that url has a readable downloaded
 * copy and, if so, returns the local uri to use as the playback source. A stale
 * localPath (file removed / grant revoked) self-heals by clearing the row.
 */
@Singleton
class PodcastOfflineResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val podcastDao: PodcastDao
) {
    /** Local uri for [streamUrl] if a readable downloaded copy exists, else null. */
    suspend fun localUriFor(streamUrl: String): Uri? {
        if (streamUrl.isBlank()) return null
        val ep = podcastDao.downloadedByAudioUrl(streamUrl) ?: return null
        val path = ep.localPath?.takeIf { it.isNotBlank() } ?: return null
        val readable = runCatching {
            val u = Uri.parse(path)
            when (u.scheme) {
                "content" ->
                    context.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
                "file", null ->
                    java.io.File(u.path ?: path).let { it.exists() && it.canRead() }
                else -> java.io.File(path).exists()
            }
        }.getOrDefault(false)
        if (!readable) {
            runCatching { podcastDao.clearLocalPath(ep.guid) }
            return null
        }
        return Uri.parse(path)
    }
}
