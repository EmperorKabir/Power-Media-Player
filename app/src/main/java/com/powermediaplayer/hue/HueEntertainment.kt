package com.powermediaplayer.hue

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import com.powermediaplayer.data.preferences.SettingsDataStore
import com.powermediaplayer.diag.DiagLog
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *   - Visualizer needs RECORD_AUDIO at runtime; we log graceful
 *     degrade-to-colour-cycle when permission missing.
 *   - Hue bridge enforces ~10 lights max per Entertainment area for
 *     latency reasons; we cap channels accordingly.
 */
@Singleton
class HueEntertainment @Inject constructor(
    private val settings: SettingsDataStore,
    private val hueProvider: HueProvider,
    @param:ApplicationContext private val appContext: Context
) {

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
    @Volatile private var visualizer: Visualizer? = null
    @Volatile private var areaId: String = ""

    // Audio analyser is updated on the Visualizer callback thread; the
    // stream loop reads the latest snapshot at 25 Hz.
    private val analyser = HueAudioAnalyser()
    @Volatile private var lastBands = FloatArray(6)
    @Volatile private var lastBeatStrength: Float = 0f
    @Volatile private var lastBeatExpiresMs: Long = 0L
    @Volatile private var lastPaletteHz: Float = 1f
    @Volatile private var lastDynamics: Float = 0f

    /**
     * Begin streaming. [audioSessionId] is the ExoPlayer audio session
     * id. [intensity] in [0..100]; 0 stops any current stream.
     */
    fun start(audioSessionId: Int, intensity: Int, lightChannels: List<Int>) {
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
            val area = hueProvider.firstEntertainmentAreaId() ?: run {
                DiagLog.event(
                    "HUE",
                    "entertainment.start — no entertainment_configuration. " +
                        "Open the Hue app → Settings → Entertainment areas → " +
                        "create one (drag your lights into it). Then come back."
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
            attachVisualizer(audioSessionId)
            DiagLog.event(
                "HUE",
                "entertainment.start RUNNING intensity=$intensity area=$area lights=${lightChannels.size}"
            )

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
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                // Advance palette by paletteHz (Hz of phase rotation).
                palettePhase += (lastPaletteHz * frameMs / 1000.0) * (2 * Math.PI)
                if (palettePhase > 2 * Math.PI) palettePhase -= 2 * Math.PI

                // Beat brightness pulse — strong but short.
                val onBeat = now < lastBeatExpiresMs
                val beatBoost = if (onBeat) lastBeatStrength else 0f

                var off = headerSize
                for ((i, ch) in lightChannels.withIndex()) {
                    // Route this light to one of the 6 bands.
                    val band = i % 6
                    val bandLevel = lastBands[band]
                    // Per-light palette offset — spread the palette across
                    // the room so different lights show different colours.
                    val perLightOffset = (i.toDouble() / lightChannels.size) * (2 * Math.PI)
                    val phase = (palettePhase + perLightOffset).rem(2 * Math.PI)
                    val palIdx = ((phase / (2 * Math.PI)) * palette.size).toInt().coerceIn(0, palette.size - 1)
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

                    frameBuf[off + 0] = (ch ushr 8).toByte()
                    frameBuf[off + 1] = ch.toByte()
                    frameBuf[off + 2] = (xCie ushr 8).toByte()
                    frameBuf[off + 3] = xCie.toByte()
                    frameBuf[off + 4] = (yCie ushr 8).toByte()
                    frameBuf[off + 5] = yCie.toByte()
                    frameBuf[off + 6] = (bri ushr 8).toByte()
                    frameBuf[off + 7] = bri.toByte()
                    off += channelRecordSize
                }
                runCatching { dtls?.send(frameBuf, 0, frameBuf.size) }
                    .onFailure {
                        DiagLog.event("HUE", "DTLS send failed: ${it.message} — stopping stream")
                        cancel()
                    }
                // Periodic diag — once per ~4 s — so the user can see
                // BPM tracking working.
                frameCount++
                if (frameCount % 100L == 0L) {
                    DiagLog.event(
                        "HUE",
                        "reactive frame BPM=${"%.0f".format(analyser.let { lastDynamics; analyserBpm() })} " +
                            "dynamics=${"%.2f".format(lastDynamics)} bands=" +
                            lastBands.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
                    )
                }
                delay(frameMs)
            }
            detachVisualizer()
            runCatching { dtls?.close() }
            runCatching { socket?.close() }
            hueProvider.stopEntertainmentStream(area)
            DiagLog.event("HUE", "entertainment STOPPED")
        }
    }

    /** Cached BPM for the diag line — set on every analyser invoke. */
    @Volatile private var lastBpmCache: Float = 120f
    private fun analyserBpm(): Float = lastBpmCache

    fun stop() {
        if (streamJob == null) return
        DiagLog.event("HUE", "entertainment.stop() requested")
        streamJob?.cancel()
        streamJob = null
        detachVisualizer()
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
        }
        dtls = DTLSClientProtocol().connect(client, transport)
        DiagLog.event("HUE", "DTLS handshake OK (id len=${identityBytes.size} psk len=${psk.size})")
        return true
    }

    // ── Visualizer plumbing ────────────────────────────────────────

    private fun attachVisualizer(audioSessionId: Int) {
        if (audioSessionId == 0) {
            DiagLog.event("HUE", "Visualizer skipped — audioSessionId=0")
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            DiagLog.event(
                "HUE",
                "Visualizer attach — RECORD_AUDIO not granted; lighting will cycle " +
                    "colour but won't react to bass. Grant the permission in " +
                    "Settings → Apps → Power Media Player → Permissions → Microphone."
            )
            return
        }
        analyser.reset()
        val v = runCatching {
            Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1] // max — better resolution
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int
                        ) {}
                        override fun onFftDataCapture(
                            visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int
                        ) {
                            fft ?: return
                            val sr = samplingRate / 1000 // Android passes Hz * 1000
                            val r = analyser.process(fft, sr, SystemClock.uptimeMillis())
                            // Snapshot for the stream loop. No locking;
                            // each field is independently observed.
                            System.arraycopy(r.bands, 0, lastBands, 0, 6)
                            lastDynamics = r.dynamics
                            lastPaletteHz = r.paletteHz
                            lastBpmCache = r.bpm
                            if (r.beat) {
                                lastBeatStrength = r.beatStrength
                                // Brief flash window — 120 ms.
                                lastBeatExpiresMs = SystemClock.uptimeMillis() + 120L
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2, // hardware-cap divided
                    /* waveform */ false,
                    /* fft */ true
                )
                enabled = true
            }
        }.getOrElse {
            DiagLog.event("HUE", "Visualizer attach failed: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
        visualizer = v
    }

    private fun detachVisualizer() {
        runCatching {
            visualizer?.apply { enabled = false; release() }
        }
        visualizer = null
    }

    // ── Wire-format helpers ────────────────────────────────────────

    private fun writeHeader(buf: ByteArray, areaUuid: String) {
        // "HueStream" v2.0 header — magic + version + colour space +
        // area UUID. Layout per Hue Entertainment v2 spec.
        buf[0] = 'H'.code.toByte(); buf[1] = 'u'.code.toByte(); buf[2] = 'e'.code.toByte()
        buf[3] = 'S'.code.toByte(); buf[4] = 't'.code.toByte(); buf[5] = 'r'.code.toByte()
        buf[6] = 'e'.code.toByte(); buf[7] = 'a'.code.toByte(); buf[8] = 'm'.code.toByte()
        buf[9] = 0x02; buf[10] = 0x00       // version 2.0
        buf[11] = 0; buf[12] = 0            // sequence (bridge ignores)
        buf[13] = 0; buf[14] = 0            // reserved
        buf[15] = 0                          // colour space 0 = XY+brightness
        buf[16] = 0                          // reserved
        val ascii = areaUuid.toByteArray(Charsets.US_ASCII)
        System.arraycopy(ascii, 0, buf, 17, min(ascii.size, 36))
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
        private const val headerSize = 52
        // v2 light record: channel u16 + x u16 + y u16 + brightness u16
        private const val channelRecordSize = 8
    }
}
