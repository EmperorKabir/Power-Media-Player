package com.powermediaplayer.enrichment

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * §C17 — Discogs metadata client. The Discogs database API is open
 * and serves rich release metadata; queries return the full release
 * record including year + genre + cover art. We use the public
 * `q=` endpoint (no auth required for read-only search).
 *
 * Requires a User-Agent identifying our app per Discogs API terms.
 */
class DiscogsClient(
    private val httpClient: OkHttpClient = defaultClient
) {
    companion object {
        private val defaultClient = com.powermediaplayer.util.SharedHttp.base.newBuilder()  // shared pool/cache (audit 5.3)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        private const val UA =
            "PowerMediaPlayer/1.0 ( https://github.com/EmperorKabir/Power-Media-Player )"
        private const val BASE = "https://api.discogs.com/database/search"
    }

    fun lookupRecording(title: String, artistHint: String?): EnrichmentResult? {
        if (title.isBlank()) return null
        val q = URLEncoder.encode(
            (artistHint?.let { "$it $title" } ?: title), "UTF-8"
        )
        val url = "$BASE?q=$q&type=release&per_page=1"
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                parseRelease(body)
            }
        }.getOrNull()
    }

    private fun parseRelease(body: String): EnrichmentResult? {
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.getJSONObject(0)
        val title = first.optString("title").takeIf { it.isNotBlank() }
        // Discogs result.title is "Artist - Album" — split for our shape.
        val artist = title?.substringBefore(" - ", "")?.takeIf { it.isNotBlank() }
        val album = title?.substringAfter(" - ", title)?.takeIf { it.isNotBlank() }
        val year = first.optString("year").take(4).toIntOrNull()
        val genre = first.optJSONArray("genre")?.optString(0)?.takeIf { it.isNotBlank() }
        return EnrichmentResult(
            title = title, artist = artist, album = album, year = year, genre = genre
        )
    }
}
