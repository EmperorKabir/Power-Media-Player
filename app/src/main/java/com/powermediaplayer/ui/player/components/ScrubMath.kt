package com.powermediaplayer.ui.player.components

import kotlin.math.abs

/**
 * Precision scrubbing maths (2026-10-30, user video issue 3). The user's model: "the slower you
 * scrub along the slider, the slower the progression, the more precise". A fast drag moves the
 * thumb with the finger (gain 1); a slow drag moves it a fraction of the finger distance, so a
 * deliberate slow movement becomes a fine seek. Pure functions, unit-tested (ScrubMathTest).
 */
object ScrubMath {
    /** Finger speed (dp per second) at or above which the thumb follows the finger 1:1. */
    const val FULL_SPEED_DP_PER_S = 400f

    /** The slowest progression: at a crawl the thumb moves 5% of the finger distance (20x finer). */
    const val MIN_GAIN = 0.05f

    /**
     * Gain for a finger speed. Linear in speed below [FULL_SPEED_DP_PER_S], so the thumb's own
     * speed falls off quadratically: half the finger speed gives a quarter of the progression,
     * a tenth gives a hundredth (clamped at [MIN_GAIN]). At or above full speed the thumb
     * tracks the finger exactly, so a quick sweep still crosses the whole bar.
     */
    fun gain(velocityDpPerSec: Float): Float =
        (abs(velocityDpPerSec) / FULL_SPEED_DP_PER_S).coerceIn(MIN_GAIN, 1f)

    /** New slider fraction after a finger movement of [dxPx] over a track of [trackWidthPx]. */
    fun step(value: Float, dxPx: Float, trackWidthPx: Float, gain: Float): Float {
        if (trackWidthPx <= 0f) return value
        return (value + dxPx / trackWidthPx * gain).coerceIn(0f, 1f)
    }

    /** Exponential smoothing of the measured speed so a momentary stutter cannot flip the gain. */
    fun smoothVelocity(previous: Float, sample: Float, alpha: Float = 0.4f): Float =
        previous + alpha * (sample - previous)

    /** A press that never travelled past the touch slop is a tap-to-seek, not a scrub. */
    fun isTap(totalAbsDxPx: Float, touchSlopPx: Float): Boolean = totalAbsDxPx < touchSlopPx

    /** Fraction of the track under an x coordinate, allowing for the thumb's half-width inset. */
    fun fractionAt(xPx: Float, widthPx: Float, thumbHalfPx: Float): Float {
        val track = (widthPx - 2f * thumbHalfPx).coerceAtLeast(1f)
        return ((xPx - thumbHalfPx) / track).coerceIn(0f, 1f)
    }
}
