package com.powermediaplayer.subtitles

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * §C9 — OpenSubtitles REST v1 client.
 *
 * Two-stage flow:
 *   1. POST /api/v1/login (email + password) → token + user_id.
 *   2. GET  /api/v1/subtitles?query=…&languages=en — returns hits.
 *   3. POST /api/v1/download (file_id) → temporary signed URL.
 *   4. GET  signed URL → SRT bytes.
 *
 * The Api-Key header IDENTIFIES our application, NOT the user. The
 * locked spec ("bake key + per-user login") authorises shipping the
 * key in BuildConfig. We read it from BuildConfig so a leaked APK
 * doesn't expose the key in source control.
 *
 * Free tier rate limits: ~5 req/s. Caller is expected to throttle.
 */
class OpenSubtitlesClient(
    private val apiKey: String,
    private val httpClient: OkHttpClient = defaultClient
) {
    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        private const val UA = "PowerMediaPlayer v1.0"
        private const val BASE = "https://api.opensubtitles.com/api/v1"
        private val JSON = "application/json".toMediaType()
    }

    /** Auth — returns the bearer token on success or null on failure. */
    fun login(email: String, password: String): String? {
        val body = JSONObject().apply {
            put("username", email)
            put("password", password)
        }.toString()
        val req = Request.Builder()
            .url("$BASE/login")
            .header("Api-Key", apiKey)
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val s = resp.body?.string().orEmpty()
                JSONObject(s).optString("token").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /**
     * Search by filename or imdb_id. Returns the best file_id (used
     * for download) or null on miss. When [moviehash] is non-blank
     * the search uses the OpenSubtitles `moviehash` parameter
     * (8-byte hash + size) per §C9 A2.3.
     */
    fun searchBestSubtitleFileId(
        token: String,
        filename: String,
        languages: String = "en",
        moviehash: String = ""
    ): Long? {
        val q = URLEncoder.encode(filename.substringBeforeLast('.'), "UTF-8")
        val hashParam = if (moviehash.isNotBlank())
            "&moviehash=${URLEncoder.encode(moviehash, "UTF-8")}" else ""
        val url = "$BASE/subtitles?query=$q&languages=$languages$hashParam"
        val req = Request.Builder()
            .url(url)
            .header("Api-Key", apiKey)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", UA)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                val data = JSONObject(body).optJSONArray("data") ?: return@runCatching null
                if (data.length() == 0) return@runCatching null
                val first = data.getJSONObject(0)
                val attrs = first.optJSONObject("attributes") ?: return@runCatching null
                val files = attrs.optJSONArray("files") ?: return@runCatching null
                if (files.length() == 0) return@runCatching null
                files.getJSONObject(0).optLong("file_id").takeIf { it > 0 }
            }
        }.getOrNull()
    }

    /** Resolves a file_id to its temporary signed URL. */
    fun fetchDownloadLink(token: String, fileId: Long): String? {
        val body = JSONObject().apply { put("file_id", fileId) }.toString()
        val req = Request.Builder()
            .url("$BASE/download")
            .header("Api-Key", apiKey)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val s = resp.body?.string().orEmpty()
                JSONObject(s).optString("link").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** Streams the signed URL into [outFile]. Returns true on success. */
    fun downloadToFile(url: String, outFile: File): Boolean {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                resp.body?.byteStream()?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                outFile.length() > 0L
            }
        }.getOrDefault(false)
    }
}
