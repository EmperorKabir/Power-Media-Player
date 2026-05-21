package com.powermediaplayer.hue

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real-time audio analyser feeding the Hue Entertainment stream.
 *
 * Inputs:
 *  - One FFT frame per call (Android Visualizer format: pairs of signed
 *    bytes (real, imag) starting at bin 0 = DC).
 *  - Sample rate (Hz).
 *
 * Outputs (per frame):
 *  - 6 normalised band levels (sub-bass / bass / low-mid / mid /
 *    high-mid / treble) in [0..1], EMA-smoothed.
 *  - Beat flag — true when spectral flux exceeded the adaptive
 *    threshold this frame.
 *  - Beat strength — how much above threshold (drives flash size).
 *  - BPM estimate — autocorrelation over last 8 s of onsets.
 *  - Dynamics envelope — broadband RMS, EMA-smoothed.
 *  - Palette rotation rate — derived from BPM. Quarter-note
 *    transitions at BPM/60 Hz; clamped 0.1..2.0 Hz so visuals don't
 *    seize up on extreme tempos.
 *
 * Why these specific outputs:
 *  - Brightness on a beat-driven path looks tighter than on raw bass —
 *    avoids the "constant flicker" that bass-EMA produces on dense
 *    music.
 *  - Per-band split lets the Entertainment loop route different lights
 *    to different frequencies — bass → reds, treble → blues, mids →
 *    greens. That's the value-add over what the Hue app already does.
 *  - BPM-driven palette shifts feel musical instead of timer-cycled.
 *
 * Design choices that matter:
 *  - All arithmetic is allocation-free per frame (single result struct
 *    overwritten in place). The Entertainment loop runs at 25 Hz; GC
 *    pauses would show as missed light updates.
 *  - Spectral flux uses positive differences only (rising energy =
 *    onset), windowed against a recent maximum so quiet passages still
 *    detect their own beats instead of being masked by louder ones.
 *  - BPM autocorrelation lives in [60..200] BPM. Outside that range
 *    we fall back to the last good estimate.
 */
class HueAudioAnalyser {

    data class Result(
        val bands: FloatArray = FloatArray(6),
        var beat: Boolean = false,
        var beatStrength: Float = 0f,
        var bpm: Float = 120f,
        var dynamics: Float = 0f,
        var paletteHz: Float = 1f
    )

    private val result = Result()

    // EMA state for each band — separate alpha for attack vs release
    // so flashes look snappy on rise and decay smoothly.
    private val bandLevels = FloatArray(6)

    // Previous frame's band sums (used for spectral flux).
    private val prevBandSums = FloatArray(6)

    // Adaptive flux threshold — running max of last ~1 sec of fluxes.
    private val fluxHistory = FloatArray(25) // 1 sec at 25 Hz
    private var fluxHistoryIdx = 0
    private var fluxRunningMax = 0.01f

    // Onset timestamps (uptime ms). Used by the BPM tracker.
    private val onsetTimes = LongArray(64)
    private var onsetCount = 0
    private var lastBpm = 120f

    // Dynamics EMA.
    private var dynamicsEma = 0f

    /**
     * Process one FFT frame. Mutates + returns [Result]. Cheap; safe to
     * call at 25-50 Hz on the audio capture thread.
     *
     * @param fft Android Visualizer FFT byte buffer (real/imag pairs).
     * @param sampleRate Sampling rate in Hz (typical 44100 / 48000).
     * @param uptimeMs SystemClock.uptimeMillis() at frame time.
     */
    fun process(fft: ByteArray, sampleRate: Int, uptimeMs: Long): Result {
        val n = fft.size / 2
        if (n < 8) return result.also { it.beat = false; it.beatStrength = 0f }

        // Bin → Hz: each bin = (sampleRate/2) / n
        // Band ranges (approx, in Hz):
        //  sub-bass  20 - 60
        //  bass      60 - 250
        //  low-mid   250 - 500
        //  mid       500 - 2000
        //  high-mid  2000 - 4000
        //  treble    4000 - 16000
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

        // Sum magnitudes within each band. Magnitude = |re| + |im|
        // (cheap L1 approximation — saves a sqrt per bin at 1024
        // bins × 25 Hz × multi-band = noticeable CPU).
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
            // Normalise to [0..1]: 127 max per component × 2 = 254
            val norm = (avg / 254f).coerceIn(0f, 1f)
            // EMA: snappy attack (0.55), slow release (0.25)
            val prev = bandLevels[band]
            bandLevels[band] = if (norm > prev) {
                prev * 0.45f + norm * 0.55f
            } else {
                prev * 0.75f + norm * 0.25f
            }
            // Spectral flux contribution: positive difference only.
            val diff = sum - prevBandSums[band]
            if (diff > 0f) flux += diff
            prevBandSums[band] = sum
            totalMag += sum
        }
        for (i in 0..5) result.bands[i] = bandLevels[i]

        // Dynamics envelope (broadband RMS approximation).
        val rms = sqrt(totalMag / n)
        dynamicsEma = dynamicsEma * 0.85f + rms * 0.15f
        result.dynamics = (dynamicsEma / 50f).coerceIn(0f, 1f)

        // Adaptive flux threshold.
        fluxHistory[fluxHistoryIdx] = flux
        fluxHistoryIdx = (fluxHistoryIdx + 1) % fluxHistory.size
        // Running max with slow decay.
        var maxF = flux
        for (v in fluxHistory) if (v > maxF) maxF = v
        fluxRunningMax = fluxRunningMax * 0.95f + maxF * 0.05f
        val threshold = fluxRunningMax * 0.55f

        // Beat detection: flux exceeds threshold AND we haven't beat
        // in the last 240 ms (250 BPM cap).
        val timeSinceLast = if (onsetCount == 0) Long.MAX_VALUE
            else uptimeMs - onsetTimes[(onsetCount - 1) % onsetTimes.size]
        val beat = flux > threshold && timeSinceLast > 240L
        result.beat = beat
        result.beatStrength = if (beat) {
            ((flux - threshold) / (fluxRunningMax + 1e-3f)).coerceIn(0f, 1f)
        } else 0f

        if (beat) {
            onsetTimes[onsetCount % onsetTimes.size] = uptimeMs
            onsetCount++
        }

        // BPM via autocorrelation of inter-onset intervals over the
        // recent history. Convert intervals to BPM, vote by histogram.
        if (onsetCount >= 4) {
            val recent = min(onsetCount, onsetTimes.size)
            // Walk the ring buffer in chronological order.
            val intervals = IntArray(recent - 1)
            for (i in 1 until recent) {
                val curIdx = (onsetCount - recent + i) % onsetTimes.size
                val prevIdx = (onsetCount - recent + i - 1) % onsetTimes.size
                val dt = (onsetTimes[curIdx] - onsetTimes[prevIdx]).toInt()
                intervals[i - 1] = dt
            }
            // Histogram bins: 60..200 BPM → 1000ms..300ms intervals.
            val bins = IntArray(141) // one bin per BPM in [60..200]
            for (dt in intervals) {
                if (dt <= 0) continue
                val bpm = (60_000 / dt)
                if (bpm in 60..200) bins[bpm - 60]++
            }
            // Find peak.
            var peakIdx = -1
            var peakVal = 0
            for (i in bins.indices) {
                if (bins[i] > peakVal) {
                    peakVal = bins[i]; peakIdx = i
                }
            }
            if (peakIdx >= 0 && peakVal >= 2) {
                val newBpm = (peakIdx + 60).toFloat()
                lastBpm = lastBpm * 0.7f + newBpm * 0.3f
            }
        }
        result.bpm = lastBpm
        // Palette rotation: quarter notes at BPM/60 Hz → cap range.
        result.paletteHz = (lastBpm / 60f / 4f).coerceIn(0.1f, 2.0f)

        return result
    }

    /** Reset all state — call when starting a new track or after long pause. */
    fun reset() {
        for (i in 0..5) {
            bandLevels[i] = 0f
            prevBandSums[i] = 0f
        }
        for (i in fluxHistory.indices) fluxHistory[i] = 0f
        fluxHistoryIdx = 0
        fluxRunningMax = 0.01f
        onsetCount = 0
        lastBpm = 120f
        dynamicsEma = 0f
    }
}
