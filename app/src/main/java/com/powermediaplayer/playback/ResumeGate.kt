package com.powermediaplayer.playback

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * vc32 (E12/E13): process-wide resume coordination. Instance-field
 * guards died with their ViewModel (destination-scoped VMs are cleared
 * on back-stack pop — both ghost-bug taps logged attempt=1), so the
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
    private val active = AtomicInteger(0)
    private val ended = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Start a new play intent; invalidates all earlier tokens. */
    fun begin(): Long {
        active.incrementAndGet()
        return generation.incrementAndGet()
    }

    /** True iff no newer play intent has begun since [token]. */
    fun isCurrent(token: Long): Boolean = generation.get() == token

    /** End an intent. Idempotent per token (safe in finally after an
     *  ABORT-return that may already have ended it). */
    fun end(token: Long) {
        if (ended.add(token)) {
            active.updateAndGet { (it - 1).coerceAtLeast(0) }
            if (ended.size > 64) ended.clear() // tokens monotonic; bound memory
        }
    }

    /** In-flight intents (debounce input). */
    fun activeCount(): Int = active.get()
}
