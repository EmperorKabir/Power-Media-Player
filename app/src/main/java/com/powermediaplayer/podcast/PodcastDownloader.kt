package com.powermediaplayer.podcast

import android.content.Context
import android.net.Uri
import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import com.powermediaplayer.util.SafStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * §C10 — episode audio downloader with USER-CHOSEN storage (power-user
 * control). Destination precedence per show:
 *   1. the show's OWN picked folder   — show.downloadTreeUri (a SAF tree)
 *   2. the GLOBAL default folder       — globalTreeUri (one SAF tree)
 *   3. app-private fallback            — externalFilesDir/podcasts/<show>/…
 *      (no permission prompt, scoped-storage-safe; used until the user picks)
 *
 * Returns [Saved] (the written uri + byte size) so the caller records
 * localPath in the DB. Idempotent: [downloadIfMissing] skips when a readable
 * copy already exists.
 */
class PodcastDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultClient
) {
    /** Result of a successful download: where it landed + how big it is. */
    data class Saved(val uri: String, val bytes: Long)

    companion object {
        private val defaultClient = com.powermediaplayer.util.SharedHttp.base.newBuilder()  // shared pool/cache (audit 5.3)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)  // episode downloads run long
            .build()
    }

    /** Worker auto-download: fetch only when no readable copy already exists. */
    fun downloadIfMissing(
        show: PodcastShowEntity,
        episode: PodcastEpisodeEntity,
        globalTreeUri: String? = null
    ): Saved? {
        val current = episode.localPath
        if (!current.isNullOrBlank() &&
            SafStorage.exists(context, Uri.parse(current))
        ) {
            return null
        }
        return download(show, episode, globalTreeUri)
    }

    /** Force a (re)download into the show's resolved location. */
    fun download(
        show: PodcastShowEntity,
        episode: PodcastEpisodeEntity,
        globalTreeUri: String? = null
    ): Saved? {
        if (episode.audioUrl.isBlank()) return null
        val ext = guessExt(episode.audioUrl)
        val fileName = "${slug(episode.title.ifBlank { episode.guid }).take(80)}.$ext"
        val req = Request.Builder().url(episode.audioUrl).build()

        val treeUri = (show.downloadTreeUri ?: globalTreeUri)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

        return if (treeUri != null && SafStorage.hasWriteAccess(context, treeUri)) {
            downloadToTree(show, treeUri, fileName, mimeFor(ext), req)
        } else {
            downloadToAppDir(show, fileName, req)
        }
    }

    private fun downloadToTree(
        show: PodcastShowEntity,
        treeUri: Uri,
        fileName: String,
        mime: String,
        req: Request
    ): Saved? {
        val showSlug = slug(show.title.ifBlank { show.feedUrl })
        val dir = SafStorage.resolveDir(context, treeUri, "PowerMediaPlayer", "podcasts", showSlug)
            ?: return null
        val child = SafStorage.createChild(dir, fileName, mime) ?: return null
        val bytes = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching 0L
                resp.body?.byteStream()?.use { input ->
                    SafStorage.writeStream(context, child.uri, input)
                } ?: 0L
            }
        }.getOrDefault(0L)
        if (bytes <= 0L) {
            runCatching { child.delete() }
            return null
        }
        return Saved(child.uri.toString(), bytes)
    }

    private fun downloadToAppDir(
        show: PodcastShowEntity,
        fileName: String,
        req: Request
    ): Saved? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val showSlug = slug(show.title.ifBlank { show.feedUrl })
        val target = File(base, "podcasts/$showSlug/$fileName")
        target.parentFile?.mkdirs()
        val bytes = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching 0L
                resp.body?.byteStream()?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: 0L
            }
        }.getOrDefault(0L)
        if (bytes <= 0L) {
            runCatching { target.delete() }
            return null
        }
        return Saved(Uri.fromFile(target).toString(), bytes)
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "audio/*"
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
