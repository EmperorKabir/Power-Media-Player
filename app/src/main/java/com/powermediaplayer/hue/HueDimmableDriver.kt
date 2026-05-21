package com.powermediaplayer.hue

import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Reactive-lighting parallel driver for DIMMABLE Hue lights — bulbs
 * with only `dimming` support, no `color`, no `color_temperature`.
 *
 * Why this exists:
 *   - Hue DTLS Entertainment streaming refuses to add dimmable-only
 *     lights to an entertainment_configuration (the bridge rejects
 *     POST on those entertainment-service ids — they don't exist).
 *   - But these lights can still pulse with the music if we PUT
 *     /clip/v2/resource/light/{id} with new brightness values.
 *   - The REST endpoint is rate-limited (Philips advisory: <= 10 PUT/s
 *     per light). We cap at 10 Hz per light + stagger lights so we
 *     don't burst.
 *
 * Behaviour:
 *   - Shares the same [HueAnalyserAudioProcessor.latest] snapshot the
 *     Entertainment loop reads. Timing stays coherent across the two
 *     control paths.
 *   - Brightness = baseline + dynamics + beat boost, all scaled by
 *     user intensity (matches the Entertainment loop math so light
 *     output looks unified).
 *   - On stop(): no extra "all off" — leaves lights at whatever
 *     brightness their last frame set, so the user can resume music
 *     and have things continue without a flash.
 */
@Singleton
class HueDimmableDriver @Inject constructor(
    private val settings: SettingsDataStore,
    private val analyserProcessor: HueAnalyserAudioProcessor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    private val http: okhttp3.OkHttpClient by lazy {
        // Reuse a permissive client (bridge cert is self-signed). The
        // standard HueProvider client could be injected but keeping
        // this driver self-contained avoids cross-class coupling.
        val trustAll = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
        okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Start brightness-pulsing [dimmableLightIds] at up to 10 Hz per
     * light. [intensity] in [0..100] follows the same convention as
     * HueEntertainment. 0 stops the driver.
     */
    fun start(dimmableLightIds: List<String>, intensity: Int) {
        if (intensity <= 0 || dimmableLightIds.isEmpty()) {
            stop()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            val ip = settings.hueBridgeIp.first()
            val key = settings.hueAppKey.first()
            if (ip.isBlank() || key.isBlank()) return@launch
            DiagLog.event(
                "HUE",
                "dimmable driver START intensity=$intensity lights=${dimmableLightIds.size}"
            )
            val intensityF = (intensity / 100f).coerceIn(0.01f, 1f)
            val perLightIntervalMs = 100L // 10 Hz/light advisory ceiling
            // Stagger writes across lights — light i fires offset by
            // (perLightInterval / count) so total per-second traffic
            // distributes evenly across all lights.
            val stagger = max(20L, perLightIntervalMs / dimmableLightIds.size.coerceAtLeast(1))
            val deadlines = LongArray(dimmableLightIds.size)
            val now0 = android.os.SystemClock.uptimeMillis()
            for (i in dimmableLightIds.indices) deadlines[i] = now0 + i * stagger
            // Track last sent value per light to skip near-duplicates.
            val lastBri = FloatArray(dimmableLightIds.size) { -1f }
            while (isActive) {
                val now = android.os.SystemClock.uptimeMillis()
                // Read the analyser snapshot at the SAME offset the
                // Entertainment loop uses — keeps light paths coherent.
                val syncOffset = runCatching {
                    settings.hueSyncOffsetMs.first() +
                        settings.audioDelayMs.first() +
                        settings.btVideoAudioOffsetMs.first()
                }.getOrDefault(200)
                val r = analyserProcessor.getSnapshotAt(syncOffset)
                // Dimmable lights take a single brightness — combine
                // dynamics + beat boost. No band-routing (no colour).
                val beatBoost = if (r.beat) r.beatStrength else 0f
                // Hue dimming uses 0..100 (not 0..65535 like the
                // Entertainment stream's brightness u16).
                val target = ((0.30f + r.dynamics * 0.40f + beatBoost * 0.30f) * intensityF * 100f)
                    .coerceIn(1f, 100f)
                for ((idx, lid) in dimmableLightIds.withIndex()) {
                    if (now < deadlines[idx]) continue
                    val delta = kotlin.math.abs(target - lastBri[idx])
                    // Skip if change is below 3% — saves an HTTP call
                    // for cosmetic no-ops.
                    if (lastBri[idx] >= 0f && delta < 3f) {
                        deadlines[idx] = now + perLightIntervalMs
                        continue
                    }
                    putBrightness(ip, key, lid, target)
                    lastBri[idx] = target
                    deadlines[idx] = now + perLightIntervalMs
                }
                delay(30) // overall loop @ ~33 Hz
            }
            DiagLog.event("HUE", "dimmable driver STOPPED")
        }
    }

    fun stop() {
        if (job == null) return
        job?.cancel()
        job = null
    }

    private fun putBrightness(ip: String, key: String, lightId: String, bri: Float) {
        val body = """{"on":{"on":true},"dimming":{"brightness":${"%.1f".format(bri)}}}"""
        val req = Request.Builder()
            .url("https://$ip/clip/v2/resource/light/$lightId")
            .header("hue-application-key", key)
            .put(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        runCatching {
            http.newCall(req).execute().use { /* discard body */ }
        }
        // Don't log per-PUT — at 10 Hz across many lights this would
        // dominate the log. The driver's start/stop lines + the
        // Entertainment-loop diag give enough signal at 4-second
        // cadence.
    }
}
