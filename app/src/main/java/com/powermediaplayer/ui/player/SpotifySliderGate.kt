package com.powermediaplayer.ui.player

/**
 * SCRUB-FLIP (2026-10-30): the Spotify poll reports `durationMs = 0` for 0.5–1.6 s on every
 * track change (phone-proven), which greyed the Track bar and — before the gesture was
 * decoupled — cancelled a drag in flight. The bar stays enabled for a short grace period after
 * the last real duration so a poll gap is invisible.
 */
object SpotifySliderGate {
    const val GRACE_MS = 3_000L

    /** @return enabled, and the updated "last real duration" timestamp to store. */
    fun enabled(durationMs: Long, nowMs: Long, lastRealDurationAtMs: Long): Pair<Boolean, Long> =
        if (durationMs > 0L) true to nowMs
        else (nowMs - lastRealDurationAtMs < GRACE_MS) to lastRealDurationAtMs
}
