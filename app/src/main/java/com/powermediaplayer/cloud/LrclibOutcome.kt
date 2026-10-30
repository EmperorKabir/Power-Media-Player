package com.powermediaplayer.cloud

/**
 * RT3 (2026-10-30): the user's "lyrics no longer loading" was lrclib.net answering HTTP 503
 * ServerOverloaded to every lookup (phone log + PC probe). The fetch treated ANY non-success as
 * "no lyrics" and cached that miss for the process lifetime, so tracks seen during the outage
 * stayed lyric-less until the app was killed. A service error is now distinguished from a
 * genuine miss: it is never cached, the panel says the service is busy, and the lookup retries.
 */
enum class LrclibOutcome { FOUND, NOT_FOUND, SERVICE_ERROR }

object LrclibClassifier {
    /** 2xx with content → FOUND; 2xx without content or 404 → NOT_FOUND (cacheable miss);
     *  anything else (429, 5xx, network) → SERVICE_ERROR (transient, never cached). */
    fun classify(httpCode: Int, hasContent: Boolean): LrclibOutcome = when {
        httpCode in 200..299 -> if (hasContent) LrclibOutcome.FOUND else LrclibOutcome.NOT_FOUND
        httpCode == 404 -> LrclibOutcome.NOT_FOUND
        else -> LrclibOutcome.SERVICE_ERROR
    }

    /** Retry delays after a SERVICE_ERROR for the same track (ms); null = give up. */
    fun retryDelayMs(attempt: Int): Long? = when (attempt) {
        0 -> 15_000L
        1 -> 45_000L
        2 -> 120_000L
        else -> null
    }
}
