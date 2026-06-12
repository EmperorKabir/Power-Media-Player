package com.powermediaplayer.hue

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin

/**
 * Taps PCM 16-bit audio from our own ExoPlayer audio chain, runs an FFT
 * + the [HueAudioAnalyser] on it, and passes the audio through to the
 * speakers unchanged.
 *
 * Why this exists (and not Android's Visualizer):
 *  - Visualizer needs RECORD_AUDIO at runtime on API 28+. Even for our
 *    OWN music. Permission UX is poor for a music-syncing feature.
 *  - Visualizer reads AFTER the AudioTrack output buffer, so lights
 *    would react ~250 ms behind what the user hears. The AudioProcessor
 *    chain runs BEFORE the output buffer — we get sub-50 ms latency
 *    from input PCM to analyser output.
 *  - This is also the natural place to tap: we already have two
 *    AudioProcessors in the chain (StereoTransformProcessor,
 *    AudioDelayProcessor), so the architecture is established.
 *
 * Output:
 *  - Latest [HueAudioAnalyser.Result] is exposed via [latest] so the
 *    Hue Entertainment stream loop can read it lock-free. Each field
 *    is independently observable; the loop runs at 25 Hz while this
 *    processor runs at the PCM frame rate (~10-50 Hz depending on
 *    AudioTrack buffer size).
 *
 * Format compatibility:
 *  - Only ENCODING_PCM_16BIT supported. Other formats (float, passthrough,
 *    surround) are rejected by [onConfigure] which makes the processor
 *    transparent — Media3 routes audio around it without our analysis
 *    firing. That's the correct behaviour: passthrough streams have no
 *    PCM samples for us to look at.
 *
 * Performance:
 *  - FFT size: 512 (covers ~12 ms at 44.1 kHz). Allocation-free per
 *    frame after warm-up.
 *  - Channel downmix: stereo → mono via average; we don't need spatial
 *    info for the analyser.
 *  - The native FFT runs in-place on the buffer copy; we don't have a
 *    JNI dependency on a library — just a tight Kotlin radix-2.
 */
@OptIn(UnstableApi::class)
@Singleton
class HueAnalyserAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    private val analyser = HueAudioAnalyser()
    @Volatile var latest: HueAudioAnalyser.Result = analyser.process(
        ByteArray(16), 44100, SystemClock.uptimeMillis()
    )
        private set

    /**
     * Audit 3.4 — the FFT pipeline ran for EVERY local playback, Hue
     * paired or not (~0.5-2% of a core on the audio sink thread, for
     * everyone, always). The service's Hue collector flips this on only
     * when the reactive engine can actually consume the analysis.
     */
    @Volatile private var analysisEnabled: Boolean = false

    fun setAnalysis(enabled: Boolean) {
        if (enabled && !analysisEnabled) {
            // Fresh stream must not read snapshots from a previous one —
            // same contract as onFlush (matrix DEP: ring continuity).
            onFlush()
        }
        analysisEnabled = enabled
    }

    /** Hann window precomputed once: the per-sample cos() recompute cost
     *  512 transcendental ops per FFT frame (audit 3.4). */
    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)).toFloat())
    }

    /**
     * 1-second ring of analyser snapshots @ 25 Hz, indexed by uptime
     * ms. HueEntertainment reads via [getSnapshotAt] so the stream
     * loop can compensate for the AudioTrack output-buffer latency
     * (lights would otherwise lead the sound by ~250 ms because the
     * PCM tap runs BEFORE the speaker buffer).
     *
     * Stores up to RING_SIZE entries; oldest is overwritten in place.
     */
    // Mutable preallocated slots written in place: the old per-frame
    // data-class + 2×copyOf() allocation drip ran on the time-sensitive
    // audio thread (audit 3.4). uptimeMs == 0 marks an unfilled slot.
    private class TimedSnapshot {
        var uptimeMs: Long = 0L
        val bands = FloatArray(6)
        val normalisedBands = FloatArray(6)
        var beat: Boolean = false
        var beatStrength: Float = 0f
        var normalisedBeatStrength: Float = 0f
        var bpm: Float = 0f
        var dynamics: Float = 0f
        var normalisedDynamics: Float = 0f
        var paletteHz: Float = 0f
    }

    private val ring = Array(RING_SIZE) { TimedSnapshot() }
    private var ringWrite = 0

    /**
     * Returns the analyser snapshot that was current [offsetMs] ms ago,
     * or [latest] if the ring isn't full yet. Used by HueEntertainment
     * to align light flashes with the speaker output.
     */
    fun getSnapshotAt(offsetMs: Int): HueAudioAnalyser.Result {
        if (offsetMs <= 0) return latest
        val target = SystemClock.uptimeMillis() - offsetMs
        // Linear scan — at most RING_SIZE=25 entries; cheap.
        var best: TimedSnapshot? = null
        for (entry in ring) {
            if (entry.uptimeMs == 0L) continue   // unfilled slot
            if (entry.uptimeMs <= target) {
                if (best == null || entry.uptimeMs > best.uptimeMs) best = entry
            }
        }
        if (best == null) return latest
        return rebuildResult(best)
    }

    private fun rebuildResult(s: TimedSnapshot): HueAudioAnalyser.Result {
        val r = HueAudioAnalyser.Result()
        System.arraycopy(s.bands, 0, r.bands, 0, 6)
        System.arraycopy(s.normalisedBands, 0, r.normalisedBands, 0, 6)
        r.beat = s.beat
        r.beatStrength = s.beatStrength
        r.normalisedBeatStrength = s.normalisedBeatStrength
        r.bpm = s.bpm
        r.dynamics = s.dynamics
        r.normalisedDynamics = s.normalisedDynamics
        r.paletteHz = s.paletteHz
        return r
    }

    private var sampleRate: Int = 44100
    private var channelCount: Int = 2
    private var bytesPerFrame: Int = 4

    // Mono float ring filled by the input buffers; FFT runs on it when
    // we've accumulated >= FFT_SIZE samples.
    private val monoRing = FloatArray(FFT_SIZE * 2)
    private var monoWrite = 0
    private var monoFilled = 0

    // FFT scratch buffers + precomputed twiddles.
    private val fftReal = FloatArray(FFT_SIZE)
    private val fftImag = FloatArray(FFT_SIZE)
    private val twiddleCos = FloatArray(FFT_SIZE / 2)
    private val twiddleSin = FloatArray(FFT_SIZE / 2)
    private val fftMagsBytes = ByteArray(FFT_SIZE)

    init {
        for (i in 0 until FFT_SIZE / 2) {
            val theta = -2.0 * Math.PI * i / FFT_SIZE
            twiddleCos[i] = cos(theta).toFloat()
            twiddleSin[i] = sin(theta).toFloat()
        }
    }

    override fun onConfigure(
        input: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) {
            // Surround / float / passthrough — bypass.
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = input.sampleRate
        channelCount = input.channelCount
        bytesPerFrame = channelCount * 2
        monoWrite = 0
        monoFilled = 0
        analyser.reset()
        for (i in ring.indices) ring[i].uptimeMs = 0L
        ringWrite = 0
        return input
    }

    /**
     * Called by Media3 on seek / pause-resume / format change. Reset
     * the analyser + ring; PCM samples from after the seek shouldn't
     * be correlated with the buffer from before it.
     */
    override fun onFlush() {
        monoWrite = 0
        monoFilled = 0
        analyser.reset()
        for (i in ring.indices) ring[i].uptimeMs = 0L
        ringWrite = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (!analysisEnabled) {
            // Pure passthrough — zero analysis cost when Hue isn't
            // streaming (audit 3.4).
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }

        // Allocate / size output buffer (passthrough).
        val length = inputBuffer.remaining()
        val out = replaceOutputBuffer(length)

        // Snapshot the input position/order so we can read samples
        // without disturbing the buffer that Media3 will pull from
        // when we write to `out` below.
        val savedPosition = inputBuffer.position()
        val order = inputBuffer.order()
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // Walk frames: each frame is `channelCount` 16-bit samples.
        val frameCount = length / bytesPerFrame
        var idx = savedPosition
        for (f in 0 until frameCount) {
            // Average the channels into mono.
            var sum = 0
            for (ch in 0 until channelCount) {
                val sample = inputBuffer.getShort(idx + ch * 2).toInt()
                sum += sample
            }
            val mono = (sum / channelCount).toFloat() / 32768f
            monoRing[monoWrite] = mono
            monoWrite = (monoWrite + 1) % monoRing.size
            monoFilled = (monoFilled + 1).coerceAtMost(monoRing.size)
            idx += bytesPerFrame
        }

        // If we have a full FFT_SIZE window, run it. Run AT MOST ONCE
        // per input buffer — the analyser runs further smoothing.
        if (monoFilled >= FFT_SIZE) {
            // Copy the last FFT_SIZE samples (oldest-to-newest order)
            // into fftReal; zero imaginary part.
            val startIdx = (monoWrite - FFT_SIZE + monoRing.size) % monoRing.size
            for (i in 0 until FFT_SIZE) {
                fftReal[i] = monoRing[(startIdx + i) % monoRing.size]
                fftImag[i] = 0f
            }
            // Hann window so spectral leakage doesn't smear bass into mids.
            for (i in 0 until FFT_SIZE) {
                fftReal[i] *= hannWindow[i]
            }
            fftInPlaceRadix2()
            // Pack magnitudes into the same byte layout HueAudioAnalyser
            // expects (real / imag pairs in [-127..127] range). The
            // analyser only reads |re| + |im| so we don't need a sign
            // recovery here — pack magnitudes as positive values.
            val nBins = FFT_SIZE / 2
            for (k in 0 until nBins) {
                // Scale: roughly 100 dB dynamic range → 127 max.
                val mag = kotlin.math.hypot(fftReal[k], fftImag[k])
                val scaled = (mag * 4f).coerceAtMost(127f)
                fftMagsBytes[2 * k] = scaled.toInt().toByte()
                fftMagsBytes[2 * k + 1] = 0  // imag — analyser uses |re| + |im|, leave 0
            }
            val now = SystemClock.uptimeMillis()
            val result = analyser.process(fftMagsBytes, sampleRate, now)
            latest = result
            // Push into ring so HueEntertainment can read aged
            // snapshots for sync-offset compensation.
            val slot = ring[ringWrite]
            slot.uptimeMs = now
            System.arraycopy(result.bands, 0, slot.bands, 0, 6)
            System.arraycopy(result.normalisedBands, 0, slot.normalisedBands, 0, 6)
            slot.beat = result.beat
            slot.beatStrength = result.beatStrength
            slot.normalisedBeatStrength = result.normalisedBeatStrength
            slot.bpm = result.bpm
            slot.dynamics = result.dynamics
            slot.normalisedDynamics = result.normalisedDynamics
            slot.paletteHz = result.paletteHz
            ringWrite = (ringWrite + 1) % RING_SIZE
        }

        // Passthrough: copy input → output, restore position so Media3
        // moves on to the next processor / sink.
        inputBuffer.position(savedPosition)
        inputBuffer.order(order)
        out.put(inputBuffer)
        out.flip()
    }

    /**
     * In-place radix-2 Cooley-Tukey FFT. Allocation-free; trades a
     * little speed for clarity. FFT_SIZE must be a power of two.
     */
    private fun fftInPlaceRadix2() {
        val n = FFT_SIZE
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = fftReal[i]; fftReal[i] = fftReal[j]; fftReal[j] = tr
                val ti = fftImag[i]; fftImag[i] = fftImag[j]; fftImag[j] = ti
            }
        }
        // Butterflies.
        var size = 2
        while (size <= n) {
            val half = size / 2
            val tableStep = n / size
            var i0 = 0
            while (i0 < n) {
                var k = 0
                for (i in i0 until i0 + half) {
                    val cosT = twiddleCos[k]
                    val sinT = twiddleSin[k]
                    val tre = cosT * fftReal[i + half] - sinT * fftImag[i + half]
                    val tim = sinT * fftReal[i + half] + cosT * fftImag[i + half]
                    fftReal[i + half] = fftReal[i] - tre
                    fftImag[i + half] = fftImag[i] - tim
                    fftReal[i] += tre
                    fftImag[i] += tim
                    k += tableStep
                }
                i0 += size
            }
            size = size shl 1
        }
    }

    companion object {
        // 512-point FFT — ~12 ms window at 44.1 kHz. Good balance: tight
        // enough for beat detection, big enough to resolve bass bins.
        private const val FFT_SIZE = 512
        // 1 second of history at 25 Hz analyser cadence — enough to
        // compensate sync offsets up to ~1000 ms.
        private const val RING_SIZE = 25
    }
}
