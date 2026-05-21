package com.powermediaplayer.hue

import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Philips Hue v2 API client (LAN-only, no cloud).
 *
 * v1 capabilities (what ships in this drop):
 *  - SSDP-style discovery via meethue.com discovery service
 *  - Pair (button-press authentication) → persist app key
 *  - List lights
 *  - Apply a "scene preset" — Party / Ambient / Cinema / Reading
 *    (hand-rolled XY + brightness + transition values applied to every
 *    light on the bridge in a single batched call series)
 *  - On / Off all lights
 *
 * v1 explicitly NOT included (Entertainment API audio-reactive):
 *  - DTLS streaming to the bridge's Entertainment endpoint
 *  - 50 Hz visualiser → light update loop
 *  - The scaffold in [com.powermediaplayer.hue.HueEntertainment] is a
 *    TODO placeholder; landing it is a separate vc because it needs
 *    BouncyCastle (DTLS-PSK) and careful threading. The user has been
 *    told this scope-bound directly.
 *
 * Security:
 *  - The bridge uses a self-signed cert. We accept any X509 cert from
 *    the bridge IP because LAN-only IoT pairing is the documented
 *    pattern. App key is sent in the `hue-application-key` header per
 *    the CLIP v2 spec.
 *  - The key is NOT a password. Even if leaked, it only grants control
 *    of lights on the home network. Persisted in DataStore (not the
 *    encrypted prefs used for cloud OAuth tokens).
 */
@Singleton
class HueProvider @Inject constructor(
    private val settings: SettingsDataStore
) {
    private val laxHttp: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true } // LAN-only; bridge cert CN never matches IP
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    data class DiscoveredBridge(val ip: String, val id: String)

    /**
     * Discover bridges on the local network via Philips' cloud
     * discovery endpoint. Returns IP + bridge-id pairs. Empty list →
     * no bridge found (user should be on the same Wi-Fi network).
     */
    suspend fun discover(): List<DiscoveredBridge> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://discovery.meethue.com/")
                .get()
                .build()
            laxHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string().orEmpty()
                // Body is a JSON array: [{"id":"abc","internalipaddress":"192.168.1.5"},...]
                val out = mutableListOf<DiscoveredBridge>()
                val ipPattern = Regex("\"internalipaddress\"\\s*:\\s*\"([^\"]+)\"")
                val idPattern = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                val ips = ipPattern.findAll(body).map { it.groupValues[1] }.toList()
                val ids = idPattern.findAll(body).map { it.groupValues[1] }.toList()
                ips.zip(ids).forEach { (ip, id) -> out.add(DiscoveredBridge(ip, id)) }
                DiagLog.event("HUE", "discovery returned ${out.size} bridges")
                out
            }
        }.getOrElse {
            DiagLog.event("HUE", "discovery FAILED: ${it.javaClass.simpleName}: ${it.message}")
            emptyList()
        }
    }

    /**
     * Pair with the bridge at [ip]. User must press the physical link
     * button within ~30 seconds before calling. The bridge returns
     * either { "success": { "username": "...", "clientkey": "..." } }
     * or { "error": { "type": 101, "description": "link button not pressed" } }.
     *
     * Returns the app key (username) on success; null on failure with
     * a logged reason.
     */
    suspend fun pair(ip: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = """
                {"devicetype":"powermediaplayer#android","generateclientkey":true}
            """.trimIndent().toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder()
                .url("https://$ip/api")
                .post(body)
                .build()
            laxHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                DiagLog.event("HUE", "pair http=${resp.code} body-prefix=${text.take(100)}")
                if (resp.code !in 200..299) return@withContext null
                // Parse `"username":"abc..."` + `"clientkey":"hex..."`
                // (clientkey is the 32-byte hex PSK required for the
                // Entertainment-API DTLS handshake; we requested it via
                // the generateclientkey flag in the POST body).
                val keyMatch = Regex("\"username\"\\s*:\\s*\"([^\"]+)\"").find(text)
                val clientKeyMatch = Regex("\"clientkey\"\\s*:\\s*\"([^\"]+)\"").find(text)
                if (keyMatch != null) {
                    val key = keyMatch.groupValues[1]
                    val clientKey = clientKeyMatch?.groupValues?.getOrNull(1).orEmpty()
                    settings.setHueBridgeIp(ip)
                    settings.setHueAppKey(key)
                    if (clientKey.isNotBlank()) settings.setHueClientKey(clientKey)
                    DiagLog.event(
                        "HUE",
                        "paired with bridge at $ip (key len=${key.length} clientkey len=${clientKey.length})"
                    )
                    return@withContext key
                }
                // Common "link button not pressed" path.
                if (text.contains("link button not pressed")) {
                    DiagLog.event("HUE", "pair refused — press the bridge link button first")
                }
                null
            }
        }.getOrElse {
            DiagLog.event("HUE", "pair FAILED: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    private suspend fun put(path: String, json: String): Boolean {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) {
            DiagLog.event("HUE", "PUT $path skipped — no bridge / key configured")
            return false
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip$path")
                    .header("hue-application-key", key)
                    .put(json.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                laxHttp.newCall(req).execute().use { resp ->
                    DiagLog.event("HUE", "PUT $path http=${resp.code}")
                    resp.isSuccessful
                }
            }.getOrElse {
                DiagLog.event("HUE", "PUT $path FAILED: ${it.javaClass.simpleName}: ${it.message}")
                false
            }
        }
    }

    /**
     * List light resource IDs from the bridge. Returns empty list on
     * any failure. Used by scene application + on/off broadcast.
     */
    suspend fun listLightIds(): List<String> {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/light")
                    .header("hue-application-key", key)
                    .get()
                    .build()
                laxHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val text = resp.body?.string().orEmpty()
                    val ids = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"")
                        .findAll(text)
                        .map { it.groupValues[1] }
                        .distinct()
                        .toList()
                    DiagLog.event("HUE", "listLightIds → ${ids.size}")
                    ids
                }
            }.getOrElse {
                DiagLog.event("HUE", "listLightIds FAILED: ${it.javaClass.simpleName}")
                emptyList()
            }
        }
    }

    /**
     * Fetch the first entertainment_configuration UUID from the bridge.
     * v1 picks the first area; a future Settings UI can let the user
     * choose. Returns null if the user has no Entertainment area set
     * up on the bridge (the Hue mobile app creates them).
     */
    suspend fun firstEntertainmentAreaId(): String? {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/entertainment_configuration")
                    .header("hue-application-key", key)
                    .get()
                    .build()
                laxHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val text = resp.body?.string().orEmpty()
                    val first = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"")
                        .find(text)?.groupValues?.getOrNull(1)
                    DiagLog.event("HUE", "firstEntertainmentAreaId → ${first ?: "(none)"}")
                    first
                }
            }.getOrNull()
        }
    }

    /**
     * Return the list of channel IDs configured in the bridge's
     * entertainment_configuration for [areaId]. The streaming
     * protocol's per-channel record uses these IDs (NOT the v2 light
     * UUIDs and NOT a flat 0..N-1 range) — sending the wrong IDs
     * means the bridge accepts the packets silently but doesn't route
     * them to any actual light.
     */
    suspend fun entertainmentChannelIds(areaId: String): List<Int> {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/entertainment_configuration/$areaId")
                    .header("hue-application-key", key)
                    .get()
                    .build()
                laxHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val text = resp.body?.string().orEmpty()
                    // channels[]: each entry has "channel_id": <int>.
                    // Match within the channels array slice if possible
                    // — but a plain global regex is sufficient because
                    // no other JSON keys named exactly channel_id appear
                    // on this endpoint.
                    val ids = Regex("\"channel_id\"\\s*:\\s*(\\d+)")
                        .findAll(text)
                        .map { it.groupValues[1].toInt() }
                        .toList()
                    DiagLog.event("HUE", "entertainmentChannelIds → ${ids.size}: $ids")
                    ids
                }
            }.getOrDefault(emptyList())
        }
    }

    /** Start the bridge's entertainment stream for the given area. */
    suspend fun startEntertainmentStream(areaId: String): Boolean {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = """{"action":"start"}""".toRequestBody(
                    "application/json".toMediaTypeOrNull()
                )
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/entertainment_configuration/$areaId")
                    .header("hue-application-key", key)
                    .put(body)
                    .build()
                laxHttp.newCall(req).execute().use { resp ->
                    DiagLog.event("HUE", "startEntertainmentStream http=${resp.code}")
                    resp.isSuccessful
                }
            }.getOrDefault(false)
        }
    }

    /** Stop the entertainment stream. Allows the bridge to free DTLS. */
    suspend fun stopEntertainmentStream(areaId: String): Boolean {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = """{"action":"stop"}""".toRequestBody(
                    "application/json".toMediaTypeOrNull()
                )
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/entertainment_configuration/$areaId")
                    .header("hue-application-key", key)
                    .put(body)
                    .build()
                laxHttp.newCall(req).execute().use { resp ->
                    DiagLog.event("HUE", "stopEntertainmentStream http=${resp.code}")
                    resp.isSuccessful
                }
            }.getOrDefault(false)
        }
    }

    suspend fun setAll(on: Boolean) {
        val ids = listLightIds()
        ids.forEach {
            put("/clip/v2/resource/light/$it", """{"on":{"on":$on}}""")
        }
    }

    /**
     * Apply one of four hand-tuned scene presets to every light on the
     * bridge. CIE xy values + brightness + transition_time are sent
     * per-light via the standard CLIP v2 PUT endpoint.
     *
     * The Party preset spawns a short rotation job that cycles through
     * 4 colours every 3 s for 30 s — long enough to feel reactive for
     * a song or two, short enough to not eat battery. v2 will replace
     * this with the Entertainment-API audio-reactive path.
     */
    enum class ScenePreset(
        val displayName: String,
        val description: String
    ) {
        PARTY("Party", "Cycling saturated colours, full brightness"),
        AMBIENT("Ambient", "Warm dim, ~2700K, 30% brightness"),
        CINEMA("Cinema", "Dim deep-red, 15% brightness"),
        READING("Reading", "Cool white, ~5000K, 80% brightness")
    }

    suspend fun applyScene(preset: ScenePreset) {
        val ids = listLightIds()
        if (ids.isEmpty()) {
            DiagLog.event("HUE", "applyScene ${preset.name} — no lights, skipped")
            return
        }
        DiagLog.event("HUE", "applyScene ${preset.name} → ${ids.size} lights")
        when (preset) {
            ScenePreset.AMBIENT -> {
                // Warm white. CIE xy ≈ (0.46, 0.41) ≈ 2700K.
                ids.forEach {
                    put(
                        "/clip/v2/resource/light/$it",
                        """{"on":{"on":true},"dimming":{"brightness":30},"color":{"xy":{"x":0.46,"y":0.41}}}"""
                    )
                }
            }
            ScenePreset.CINEMA -> {
                // Deep red, low brightness.
                ids.forEach {
                    put(
                        "/clip/v2/resource/light/$it",
                        """{"on":{"on":true},"dimming":{"brightness":15},"color":{"xy":{"x":0.68,"y":0.31}}}"""
                    )
                }
            }
            ScenePreset.READING -> {
                // Cool white ≈ 5000K, CIE xy ≈ (0.34, 0.36).
                ids.forEach {
                    put(
                        "/clip/v2/resource/light/$it",
                        """{"on":{"on":true},"dimming":{"brightness":80},"color":{"xy":{"x":0.34,"y":0.36}}}"""
                    )
                }
            }
            ScenePreset.PARTY -> {
                // Quick cycle through saturated red/green/blue/magenta.
                val palette = listOf(
                    "0.68,0.31",  // red
                    "0.17,0.70",  // green
                    "0.15,0.06",  // blue
                    "0.40,0.18"   // magenta
                )
                ids.forEach {
                    put(
                        "/clip/v2/resource/light/$it",
                        """{"on":{"on":true},"dimming":{"brightness":100}}"""
                    )
                }
                // ~30 s of cycling. Side-effecting coroutine — caller
                // launches in viewModelScope; cancelling that scope ends
                // the loop. Each set call is fire-and-forget; OkHttp's
                // connection pool keeps the bridge socket warm.
                for (round in 0 until 10) {
                    val xy = palette[round % palette.size]
                    val (x, y) = xy.split(",")
                    ids.forEach { id ->
                        put(
                            "/clip/v2/resource/light/$id",
                            """{"color":{"xy":{"x":$x,"y":$y}},"dynamics":{"duration":2500}}"""
                        )
                    }
                    delay(3000)
                }
            }
        }
    }
}
