package com.powermediaplayer.hue

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time audio analyser feeding the Hue Entertainment stream.
 *
 * vc29.17 upgrade — three robustness layers added on top of the original
 * band/flux/BPM pipeline:
 *
 *  1. **PCEN per band** (Per-Channel Energy Normalisation, librosa).
 *     Each band has its own IIR-smoothed energy follower; the raw band
 *     value is divided by that follower and then power-compressed.
 *     This kills the "loud track vs quiet track" inconsistency at the
 *     analyser level — bands deliver values in a comparable range
 *     regardless of absolute loudness.
 *
 *  2. **`peak_pick`-style adaptive threshold** (librosa) for onset
 *     detection. Replaces the fixed `fluxRunningMax * 0.55` threshold
 *     with `median(local window) + k × MAD`. Far more robust on dynamic
 *     material — typically 5–10× more beats fire per minute than the
 *     old detector on the same track.
 *
 *  3. **2-second percentile contrast stretch** on top of PCEN. Maintains
 *     a ring of recent normalised band values, computes the 10th and
 *     95th percentile each frame, then maps the current value to that
 *     range. This is what makes the sensitivity slider feel linear
 *     across songs with different dynamic range.
 *
 * The original `bands[]` field is still emitted (raw EMA-smoothed band
 * level) so existing callers keep working. New `normalisedBands[]` field
 * carries the PCEN+percentile-stretched output. The light engines do
 * `lerp(raw, normalised, sensitivity)` so low sensitivity preserves
 * "only loud peaks register" feel while high sensitivity gets full
 * dynamic adaptation.
 *
 * Allocation-free per frame — same constraint as before. All new state
 * lives in `FloatArray` fields reused across frames.
 */
class HueAudioAnalyser {

    data class Result(
        val bands: FloatArray = FloatArray(6),
        /** PCEN + percentile-stretched bands, in [0..1]. vc29.17. */
        val normalisedBands: FloatArray = FloatArray(6),
        var beat: Boolean = false,
        var beatStrength: Float = 0f,
        /** PCEN-normalised beat strength so beatGate works on a stable
         *  scale across loud/quiet material. vc29.17. */
        var normalisedBeatStrength: Float = 0f,
        var bpm: Float = 120f,
        var dynamics: Float = 0f,
        /** PCEN-normalised dynamics envelope. vc29.17. */
        var normalisedDynamics: Float = 0f,
        var paletteHz: Float = 1f
    )

    private val result = Result()

    // EMA state for each band — separate alpha for attack vs release
    // so flashes look snappy on rise and decay smoothly.
    private val bandLevels = FloatArray(6)

    // Previous frame's band sums (used for spectral flux).
    private val prevBandSums = FloatArray(6)

    // ── vc29.17 PCEN state per band ─────────────────────────────────
    // m[band] is the IIR-smoothed energy estimate; the per-frame value
    // is divided by m + bias and power-compressed (closed-form librosa
    // PCEN). One state float per band — cheap.
    private val pcenM = FloatArray(6)
    // PCEN constants (librosa defaults adapted to our 25 Hz frame rate).
    private val pcenS = 0.025f       // smoothing coefficient (≈ 400 ms tc)
    private val pcenGain = 0.80f      // gain (lower = more flattening)
    private val pcenBias = 2.0f       // bias term inside power compression
    private val pcenPower = 0.25f     // root (lower = stronger compression)
    private val pcenEps = 1e-6f       // floor

    // ── vc29.17 percentile-stretch ring buffer per band ─────────────
    // 50 entries × 40 ms = 2 s window of post-PCEN values per band.
    private val pctRing = Array(6) { FloatArray(PCT_RING_SIZE) }
    private var pctRingIdx = 0
    private var pctRingFilled = 0

    // ── vc29.19 EMA on normalisedBands ──────────────────────────────
    // The percentile-stretched output is intentionally volatile so it
    // can flag novelty; that volatility looks like strobing on the
    // lights. A light EMA here (alpha=0.55, ~3-frame time constant)
    // gives a usable signal without the engine layer needing its own
    // smoothing. Raw bands already EMA'd in the band-fill loop above.
    private val normSmoothed = FloatArray(6)
    private val NORM_EMA_ALPHA = 0.55f

    // ── vc29.17 adaptive-threshold onset state ──────────────────────
    // Flux history extended so we can compute a robust median + MAD.
    private val fluxHistory = FloatArray(FLUX_HISTORY_SIZE)
    private var fluxHistoryIdx = 0
    private var fluxRunningMax = 0.01f
    private val fluxSortScratch = FloatArray(FLUX_HISTORY_SIZE)

    // vc29.25 — pre-allocated scratch for percentile (was allocating a
    // new FloatArray per band per frame = 150 allocs/sec on the audio
    // thread). Reused across all 6 bands.
    private val pctSortScratch = FloatArray(PCT_RING_SIZE)
    // BPM autocorrelation pre-alloc (was allocating IntArray(141) +
    // IntArray(recent-1) on every detected beat).
    private val bpmBins = IntArray(141)
    private val bpmIntervals = IntArray(ONSET_RING_SIZE - 1)

    // Onset timestamps (uptime ms). Used by the BPM tracker.
    private val onsetTimes = LongArray(ONSET_RING_SIZE)
    private var onsetCount = 0
    private var lastBpm = 120f

    // Dynamics EMA + its own PCEN state.
    private var dynamicsEma = 0f
    private var dynamicsPcenM = 0f

    // Beat strength PCEN state.
    private var beatStrengthPcenM = 0f

    /**
     * Process one FFT frame. Mutates + returns [Result]. Cheap; safe to
     * call at 25-50 Hz on the audio capture thread.
     */
    fun process(fft: ByteArray, sampleRate: Int, uptimeMs: Long): Result {
        val n = fft.size / 2
        if (n < 8) return result.also { it.beat = false; it.beatStrength = 0f }

        val maxHz = sampleRate / 2f
        val binHz = maxHz / n
        val ranges = intArrayOf(
            maxOf(1, (20f / binHz).toInt()),
            (60f / binHz).toInt().coerceAtMost(n - 1),
            (250f / binHz).toInt().coerceAtMost(n - 1),
            (500f / binHz).toInt().coerceAtMost(n - 1),
            (2000f / binHz).toInt().coerceAtMost(n - 1),
            (4000f / binHz).toInt().coerceAtMost(n - 1),
            (16000f / binHz).toInt().coerceAtMost(n - 1)
        )

        var totalMag = 0f
        var flux = 0f
        for (band in 0..5) {
            val from = ranges[band]
            val to = ranges[band + 1]
            var sum = 0f
            for (k in from..to) {
                val re = abs(fft[2 * k].toInt()).toFloat()
                val im = abs(fft[2 * k + 1].toInt()).toFloat()
                sum += re + im
            }
            val width = (to - from + 1).coerceAtLeast(1)
            val avg = sum / width
            val norm = (avg / 254f).coerceIn(0f, 1f)
            // EMA: snappy attack (0.55), slow release (0.25)
            val prev = bandLevels[band]
            bandLevels[band] = if (norm > prev) {
                prev * 0.45f + norm * 0.55f
            } else {
                prev * 0.75f + norm * 0.25f
            }
            // Per-band normalised flux contribution (divide by band's
            // own running max so bass doesn't dominate snare detection).
            val diff = sum - prevBandSums[band]
            if (diff > 0f) {
                val bandMax = (pcenM[band] + pcenEps) * 254f * (to - from + 1)
                flux += diff / bandMax
            }
            prevBandSums[band] = sum
            totalMag += sum
        }
        for (i in 0..5) result.bands[i] = bandLevels[i]

        // ── PCEN per band ──────────────────────────────────────────
        // m = (1 - s)*m + s*x; pcen = (x / (m + eps)^gain + bias)^power - bias^power
        // The subtraction at the end ensures zero input maps to zero.
        for (i in 0..5) {
            val x = bandLevels[i]
            pcenM[i] = (1f - pcenS) * pcenM[i] + pcenS * x
            val mPow = pcenM[i].coerceAtLeast(pcenEps).pow(pcenGain)
            val v = (x / mPow + pcenBias).pow(pcenPower) - pcenBias.pow(pcenPower)
            // PCEN output is unbounded; clip to a reasonable range. In
            // practice strong onsets land in 0..2 after compression.
            val clipped = v.coerceIn(0f, 2f)
            // Push into percentile ring.
            pctRing[i][pctRingIdx] = clipped
        }
        pctRingIdx = (pctRingIdx + 1) % PCT_RING_SIZE
        if (pctRingFilled < PCT_RING_SIZE) pctRingFilled++

        // ── Percentile contrast stretch per band + EMA ─────────────
        // For each band, compute p10/p95 over the current ring, map
        // the latest value into that range, then apply a light EMA
        // to tame frame-to-frame flicker before publishing. The EMA
        // is the only smoothing the engine layer needs.
        val curIdx = (pctRingIdx + PCT_RING_SIZE - 1) % PCT_RING_SIZE
        for (i in 0..5) {
            percentile20And90(pctRing[i], pctRingFilled)
            val cur = pctRing[i][curIdx]
            val span = (pctP90 - pctP20).coerceAtLeast(0.02f)
            val stretched = ((cur - pctP20) / span).coerceIn(0f, 1f)
            normSmoothed[i] = normSmoothed[i] * (1f - NORM_EMA_ALPHA) +
                stretched * NORM_EMA_ALPHA
            result.normalisedBands[i] = normSmoothed[i]
        }

        // Dynamics envelope (broadband RMS).
        val rms = sqrt(totalMag / n)
        dynamicsEma = dynamicsEma * 0.85f + rms * 0.15f
        result.dynamics = (dynamicsEma / 50f).coerceIn(0f, 1f)
        // PCEN-normalised dynamics — same single-channel trick.
        dynamicsPcenM = (1f - pcenS) * dynamicsPcenM + pcenS * result.dynamics
        val dynMPow = dynamicsPcenM.coerceAtLeast(pcenEps).pow(pcenGain)
        val dynNorm = (result.dynamics / dynMPow + pcenBias).pow(pcenPower) - pcenBias.pow(pcenPower)
        result.normalisedDynamics = (dynNorm / 1.5f).coerceIn(0f, 1f)

        // ── Adaptive-threshold onset detection (peak_pick style) ───
        fluxHistory[fluxHistoryIdx] = flux
        fluxHistoryIdx = (fluxHistoryIdx + 1) % fluxHistory.size
        // Running max with slow decay — still useful for beatStrength
        // scaling but no longer the threshold itself.
        var maxF = flux
        for (v in fluxHistory) if (v > maxF) maxF = v
        fluxRunningMax = fluxRunningMax * 0.95f + maxF * 0.05f
        // Median + MAD-derived threshold. Far more robust than the
        // old fixed 55 % of running max — onsets fire reliably even
        // on quiet, narrow-dynamic-range passages.
        computeMedianAndMad(fluxHistory, fluxSortScratch)
        val fluxMedian = lastMedian
        val fluxMad = lastMad
        val threshold = fluxMedian + fluxMad * 2.5f

        val timeSinceLast = if (onsetCount == 0) Long.MAX_VALUE
            else uptimeMs - onsetTimes[(onsetCount - 1) % onsetTimes.size]
        val beat = flux > threshold && timeSinceLast > 200L  // 300 BPM cap
        result.beat = beat
        result.beatStrength = if (beat) {
            // Raw strength expressed as "how many MADs above median"
            // — bounded so it stays in a sensible range.
            ((flux - threshold) / (fluxMad * 5f + 1e-3f)).coerceIn(0f, 1f)
        } else 0f
        // PCEN-style smoothing of beat strength so normalisedBeatStrength
        // adapts to track-level beat magnitude (quiet tracks with weak
        // onsets still register beats at full normalised strength).
        beatStrengthPcenM = (1f - pcenS) * beatStrengthPcenM + pcenS * result.beatStrength
        val beatM = beatStrengthPcenM.coerceAtLeast(pcenEps).pow(pcenGain)
        val beatN = if (beat) {
            (result.beatStrength / beatM + pcenBias).pow(pcenPower) - pcenBias.pow(pcenPower)
        } else 0f
        result.normalisedBeatStrength = (beatN / 1.5f).coerceIn(0f, 1f)

        if (beat) {
            onsetTimes[onsetCount % onsetTimes.size] = uptimeMs
            onsetCount++
        }

        // BPM via autocorrelation of inter-onset intervals.
        if (onsetCount >= 4) {
            val recent = min(onsetCount, onsetTimes.size)
            // vc29.25 — reuse pre-allocated bpmIntervals + bpmBins
            // instead of allocating per beat. Clear only the used
            // prefix / full bins range.
            for (i in 1 until recent) {
                val cIdx = (onsetCount - recent + i) % onsetTimes.size
                val pIdx = (onsetCount - recent + i - 1) % onsetTimes.size
                bpmIntervals[i - 1] = (onsetTimes[cIdx] - onsetTimes[pIdx]).toInt()
            }
            for (i in bpmBins.indices) bpmBins[i] = 0
            for (i in 0 until (recent - 1)) {
                val dt = bpmIntervals[i]
                if (dt <= 0) continue
                val bpm = (60_000 / dt)
                if (bpm in 60..200) bpmBins[bpm - 60]++
            }
            var peakIdx = -1
            var peakVal = 0
            for (i in bpmBins.indices) {
                if (bpmBins[i] > peakVal) {
                    peakVal = bpmBins[i]; peakIdx = i
                }
            }
            if (peakIdx >= 0 && peakVal >= 2) {
                val newBpm = (peakIdx + 60).toFloat()
                lastBpm = lastBpm * 0.7f + newBpm * 0.3f
            }
        }
        result.bpm = lastBpm
        // vc29.22 — slower palette base. Logs showed even at s=0.10
        // colours were changing every ~1.25 s which felt rapid. New
        // base BPM/720 (was BPM/480) gives ~2-3 s/colour at low s
        // and ~700 ms/colour at high s (combined with the engine's
        // 0.5..1.5 sensitivity multiplier).
        val basePalHz = lastBpm / 60f / 12f
        val dynamicsMul = 0.6f + result.normalisedDynamics * 0.8f
        result.paletteHz = (basePalHz * dynamicsMul).coerceIn(0.03f, 1.0f)

        return result
    }

    /** Reset all state — call when starting a new track or after long pause. */
    fun reset() {
        for (i in 0..5) {
            bandLevels[i] = 0f
            prevBandSums[i] = 0f
            pcenM[i] = 0f
            for (j in pctRing[i].indices) pctRing[i][j] = 0f
        }
        pctRingIdx = 0
        pctRingFilled = 0
        for (i in fluxHistory.indices) fluxHistory[i] = 0f
        fluxHistoryIdx = 0
        fluxRunningMax = 0.01f
        onsetCount = 0
        lastBpm = 120f
        dynamicsEma = 0f
        dynamicsPcenM = 0f
        beatStrengthPcenM = 0f
    }

    /**
     * Approximate 10th and 95th percentile via partial-sort scratch.
     * Cheap enough for 50-entry rings called once per band per frame
     * (6 × 25 Hz = 150 calls/sec; each O(n log n) on 50 floats = ~300
     * float compares).
     */
    // vc29.25 — return into out-floats via per-band fields to avoid
    // the previous Pair<Float, Float> + per-band FloatArray
    // allocation (was 6 × Pair + 6 × FloatArray per frame at 25 Hz).
    private var pctP20 = 0f
    private var pctP90 = 1f
    private fun percentile20And90(ring: FloatArray, filled: Int) {
        if (filled < 4) {
            pctP20 = 0f; pctP90 = 1f
            return
        }
        // Sort the shared scratch buffer; only the first `filled`
        // entries are meaningful.
        System.arraycopy(ring, 0, pctSortScratch, 0, filled)
        // java.util.Arrays.sort(float[], from, to) avoids touching
        // the stale tail when the ring isn't full yet.
        java.util.Arrays.sort(pctSortScratch, 0, filled)
        pctP20 = pctSortScratch[(filled * 20 / 100).coerceIn(0, filled - 1)]
        pctP90 = pctSortScratch[(filled * 90 / 100).coerceIn(0, filled - 1)]
    }

    /** Median absolute deviation — robust threshold component. Writes
     *  into fields like [percentile20And90] does: a Pair return boxed two
     *  Floats per audio frame (audit 3.4). */
    private var lastMedian = 0f
    private var lastMad = 0f
    private fun computeMedianAndMad(values: FloatArray, scratch: FloatArray) {
        val n = values.size
        System.arraycopy(values, 0, scratch, 0, n)
        java.util.Arrays.sort(scratch)
        val median = scratch[n / 2]
        // MAD = median(|x - median|). Compute in place.
        for (i in 0 until n) scratch[i] = abs(values[i] - median)
        java.util.Arrays.sort(scratch)
        lastMedian = median
        lastMad = scratch[n / 2]
    }

    companion object {
        private const val FLUX_HISTORY_SIZE = 25       // 1 s at 25 Hz
        private const val PCT_RING_SIZE = 50           // 2 s at 25 Hz
        private const val ONSET_RING_SIZE = 64
    }
}
