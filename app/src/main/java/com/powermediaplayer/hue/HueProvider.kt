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

    // ─── Area + capability model (vc29) ──────────────────────────────

    /** Capability tier for an individual light, derived from which
     *  optional v2 service fields are present on /clip/v2/resource/light. */
    enum class LightCapability { COLOUR, AMBIANCE, DIMMABLE, ONOFF }

    /** Per-light view used by the reactive engine to decide control path. */
    data class LightInfo(
        val id: String,
        val name: String,
        val ownerDeviceId: String?,
        val capability: LightCapability
    )

    /** A user-pickable target — a room, a zone, or an existing
     *  entertainment area. Lights are looked up via the provider's
     *  cached light list so the picker doesn't N+1 the bridge. */
    data class HueArea(
        val id: String,
        val kind: Kind,
        val name: String,
        val lightIds: List<String>
    ) {
        enum class Kind { ROOM, ZONE, ENTERTAINMENT }
    }

    /**
     * Per-bulb command-to-photon latency profile, used by the dimmable
     * driver to time CLIP-REST PUTs so each bulb's brightness change
     * lands in sync with what's hitting the speaker. The latency
     * numbers below are empirical buckets — Philips publishes no
     * official latency table for CLIP REST. They are conservative
     * estimates per manufacturer / generation that we cross-checked
     * against zigbee2mqtt's per-bulb response notes.
     */
    data class BulbLatencyProfile(
        val manufacturer: String,
        val productName: String,
        val latencyMs: Int
    )

    /**
     * Map a bulb's `manufacturer_name` + `product_name` + `model_id`
     * to a typical command-to-photon latency in ms. Buckets keep the
     * surface small while covering the bulbs people actually own.
     * Default-unknown bucket is 400 ms — conservative; better to be
     * slightly late than slightly early because early-firing lights
     * look more visibly wrong than late-firing.
     */
    private fun latencyForBulb(
        manufacturer: String,
        productName: String,
        modelId: String
    ): Int {
        val mfr = manufacturer.lowercase()
        val pn = productName.lowercase()
        val mid = modelId.uppercase()
        // ── Philips / Signify (Hue) — generation-aware ──────────────
        if ("signify" in mfr || "philips" in mfr || "hue" in pn) {
            // Pre-2018 first-generation bulbs were noticeably slower.
            if (mid in setOf("LCT001", "LCT002", "LCT003", "LWB004") ||
                mid.startsWith("LLC")
            ) return 280
            // Gen3+ colour (LCT/LCA/LCG/LCB/LCL/LCS/LCX/LCP families).
            if (mid.startsWith("LCT") || mid.startsWith("LCA") ||
                mid.startsWith("LCG") || mid.startsWith("LCB") ||
                mid.startsWith("LCL") || mid.startsWith("LCS") ||
                mid.startsWith("LCX") || mid.startsWith("LCP")
            ) return 180
            // White / White Ambiance native dimmables.
            if (mid.startsWith("LWB") || mid.startsWith("LWA") ||
                mid.startsWith("LWE") || mid.startsWith("LWO") ||
                mid.startsWith("LTW") || mid.startsWith("LTA") ||
                mid.startsWith("LTG") || mid.startsWith("LTC")
            ) return 200
            return 200 // default Hue
        }
        // ── IKEA TRÅDFRI / STARKVIND / etc. ─────────────────────────
        if ("ikea" in mfr) return 430
        // ── Innr ─────────────────────────────────────────────────────
        if ("innr" in mfr) return 350
        // ── Sengled ─────────────────────────────────────────────────
        if ("sengled" in mfr) return 380
        // ── GLEDOPTO ────────────────────────────────────────────────
        if ("gledopto" in mfr) return 350
        // ── OSRAM / Ledvance / Sylvania (older Lightify slower) ─────
        if ("osram" in mfr || "ledvance" in mfr || "sylvania" in mfr) return 400
        // ── Eve ─────────────────────────────────────────────────────
        if ("eve" in mfr) return 250
        // ── Aqara / LUMI ────────────────────────────────────────────
        if ("lumi" in mfr || "aqara" in mfr) return 350
        // ── Tuya / AliExpress generic (manufacturer = _TZ* or blank) ─
        if (manufacturer.startsWith("_T") || manufacturer.isBlank()) return 450
        // Unknown brand → conservative.
        return 400
    }

    /** Cap-tier breakdown for an area, surfaced in the Settings UI. */
    data class AreaCapabilityBreakdown(
        val colour: List<LightInfo>,
        val ambiance: List<LightInfo>,
        val dimmable: List<LightInfo>,
        val onoff: List<LightInfo>
    ) {
        val total: Int get() = colour.size + ambiance.size + dimmable.size + onoff.size
        val entertainmentEligible: List<LightInfo> get() = colour + ambiance
    }

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
     * One-shot fetch of every light on the bridge with its capability
     * tier resolved from `color` / `color_temperature` / `dimming`
     * fields. Cached per provider instance but re-fetched on demand
     * (Hue light counts change rarely).
     */
    suspend fun listAllLights(): Map<String, LightInfo> {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return emptyMap()
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/light")
                    .header("hue-application-key", key).get().build()
                laxHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyMap()
                    val text = resp.body?.string().orEmpty()
                    parseLightList(text).also {
                        DiagLog.event("HUE", "listAllLights → ${it.size}")
                    }
                }
            }.getOrDefault(emptyMap())
        }
    }

    /**
     * Parse the light list response into LightInfo records. We rely on
     * grep-style regex rather than a full JSON parser to stay
     * dependency-free + match the rest of HueProvider's style. Per-light
     * blocks are split at `"type":"light"` boundaries.
     */
    private fun parseLightList(json: String): Map<String, LightInfo> {
        // Split on each light object boundary. Each object roughly
        // spans from "id": to the next "id": or end.
        val out = mutableMapOf<String, LightInfo>()
        // Find every {"id":"<uuid>" ... "type":"light"} block.
        // Cheaper than full JSON: identify each top-level light by the
        // unique 36-char UUID pattern that immediately precedes the
        // "id_v1" field.
        // NB: bridge light objects embed nested {} blocks (`owner`,
        // `metadata`, `on`, `dimming`, ...) between `id` and `type`, so
        // `[^{]*?` would never match. Use lazy `.*?` under
        // DOT_MATCHES_ALL — the unique `"type":"light"` substring still
        // terminates each block before the next light starts.
        val blockRegex = Regex(
            """\{"id":"([0-9a-f-]{36})".*?"type":"light"""",
            RegexOption.DOT_MATCHES_ALL
        )
        // Find each light's id position and slice between consecutive
        // matches. Using indexOf walks once.
        val starts = blockRegex.findAll(json).map { it.range.first }.toList()
        for ((idx, start) in starts.withIndex()) {
            val end = if (idx + 1 < starts.size) starts[idx + 1] else json.length
            val slice = json.substring(start, end)
            val id = Regex("""^\{"id":"([0-9a-f-]{36})"""").find(slice)
                ?.groupValues?.getOrNull(1) ?: continue
            val name = Regex(""""metadata":\{"name":"([^"]+)"""")
                .find(slice)?.groupValues?.getOrNull(1) ?: "light"
            val ownerDevice = Regex(""""owner":\{"rid":"([0-9a-f-]{36})","rtype":"device"""")
                .find(slice)?.groupValues?.getOrNull(1)
            val hasColor = slice.contains(""""color":{""")
            val hasColorTemp = slice.contains(""""color_temperature":{""")
            val hasDimming = slice.contains(""""dimming":{""")
            val cap = when {
                hasColor -> LightCapability.COLOUR
                hasColorTemp -> LightCapability.AMBIANCE
                hasDimming -> LightCapability.DIMMABLE
                else -> LightCapability.ONOFF
            }
            out[id] = LightInfo(id, name, ownerDevice, cap)
        }
        return out
    }

    /**
     * Combined list of every user-pickable area on the bridge:
     * rooms + zones + entertainment areas. Each entry knows its
     * light IDs so the UI can show a capability breakdown without
     * another roundtrip.
     */
    suspend fun listAreas(): List<HueArea> {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val lights = listAllLights()
            // Build device → lightIds map. Rooms enumerate devices,
            // not lights directly; this gives us the bridge from one
            // to the other without per-room queries.
            val deviceToLights = lights.values
                .filter { it.ownerDeviceId != null }
                .groupBy({ it.ownerDeviceId!! }, { it.id })
            val out = mutableListOf<HueArea>()
            // Rooms.
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/room")
                    .header("hue-application-key", key).get().build()
                laxHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching
                    val text = resp.body?.string().orEmpty()
                    val blocks = Regex(
                        """\{"id":"([0-9a-f-]{36})".*?"type":"room"""",
                        RegexOption.DOT_MATCHES_ALL
                    ).findAll(text).map { it.range.first }.toList()
                    for ((idx, s) in blocks.withIndex()) {
                        val e = if (idx + 1 < blocks.size) blocks[idx + 1] else text.length
                        val slice = text.substring(s, e)
                        val id = Regex("""^\{"id":"([0-9a-f-]{36})"""")
                            .find(slice)?.groupValues?.getOrNull(1) ?: continue
                        val name = Regex(""""metadata":\{"name":"([^"]+)"""")
                            .find(slice)?.groupValues?.getOrNull(1) ?: "Room"
                        val deviceIds = Regex(
                            """"children":\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL
                        ).find(slice)?.groupValues?.getOrNull(1)?.let { ch ->
                            Regex(""""rid":"([0-9a-f-]{36})","rtype":"device"""")
                                .findAll(ch).map { it.groupValues[1] }.toList()
                        }.orEmpty()
                        val lightsHere = deviceIds.flatMap { d -> deviceToLights[d].orEmpty() }
                        out.add(HueArea(id, HueArea.Kind.ROOM, name, lightsHere))
                    }
                }
            }
            // Zones.
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/zone")
                    .header("hue-application-key", key).get().build()
                laxHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching
                    val text = resp.body?.string().orEmpty()
                    val blocks = Regex(
                        """\{"id":"([0-9a-f-]{36})".*?"type":"zone"""",
                        RegexOption.DOT_MATCHES_ALL
                    ).findAll(text).map { it.range.first }.toList()
                    for ((idx, s) in blocks.withIndex()) {
                        val e = if (idx + 1 < blocks.size) blocks[idx + 1] else text.length
                        val slice = text.substring(s, e)
                        val id = Regex("""^\{"id":"([0-9a-f-]{36})"""")
                            .find(slice)?.groupValues?.getOrNull(1) ?: continue
                        val name = Regex(""""metadata":\{"name":"([^"]+)"""")
                            .find(slice)?.groupValues?.getOrNull(1) ?: "Zone"
                        // Zones have light rids directly.
                        val lightIds = Regex(
                            """"rid":"([0-9a-f-]{36})","rtype":"light""""
                        ).findAll(slice).map { it.groupValues[1] }.toList()
                        out.add(HueArea(id, HueArea.Kind.ZONE, name, lightIds))
                    }
                }
            }
            // Entertainment areas (user-created ones survive — useful so
            // we don't auto-recreate when the user already has e.g. a
            // "Living Area" entertainment area).
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/entertainment_configuration")
                    .header("hue-application-key", key).get().build()
                laxHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching
                    val text = resp.body?.string().orEmpty()
                    val blocks = Regex(
                        """\{"id":"([0-9a-f-]{36})".*?"type":"entertainment_configuration"""",
                        RegexOption.DOT_MATCHES_ALL
                    ).findAll(text).map { it.range.first }.toList()
                    for ((idx, s) in blocks.withIndex()) {
                        val e = if (idx + 1 < blocks.size) blocks[idx + 1] else text.length
                        val slice = text.substring(s, e)
                        val id = Regex("""^\{"id":"([0-9a-f-]{36})"""")
                            .find(slice)?.groupValues?.getOrNull(1) ?: continue
                        val name = Regex(""""metadata":\{"name":"([^"]+)"""")
                            .find(slice)?.groupValues?.getOrNull(1) ?: "Entertainment"
                        // light_services[] in entertainment area gives lights directly.
                        val lightIds = Regex(
                            """"light_services":\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL
                        ).find(slice)?.groupValues?.getOrNull(1)?.let { ls ->
                            Regex(""""rid":"([0-9a-f-]{36})","rtype":"light"""")
                                .findAll(ls).map { it.groupValues[1] }.toList()
                        }.orEmpty()
                        out.add(HueArea(id, HueArea.Kind.ENTERTAINMENT, name, lightIds))
                    }
                }
            }
            DiagLog.event("HUE", "listAreas → ${out.size} (rooms+zones+entertainment)")
            out
        }
    }

    /** Bucket [area]'s lights into capability tiers using [lights] map. */
    fun classifyArea(area: HueArea, lights: Map<String, LightInfo>): AreaCapabilityBreakdown {
        val colour = mutableListOf<LightInfo>()
        val ambiance = mutableListOf<LightInfo>()
        val dimmable = mutableListOf<LightInfo>()
        val onoff = mutableListOf<LightInfo>()
        for (id in area.lightIds) {
            val li = lights[id] ?: continue
            when (li.capability) {
                LightCapability.COLOUR -> colour.add(li)
                LightCapability.AMBIANCE -> ambiance.add(li)
                LightCapability.DIMMABLE -> dimmable.add(li)
                LightCapability.ONOFF -> onoff.add(li)
            }
        }
        return AreaCapabilityBreakdown(colour, ambiance, dimmable, onoff)
    }

    /**
     * Fetch device-level metadata from the bridge and produce a per-
     * light latency profile keyed by light id. Used by
     * [HueDimmableDriver] to time each bulb's PCM read so a fast Hue
     * native bulb fires later than a slow IKEA TRÅDFRI for the same
     * audio moment, keeping mixed-brand rooms in sync.
     *
     * Returns an empty map on any failure — the driver falls back to
     * a default latency, never blocks playback start.
     */
    suspend fun fetchBulbLatencyProfiles(lightIds: List<String>): Map<String, BulbLatencyProfile> {
        if (lightIds.isEmpty()) return emptyMap()
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return emptyMap()
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/device")
                    .header("hue-application-key", key).get().build()
                laxHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyMap()
                    val text = resp.body?.string().orEmpty()
                    // Per-device blocks. Same lazy .*? pattern the
                    // light/room/zone parsers use, anchored on the
                    // unique terminating "type":"device" string.
                    val deviceMeta = mutableMapOf<String, Triple<String, String, String>>()
                    val blockStarts = Regex(
                        """\{"id":"([0-9a-f-]{36})".*?"type":"device"""",
                        RegexOption.DOT_MATCHES_ALL
                    ).findAll(text).map { it.range.first }.toList()
                    for ((idx, s) in blockStarts.withIndex()) {
                        val e = if (idx + 1 < blockStarts.size) blockStarts[idx + 1] else text.length
                        val slice = text.substring(s, e)
                        val id = Regex("""^\{"id":"([0-9a-f-]{36})"""")
                            .find(slice)?.groupValues?.getOrNull(1) ?: continue
                        // product_data block is nested — scope the
                        // field lookups to the chars after it so we
                        // don't accidentally read a sibling block.
                        val pdStart = slice.indexOf("\"product_data\"")
                        val pdSlice = if (pdStart >= 0) slice.substring(pdStart) else slice
                        val mfr = Regex(""""manufacturer_name":"([^"]+)"""")
                            .find(pdSlice)?.groupValues?.getOrNull(1).orEmpty()
                        val productName = Regex(""""product_name":"([^"]+)"""")
                            .find(pdSlice)?.groupValues?.getOrNull(1).orEmpty()
                        val modelId = Regex(""""model_id":"([^"]+)"""")
                            .find(pdSlice)?.groupValues?.getOrNull(1).orEmpty()
                        deviceMeta[id] = Triple(mfr, productName, modelId)
                    }
                    // Cross-reference with the light list to map
                    // lightId → owner device → (mfr, product, model)
                    // → latency.
                    val lights = listAllLights()
                    val out = mutableMapOf<String, BulbLatencyProfile>()
                    val tally = mutableMapOf<String, Int>()
                    for (lid in lightIds) {
                        val deviceId = lights[lid]?.ownerDeviceId ?: continue
                        val meta = deviceMeta[deviceId] ?: continue
                        val (mfr, productName, modelId) = meta
                        val latency = latencyForBulb(mfr, productName, modelId)
                        out[lid] = BulbLatencyProfile(mfr, productName, latency)
                        val brand = when {
                            "signify" in mfr.lowercase() ||
                                "philips" in mfr.lowercase() -> "hue"
                            "ikea" in mfr.lowercase() -> "ikea"
                            else -> "other"
                        }
                        tally[brand] = (tally[brand] ?: 0) + 1
                    }
                    DiagLog.event(
                        "HUE",
                        "fetchBulbLatencyProfiles → ${out.size} (" +
                            tally.entries.joinToString { "${it.key}=${it.value}" } + ")"
                    )
                    out
                }
            }.getOrDefault(emptyMap())
        }
    }

    /**
     * Map from a light's owner device id → that device's entertainment
     * service id. Needed when creating an entertainment_configuration
     * because channels[].members reference entertainment service IDs,
     * not light IDs. Lights without an entertainment service (e.g.
     * white-only / smart-plug) are absent from the map.
     */
    suspend fun deviceEntertainmentServices(): Map<String, String> {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return emptyMap()
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://$ip/clip/v2/resource/entertainment")
                    .header("hue-application-key", key).get().build()
                laxHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyMap()
                    val text = resp.body?.string().orEmpty()
                    val out = mutableMapOf<String, String>()
                    // "type":"entertainment" (with the trailing quote)
                    // does not collide with "type":"entertainment_configuration"
                    // because the latter has `_` after `entertainment`,
                    // not `"`.
                    val blocks = Regex(
                        """\{"id":"([0-9a-f-]{36})".*?"type":"entertainment"""",
                        RegexOption.DOT_MATCHES_ALL
                    ).findAll(text).map { it.range.first }.toList()
                    for ((idx, s) in blocks.withIndex()) {
                        val e = if (idx + 1 < blocks.size) blocks[idx + 1] else text.length
                        val slice = text.substring(s, e)
                        val id = Regex("""^\{"id":"([0-9a-f-]{36})"""")
                            .find(slice)?.groupValues?.getOrNull(1) ?: continue
                        val device = Regex(""""owner":\{"rid":"([0-9a-f-]{36})","rtype":"device"""")
                            .find(slice)?.groupValues?.getOrNull(1) ?: continue
                        out[device] = id
                    }
                    DiagLog.event("HUE", "deviceEntertainmentServices → ${out.size}")
                    out
                }
            }.getOrDefault(emptyMap())
        }
    }

    /**
     * Ensure an entertainment_configuration exists that wraps the
     * COLOUR + AMBIANCE lights of [area]. Strategy:
     *   1. If [area] is itself an entertainment area, return its id +
     *      already-set channel ids verbatim (so user-curated areas like
     *      the Hue app's "Living Area" stay authoritative).
     *   2. Otherwise look for an existing entertainment_configuration
     *      whose name is exactly the area's name OR is prefixed by
     *      "PMP_Reactive_<area-name>"; reuse if found.
     *   3. Otherwise create one via POST. Spatial positions are spread
     *      evenly around a unit circle at z = -1.
     * Returns the entertainment_configuration's id + its channel id
     * list (mirroring entertainmentChannelIds).
     */
    data class EnsuredArea(val areaId: String, val channelIds: List<Int>)

    suspend fun ensureEntertainmentConfigForArea(
        area: HueArea,
        breakdown: AreaCapabilityBreakdown
    ): EnsuredArea? {
        val ip = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (ip.isBlank() || key.isBlank()) return null
        return withContext(Dispatchers.IO) {
            // (1) entertainment-kind area — use as-is.
            if (area.kind == HueArea.Kind.ENTERTAINMENT) {
                val chs = entertainmentChannelIds(area.id)
                return@withContext EnsuredArea(area.id, chs)
            }
            // (2) try to reuse existing entertainment area by name.
            val existing = listAreas().firstOrNull {
                it.kind == HueArea.Kind.ENTERTAINMENT &&
                    (it.name == area.name ||
                        it.name == "PMP_Reactive_${area.name}")
            }
            if (existing != null) {
                val chs = entertainmentChannelIds(existing.id)
                DiagLog.event(
                    "HUE",
                    "ensureEntertainmentConfig — reusing existing area '${existing.name}'"
                )
                return@withContext EnsuredArea(existing.id, chs)
            }
            // (3) create one. Map each entertainment-eligible light to
            // its device's entertainment service.
            val eligible = breakdown.entertainmentEligible
            if (eligible.isEmpty()) {
                DiagLog.event(
                    "HUE",
                    "ensureEntertainmentConfig — area '${area.name}' has no colour/ambiance lights; cannot stream"
                )
                return@withContext null
            }
            val devToEntService = deviceEntertainmentServices()
            // Build the service_locations[] array. The Hue v2 bridge
            // INFERS channels from this list — POSTing a "channels"
            // field returns HTTP 400 "Property [channels] cannot be
            // specified for this request type". Spread positions evenly
            // around a unit circle at z = -1 (eye-level ring layout).
            val sb = StringBuilder("[")
            var serviceCount = 0
            for ((i, light) in eligible.withIndex()) {
                val device = light.ownerDeviceId ?: continue
                val entService = devToEntService[device] ?: continue
                val angle = 2.0 * Math.PI * i / eligible.size
                val x = "%.4f".format(kotlin.math.cos(angle).coerceIn(-1.0, 1.0))
                val y = "%.4f".format(kotlin.math.sin(angle).coerceIn(-1.0, 1.0))
                if (sb.length > 1) sb.append(',')
                sb.append(
                    """{"service":{"rid":"$entService","rtype":"entertainment"},"positions":[{"x":$x,"y":$y,"z":-1.0}]}"""
                )
                serviceCount++
            }
            sb.append("]")
            if (serviceCount == 0) {
                DiagLog.event(
                    "HUE",
                    "ensureEntertainmentConfig — area '${area.name}' eligible lights " +
                        "have no entertainment services on the bridge"
                )
                return@withContext null
            }
            val body = """
                {"type":"entertainment_configuration",
                 "metadata":{"name":"PMP_Reactive_${area.name}"},
                 "configuration_type":"music",
                 "locations":{"service_locations":$sb}}
            """.trimIndent()
            val req = Request.Builder()
                .url("https://$ip/clip/v2/resource/entertainment_configuration")
                .header("hue-application-key", key)
                .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            runCatching {
                laxHttp.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    DiagLog.event(
                        "HUE",
                        "ensureEntertainmentConfig POST http=${resp.code} " +
                            "for area='${area.name}' services=$serviceCount " +
                            "respPrefix=${text.take(200)}"
                    )
                    if (!resp.isSuccessful) return@withContext null
                    val newId = Regex(""""rid":"([0-9a-f-]{36})"""")
                        .find(text)?.groupValues?.getOrNull(1) ?: return@withContext null
                    // Bridge auto-assigns channel ids — fetch them back
                    // by GETing the new entertainment_configuration.
                    val chs = entertainmentChannelIds(newId)
                    EnsuredArea(newId, chs)
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
