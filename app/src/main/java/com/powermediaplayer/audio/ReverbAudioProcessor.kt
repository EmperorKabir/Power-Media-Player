package com.powermediaplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * In-chain reverb (Freeverb topology: 8 parallel feedback-comb filters +
 * 4 series all-pass filters per channel, with a small sample offset on
 * the right channel for stereo width).
 *
 * vc32: replaces the platform `EnvironmentalReverb`. The framework
 * effect failed twice on real hardware — the aux-bus construction is
 * denied by AudioFlinger ("no permission for AUDIO_SESSION_OUTPUT_MIX")
 * and the session-attached insert crackled on switch while remaining
 * inaudible. Rendering in our own AudioProcessor chain (alongside the
 * delay / stereo-transform processors) is deterministic on every device
 * and inherently click-free: parameters glide per-sample toward their
 * targets instead of jumping.
 *
 * Reads [presetSupplier] / [wetSupplier] lazily per input buffer so
 * Settings changes apply live without rebuilding the chain. Preset 0 is
 * a true bypass (exact pass-through; tails cleared on re-entry).
 *
 * Format: 16-bit PCM, mono or stereo, any sample rate (comb/all-pass
 * lengths scale from their 44.1 kHz tunings). Other encodings pass
 * through untouched.
 */
@OptIn(UnstableApi::class)
class ReverbAudioProcessor(
    private val presetSupplier: () -> Int,
    private val wetSupplier: () -> Float
) : BaseAudioProcessor() {

    // Classic Freeverb tunings (samples @ 44.1 kHz).
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTunings = intArrayOf(556, 441, 341, 225)
    private val stereoSpread = 23

    private class Comb(size: Int) {
        val buf = FloatArray(size)
        var idx = 0
        var filterStore = 0f
        fun process(input: Float, feedback: Float, damp: Float): Float {
            val out = buf[idx]
            filterStore = out * (1f - damp) + filterStore * damp
            buf[idx] = input + filterStore * feedback
            if (++idx >= buf.size) idx = 0
            return out
        }
        fun clear() { buf.fill(0f); filterStore = 0f }
    }

    private class Allpass(size: Int) {
        val buf = FloatArray(size)
        var idx = 0
        fun process(input: Float): Float {
            val bufOut = buf[idx]
            val out = bufOut - input
            buf[idx] = input + bufOut * 0.5f
            if (++idx >= buf.size) idx = 0
            return out
        }
        fun clear() { buf.fill(0f) }
    }

    private var combsL: Array<Comb> = emptyArray()
    private var combsR: Array<Comb> = emptyArray()
    private var allpassL: Array<Allpass> = emptyArray()
    private var allpassR: Array<Allpass> = emptyArray()
    private var channelCount = 0

    // Per-sample-glided parameters (click-free preset/wet changes).
    private var curFeedback = 0f
    private var curDamp = 0.5f
    private var curWet = 0f
    private var wasActive = false

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        if (input.channelCount != 1 && input.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channelCount = input.channelCount
        val scale = input.sampleRate / 44100f
        fun sz(t: Int, spread: Int) = ((t + spread) * scale).toInt().coerceAtLeast(8)
        combsL = Array(combTunings.size) { Comb(sz(combTunings[it], 0)) }
        combsR = Array(combTunings.size) { Comb(sz(combTunings[it], stereoSpread)) }
        allpassL = Array(allpassTunings.size) { Allpass(sz(allpassTunings[it], 0)) }
        allpassR = Array(allpassTunings.size) { Allpass(sz(allpassTunings[it], stereoSpread)) }
        return input
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val preset = presetSupplier().coerceIn(0, 5)
        val targetWet = if (preset == 0) 0f else wetSupplier().coerceIn(0f, 1f)
        // Room size (comb feedback) + damping per preset.
        val targetFeedback: Float
        val targetDamp: Float
        when (preset) {
            1 -> { targetFeedback = 0.78f; targetDamp = 0.55f }   // Room
            2 -> { targetFeedback = 0.84f; targetDamp = 0.45f }   // Medium hall
            3 -> { targetFeedback = 0.90f; targetDamp = 0.30f }   // Large hall
            4 -> { targetFeedback = 0.82f; targetDamp = 0.10f }   // Plate
            5 -> { targetFeedback = 0.96f; targetDamp = 0.20f }   // Cave
            else -> { targetFeedback = curFeedback; targetDamp = curDamp }
        }

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Fully bypassed and settled → exact pass-through, no DSP cost.
        if (targetWet == 0f && curWet < 0.0005f) {
            if (wasActive) {
                combsL.forEach { it.clear() }; combsR.forEach { it.clear() }
                allpassL.forEach { it.clear() }; allpassR.forEach { it.clear() }
                wasActive = false
            }
            curWet = 0f
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }
        wasActive = true

        val out = replaceOutputBuffer(remaining)
        val frames = remaining / (2 * channelCount)
        // ~6 ms glide at 48 kHz — inaudible as a transition, fast enough
        // to feel immediate.
        val glide = 0.0035f
        repeat(frames) {
            curWet += (targetWet - curWet) * glide
            curFeedback += (targetFeedback - curFeedback) * glide
            curDamp += (targetDamp - curDamp) * glide

            val l: Float
            val r: Float
            if (channelCount == 2) {
                l = inputBuffer.short.toFloat()
                r = inputBuffer.short.toFloat()
            } else {
                l = inputBuffer.short.toFloat(); r = l
            }
            // Freeverb input gain keeps the comb bank out of saturation.
            val inMix = (l + r) * 0.5f * 0.015f
            var wetL = 0f
            var wetR = 0f
            for (i in combsL.indices) {
                wetL += combsL[i].process(inMix, curFeedback, curDamp)
                wetR += combsR[i].process(inMix, curFeedback, curDamp)
            }
            for (i in allpassL.indices) {
                wetL = allpassL[i].process(wetL)
                wetR = allpassR[i].process(wetR)
            }
            // Wet level normalised by the comb steady-state gain
            // 1/(1-feedback): every preset lands at roughly dry level
            // at wet=1 instead of overdriving big rooms into clipping
            // (the audible "crackle"). The dry path ducks slightly as
            // wet rises so the sum keeps headroom.
            val gain = curWet * (1f - curFeedback) * 7f
            val dry = 1f - 0.25f * curWet
            val outL = (l * dry + wetL * gain).coerceIn(-32768f, 32767f)
            val outR = (r * dry + wetR * gain).coerceIn(-32768f, 32767f)
            if (channelCount == 2) {
                out.putShort(outL.toInt().toShort())
                out.putShort(outR.toInt().toShort())
            } else {
                out.putShort(((outL + outR) * 0.5f).toInt().toShort())
            }
        }
        out.flip()
    }

    override fun onFlush() {
        combsL.forEach { it.clear() }; combsR.forEach { it.clear() }
        allpassL.forEach { it.clear() }; allpassR.forEach { it.clear() }
    }

    override fun onReset() = onFlush()
}
