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

    // Stashed bridge + group context — used by stop() to fire a
    // single restore PUT so white bulbs snap back to full brightness
    // when playback ends, instead of being left frozen at whatever
    // the music's last commanded value was.
    @Volatile private var restoreIp: String = ""
    @Volatile private var restoreKey: String = ""
    @Volatile private var restoreGroupId: String? = null
    @Volatile private var restoreLightIds: List<String> = emptyList()

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
     * Start brightness-pulsing [dimmableLights]. When
     * [groupedLightId] is non-null (rooms + zones have one), the
     * driver issues ONE brightness PUT per cycle to the group —
     * this is ~13× lighter on the bridge than per-light PUTs and
     * was the only way to stop the bridge from refusing DTLS
     * frames under sustained load. Per-light fallback is retained
     * at a slower 2 Hz/light for entertainment-area picks that
     * don't expose a grouped_light. [intensity] in [0..100]
     * matches HueEntertainment's slider. 0 stops the driver.
     */
    fun start(
        dimmableLights: List<DimmableLight>,
        intensity: Int,
        groupedLightId: String? = null
    ) {
        if (intensity <= 0 || dimmableLights.isEmpty()) {
            stop()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            val ip = settings.hueBridgeIp.first()
            val key = settings.hueAppKey.first()
            if (ip.isBlank() || key.isBlank()) return@launch
            restoreIp = ip
            restoreKey = key
            restoreGroupId = groupedLightId
            restoreLightIds = dimmableLights.map { it.id }
            // Summarise latency bucket distribution so testers can
            // verify the manufacturer detection worked.
            val latencySummary = dimmableLights
                .groupingBy { it.latencyMs }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString { "${it.key}ms×${it.value}" }
            val routing = if (groupedLightId != null) "group(${groupedLightId.take(8)})" else "per-light"
            DiagLog.event(
                "HUE",
                "dimmable driver START intensity=$intensity lights=${dimmableLights.size} " +
                    "latencies=$latencySummary routing=$routing"
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
            // vc29.12 — even more aggressive low-sensitivity swing.
            //   vc29.11 produced ~30 pp swing at s=0.20 (50%-60% in
            //   logs) which IKEA GU10s still barely registered. The
            //   only way to overcome their internal smoothing is to
            //   send bigger absolute brightness changes. New math
            //   gives ~60 pp swing at s=0.10 and ~95 pp at s=1.0 —
            //   the slider now controls how OFTEN big swings happen
            //   rather than how big each swing is.
            val baseFloor = 0.20f - s * 0.15f      // 0.20 → 0.05
            val dynSpan = 0.60f + s * 0.30f        // 0.60 → 0.90
            val curve = 0.50f - s * 0.10f          // 0.50 → 0.40
            val gate = 0.03f * (1f - s)            // 0.03 → 0
            val invGate = (1f - gate).coerceAtLeast(0.01f)
            val beatGate = 0.15f * (1f - s)
            val invBeatGate = (1f - beatGate).coerceAtLeast(0.01f)
            val beatSpan = 0.30f + s * 0.20f       // 0.30 → 0.50
            // vc29.10 — settings reads cached once per loop (was 4 .first()
            // calls per iteration = wasteful DataStore churn). Loop reads
            // them once into locals and only refreshes every 2 s.
            var lastSettingsReadMs = 0L
            var syncOffset = 200
            var userLagOverrideMs = 0

            if (groupedLightId != null) {
                // Group mode — ONE PUT per cycle to the room/zone's
                // grouped_light. Bridge load goes from N×5 Hz to 1×~5 Hz
                // total; this is what stops the bridge from refusing
                // DTLS frames under sustained load. Per-bulb latency is
                // averaged across the dimmable set; perfect sync per-
                // bulb is sacrificed for stability, but the visual
                // result is far closer than the previous "lights
                // freeze" failure mode.
                val avgLatencyMs = dimmableLights.sumOf { it.latencyMs } / dimmableLights.size
                // vc29.13 — dropped from 2 Hz (500 ms) to 1 Hz (1000 ms).
                // IKEA Tradfri / GU10 bulbs QUEUE transitions instead
                // of aborting in-flight ones (the way native Hue does),
                // so anything faster than ~1 Hz piles up a backlog the
                // bulb processes for tens of seconds after pause. 1 Hz
                // is also right at the documented Hue v1 grouped state
                // ceiling, so the bridge stays comfortable too.
                val groupIntervalMs = 1000L
                var lastSent = -1f
                var lastDiagWindow = -1L
                var nextDeadline = android.os.SystemClock.uptimeMillis()
                while (isActive) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastSettingsReadMs > 2000L) {
                        lastSettingsReadMs = now
                        syncOffset = runCatching {
                            settings.hueSyncOffsetMs.first() +
                                settings.audioDelayMs.first() +
                                settings.btVideoAudioOffsetMs.first()
                        }.getOrDefault(200)
                        userLagOverrideMs = runCatching {
                            settings.hueDimmableLagOffsetMs.first()
                        }.getOrDefault(0)
                    }
                    if (now < backoffUntilMs || now < nextDeadline) {
                        delay(25)
                        continue
                    }
                    val groupOffset =
                        (syncOffset - avgLatencyMs - userLagOverrideMs).coerceAtLeast(0)
                    val r = analyserProcessor.getSnapshotAt(groupOffset)
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
                    val window = now / 2000L
                    if (window != lastDiagWindow) {
                        lastDiagWindow = window
                        DiagLog.event(
                            "HUE",
                            "dimmable group frame s=${"%.2f".format(s)} avgLag=${avgLatencyMs}ms " +
                                "offset=${groupOffset}ms bandAvg=${"%.2f".format(bandAvg)} target=${"%.0f".format(target)}%"
                        )
                    }
                    val delta = kotlin.math.abs(target - lastSent)
                    // vc29.13 — bigger delta filter (5 %) so we only
                    // PUT meaningful brightness changes. Eliminates
                    // cosmetic micro-wobbles that would otherwise
                    // contribute to the IKEA Zigbee queue build-up.
                    if (lastSent < 0f || delta >= 5f) {
                        putGroupBrightness(ip, key, groupedLightId, target)
                        lastSent = target
                    }
                    nextDeadline = now + groupIntervalMs
                }
            } else {
                // Per-light fallback (used when an Entertainment area
                // is selected; rooms/zones go through group mode above).
                // 1 Hz/light — slow enough for IKEA bulbs (which queue
                // commands) to keep up without backing up. Faster paths
                // were causing 20+ second post-pause settling.
                val perLightIntervalMs = 1000L
                val stagger = max(50L, perLightIntervalMs / dimmableLights.size.coerceAtLeast(1))
                val deadlines = LongArray(dimmableLights.size)
                val now0 = android.os.SystemClock.uptimeMillis()
                for (i in dimmableLights.indices) deadlines[i] = now0 + i * stagger
                val lastBri = FloatArray(dimmableLights.size) { -1f }
                var lastDiagWindow = -1L
                while (isActive) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastSettingsReadMs > 2000L) {
                        lastSettingsReadMs = now
                        syncOffset = runCatching {
                            settings.hueSyncOffsetMs.first() +
                                settings.audioDelayMs.first() +
                                settings.btVideoAudioOffsetMs.first()
                        }.getOrDefault(200)
                        userLagOverrideMs = runCatching {
                            settings.hueDimmableLagOffsetMs.first()
                        }.getOrDefault(0)
                    }
                    if (now < backoffUntilMs) {
                        delay(50)
                        continue
                    }
                    var diagThisFrame: String? = null
                    for ((idx, light) in dimmableLights.withIndex()) {
                        if (now < deadlines[idx]) continue
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
                        if (idx == 0 && diagThisFrame == null) {
                            diagThisFrame = "s=${"%.2f".format(s)} lag=${light.latencyMs}+${userLagOverrideMs}ms " +
                                "offset=${perLightOffset}ms bandAvg=${"%.2f".format(bandAvg)} target=${"%.0f".format(target)}%"
                        }
                        val delta = kotlin.math.abs(target - lastBri[idx])
                        if (lastBri[idx] >= 0f && delta < 5f) {
                            deadlines[idx] = now + perLightIntervalMs
                            continue
                        }
                        putBrightness(ip, key, light.id, target)
                        lastBri[idx] = target
                        deadlines[idx] = now + perLightIntervalMs
                    }
                    if (diagThisFrame != null) {
                        val window = android.os.SystemClock.uptimeMillis() / 2000L
                        if (window != lastDiagWindow) {
                            lastDiagWindow = window
                            DiagLog.event("HUE", "dimmable per-light frame $diagThisFrame")
                        }
                    }
                    delay(60) // overall loop @ ~16 Hz (lighter)
                }
            }
            DiagLog.event("HUE", "dimmable driver STOPPED")
        }
    }

    fun stop() {
        if (job == null) return
        job?.cancel()
        job = null
        // vc29.12 — when playback stops, snap white bulbs back to
        // 100 % so the room doesn't feel "stuck dim" at whatever the
        // music's last commanded value happened to be. One group PUT
        // (or per-light PUTs in the entertainment-area fallback) with
        // a fast 300 ms transition.
        val ip = restoreIp
        val key = restoreKey
        if (ip.isBlank() || key.isBlank()) return
        val groupId = restoreGroupId
        val lightIds = restoreLightIds
        scope.launch {
            runCatching {
                if (groupId != null) {
                    val body =
                        """{"on":{"on":true},"dimming":{"brightness":100.0},"dynamics":{"duration":200}}"""
                    val req = Request.Builder()
                        .url("https://$ip/clip/v2/resource/grouped_light/$groupId")
                        .header("hue-application-key", key)
                        .put(body.toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    http.newCall(req).execute().use { resp ->
                        DiagLog.event(
                            "HUE",
                            "dimmable RESTORE group http=${resp.code} group=${groupId.take(8)}"
                        )
                    }
                } else {
                    for (lid in lightIds) {
                        val body =
                            """{"on":{"on":true},"dimming":{"brightness":100.0},"dynamics":{"duration":200}}"""
                        val req = Request.Builder()
                            .url("https://$ip/clip/v2/resource/light/$lid")
                            .header("hue-application-key", key)
                            .put(body.toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()
                        runCatching { http.newCall(req).execute().close() }
                        kotlinx.coroutines.delay(100) // stay under bridge throughput cap
                    }
                    DiagLog.event(
                        "HUE",
                        "dimmable RESTORE per-light → ${lightIds.size} bulbs"
                    )
                }
            }
        }
        // Clear stash so a stale start() doesn't reuse old context.
        restoreIp = ""
        restoreKey = ""
        restoreGroupId = null
        restoreLightIds = emptyList()
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

    /**
     * Group brightness PUT — one HTTP call affects every member of
     * the room/zone's grouped_light. Hue's docs state that lights
     * currently locked by an active entertainment_configuration
     * silently ignore CLIP brightness commands, so the colour bulbs
     * (driven by our DTLS path) are unaffected.
     */
    private fun putGroupBrightness(ip: String, key: String, groupId: String, bri: Float) {
        // vc29.13 — duration:0 (no transition) instead of 200 ms.
        // Each PUT becomes an instant snap. IKEA bulbs can't queue a
        // transition that doesn't exist, so the post-pause backlog
        // collapses to zero.
        val body =
            """{"on":{"on":true},"dimming":{"brightness":${"%.1f".format(bri)}},"dynamics":{"duration":0}}"""
        val req = Request.Builder()
            .url("https://$ip/clip/v2/resource/grouped_light/$groupId")
            .header("hue-application-key", key)
            .put(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 503 || resp.code == 429 || resp.code >= 500) {
                    // vc29.10 — bigger backoff (2.5 s). Earlier 500 ms
                    // backoff didn't give the bridge time to recover
                    // and we ended up in a hammer-spiral.
                    backoffUntilMs = android.os.SystemClock.uptimeMillis() + 2500
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastHttpDiagMs > 2000L) {
                    lastHttpDiagMs = now
                    DiagLog.event(
                        "HUE",
                        "dimmable GROUP PUT http=${resp.code} group=${groupId.take(8)} bri=${"%.1f".format(bri)}%"
                    )
                }
            }
        }.onFailure {
            backoffUntilMs = android.os.SystemClock.uptimeMillis() + 2500
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastHttpDiagMs > 2000L) {
                lastHttpDiagMs = now
                DiagLog.event(
                    "HUE",
                    "dimmable GROUP PUT FAILED group=${groupId.take(8)} err=${it.javaClass.simpleName}: ${it.message}"
                )
            }
        }
    }

    private fun putBrightness(ip: String, key: String, lightId: String, bri: Float) {
        // vc29.13 — duration:0. See putGroupBrightness for rationale.
        val body =
            """{"on":{"on":true},"dimming":{"brightness":${"%.1f".format(bri)}},"dynamics":{"duration":0}}"""
        val req = Request.Builder()
            .url("https://$ip/clip/v2/resource/light/$lightId")
            .header("hue-application-key", key)
            .put(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 503 || resp.code == 429 || resp.code >= 500) {
                    // vc29.10 — 2.5 s backoff (was 500 ms). The
                    // previous short window let us keep hammering a
                    // already-dying bridge and pushed it past
                    // recovery.
                    backoffUntilMs = android.os.SystemClock.uptimeMillis() + 2500
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
            backoffUntilMs = android.os.SystemClock.uptimeMillis() + 2500
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
