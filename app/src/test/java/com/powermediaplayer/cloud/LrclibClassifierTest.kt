package com.powermediaplayer.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrclibClassifierTest {
    @Test fun outcomes() {
        assertEquals(LrclibOutcome.FOUND, LrclibClassifier.classify(200, true))
        assertEquals(LrclibOutcome.NOT_FOUND, LrclibClassifier.classify(200, false))
        assertEquals(LrclibOutcome.NOT_FOUND, LrclibClassifier.classify(404, false))
        assertEquals(LrclibOutcome.SERVICE_ERROR, LrclibClassifier.classify(503, false))   // the 2026-10-30 outage
        assertEquals(LrclibOutcome.SERVICE_ERROR, LrclibClassifier.classify(520, false))   // the Cloudflare UA block
        assertEquals(LrclibOutcome.SERVICE_ERROR, LrclibClassifier.classify(429, false))
    }

    @Test fun retriesBackOffThenStop() {
        assertEquals(15_000L, LrclibClassifier.retryDelayMs(0))
        assertEquals(45_000L, LrclibClassifier.retryDelayMs(1))
        assertEquals(120_000L, LrclibClassifier.retryDelayMs(2))
        assertNull(LrclibClassifier.retryDelayMs(3))
    }
}
