package com.powermediaplayer.enrichment

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * §C17 — minimal MusicBrainz lookup for filling in missing metadata.
 *
 * MusicBrainz requires a User-Agent identifying the application per
 * their terms of service. Free, no API key needed. Rate-limit is
 * "1 request per second" — callers must throttle.
 *
 * Returned values are confidence-ordered results — the FIRST hit is
 * picked. Missing fields stay null so callers can keep whatever
 * embedded tag was already present.
 */
class MusicBrainzClient(
    private val httpClient: OkHttpClient = defaultClient
) {
    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        private const val UA =
            "PowerMediaPlayer/1.0 ( https://github.com/EmperorKabir/Power-Media-Player )"
    }

    /**
     * Look up a recording by title (and optionally artist). Returns
     * the first matching recording's enrichment fields, or null on
     * miss / network error.
     */
    fun lookupRecording(title: String, artistHint: String?): EnrichmentResult? {
        if (title.isBlank()) return null
        val q = buildString {
            append("recording:")
            append(URLEncoder.encode("\"${title.replace("\"", "")}\"", "UTF-8"))
            if (!artistHint.isNullOrBlank()) {
                append(" AND artist:")
                append(URLEncoder.encode("\"${artistHint.replace("\"", "")}\"", "UTF-8"))
            }
        }
        val url = "https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=1"
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                parseRecording(body)
            }
        }.getOrNull()
    }

    private fun parseRecording(body: String): EnrichmentResult? {
        val root = JSONObject(body)
        val recordings = root.optJSONArray("recordings") ?: return null
        if (recordings.length() == 0) return null
        val first = recordings.getJSONObject(0)

        val title = first.optString("title").takeIf { it.isNotBlank() }

        val creditArr = first.optJSONArray("artist-credit")
        val artist = if (creditArr != null && creditArr.length() > 0) {
            buildString {
                for (i in 0 until creditArr.length()) {
                    val obj = creditArr.optJSONObject(i) ?: continue
                    val artistObj = obj.optJSONObject("artist")
                    append(artistObj?.optString("name").orEmpty())
                    append(obj.optString("joinphrase").orEmpty())
                }
            }.takeIf { it.isNotBlank() }
        } else null

        val releaseArr = first.optJSONArray("releases")
        val album = if (releaseArr != null && releaseArr.length() > 0)
            releaseArr.getJSONObject(0).optString("title").takeIf { it.isNotBlank() }
        else null
        val year = if (releaseArr != null && releaseArr.length() > 0) {
            val date = releaseArr.getJSONObject(0).optString("date")
            date.take(4).toIntOrNull()
        } else null

        val tagsArr = first.optJSONArray("tags")
        val genre = if (tagsArr != null && tagsArr.length() > 0) {
            // Pick the highest-count tag.
            var bestName: String? = null
            var bestCount = -1
            for (i in 0 until tagsArr.length()) {
                val t = tagsArr.optJSONObject(i) ?: continue
                val c = t.optInt("count", 0)
                if (c > bestCount) {
                    bestCount = c
                    bestName = t.optString("name")
                }
            }
            bestName?.takeIf { it.isNotBlank() }
        } else null

        return EnrichmentResult(
            title = title,
            artist = artist,
            album = album,
            year = year,
            genre = genre
        )
    }
}

data class EnrichmentResult(
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val genre: String?
)
