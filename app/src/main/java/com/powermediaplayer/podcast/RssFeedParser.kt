package com.powermediaplayer.podcast

import com.powermediaplayer.data.db.entity.PodcastEpisodeEntity
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * §C10 — minimal RSS 2.0 + iTunes-namespace parser. Walks the
 * `<channel>` for show metadata and each `<item>` for episode rows.
 * Returns a (show, episodes) pair or null on failure.
 */
class RssFeedParser(
    private val httpClient: OkHttpClient = defaultClient
) {
    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android) PowerMediaPlayer (podcast feed reader)"
        private val defaultClient = com.powermediaplayer.util.SharedHttp.base.newBuilder()  // shared pool/cache (audit 5.3)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Many podcast CDNs (Megaphone/Fastly/Akamai) 403 the default okhttp
            // UA; a browser-ish UA + an RSS Accept header defeats that. Scoped to
            // THIS client only — SharedHttp (Drive/Spotify) is untouched.
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", UA)
                        .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                        .build()
                )
            }
            .build()
    }

    /** Typed fetch outcome so the UI can show WHY a feed failed (403 vs not-RSS
     *  vs network) instead of one generic "couldn't parse". */
    sealed interface FetchResult {
        data class Ok(
            val show: PodcastShowEntity,
            val episodes: List<PodcastEpisodeEntity>
        ) : FetchResult
        data class HttpError(val code: Int) : FetchResult
        data class NotFeed(val reason: String) : FetchResult
        data object Network : FetchResult
    }

    fun fetchResult(feedUrl: String): FetchResult {
        val req = Request.Builder().url(feedUrl).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return FetchResult.HttpError(resp.code)
                val xml = resp.body?.string() ?: return FetchResult.NotFeed("empty body")
                val parsed = parse(feedUrl, xml) ?: return FetchResult.NotFeed("not RSS")
                // parse() returns a non-null show with 0 episodes for an HTML page
                // or a non-podcast XML — that's not a usable feed.
                if (parsed.second.isEmpty()) return FetchResult.NotFeed("no episodes")
                FetchResult.Ok(parsed.first, parsed.second)
            }
        }.getOrElse { FetchResult.Network }
    }

    /** Back-compat shim — existing callers (PodcastSyncWorker) keep using this. */
    fun fetch(feedUrl: String): Pair<PodcastShowEntity, List<PodcastEpisodeEntity>>? =
        (fetchResult(feedUrl) as? FetchResult.Ok)?.let { it.show to it.episodes }

    fun parse(feedUrl: String, xml: String): Pair<PodcastShowEntity, List<PodcastEpisodeEntity>>? {
        return runCatching {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(java.io.StringReader(xml))

            var showTitle = ""
            var showArtwork: String? = null
            var showDesc = ""
            val episodes = mutableListOf<PodcastEpisodeEntity>()

            // Working buffers for the current item.
            var inItem = false
            var inImage = false
            var itemTitle = ""
            var itemGuid = ""
            var itemAudio = ""
            var itemDuration = 0L
            var itemPub = 0L
            var lastTag = ""
            var text = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        lastTag = parser.name
                        when (parser.name.lowercase()) {
                            "item" -> {
                                inItem = true
                                itemTitle = ""; itemGuid = ""; itemAudio = ""
                                itemDuration = 0L; itemPub = 0L
                            }
                            "enclosure" -> if (inItem) {
                                itemAudio = parser.getAttributeValue(null, "url").orEmpty()
                            }
                            "itunes:image" -> if (!inItem && showArtwork == null) {
                                showArtwork = parser.getAttributeValue(null, "href")
                            }
                            "image" -> if (!inItem) inImage = true
                        }
                    }
                    XmlPullParser.TEXT -> text = parser.text.orEmpty().trim()
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase()
                        if (inItem) {
                            when (tag) {
                                "title" -> if (itemTitle.isBlank()) itemTitle = text
                                "guid" -> itemGuid = text
                                "pubdate" -> itemPub = parsePubDate(text)
                                "itunes:duration" -> itemDuration = parseDurationSec(text)
                                "item" -> {
                                    if (itemAudio.isNotBlank()) {
                                        episodes += PodcastEpisodeEntity(
                                            guid = itemGuid.ifBlank { itemAudio },
                                            feedUrl = feedUrl,
                                            title = itemTitle,
                                            audioUrl = itemAudio,
                                            durationS = itemDuration,
                                            publishedAt = itemPub
                                        )
                                    }
                                    inItem = false
                                }
                            }
                        } else {
                            when (tag) {
                                "title" -> if (showTitle.isBlank()) showTitle = text
                                "description" -> if (showDesc.isBlank()) showDesc = text
                                "url" -> if (inImage && showArtwork == null) showArtwork = text
                                "image" -> inImage = false
                            }
                        }
                        text = ""
                    }
                }
                event = parser.next()
            }

            val show = PodcastShowEntity(
                feedUrl = feedUrl,
                title = showTitle.ifBlank { feedUrl },
                artworkUrl = showArtwork,
                description = showDesc,
                lastChecked = System.currentTimeMillis()
            )
            show to episodes
        }.onFailure { ex ->
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "RSS parse failed: ${ex.message}", ex)
            // Surface to stderr in unit tests where Log is muted.
            ex.printStackTrace()
        }.getOrNull()
    }

    private fun parsePubDate(s: String): Long = parsePubDateMs(s)

    private fun parseDurationSec(s: String): Long {
        if (s.isBlank()) return 0L
        return runCatching {
            // Accept "HH:MM:SS", "MM:SS", or raw seconds.
            val parts = s.split(":")
            when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                1 -> parts[0].toLong()
                else -> 0L
            }
        }.getOrDefault(0L)
    }
}

// ── Pure pubDate parse (unit-tested: RssPubDateTest) ─────────────────────────────
// The old single RFC-822 pattern ("EEE, dd MMM yyyy HH:mm:ss Z") returned 0L for common
// real-feed variants — seconds omitted, ISO-8601 dates, and +00:00 colon offsets — so
// affected feeds got all-zero timestamps → episodes lost chronological order (ORDER BY
// publishedAt) and rendered 1970 dates (bug 2026-08-25). Try a list of formats, each
// required to consume the WHOLE string (ParsePosition) so a mismatched pattern can't
// partial-mis-parse; return 0L only when all fail.
private val PUBDATE_PATTERNS = listOf(
    "EEE, dd MMM yyyy HH:mm:ss Z",
    "EEE, dd MMM yyyy HH:mm:ss zzz",
    "EEE, dd MMM yyyy HH:mm Z",
    "EEE, dd MMM yyyy HH:mm zzz",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
    "yyyy-MM-dd'T'HH:mm:ssZ"
)

internal fun parsePubDateMs(s: String): Long {
    val t = s.trim()
    if (t.isEmpty()) return 0L
    for (p in PUBDATE_PATTERNS) {
        val ms = runCatching {
            val sdf = SimpleDateFormat(p, Locale.ENGLISH)
            val pos = java.text.ParsePosition(0)
            val d = sdf.parse(t, pos)
            // require the pattern to have consumed the entire string (no partial match)
            if (d != null && pos.index == t.length) d.time else null
        }.getOrNull()
        if (ms != null) return ms
    }
    return 0L
}
