package com.powermediaplayer.playback

import java.util.concurrent.atomic.AtomicLong

/**
 * vc32: process-wide resume coordination. Instance-field
 * guards died with their ViewModel (destination-scoped ViewModels are
 * cleared on back-stack pop), so the
 * debounce counter AND the staleness check live here, JVM-wide.
 *
 * Slow, parse-bearing resume paths:
 *   val token = ResumeGate.begin()
 *   try {
 *     …parse…
 *     if (!ResumeGate.isCurrent(token)) return   // superseded — abort
 *     playbackConnection.setMediaItems(…)
 *   } finally { ResumeGate.end(token) }
 *
 * Fast play paths call ResumeGate.end(ResumeGate.begin()) — not to check
 * it, but so THEIR intent invalidates any older in-flight slow resume.
 */
object ResumeGate {
    private val generation = AtomicLong(0L)
    // Live (not-yet-ended) tokens. Tracking live tokens directly makes end() naturally
    // idempotent (removing an absent token is a no-op) and bounds memory by the real
    // in-flight count. The previous counter+ended-set had a bug: `ended.clear()` (to bound
    // memory) wiped the ended-marks, so a legitimate double-end after the clear decremented
    // the counter twice → activeCount under-reported (broke the documented idempotency).
    private val live = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Start a new play intent; invalidates all earlier tokens. */
    fun begin(): Long {
        val token = generation.incrementAndGet()
        live.add(token)
        return token
    }

    /** True iff no newer play intent has begun since [token]. */
    fun isCurrent(token: Long): Boolean = generation.get() == token

    /** End an intent. Idempotent per token (safe in finally after an
     *  ABORT-return that may already have ended it) — remove of an absent token is a no-op. */
    fun end(token: Long) {
        live.remove(token)
    }

    /** In-flight intents (debounce input). */
    fun activeCount(): Int = live.size
}
