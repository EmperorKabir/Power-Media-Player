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

    /** Live-updatable sensitivity. vc29.15 fix — was previously
     *  captured once at start() and never refreshed, so slider
     *  moves during playback were ignored. */
    @Volatile private var liveIntensity: Int = 0

    /** Apply a new sensitivity to the running driver without
     *  restarting it. Called by HueEntertainment.setIntensity(). */
    fun setIntensity(v: Int) {
        liveIntensity = v
    }

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
    data class DimmableLight(
        val id: String,
        val latencyMs: Int,
        /** Per-bulb max safe PUT rate. The group cadence uses the
         *  min across all members so the slowest bulb gates the
         *  room. Defaults to 1 Hz when unknown. */
        val maxSafeRateHz: Float = 1.0f
    )

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
            // vc29.15 — swing constants computed per-frame from
            // liveIntensity inside the inner loops below so slider
            // moves during playback take effect immediately. Seed
            // liveIntensity from the initial start() argument.
            liveIntensity = intensity
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
                // vc29.14 — ADAPTIVE PUT cadence. Picks the slowest
                // bulb's safe rate so the group never out-paces its
                // weakest member. All-Hue group → 2 Hz (silky);
                // any-IKEA → 1 Hz (queue-safe); mixed others →
                // 1.5 Hz. Clamped 500..2000 ms.
                val groupRateHz = dimmableLights.minOfOrNull { it.maxSafeRateHz } ?: 1.0f
                val groupIntervalMs = (1000.0 / groupRateHz).toLong().coerceIn(500L, 2000L)
                // Tighter delta filter at higher rates so we don't
                // skip too many of the now-faster updates.
                val deltaFilterPct = if (groupRateHz >= 2.0f) 3f else 5f
                DiagLog.event(
                    "HUE",
                    "dimmable group cadence ${"%.1f".format(groupRateHz)} Hz " +
                        "interval=${groupIntervalMs}ms deltaFilter=${"%.0f".format(deltaFilterPct)}%"
                )
                var lastSent = -1f
                var lastDiagWindow = -1L
                var nextDeadline = android.os.SystemClock.uptimeMillis()
                // vc29.19 — analyser handles smoothing; only slew-limit
                // state remains here for the IKEA-friendly per-PUT cap.
                var lastTarget = 50f
                // vc29.21 — onset-driven envelope for Option C. Peak-
                // follower with fast attack + slow decay so quiet
                // passages return to baseline and transients pulse the
                // bulbs up to peak. Used at high sensitivity to replace
                // the continuous bandAvg target so IKEA bulbs visibly
                // PULSE on beats instead of sitting at 85 % mean.
                var onsetEnvelope = 0f       // current envelope value [0..1]
                var lastBandForDelta = 0f    // for delta-on-rise detection
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
                    // vc29.15 — live sensitivity. Recompute the swing
                    // constants each iteration so slider moves take
                    // effect within one PUT cycle.
                    val s = (liveIntensity / 100f).coerceIn(0.01f, 1f)
                    val baseFloor = 0.20f - s * 0.15f
                    val dynSpan = 0.60f + s * 0.30f
                    val curve = 0.50f - s * 0.10f
                    val gate = 0.03f * (1f - s)
                    val invGate = (1f - gate).coerceAtLeast(0.01f)
                    val beatGate = 0.15f * (1f - s)
                    val invBeatGate = (1f - beatGate).coerceAtLeast(0.01f)
                    val beatSpan = 0.30f + s * 0.20f
                    val groupOffset =
                        (syncOffset - avgLatencyMs - userLagOverrideMs).coerceAtLeast(0)
                    val r = analyserProcessor.getSnapshotAt(groupOffset)
                    // vc29.17 — lerp raw with PCEN+percentile-normalised
                    // metric so high s gets dramatic swing on quiet music.
                    val rawAvg = maxOf(
                        r.bands[1], r.bands[2], r.bands[3], r.bands[4], r.bands[5]
                    )
                    val normAvg = maxOf(
                        r.normalisedBands[1], r.normalisedBands[2],
                        r.normalisedBands[3], r.normalisedBands[4], r.normalisedBands[5]
                    )
                    // vc29.19 — engine EMA removed; analyser smooths
                    // normalisedBands at source so the eff signal is
                    // already stable here.
                    val bandAvg = rawAvg * (1f - s) + normAvg * s
                    val rawEff = bandAvg  // kept for diag
                    val effectiveBeatStrength =
                        r.beatStrength * (1f - s) + r.normalisedBeatStrength * s
                    val gatedBand = ((bandAvg - gate) / invGate).coerceAtLeast(0f)
                    val shapedBand = if (gatedBand > 0f)
                        Math.pow(gatedBand.toDouble(), curve.toDouble()).toFloat()
                    else 0f
                    val dyn = (shapedBand * dynSpan).coerceAtMost(0.85f)
                    val beatTerm = if (r.beat && effectiveBeatStrength >= beatGate) {
                        val gatedBeat = ((effectiveBeatStrength - beatGate) / invBeatGate)
                            .coerceAtLeast(0f)
                        val shapedBeat = Math.pow(gatedBeat.toDouble(), curve.toDouble()).toFloat()
                        shapedBeat * beatSpan
                    } else 0f
                    val continuousTarget = ((baseFloor + dyn + beatTerm).coerceIn(0f, 1f) * 100f)
                        .coerceIn(1f, 100f)

                    // ── vc29.21 Option C: onset-driven target ────────
                    // Peak-follower: envelope rises FAST on rising
                    // bandAvg or beat detection, decays slowly back to
                    // a low baseline. At high sensitivity this REPLACES
                    // the continuous target so bulbs visibly pulse on
                    // each transient instead of sitting at mean ≈ 87 %.
                    val rise = (bandAvg - lastBandForDelta).coerceAtLeast(0f)
                    lastBandForDelta = bandAvg
                    val onsetAttack = (rise * 3.5f).coerceAtMost(1.0f) // amplify deltas
                    val beatAttack = if (r.beat) effectiveBeatStrength else 0f
                    val attackVal = maxOf(onsetAttack, beatAttack)
                    // Fast attack (jump up if new attack > envelope),
                    // slow decay (multiply by 0.55 per ~1s cycle ≈ 600
                    // ms half-life).
                    onsetEnvelope = maxOf(onsetEnvelope * 0.35f, attackVal)
                    // Map envelope to brightness: low baseline (25%)
                    // + envelope * (peak 100% - baseline). When
                    // envelope is 0 → 25%; when envelope is 1.0 → 100%.
                    val onsetBaseline = 25f
                    val onsetPeak = 100f
                    val onsetTarget = onsetBaseline +
                        onsetEnvelope * (onsetPeak - onsetBaseline)

                    // Blend continuous + onset by s² so onset mode
                    // dominates only at high sensitivity. At s=0.3
                    // weight is only 0.09; at s=0.7 it's 0.49; at
                    // s=1.0 it's 1.0 (pure onset).
                    val onsetWeight = s * s
                    val rawTarget = (continuousTarget * (1f - onsetWeight) +
                        onsetTarget * onsetWeight).coerceIn(1f, 100f)
                    // vc29.18 — slew limit. IKEA / Tradfri / GU10 can
                    // physically follow ~30 pp/sec brightness change;
                    // anything bigger and the bulb just settles on the
                    // average and looks stuck. Allow native Hue to
                    // slew faster (35 pp/sec). Other brands 25 pp/sec.
                    val maxSlewPp = when {
                        groupRateHz >= 2.0f -> 35f   // all-Hue
                        groupRateHz <= 1.0f -> 25f   // IKEA-only / mixed
                        else -> 30f
                    }
                    val target = rawTarget.coerceIn(
                        lastTarget - maxSlewPp,
                        lastTarget + maxSlewPp
                    )
                    lastTarget = target
                    val window = now / 1000L
                    if (window != lastDiagWindow) {
                        lastDiagWindow = window
                        DiagLog.event(
                            "HUE",
                            "dimmable group frame s=${"%.2f".format(s)} avgLag=${avgLatencyMs}ms " +
                                "offset=${groupOffset}ms raw=${"%.2f".format(rawAvg)} " +
                                "norm=${"%.2f".format(normAvg)} eff=${"%.2f".format(bandAvg)} " +
                                "onsetEnv=${"%.2f".format(onsetEnvelope)} " +
                                "cont=${"%.0f".format(continuousTarget)}% " +
                                "onset=${"%.0f".format(onsetTarget)}% " +
                                "blendW=${"%.2f".format(onsetWeight)} " +
                                "rawTarget=${"%.0f".format(rawTarget)}% target=${"%.0f".format(target)}%"
                        )
                    }
                    val delta = kotlin.math.abs(target - lastSent)
                    // vc29.14 — delta filter adapts with cadence: 3 %
                    // at >=2 Hz (smoother), 5 % at 1 Hz (more
                    // queue-safe for IKEA).
                    if (lastSent < 0f || delta >= deltaFilterPct) {
                        putGroupBrightness(ip, key, groupedLightId, target)
                        lastSent = target
                    }
                    nextDeadline = now + groupIntervalMs
                }
            } else {
                // Per-light fallback (used when an Entertainment area
                // is selected; rooms/zones go through group mode above).
                // vc29.14 — per-light cadence still uses the min rate
                // so a single IKEA bulb in the set caps the whole
                // group at the slow path. Stagger keeps total bridge
                // load comfortable.
                val perLightRateHz = dimmableLights.minOfOrNull { it.maxSafeRateHz } ?: 1.0f
                val perLightIntervalMs = (1000.0 / perLightRateHz).toLong().coerceIn(500L, 2000L)
                val perLightDeltaPct = if (perLightRateHz >= 2.0f) 3f else 5f
                // vc29.21 — per-light onset envelope (one per bulb)
                val onsetEnvPerLight = FloatArray(dimmableLights.size)
                val lastBandPerLight = FloatArray(dimmableLights.size)
                DiagLog.event(
                    "HUE",
                    "dimmable per-light cadence ${"%.1f".format(perLightRateHz)} Hz interval=${perLightIntervalMs}ms"
                )
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
                    // vc29.15 — live sensitivity recompute (per-light path).
                    val s = (liveIntensity / 100f).coerceIn(0.01f, 1f)
                    val baseFloor = 0.20f - s * 0.15f
                    val dynSpan = 0.60f + s * 0.30f
                    val curve = 0.50f - s * 0.10f
                    val gate = 0.03f * (1f - s)
                    val invGate = (1f - gate).coerceAtLeast(0.01f)
                    val beatGate = 0.15f * (1f - s)
                    val invBeatGate = (1f - beatGate).coerceAtLeast(0.01f)
                    val beatSpan = 0.30f + s * 0.20f
                    var diagThisFrame: String? = null
                    for ((idx, light) in dimmableLights.withIndex()) {
                        if (now < deadlines[idx]) continue
                        val perLightOffset =
                            (syncOffset - light.latencyMs - userLagOverrideMs).coerceAtLeast(0)
                        val r = analyserProcessor.getSnapshotAt(perLightOffset)
                        val rawAvg = maxOf(
                            r.bands[1], r.bands[2], r.bands[3], r.bands[4], r.bands[5]
                        )
                        val normAvg = maxOf(
                            r.normalisedBands[1], r.normalisedBands[2],
                            r.normalisedBands[3], r.normalisedBands[4], r.normalisedBands[5]
                        )
                        val bandAvg = rawAvg * (1f - s) + normAvg * s
                        val effectiveBeatStrength =
                            r.beatStrength * (1f - s) + r.normalisedBeatStrength * s
                        val gatedBand = ((bandAvg - gate) / invGate).coerceAtLeast(0f)
                        val shapedBand = if (gatedBand > 0f)
                            Math.pow(gatedBand.toDouble(), curve.toDouble()).toFloat()
                        else 0f
                        val dyn = (shapedBand * dynSpan).coerceAtMost(0.85f)
                        val beatTerm = if (r.beat && effectiveBeatStrength >= beatGate) {
                            val gatedBeat = ((effectiveBeatStrength - beatGate) / invBeatGate)
                                .coerceAtLeast(0f)
                            val shapedBeat = Math.pow(gatedBeat.toDouble(), curve.toDouble()).toFloat()
                            shapedBeat * beatSpan
                        } else 0f
                        val continuousTarget = ((baseFloor + dyn + beatTerm).coerceIn(0f, 1f) * 100f)
                            .coerceIn(1f, 100f)
                        // vc29.21 — per-light onset envelope mirroring
                        // group-mode logic.
                        val rise = (bandAvg - lastBandPerLight[idx]).coerceAtLeast(0f)
                        lastBandPerLight[idx] = bandAvg
                        val onsetAttack = (rise * 3.5f).coerceAtMost(1.0f)
                        val beatAttack = if (r.beat) effectiveBeatStrength else 0f
                        val attackVal = maxOf(onsetAttack, beatAttack)
                        onsetEnvPerLight[idx] = maxOf(onsetEnvPerLight[idx] * 0.35f, attackVal)
                        val onsetTarget = 25f + onsetEnvPerLight[idx] * 75f
                        val onsetWeight = s * s
                        val target = (continuousTarget * (1f - onsetWeight) +
                            onsetTarget * onsetWeight).coerceIn(1f, 100f)
                        if (idx == 0 && diagThisFrame == null) {
                            diagThisFrame = "s=${"%.2f".format(s)} lag=${light.latencyMs}+${userLagOverrideMs}ms " +
                                "offset=${perLightOffset}ms bandAvg=${"%.2f".format(bandAvg)} target=${"%.0f".format(target)}%"
                        }
                        val delta = kotlin.math.abs(target - lastBri[idx])
                        if (lastBri[idx] >= 0f && delta < perLightDeltaPct) {
                            deadlines[idx] = now + perLightIntervalMs
                            continue
                        }
                        putBrightness(ip, key, light.id, target)
                        lastBri[idx] = target
                        deadlines[idx] = now + perLightIntervalMs
                    }
                    if (diagThisFrame != null) {
                        val window = android.os.SystemClock.uptimeMillis() / 1000L
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
                    backoffUntilMs = android.os.SystemClock.uptimeMillis() + 2500
                }
                // vc29.16 — log EVERY group PUT (1 Hz cadence makes
                // unthrottled logging trivial). Lets the post-test
                // analyser correlate each PUT with the slider value.
                DiagLog.event(
                    "HUE",
                    "dimmable GROUP PUT http=${resp.code} group=${groupId.take(8)} bri=${"%.1f".format(bri)}% s=${liveIntensity}"
                )
            }
        }.onFailure {
            backoffUntilMs = android.os.SystemClock.uptimeMillis() + 2500
            DiagLog.event(
                "HUE",
                "dimmable GROUP PUT FAILED group=${groupId.take(8)} err=${it.javaClass.simpleName}: ${it.message}"
            )
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
                    backoffUntilMs = android.os.SystemClock.uptimeMillis() + 2500
                }
                // vc29.16 — log every per-light PUT (used only in the
                // entertainment-area fallback, max ~13 lights × 1 Hz
                // = 13 lines/sec which is still acceptable for an
                // analysis-grade log).
                DiagLog.event(
                    "HUE",
                    "dimmable PUT http=${resp.code} light=${lightId.take(8)} bri=${"%.1f".format(bri)}% s=${liveIntensity}"
                )
            }
        }.onFailure {
            backoffUntilMs = android.os.SystemClock.uptimeMillis() + 2500
            DiagLog.event(
                "HUE",
                "dimmable PUT FAILED light=${lightId.take(8)} err=${it.javaClass.simpleName}: ${it.message}"
            )
        }
    }
}
