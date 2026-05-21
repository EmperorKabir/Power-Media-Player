package com.powermediaplayer.hue

import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsPSKIdentity
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Hue Entertainment-API audio-reactive lighting — proper version.
 *
 * Pipeline per 40 ms frame (25 Hz):
 *   FFT byte buffer (1024 bins) → [HueAudioAnalyser]
 *   → 6 band levels + beat flag + beat strength + BPM + dynamics envelope
 *   → per-light routing (each light is mapped to one of the 6 bands)
 *   → CIE xy + brightness encoded per channel
 *   → DTLS-PSK UDP packet to bridge:2100
 *
 * Per-light band routing:
 *   The N detected lights are striped across the 6 bands. Light 0 →
 *   sub-bass, 1 → bass, 2 → low-mid, 3 → mid, 4 → high-mid, 5 → treble,
 *   then wraps. So with 12 lights you get 2 lights per band; with 6 each
 *   light has its own band. Result: bass kicks drive the lights you
 *   mapped to reds; treble runs cool blues; the music's spatial spectrum
 *   becomes the room's spatial spectrum.
 *
 * Single "intensity" knob (0..100) replaces the v1 mode picker:
 *   0    → off (loop exits, DTLS closes)
 *   1-50 → "subtle" — narrow brightness swing, no beat flashes
 *   51-90→ "active" — full band routing, beat flashes on
 *   91-100→ "vivid" — boosted contrast + faster palette rotation
 *
 * Privacy / safety:
 *   - LAN-only DTLS. Self-signed bridge cert accepted (LAN IoT norm).
 *   - No microphone permission required — audio is read from our own
 *     ExoPlayer AudioProcessor chain via [HueAnalyserAudioProcessor],
 *     not Android's audio-capture path.
 *   - Hue bridge enforces ~10 lights max per Entertainment area for
 *     latency reasons; we cap channels accordingly.
 */
@Singleton
class HueEntertainment @Inject constructor(
    private val settings: SettingsDataStore,
    private val hueProvider: HueProvider,
    /**
     * Same Hilt @Singleton as the one PlaybackService injects into the
     * AudioProcessor chain. We READ the latest analyser snapshot from
     * it at 25 Hz; PCM samples are fed in from the audio thread by
     * Media3's processor pipeline. No microphone permission needed.
     */
    private val analyserProcessor: HueAnalyserAudioProcessor,
    private val dimmableDriver: HueDimmableDriver
) {

    /** Streaming mode picked per-area based on colour-light count. */
    enum class Mode {
        /** ≥3 COLOUR lights: each light maps to one of the 6 audio
         *  bands; bass → reds, mid → greens, treble → blues, with a
         *  per-light palette offset spreading the colour wave around
         *  the room. */
        SPREAD,
        /** <3 COLOUR lights: all colour lights show the same XY+
         *  brightness at any moment, BPM-timed colour wave. Feels
         *  coherent across mixed-type areas. */
        COHERENT
    }

    /** Legacy v1 enum, retained because PlaybackService still references
     *  it; mapped onto the new intensity-only model so call sites don't
     *  break. OFF → intensity 0; everything else → intensity 75. */
    enum class ReactiveMode(val key: String) {
        OFF("off"),
        BASS_FLASH("on"),       // legacy alias
        SPECTRUM("on"),         // legacy alias
        COLOUR_FOLLOW_TRACK("on") // legacy alias
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var streamJob: Job? = null
    @Volatile private var dtls: DTLSTransport? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var areaId: String = ""

    /**
     * Begin streaming the audio-reactive lighting against [ensured].
     * - [ensured] is the entertainment_configuration to stream into,
     *   plus the channel ids that make up its colour/ambiance set
     *   (HueProvider.ensureEntertainmentConfigForArea).
     * - [colourCount] tells us whether to use SPREAD or COHERENT mode.
     * - [dimmableLightIds] are white-only lights driven in parallel by
     *   HueDimmableDriver at ≤10 Hz per light. ONOFF smart plugs are
     *   excluded by the caller.
     * - [intensity] 0..100; 0 stops any current stream.
     */
    fun start(
        ensured: HueProvider.EnsuredArea,
        colourCount: Int,
        dimmableLightIds: List<String>,
        intensity: Int
    ) {
        if (intensity <= 0) {
            stop()
            return
        }
        if (streamJob?.isActive == true) {
            DiagLog.event("HUE", "entertainment.start ignored — already streaming")
            return
        }
        streamJob = scope.launch {
            val ip = settings.hueBridgeIp.first()
            val appKey = settings.hueAppKey.first()
            val clientKey = settings.hueClientKey.first()
            if (ip.isBlank() || appKey.isBlank() || clientKey.isBlank()) {
                DiagLog.event("HUE", "entertainment.start — missing creds")
                return@launch
            }
            val area = ensured.areaId
            val lightChannels = ensured.channelIds
            if (lightChannels.isEmpty()) {
                DiagLog.event(
                    "HUE",
                    "entertainment.start — ensured area has 0 channels; can't stream"
                )
                return@launch
            }
            areaId = area
            settings.setHueEntertainmentId(area)
            val started = hueProvider.startEntertainmentStream(area)
            if (!started) {
                DiagLog.event("HUE", "entertainment.start — bridge refused streaming PUT")
                return@launch
            }
            val handshakeOk = runCatching { connectDtls(ip, appKey, hexDecode(clientKey)) }
                .onFailure {
                    DiagLog.event(
                        "HUE",
                        "DTLS handshake FAILED: ${it.javaClass.simpleName}: ${it.message}"
                    )
                }.getOrDefault(false)
            if (!handshakeOk) {
                hueProvider.stopEntertainmentStream(area)
                return@launch
            }
            // Mode decision: SPREAD when there are enough colour lights
            // to give each band a slot AND the user toggle agrees;
            // COHERENT otherwise. COHERENT looks more unified across
            // mixed-tier areas; SPREAD shows the spectrum spatially.
            val spreadToggle = runCatching { settings.hueSpreadBands.first() }.getOrDefault(true)
            val mode = if (colourCount >= 3 && spreadToggle) Mode.SPREAD else Mode.COHERENT
            DiagLog.event(
                "HUE",
                "entertainment.start RUNNING intensity=$intensity area=$area " +
                    "channels=${lightChannels.size} colour=$colourCount mode=${mode.name}"
            )
            // Parallel CLIP-REST driver for DIMMABLE lights (white-only,
            // no colour). Gated by the "drive dimmable" toggle; ONOFF
            // smart plugs are filtered out upstream in PlaybackService.
            val driveDim = runCatching { settings.hueDriveDimmable.first() }.getOrDefault(true)
            if (driveDim && dimmableLightIds.isNotEmpty()) {
                dimmableDriver.start(dimmableLightIds, intensity)
            }

            // ── Stream loop ──────────────────────────────────────────
            val intensityF = (intensity / 100f).coerceIn(0.01f, 1f)
            val frameMs = 40L  // 25 Hz
            // Palette cycle phase (radians); BPM-driven rotation rate.
            var palettePhase = 0.0
            // 8-stop palette in CIE xy (red / orange / yellow / green /
            // cyan / blue / magenta / pink), tuned for vivid Hue output.
            val palette = arrayOf(
                floatArrayOf(0.68f, 0.31f),  // red
                floatArrayOf(0.59f, 0.39f),  // orange
                floatArrayOf(0.46f, 0.51f),  // yellow-green
                floatArrayOf(0.30f, 0.55f),  // green
                floatArrayOf(0.21f, 0.30f),  // cyan
                floatArrayOf(0.15f, 0.06f),  // blue
                floatArrayOf(0.32f, 0.10f),  // magenta
                floatArrayOf(0.48f, 0.22f)   // pink
            )
            val frameBuf = ByteArray(headerSize + lightChannels.size * channelRecordSize)
            writeHeader(frameBuf, area)
            var frameCount = 0L
            var seqId = 0
            while (isActive) {
                // Increment the sequence ID byte (rolls over at 255).
                // Some bridge firmware checks for monotonic sequencing
                // and drops duplicate packets — keeping it constant
                // would let the bridge legitimately ignore us as a
                // stuck transmitter.
                frameBuf[11] = seqId.toByte()
                seqId = (seqId + 1) and 0xFF
                val now = android.os.SystemClock.uptimeMillis()
                // Effective offset auto-sums the three legs that all
                // shift audio time relative to the PCM tap:
                //   hueSyncOffsetMs        — user-tunable headroom for
                //                            speaker buffer + chain latency
                //   audioDelayMs           — user slider on the audio chain
                //   btVideoAudioOffsetMs   — BT lip-sync compensation
                // If we only used hueSyncOffsetMs the user would have
                // to re-tune Hue every time they changed BT or audio
                // delay; auto-summing keeps the Hue slider stable
                // across other offsets.
                val hueOffset = runCatching { settings.hueSyncOffsetMs.first() }.getOrDefault(200)
                val audioDelay = runCatching { settings.audioDelayMs.first() }.getOrDefault(0)
                val btOffset = runCatching { settings.btVideoAudioOffsetMs.first() }.getOrDefault(0)
                val syncOffsetMs = (hueOffset + audioDelay + btOffset).coerceAtLeast(0)
                val r = analyserProcessor.getSnapshotAt(syncOffsetMs)
                // Advance palette by BPM-driven Hz.
                palettePhase += (r.paletteHz * frameMs / 1000.0) * (2 * Math.PI)
                if (palettePhase > 2 * Math.PI) palettePhase -= 2 * Math.PI

                // Beat brightness pulse — short window after onset.
                val beatBoost = if (r.beat) r.beatStrength else 0f

                var off = headerSize
                // COHERENT-mode pre-computed globals (same across lights
                // in this frame; only positional palette offset shifts).
                val coherentPalIdx = if (mode == Mode.COHERENT) {
                    val phase = palettePhase.rem(2 * Math.PI)
                    ((phase / (2 * Math.PI)) * palette.size).toInt()
                        .coerceIn(0, palette.size - 1)
                } else 0
                val coherentBandAvg = if (mode == Mode.COHERENT) {
                    // Bass-weighted RMS: low bands matter most for the
                    // perceived energy. Result roughly in [0..1].
                    (r.bands[0] * 0.50f + r.bands[1] * 0.80f +
                        r.bands[2] * 0.40f + r.bands[3] * 0.30f +
                        r.bands[4] * 0.20f + r.bands[5] * 0.15f) / 2.35f
                } else 0f
                for ((i, ch) in lightChannels.withIndex()) {
                    val bandLevel: Float
                    val palIdx: Int
                    if (mode == Mode.SPREAD) {
                        bandLevel = r.bands[i % 6]
                        // Per-light palette offset — spread colour
                        // around the room so different lights show
                        // different colours simultaneously.
                        val perLightOffset = (i.toDouble() / lightChannels.size) * (2 * Math.PI)
                        val phase = (palettePhase + perLightOffset).rem(2 * Math.PI)
                        palIdx = ((phase / (2 * Math.PI)) * palette.size).toInt()
                            .coerceIn(0, palette.size - 1)
                    } else {
                        // COHERENT — same colour across all lights.
                        bandLevel = coherentBandAvg
                        palIdx = coherentPalIdx
                    }
                    val xy = palette[palIdx]
                    // Brightness:
                    //  - baseline: 25 % so lights stay visibly on
                    //  - + band level × 50 % (continuous following)
                    //  - + beat boost × 25 % (transient flash)
                    //  scaled by user intensity.
                    val base = 0.25f
                    val dyn = bandLevel * 0.50f
                    val beat = beatBoost * 0.25f
                    val combined = ((base + dyn + beat) * intensityF).coerceIn(0f, 1f)
                    val xCie = (xy[0] * 65535).toInt().coerceIn(0, 65535)
                    val yCie = (xy[1] * 65535).toInt().coerceIn(0, 65535)
                    val bri = (combined * 65535).toInt().coerceIn(0, 65535)

                    // v2 record: 1-byte channel id + 3 u16 BE values
                    frameBuf[off + 0] = ch.toByte()
                    frameBuf[off + 1] = (xCie ushr 8).toByte()
                    frameBuf[off + 2] = xCie.toByte()
                    frameBuf[off + 3] = (yCie ushr 8).toByte()
                    frameBuf[off + 4] = yCie.toByte()
                    frameBuf[off + 5] = (bri ushr 8).toByte()
                    frameBuf[off + 6] = bri.toByte()
                    off += channelRecordSize
                }
                runCatching { dtls?.send(frameBuf, 0, frameBuf.size) }
                    .onFailure {
                        DiagLog.event("HUE", "DTLS send failed: ${it.message} — stopping stream")
                        cancel()
                    }
                // One-shot dump of the first frame so a future debug
                // session can compare bytes against the Philips spec.
                if (frameCount == 0L) {
                    val hex = StringBuilder()
                    for (i in 0 until kotlin.math.min(64, frameBuf.size)) {
                        hex.append("%02x".format(frameBuf[i].toInt() and 0xff))
                        if (i % 16 == 15) hex.append(' ')
                    }
                    DiagLog.event(
                        "HUE",
                        "frame[0] size=${frameBuf.size} bytes hexHead=$hex"
                    )
                }
                // Periodic diag — once per ~4 s — so we can see BPM
                // tracking working at runtime.
                frameCount++
                if (frameCount % 100L == 0L) {
                    DiagLog.event(
                        "HUE",
                        "reactive frame BPM=${"%.0f".format(r.bpm)} " +
                            "dynamics=${"%.2f".format(r.dynamics)} " +
                            "syncOffset=${syncOffsetMs}ms bands=" +
                            r.bands.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
                    )
                }
                delay(frameMs)
            }
            runCatching { dtls?.close() }
            runCatching { socket?.close() }
            hueProvider.stopEntertainmentStream(area)
            DiagLog.event("HUE", "entertainment STOPPED")
        }
    }

    fun stop() {
        if (streamJob == null) return
        DiagLog.event("HUE", "entertainment.stop() requested")
        streamJob?.cancel()
        streamJob = null
        // Stop the parallel dimmable driver too — keeps the two paths
        // started + stopped together.
        dimmableDriver.stop()
        runCatching { dtls?.close() }
        runCatching { socket?.close() }
        dtls = null
        socket = null
        scope.launch {
            if (areaId.isNotBlank()) hueProvider.stopEntertainmentStream(areaId)
        }
    }

    // ── DTLS-PSK handshake ─────────────────────────────────────────

    private fun connectDtls(ip: String, identity: String, psk: ByteArray): Boolean {
        val address = InetAddress.getByName(ip)
        val sock = DatagramSocket()
        sock.connect(address, BRIDGE_DTLS_PORT)
        socket = sock
        val transport: DatagramTransport = UDPTransport(sock, 1500)
        val crypto = BcTlsCrypto(java.security.SecureRandom())
        val identityBytes = identity.toByteArray(Charsets.US_ASCII)
        val pskIdentity = object : TlsPSKIdentity {
            override fun skipIdentityHint() {}
            override fun notifyIdentityHint(hint: ByteArray?) {}
            override fun getPSKIdentity(): ByteArray = identityBytes
            override fun getPSK(): ByteArray = psk
        }
        val client = object : PSKTlsClient(crypto, pskIdentity) {
            override fun getSupportedVersions(): Array<ProtocolVersion> =
                arrayOf(ProtocolVersion.DTLSv12)
            // Hue bridge accepts ONLY TLS_PSK_WITH_AES_128_GCM_SHA256
            // for Entertainment streaming. The BC PSKTlsClient default
            // negotiates a broader cipher set; the bridge replies with
            // handshake_failure(40) when we offer non-matching suites
            // first. Constraining to the one suite is what makes the
            // handshake go through.
            override fun getSupportedCipherSuites(): IntArray =
                intArrayOf(org.bouncycastle.tls.CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
        }
        dtls = DTLSClientProtocol().connect(client, transport)
        DiagLog.event("HUE", "DTLS handshake OK (id len=${identityBytes.size} psk len=${psk.size})")
        return true
    }

    // ── Wire-format helpers ────────────────────────────────────────

    private fun writeHeader(buf: ByteArray, areaUuid: String) {
        // "HueStream" v2.0 header (Philips Entertainment v2 spec):
        //   bytes 0..8  : ASCII "HueStream"
        //   byte 9      : version major (0x02)
        //   byte 10     : version minor (0x00)
        //   byte 11     : sequence id (any, bridge tolerates)
        //   bytes 12-13 : reserved (0x00 0x00)
        //   byte 14     : color space — 0x00 RGB, 0x01 XY+brightness
        //   byte 15     : reserved (0x00)
        //   bytes 16-51 : entertainment area UUID (36 ASCII)
        buf[0] = 'H'.code.toByte(); buf[1] = 'u'.code.toByte(); buf[2] = 'e'.code.toByte()
        buf[3] = 'S'.code.toByte(); buf[4] = 't'.code.toByte(); buf[5] = 'r'.code.toByte()
        buf[6] = 'e'.code.toByte(); buf[7] = 'a'.code.toByte(); buf[8] = 'm'.code.toByte()
        buf[9] = 0x02; buf[10] = 0x00       // version 2.0
        buf[11] = 0x00                       // sequence id
        buf[12] = 0x00; buf[13] = 0x00      // reserved
        buf[14] = 0x01                       // color space = XY brightness
        buf[15] = 0x00                       // reserved
        val ascii = areaUuid.toByteArray(Charsets.US_ASCII)
        System.arraycopy(ascii, 0, buf, 16, min(ascii.size, 36))
    }

    private fun hexDecode(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[2 * i], 16) shl 4)
                + Character.digit(clean[2 * i + 1], 16)).toByte()
        }
        return out
    }

    companion object {
        private const val BRIDGE_DTLS_PORT = 2100
        // v2 header is 16 bytes of magic+meta plus a 36-byte ASCII
        // entertainment-area UUID = 52 bytes total.
        private const val headerSize = 52
        // v2 light record (per Philips Entertainment v2 spec):
        //   1 byte channel id + 6 bytes (3 u16 BE values).
        // Total 7 bytes. We had it as 8 (with a 2-byte channel id) in
        // an earlier draft — wrong: the bridge silently accepted those
        // packets but routed them to no lights.
        private const val channelRecordSize = 7
    }
}
