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
     * One dimmable light plus the latency we should subtract from
     * the global syncOffset when reading PCM for it. Built by
     * [PlaybackService] using [HueProvider.fetchBulbLatencyProfiles].
     */
    data class DimmableLight(val id: String, val latencyMs: Int)

    /**
     * Start brightness-pulsing [dimmableLights] at 5 Hz per light.
     * Each entry carries its own latency so a native Hue dimmable
     * (~200 ms) and an IKEA TRÅDFRI (~430 ms) sitting in the same
     * room land in time with each other. [intensity] in [0..100]
     * matches HueEntertainment's slider. 0 stops the driver.
     */
    fun start(dimmableLights: List<DimmableLight>, intensity: Int) {
        if (intensity <= 0 || dimmableLights.isEmpty()) {
            stop()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            val ip = settings.hueBridgeIp.first()
            val key = settings.hueAppKey.first()
            if (ip.isBlank() || key.isBlank()) return@launch
            // Summarise latency bucket distribution so testers can
            // verify the manufacturer detection worked.
            val latencySummary = dimmableLights
                .groupingBy { it.latencyMs }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString { "${it.key}ms×${it.value}" }
            DiagLog.event(
                "HUE",
                "dimmable driver START intensity=$intensity lights=${dimmableLights.size} latencies=$latencySummary"
            )
            // Sensitivity-shaped curve — mirrors HueEntertainment so
            // colour + dimmable lights swing together. See the
            // commentary block in HueEntertainment for the rationale.
            // vc29.4 — white-only bulbs use a much wider swing than the
            //   colour path (5–100 % at max sensitivity vs 30–100 % for
            //   colour). The wider sweep is needed because white bulbs
            //   can only express movement through brightness — they
            //   have no colour channel to convey activity.
            val s = (intensity / 100f).coerceIn(0.01f, 1f)
            // vc29.6 — widened low-s dynSpan so even sensitivity=10
            // produces a visible swing on IKEA Tradfri / GU10 bulbs
            // that smooth aggressively over their Zigbee mesh.
            val baseFloor = 0.50f - s * 0.45f      // 0.50 → 0.05
            val dynSpan = 0.25f + s * 0.65f        // 0.25 → 0.90
            val curve = 1.0f - s * 0.6f
            val gate = 0.10f * (1f - s)            // 0.10 → 0 (was 0.20)
            val invGate = (1f - gate).coerceAtLeast(0.01f)
            val beatGate = 0.30f * (1f - s)
            val invBeatGate = (1f - beatGate).coerceAtLeast(0.01f)
            val beatSpan = 0.10f + s * 0.40f       // 0.10 → 0.50
            val perLightIntervalMs = 200L  // 5 Hz/light = 65 PUTs/sec total for 13 lights
            // Stagger writes across lights — light i fires offset by
            // (perLightInterval / count) so total per-second traffic
            // distributes evenly across all lights.
            val stagger = max(20L, perLightIntervalMs / dimmableLights.size.coerceAtLeast(1))
            val deadlines = LongArray(dimmableLights.size)
            val now0 = android.os.SystemClock.uptimeMillis()
            for (i in dimmableLights.indices) deadlines[i] = now0 + i * stagger
            // Track last sent value per light to skip near-duplicates.
            val lastBri = FloatArray(dimmableLights.size) { -1f }
            var lastDiagWindow = -1L
            while (isActive) {
                val now = android.os.SystemClock.uptimeMillis()
                val syncOffset = runCatching {
                    settings.hueSyncOffsetMs.first() +
                        settings.audioDelayMs.first() +
                        settings.btVideoAudioOffsetMs.first()
                }.getOrDefault(200)
                // User can nudge every dimmable bulb earlier/later on
                // top of the per-bulb auto-detected value. 0 = pure
                // auto. Range coerced -300..+300 in DataStore.
                val userLagOverrideMs = runCatching {
                    settings.hueDimmableLagOffsetMs.first()
                }.getOrDefault(0)
                // Honour any 503/timeout backoff before issuing more
                // PUTs this loop iteration.
                if (now < backoffUntilMs) {
                    delay(50)
                    continue
                }
                var diagThisFrame: String? = null
                for ((idx, light) in dimmableLights.withIndex()) {
                    if (now < deadlines[idx]) continue
                    // Per-bulb PCM read: each bulb is timed for its own
                    // command-to-photon latency so native Hue + IKEA
                    // sitting in the same room land in sync.
                    val perLightOffset =
                        (syncOffset - light.latencyMs - userLagOverrideMs).coerceAtLeast(0)
                    val r = analyserProcessor.getSnapshotAt(perLightOffset)
                    val bandAvg = maxOf(
                        r.bands[1], r.bands[2], r.bands[3], r.bands[4], r.bands[5]
                    )
                    val gatedBand = ((bandAvg - gate) / invGate).coerceAtLeast(0f)
                    val shapedBand = if (gatedBand > 0f)
                        Math.pow(gatedBand.toDouble(), curve.toDouble()).toFloat()
                    else 0f
                    val dyn = (shapedBand * dynSpan).coerceAtMost(0.85f)
                    val beatTerm = if (r.beat && r.beatStrength >= beatGate) {
                        val gatedBeat = ((r.beatStrength - beatGate) / invBeatGate)
                            .coerceAtLeast(0f)
                        val shapedBeat = Math.pow(gatedBeat.toDouble(), curve.toDouble()).toFloat()
                        shapedBeat * beatSpan
                    } else 0f
                    val target = ((baseFloor + dyn + beatTerm).coerceIn(0f, 1f) * 100f)
                        .coerceIn(1f, 100f)
                    // Capture one sample for the throttled diag line
                    // — first light in the loop wins.
                    if (idx == 0 && diagThisFrame == null) {
                        diagThisFrame = "s=${"%.2f".format(s)} lag=${light.latencyMs}+${userLagOverrideMs}ms " +
                            "offset=${perLightOffset}ms bandAvg=${"%.2f".format(bandAvg)} " +
                            "gated=${"%.2f".format(gatedBand)} target=${"%.0f".format(target)}%"
                    }
                    val delta = kotlin.math.abs(target - lastBri[idx])
                    if (lastBri[idx] >= 0f && delta < 1f) {
                        deadlines[idx] = now + perLightIntervalMs
                        continue
                    }
                    putBrightness(ip, key, light.id, target)
                    lastBri[idx] = target
                    deadlines[idx] = now + perLightIntervalMs
                }
                // Throttled diag — once per 2s window.
                if (diagThisFrame != null) {
                    val window = android.os.SystemClock.uptimeMillis() / 2000L
                    if (window != lastDiagWindow) {
                        lastDiagWindow = window
                        DiagLog.event("HUE", "dimmable frame $diagThisFrame")
                    }
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

    /**
     * Throttled diagnostic for HTTP response codes — we sample one
     * PUT every ~2 s and log its outcome, just enough to spot bridge
     * throttling (429 / 503) or per-light errors without flooding the
     * log.
     */
    @Volatile private var lastHttpDiagMs: Long = 0L

    /**
     * Set when the bridge returns 503 (Service Unavailable). The main
     * loop reads this and sleeps an extra slot before the next PUT
     * round, giving the Zigbee mesh time to drain.
     */
    @Volatile internal var backoffUntilMs: Long = 0L

    private fun putBrightness(ip: String, key: String, lightId: String, bri: Float) {
        // vc29.7 — dynamics.duration:200 ms matches the new 200 ms
        // per-light PUT cadence. The bulb smoothly transitions toward
        // each new target without overshooting before the next one
        // arrives. Native Hue bulbs follow this cleanly; IKEA bulbs
        // also benefit (the longer transition gives the Zigbee mesh
        // time to deliver the command).
        val body =
            """{"on":{"on":true},"dimming":{"brightness":${"%.1f".format(bri)}},"dynamics":{"duration":200}}"""
        val req = Request.Builder()
            .url("https://$ip/clip/v2/resource/light/$lightId")
            .header("hue-application-key", key)
            .put(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 503 || resp.code == 429) {
                    // Bridge is overloaded — pause all PUTs for 500 ms
                    // so the request queue drains.
                    backoffUntilMs = android.os.SystemClock.uptimeMillis() + 500
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastHttpDiagMs > 2000L) {
                    lastHttpDiagMs = now
                    DiagLog.event(
                        "HUE",
                        "dimmable PUT sample http=${resp.code} light=${lightId.take(8)} bri=${"%.1f".format(bri)}%"
                    )
                }
            }
        }.onFailure {
            // Treat timeouts the same way as 503 — bridge is choking.
            backoffUntilMs = android.os.SystemClock.uptimeMillis() + 500
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastHttpDiagMs > 2000L) {
                lastHttpDiagMs = now
                DiagLog.event(
                    "HUE",
                    "dimmable PUT FAILED light=${lightId.take(8)} err=${it.javaClass.simpleName}: ${it.message}"
                )
            }
        }
    }
}
