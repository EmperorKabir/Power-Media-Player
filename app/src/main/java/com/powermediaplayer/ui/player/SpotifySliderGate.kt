package com.powermediaplayer.ui.player

/**
 * SCRUB-FLIP (2026-10-30): the Spotify poll reports `durationMs = 0` for 0.5–1.6 s on every
 * track change (phone-proven), which greyed the Track bar and — before the gesture was
 * decoupled — cancelled a drag in flight. The bar stays enabled for a short grace period after
 * the last real duration so a poll gap is invisible.
 *
 * A2 (audit): while the gap is bridged, the bar must also DISPLAY the last real duration and
 * position — with `durationMs = 0` every seek maths produced 0 ms and a tap in the gap restarted
 * the track. [hold] returns what the bar should show and whether it is enabled.
 */
object SpotifySliderGate {
    const val GRACE_MS = 3_000L

    /** @return enabled, and the updated "last real duration" timestamp to store. */
    fun enabled(durationMs: Long, nowMs: Long, lastRealDurationAtMs: Long): Pair<Boolean, Long> =
        if (durationMs > 0L) true to nowMs
        else (nowMs - lastRealDurationAtMs < GRACE_MS) to lastRealDurationAtMs

    data class Held(val enabled: Boolean, val durationMs: Long, val positionMs: Long)

    /**
     * Live values when the poll carries a real duration; the last real pair while a gap is
     * bridged; zeros (disabled) once the grace period has passed.
     */
    fun hold(
        liveDurationMs: Long, livePositionMs: Long,
        lastRealDurationMs: Long, lastRealPositionMs: Long,
        nowMs: Long, lastRealDurationAtMs: Long
    ): Held {
        if (liveDurationMs > 0L) return Held(true, liveDurationMs, livePositionMs.coerceAtLeast(0L))
        val bridged = nowMs - lastRealDurationAtMs < GRACE_MS && lastRealDurationMs > 0L
        return if (bridged) Held(true, lastRealDurationMs, lastRealPositionMs.coerceIn(0L, lastRealDurationMs))
        else Held(false, 0L, 0L)
    }
}
