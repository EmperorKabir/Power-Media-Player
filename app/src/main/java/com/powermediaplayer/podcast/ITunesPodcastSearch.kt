package com.powermediaplayer.podcast

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * §C10 LOCKED — iTunes / Apple-Podcasts directory search. The Apple
 * Search API is open + key-less + serves the iTunes Store podcast
 * index; results carry `feedUrl` directly so we can hand them to
 * [RssFeedParser] without an extra resolution step.
 */
class ITunesPodcastSearch(
    private val httpClient: OkHttpClient = defaultClient
) {
    companion object {
        private val defaultClient = com.powermediaplayer.util.SharedHttp.base.newBuilder()  // shared pool/cache (audit 5.3)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        private const val BASE = "https://itunes.apple.com/search"
    }

    data class Hit(
        val title: String,
        val author: String,
        val artworkUrl: String,
        val feedUrl: String
    )

    /** Resolve an Apple Podcasts numeric id (from a podcasts.apple.com/.../idNNN
     *  page URL) to its real RSS feedUrl via the open iTunes lookup endpoint. */
    fun lookupFeedUrl(id: String): String? {
        val url = "https://itunes.apple.com/lookup?id=$id&entity=podcast"
        val req = Request.Builder().url(url).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                val arr = JSONObject(body).optJSONArray("results") ?: return@runCatching null
                (0 until arr.length()).firstNotNullOfOrNull { i ->
                    arr.optJSONObject(i)?.optString("feedUrl")?.ifBlank { null }
                }
            }
        }.getOrNull()
    }

    fun search(query: String, limit: Int = 20): List<Hit> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE?media=podcast&entity=podcast&limit=$limit&term=$q"
        val req = Request.Builder().url(url).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string().orEmpty()
                val arr = JSONObject(body).optJSONArray("results") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val feed = o.optString("feedUrl").ifBlank { return@mapNotNull null }
                    Hit(
                        title = o.optString("collectionName"),
                        author = o.optString("artistName"),
                        artworkUrl = o.optString("artworkUrl600")
                            .ifBlank { o.optString("artworkUrl100") },
                        feedUrl = feed
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
