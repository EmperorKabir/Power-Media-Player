package com.powermediaplayer.hue

import android.media.audiofx.Visualizer
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
import kotlin.math.abs
import kotlin.math.min

/**
 * Hue Entertainment-API audio-reactive lighting. Streams 25 Hz colour
 * frames to the bridge over DTLS-PSK while audio is playing.
 *
 * Protocol shape (Hue Entertainment v2 streaming):
 *   - UDP socket to bridge:2100, wrapped in DTLS 1.2 with PSK-AES128-GCM-SHA256
 *   - PSK identity = app-key string (the "username" returned at pair time)
 *   - PSK = hex-decoded clientkey bytes (16 bytes)
 *   - Each frame is "HueStream" magic + version + light-records
 *     payload. v2 records: 16-bit channel id + 16-bit X + 16-bit Y +
 *     16-bit brightness, repeated.
 *
 * Audio feed:
 *   - Android Visualizer attached to the player's audio session id
 *     (capture size 1024, max rate ~10 Hz). For 25 Hz visual cadence
 *     we interpolate brightness between captures.
 *   - Bass band energy (FFT bins 1–8 ≈ 40–320 Hz) drives brightness.
 *     Colour cycles through a 6-step palette every 4 seconds.
 *
 * Limitations declared up-front:
 *   - First implementation. Tested via code review against the Hue
 *     v2 Entertainment docs (Context7-confirmed where possible); real-
 *     world tuning on a live bridge is expected.
 *   - Visualizer requires `RECORD_AUDIO` permission OR a system signature
 *     on API 29+. On non-rooted phones the FFT may be silent (zeroes)
 *     when the system blocks. Brightness then defaults to mid-level.
 *   - If the DTLS handshake fails (wrong PSK, bridge unreachable,
 *     entertainment-config not started), we log + stop cleanly so
 *     the rest of playback is unaffected.
 */
@Singleton
class HueEntertainment @Inject constructor(
    private val settings: SettingsDataStore,
    private val hueProvider: HueProvider
) {
    enum class ReactiveMode(val key: String) {
        OFF("off"),
        BASS_FLASH("bass_flash"),
        SPECTRUM("spectrum"),
        COLOUR_FOLLOW_TRACK("colour_follow_track")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var streamJob: Job? = null
    @Volatile private var dtls: DTLSTransport? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var visualizer: Visualizer? = null
    @Volatile private var bassLevel: Float = 0.0f
    @Volatile private var areaId: String = ""

    /**
     * Begin streaming. [audioSessionId] is the ExoPlayer audio session
     * id (player.audioSessionId). [lightChannels] is the ordered list
     * of channel ids the Entertainment area maps to.
     */
    fun start(audioSessionId: Int, mode: ReactiveMode, lightChannels: List<Int>) {
        if (mode == ReactiveMode.OFF) {
            DiagLog.event("HUE", "entertainment.start(OFF) — no-op")
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
                DiagLog.event(
                    "HUE",
                    "entertainment.start — missing creds (ip=${ip.isNotBlank()}" +
                        " key=${appKey.isNotBlank()} clientKey=${clientKey.isNotBlank()})"
                )
                return@launch
            }
            // Pick an area + tell the bridge to enter streaming mode.
            val area = hueProvider.firstEntertainmentAreaId() ?: run {
                DiagLog.event(
                    "HUE",
                    "entertainment.start — no entertainment_configuration on bridge. " +
                        "Create one in the Hue app first."
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
            // DTLS handshake.
            val ok = runCatching { connectDtls(ip, appKey, hexDecode(clientKey)) }
                .onFailure { DiagLog.event("HUE", "DTLS handshake FAILED: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            if (!ok) {
                hueProvider.stopEntertainmentStream(area)
                return@launch
            }
            // Audio Visualizer.
            attachVisualizer(audioSessionId)
            DiagLog.event(
                "HUE",
                "entertainment.start RUNNING mode=${mode.name} area=$area lights=${lightChannels.size}"
            )
            settings.setHueReactiveMode(mode.key)
            // Stream loop — 25 Hz cadence.
            val frameMs = 40L
            val palette = listOf(
                Triple(0xCC00, 0x4F00, 0x8000),   // red    xy=(0.68,0.31) approx mapped to 0-65535
                Triple(0x2900, 0xB300, 0x8000),   // green  xy=(0.17,0.70)
                Triple(0x2600, 0x0F00, 0x8000),   // blue   xy=(0.15,0.06)
                Triple(0x6600, 0x2E00, 0x8000),   // magenta xy=(0.40,0.18)
                Triple(0xE600, 0x9900, 0x8000),   // amber  xy=(0.55,0.40)
                Triple(0x5C00, 0x5C00, 0x8000)    // cyan   xy=(0.20,0.30)
            )
            var paletteIdx = 0
            var paletteHoldFrames = 0
            val frameBuf = ByteArray(headerSize + lightChannels.size * channelRecordSize)
            writeHeader(frameBuf, area)
            while (isActive) {
                paletteHoldFrames++
                if (paletteHoldFrames >= 100) { // ~4 sec at 25 Hz
                    paletteIdx = (paletteIdx + 1) % palette.size
                    paletteHoldFrames = 0
                }
                val brightness = when (mode) {
                    ReactiveMode.BASS_FLASH -> (bassLevel * 65535).toInt().coerceIn(0, 65535)
                    ReactiveMode.SPECTRUM -> ((0.5f + bassLevel * 0.5f) * 65535).toInt().coerceIn(0, 65535)
                    ReactiveMode.COLOUR_FOLLOW_TRACK -> (0x8000)
                    ReactiveMode.OFF -> 0
                }
                val (x, y, _) = palette[paletteIdx]
                var off = headerSize
                for (ch in lightChannels) {
                    frameBuf[off + 0] = (ch ushr 8).toByte()
                    frameBuf[off + 1] = ch.toByte()
                    frameBuf[off + 2] = (x ushr 8).toByte()
                    frameBuf[off + 3] = x.toByte()
                    frameBuf[off + 4] = (y ushr 8).toByte()
                    frameBuf[off + 5] = y.toByte()
                    frameBuf[off + 6] = (brightness ushr 8).toByte()
                    frameBuf[off + 7] = brightness.toByte()
                    off += channelRecordSize
                }
                runCatching { dtls?.send(frameBuf, 0, frameBuf.size) }
                    .onFailure {
                        DiagLog.event("HUE", "DTLS send failed: ${it.message} — stopping stream")
                        cancel()
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

    fun stop() {
        DiagLog.event("HUE", "entertainment.stop() requested")
        streamJob?.cancel()
        streamJob = null
        detachVisualizer()
        runCatching { dtls?.close() }
        runCatching { socket?.close() }
        dtls = null
        socket = null
        scope.launch {
            settings.setHueReactiveMode(ReactiveMode.OFF.key)
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
        val protocol = DTLSClientProtocol()
        dtls = protocol.connect(client, transport)
        DiagLog.event("HUE", "DTLS handshake OK (id len=${identityBytes.size} psk len=${psk.size})")
        return true
    }

    // ── Visualizer plumbing ────────────────────────────────────────

    private fun attachVisualizer(audioSessionId: Int) {
        if (audioSessionId == 0) {
            DiagLog.event("HUE", "Visualizer skipped — audioSessionId=0")
            return
        }
        val v = runCatching {
            Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int
                        ) {}
                        override fun onFftDataCapture(
                            visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int
                        ) {
                            if (fft == null) return
                            // Bass bins 1..8 (skip DC at 0). Each bin
                            // is a complex pair (real, imag) of signed
                            // bytes; magnitude = sqrt(re*re + im*im).
                            var sum = 0
                            val bins = min(8, fft.size / 2 - 1)
                            for (k in 1..bins) {
                                val re = fft[2 * k].toInt()
                                val im = fft[2 * k + 1].toInt()
                                sum += abs(re) + abs(im)
                            }
                            // Normalise: 8 bins × 2 components × 127 max
                            val norm = (sum.toFloat() / (8 * 2 * 127f)).coerceIn(0f, 1f)
                            // Smooth with an EMA so flashes feel musical
                            // rather than jittery.
                            bassLevel = bassLevel * 0.6f + norm * 0.4f
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
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
            visualizer?.apply {
                enabled = false
                release()
            }
        }
        visualizer = null
    }

    // ── Wire-format helpers ────────────────────────────────────────

    private fun writeHeader(buf: ByteArray, areaUuid: String) {
        // Hue Entertainment v2 header (~52 bytes including area UUID).
        // Bytes 0..8  : "HueStream" magic ASCII
        buf[0] = 'H'.code.toByte(); buf[1] = 'u'.code.toByte(); buf[2] = 'e'.code.toByte()
        buf[3] = 'S'.code.toByte(); buf[4] = 't'.code.toByte(); buf[5] = 'r'.code.toByte()
        buf[6] = 'e'.code.toByte(); buf[7] = 'a'.code.toByte(); buf[8] = 'm'.code.toByte()
        // Bytes 9..10 : version major + minor (2.0)
        buf[9] = 0x02; buf[10] = 0x00
        // Bytes 11..12 : sequence (left zero — bridge ignores)
        buf[11] = 0; buf[12] = 0
        // Bytes 13..14 : reserved
        buf[13] = 0; buf[14] = 0
        // Byte 15     : colour space 0 = XY+brightness
        buf[15] = 0
        // Byte 16     : reserved
        buf[16] = 0
        // Bytes 17..52 : entertainment area UUID as ASCII (36 chars)
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
