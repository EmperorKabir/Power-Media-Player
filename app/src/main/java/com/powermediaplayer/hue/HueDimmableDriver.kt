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
            var lastDiagWindow = -1L
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
                // vc29.5 — use the max of bands 1..5 (bass / low-mid /
                // mid / high-mid / treble) as the drive metric. The
                // previous bass-weighted /2.35 average compressed any
                // real-world signal to 0.10..0.25, which sat below the
                // gate threshold permanently. Max-of-bands gives the
                // engine the strongest in-band peak directly.
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
                // Hue dimming uses 0..100 (not the 0..65535 u16 of
                // the Entertainment stream).
                val target = ((baseFloor + dyn + beatTerm).coerceIn(0f, 1f) * 100f)
                    .coerceIn(1f, 100f)
                // Throttled diagnostic — surface the actual target +
                // band so testers can see whether the engine is
                // pinning at ceiling or genuinely varying.
                if ((android.os.SystemClock.uptimeMillis() / 2000L) != lastDiagWindow) {
                    lastDiagWindow = android.os.SystemClock.uptimeMillis() / 2000L
                    DiagLog.event(
                        "HUE",
                        "dimmable frame s=${"%.2f".format(s)} bandAvg=${"%.2f".format(bandAvg)} " +
                            "gated=${"%.2f".format(gatedBand)} target=${"%.0f".format(target)}%"
                    )
                }
                for ((idx, lid) in dimmableLightIds.withIndex()) {
                    if (now < deadlines[idx]) continue
                    val delta = kotlin.math.abs(target - lastBri[idx])
                    // Skip near-duplicates — narrowed from 3 % to 1 %
                    // because IKEA Tradfri / GU10 bulbs need every
                    // small variation to escape their slow Zigbee
                    // mesh smoothing.
                    if (lastBri[idx] >= 0f && delta < 1f) {
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

    /**
     * Throttled diagnostic for HTTP response codes — we sample one
     * PUT every ~5 s and log its outcome, just enough to spot bridge
     * throttling (429) or per-light errors without flooding the log.
     */
    @Volatile private var lastHttpDiagMs: Long = 0L

    private fun putBrightness(ip: String, key: String, lightId: String, bri: Float) {
        // vc29.6 — explicit dynamics.duration:100 ms. Without it the
        // bridge applies its default ~400 ms transition, which means
        // every PUT smooths toward a target that's already been
        // superseded by the next PUT. IKEA Tradfri / GU10 bulbs over
        // the Hue bridge's Zigbee mesh exhibit this much worse than
        // native Hue bulbs and end up nearly static even though our
        // engine is sending varying targets.
        val body =
            """{"on":{"on":true},"dimming":{"brightness":${"%.1f".format(bri)}},"dynamics":{"duration":100}}"""
        val req = Request.Builder()
            .url("https://$ip/clip/v2/resource/light/$lightId")
            .header("hue-application-key", key)
            .put(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastHttpDiagMs > 5000L) {
                    lastHttpDiagMs = now
                    DiagLog.event(
                        "HUE",
                        "dimmable PUT sample http=${resp.code} light=${lightId.take(8)} bri=${"%.1f".format(bri)}%"
                    )
                }
            }
        }.onFailure {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastHttpDiagMs > 5000L) {
                lastHttpDiagMs = now
                DiagLog.event(
                    "HUE",
                    "dimmable PUT FAILED light=${lightId.take(8)} err=${it.javaClass.simpleName}: ${it.message}"
                )
            }
        }
    }
}
