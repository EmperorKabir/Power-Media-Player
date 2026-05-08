package com.powermediaplayer.podcast

import android.content.Context
import android.os.Environment
import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * §C10 — episode audio downloader. Locked spec destination:
 *   `Movies/PowerMediaPlayer/podcasts/<showSlug>/<episodeSlug>.<ext>`
 *
 * Idempotent — skips episodes whose target file already exists with
 * non-zero length. Uses the public Movies directory so DocumentsUI /
 * "Files by Google" can browse the episodes alongside any other
 * sideloaded audio.
 */
class PodcastDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultClient
) {
    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun downloadIfMissing(show: PodcastShowEntity, episode: PodcastEpisodeEntity): Boolean {
        if (episode.audioUrl.isBlank()) return false
        val target = targetFile(show, episode) ?: return false
        if (target.exists() && target.length() > 0) return false
        target.parentFile?.mkdirs()
        val req = Request.Builder().url(episode.audioUrl).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                resp.body?.byteStream()?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                target.length() > 0
            }
        }.getOrDefault(false)
    }

    private fun targetFile(show: PodcastShowEntity, episode: PodcastEpisodeEntity): File? {
        val publicMovies = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }.getOrNull() ?: return null
        val showSlug = slug(show.title.ifBlank { show.feedUrl })
        val ext = guessExt(episode.audioUrl)
        val episodeSlug = slug(episode.title.ifBlank { episode.guid }).take(80)
        return File(
            publicMovies,
            "PowerMediaPlayer/podcasts/$showSlug/$episodeSlug.$ext"
        )
    }

    private fun guessExt(url: String): String {
        val pathExt = url.substringAfterLast('.', "").substringBefore('?')
        return if (pathExt.length in 2..4) pathExt.lowercase() else "mp3"
    }

    private fun slug(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "untitled" }
}
