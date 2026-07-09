package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * In-chain 10-band graphic EQ — biquad peaking filters at the displayed
 * 31 Hz … 16 kHz centres — replacing the platform `audiofx.Equalizer`.
 *
 * Why: the platform EQ exposes only the DEVICE's hardware bands (almost
 * always 5) and the app mapped its 10 sliders onto them 1:1 BY INDEX. So
 * sliders 0–4 ("31"–"500 Hz") actually drove the device's full-spectrum
 * bands (boosting them clipped → the "farty/stuttery" low end) while
 * sliders 5–9 ("1 kHz"–"16 kHz") were never applied at all. This processor
 * implements all ten bands honestly at their real frequencies.
 *
 * Output stage (item 6 redesign, 2026-07-09, adversarially cross-examined):
 * the old design reserved worst-case-boost headroom up front (preGain =
 * 1/maxGain), which cost the user the full boost amount in LOUDNESS on all
 * material, all the time — the reported "big volume loss". Root-cause fix:
 *  - NO static pre-attenuation. Boosted bands play at their true gain.
 *  - A gain-riding envelope limiter after the cascade: instant attack,
 *    45 ms peak-hold (covers a full 31 Hz cycle, so periodic bass does not
 *    re-trigger release ripple), 250 ms exponential release. Gain riding is
 *    slow relative to the waveform, so unlike a waveshaper it generates no
 *    harmonics on sustained material.
 *  - The tanh soft knee remains ONLY as a transient safety net ABOVE the
 *    limiter ceiling (knee onset == ceiling — ordering matters: a knee below
 *    the ceiling would recolour everything the limiter parks there).
 *  - Extreme presets (every band +12 dB) now trade loudness for limiter
 *    engagement on loud material instead of a permanent −12 dB; that is the
 *    standard behaviour of mainstream players.
 *
 * Transition smoothing (the "brief fart when adjusting" fix): band gains no
 * longer jump — a smoothed per-band value ramps exponentially (τ≈40 ms)
 * toward the supplier's target, and coefficients are recomputed from the
 * SMOOTHED gains per 128-frame sub-block. Every intermediate coefficient set
 * is an exact RBJ peaking biquad (unconditionally stable for any gain), and
 * per-block gain deltas stay well under 1 dB, keeping the TDF-II state
 * switching transients below audibility. Flush snaps the ramp to target so a
 * seek never replays a ghost transition.
 *
 * Levels are supplied in millibels (100 mB = 1 dB), read lazily per buffer.
 * Flat (every band 0, ramp settled) → exact byte-for-byte pass-through at
 * zero cost. Placed AFTER the reverb processor in the sink chain.
 */
@OptIn(UnstableApi::class)
class EqualizerAudioProcessor(
    private val bandLevelsMbSupplier: () -> List<Int>
) : BaseAudioProcessor() {

    private companion object {
        // Matches EqualizerViewModel.bandFrequencies labels.
        val CENTERS = doubleArrayOf(
            31.0, 63.0, 125.0, 250.0, 500.0,
            1000.0, 2000.0, 4000.0, 8000.0, 16000.0
        )
        const val Q = 1.41        // ~1 octave per band (graphic-EQ standard)
        const val N = 10

        const val FULL_SCALE = 32767f
        // Limiter ceiling AND tanh knee onset — the same point, deliberately.
        // Below it the transfer is exactly unity; the envelope limiter parks
        // sustained peaks here (constant gain → no colouration); the knee only
        // shapes the brief attack overshoot beyond it.
        const val CEILING = 0.95 * FULL_SCALE.toDouble()

        // Gain-ramp time constant (ms) + recompute granularity (frames).
        const val RAMP_TAU_MS = 40.0
        const val SUB_BLOCK_FRAMES = 128
        // Snap-to-target threshold: below 1 mB the ramp is done.
        const val SNAP_MB = 1.0

        // Envelope limiter: hold covers one full cycle of the lowest band
        // (32 ms at 31 Hz) so periodic material holds a constant gain.
        const val HOLD_MS = 45.0
        const val RELEASE_TAU_MS = 250.0
    }

    private var channels = 0
    private var sampleRate = 0

    // Per-band normalised biquad coefficients (a0 folded in).
    private val b0 = DoubleArray(N)
    private val b1 = DoubleArray(N)
    private val b2 = DoubleArray(N)
    private val a1 = DoubleArray(N)
    private val a2 = DoubleArray(N)
    private val bandActive = BooleanArray(N)

    // Per-band, per-channel filter state (Transposed Direct Form II).
    private var z1 = Array(N) { DoubleArray(0) }
    private var z2 = Array(N) { DoubleArray(0) }

    // Smoothed (currently applied) and target band gains, in millibels.
    private val smoothedMb = DoubleArray(N)
    private val targetMb = DoubleArray(N)
    private var anyActive = false

    // Envelope limiter state.
    private var env = 0.0
    private var holdFramesLeft = 0
    private var holdFrames = 0
    private var releaseCoef = 1.0

    // Reused per-frame scratch (one slot per channel).
    private var frameBuf = DoubleArray(0)

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = input.channelCount
        sampleRate = input.sampleRate
        z1 = Array(N) { DoubleArray(channels) }
        z2 = Array(N) { DoubleArray(channels) }
        frameBuf = DoubleArray(channels)
        holdFrames = (HOLD_MS * sampleRate / 1000.0).toInt()
        releaseCoef = Math.exp(-1000.0 / (RELEASE_TAU_MS * sampleRate))
        env = 0.0
        holdFramesLeft = 0
        // Track start: apply the current levels directly (no ramp from flat —
        // ramping in at track start would audibly sweep the EQ on every song).
        snapToSupplier()
        return input
    }

    private fun readTargets() {
        val levels = bandLevelsMbSupplier()
        for (i in 0 until N) targetMb[i] = levels.getOrElse(i) { 0 }.toDouble()
    }

    private fun snapToSupplier() {
        readTargets()
        System.arraycopy(targetMb, 0, smoothedMb, 0, N)
        recompute()
    }

    /** Rebuild the RBJ peaking coefficients from the SMOOTHED gains. */
    private fun recompute() {
        var active = false
        for (i in 0 until N) {
            val mb = smoothedMb[i]
            val f0 = CENTERS[i]
            // Skip flat bands and any centre at/above Nyquist (low sample
            // rates) — a peaking filter there is undefined / unstable.
            if (Math.abs(mb) < SNAP_MB || sampleRate <= 0 || f0 >= sampleRate * 0.45) {
                bandActive[i] = false
                continue
            }
            val gainDb = mb / 100.0
            val a = Math.pow(10.0, gainDb / 40.0)          // amplitude √(linear)
            val w0 = 2.0 * Math.PI * f0 / sampleRate
            val cosw0 = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha / a
            b0[i] = (1.0 + alpha * a) / a0
            b1[i] = (-2.0 * cosw0) / a0
            b2[i] = (1.0 - alpha * a) / a0
            a1[i] = (-2.0 * cosw0) / a0
            a2[i] = (1.0 - alpha / a) / a0
            bandActive[i] = true
            active = true
        }
        anyActive = active
    }

    /** Advance the gain ramp by [frames] worth of time. Returns true when any
     *  smoothed value moved (coefficients need a recompute). */
    private fun advanceRamp(frames: Int): Boolean {
        var moved = false
        var pending = false
        for (i in 0 until N) if (Math.abs(targetMb[i] - smoothedMb[i]) >= SNAP_MB) pending = true
        if (!pending) {
            // Snap any sub-threshold residue exactly to target.
            for (i in 0 until N) if (smoothedMb[i] != targetMb[i]) {
                smoothedMb[i] = targetMb[i]; moved = true
            }
            return moved
        }
        val alpha = 1.0 - Math.exp(-frames.toDouble() / (RAMP_TAU_MS * sampleRate / 1000.0))
        for (i in 0 until N) {
            val d = targetMb[i] - smoothedMb[i]
            if (d == 0.0) continue
            smoothedMb[i] = if (Math.abs(d) < SNAP_MB) targetMb[i] else smoothedMb[i] + d * alpha
            moved = true
        }
        return moved
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        readTargets()

        // Flat + settled → exact pass-through; downstream sees identical bytes
        // and the limiter never touches an un-EQ'd signal.
        var flat = true
        for (i in 0 until N) {
            if (Math.abs(targetMb[i]) >= SNAP_MB || Math.abs(smoothedMb[i]) >= SNAP_MB) {
                flat = false; break
            }
        }
        if (flat || channels == 0) {
            for (i in 0 until N) smoothedMb[i] = targetMb[i]
            anyActive = false
            env = 0.0; holdFramesLeft = 0
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }

        val out = replaceOutputBuffer(remaining)
        val totalFrames = remaining / (2 * channels)
        val span = FULL_SCALE - CEILING
        var framesLeft = totalFrames
        while (framesLeft > 0) {
            val block = if (framesLeft > SUB_BLOCK_FRAMES) SUB_BLOCK_FRAMES else framesLeft
            if (advanceRamp(block)) recompute()
            repeat(block) {
                var framePeak = 0.0
                for (ch in 0 until channels) {
                    var x = inputBuffer.short.toDouble()
                    for (i in 0 until N) {
                        if (!bandActive[i]) continue
                        val y = b0[i] * x + z1[i][ch]
                        z1[i][ch] = b1[i] * x - a1[i] * y + z2[i][ch]
                        z2[i][ch] = b2[i] * x - a2[i] * y
                        x = y
                    }
                    frameBuf[ch] = x
                    val a = Math.abs(x)
                    if (a > framePeak) framePeak = a
                }
                // Envelope: instant attack, hold, exponential release. One gain
                // per FRAME (max across channels) so the stereo image is stable.
                if (framePeak >= env) {
                    env = framePeak
                    holdFramesLeft = holdFrames
                } else if (holdFramesLeft > 0) {
                    holdFramesLeft--
                } else {
                    env *= releaseCoef
                }
                val gain = if (env > CEILING) CEILING / env else 1.0
                for (ch in 0 until channels) {
                    var v = frameBuf[ch] * gain
                    val a = Math.abs(v)
                    if (a > CEILING) {
                        // Attack-overshoot safety net only: C-infinity tanh knee
                        // from the ceiling to the FULL_SCALE asymptote.
                        val over = (a - CEILING) / span
                        v = Math.signum(v) * (CEILING + span * Math.tanh(over))
                    }
                    val s = v.coerceIn(-FULL_SCALE.toDouble(), FULL_SCALE.toDouble())
                    out.putShort(s.toInt().toShort())
                }
            }
            framesLeft -= block
        }
        out.flip()
    }

    override fun onFlush() {
        // Clear filter memory on seek/flush so a stale tail can't click, and
        // snap the gain ramp to target so a seek never replays a transition.
        for (i in 0 until N) {
            if (z1[i].isNotEmpty()) java.util.Arrays.fill(z1[i], 0.0)
            if (z2[i].isNotEmpty()) java.util.Arrays.fill(z2[i], 0.0)
        }
        env = 0.0
        holdFramesLeft = 0
        snapToSupplier()
    }

    override fun onReset() {
        z1 = Array(N) { DoubleArray(0) }
        z2 = Array(N) { DoubleArray(0) }
        frameBuf = DoubleArray(0)
        java.util.Arrays.fill(smoothedMb, 0.0)
        java.util.Arrays.fill(targetMb, 0.0)
        anyActive = false
        env = 0.0
        holdFramesLeft = 0
    }
}
