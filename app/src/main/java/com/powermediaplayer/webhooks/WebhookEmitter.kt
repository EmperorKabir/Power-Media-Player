package com.powermediaplayer.webhooks

import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires a JSON HTTP POST to the user-configured webhook URL when the
 * matching playback event occurs. v1 supports one endpoint; the per-
 * event toggles in Settings gate each event type independently.
 *
 * Body shape (stable contract — keep additions backward-compatible):
 *   {
 *     "event": "play" | "pause" | "resume" | "skip_next" | "skip_prev" | "end",
 *     "timestampMs": 1779372345678,
 *     "trackHash": "0c62cc97",
 *     "positionMs": 12340,
 *     "durationMs": 178755
 *   }
 *
 * Privacy: only an 8-char SHA-256 prefix of the mediaUri is sent. Title
 * / file path / personal-identifying content never leave the device.
 * If the user wants richer metadata they can attach a JSON transform on
 * their side (IFTTT / n8n can correlate via trackHash + their own
 * library indexer).
 *
 * Reliability: best-effort. No retry, no queue. 4 s connect / 4 s read
 * timeouts so a dead endpoint can't pile up coroutines. Failures are
 * logged via DiagLog.event("WEBHOOK", ...) and ignored.
 */
@Singleton
class WebhookEmitter @Inject constructor(
    private val settings: SettingsDataStore
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Event(val key: String) {
        PLAY("play"),
        PAUSE("pause"),
        RESUME("resume"),
        SKIP_NEXT("skip_next"),
        SKIP_PREV("skip_prev"),
        END("end")
    }

    fun fire(event: Event, mediaUri: String?, positionMs: Long, durationMs: Long) {
        scope.launch {
            val enabled = isEventEnabled(event)
            if (!enabled) return@launch
            doFire(event, mediaUri, positionMs, durationMs, isTest = false)
        }
    }

    /**
     * Synchronous test fire — sends one payload to the configured URL
     * regardless of per-event toggles. Returns Result so the Settings
     * "Test" button can surface the bridge's HTTP status to the user.
     * Designed for one-shot validation, not the hot playback path.
     */
    suspend fun fireTestSync(): Result<Int> {
        val url = settings.webhookUrl.first().trim()
        if (url.isBlank()) return Result.failure(IllegalStateException("Set a URL first"))
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            return Result.failure(IllegalStateException("URL must start https:// or http://"))
        }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val body = """{"event":"test","timestampMs":${System.currentTimeMillis()},""" +
                    """"trackHash":"test","positionMs":0,"durationMs":0,"message":"Power Media Player test"}"""
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PowerMediaPlayer/webhook-test")
                    .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    DiagLog.event(
                        "WEBHOOK",
                        "test fire url-prefix=${url.take(40)} http=${resp.code}"
                    )
                    resp.code
                }
            }
        }
    }

    private suspend fun doFire(
        event: Event, mediaUri: String?, positionMs: Long, durationMs: Long, isTest: Boolean
    ) {
        val url = settings.webhookUrl.first().trim()
        if (url.isBlank()) return
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            DiagLog.event("WEBHOOK", "URL rejected (must start http/https): $url")
            return
        }
        val body = buildBody(event, mediaUri, positionMs, durationMs)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PowerMediaPlayer/webhook")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                DiagLog.event(
                    "WEBHOOK",
                    "POST ${event.key} url-prefix=${url.take(40)} http=${resp.code}"
                )
            }
        }.onFailure {
            DiagLog.event(
                "WEBHOOK",
                "POST ${event.key} FAILED: ${it.javaClass.simpleName}: ${it.message}"
            )
        }
    }

    private suspend fun isEventEnabled(event: Event): Boolean = when (event) {
        Event.PLAY -> settings.webhookOnPlay.first()
        Event.PAUSE -> settings.webhookOnPause.first()
        Event.RESUME -> settings.webhookOnResume.first()
        Event.SKIP_NEXT -> settings.webhookOnSkipNext.first()
        Event.SKIP_PREV -> settings.webhookOnSkipPrev.first()
        Event.END -> settings.webhookOnEnd.first()
    }

    private fun buildBody(
        event: Event,
        mediaUri: String?,
        positionMs: Long,
        durationMs: Long
    ): String {
        val hash = DiagLog.hash(mediaUri)
        return """
            {"event":"${event.key}","timestampMs":${System.currentTimeMillis()}""" +
            ""","trackHash":"$hash","positionMs":$positionMs,"durationMs":$durationMs}"""
    }
}
