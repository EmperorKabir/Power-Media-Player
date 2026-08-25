package com.powermediaplayer.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [parsePubDateMs] (bug 2026-08-25): the old single RFC-822 pattern returned 0L
 * for seconds-omitted / ISO-8601 / colon-offset pubDates → episodes lost chronological
 * order + showed 1970. All offset-based cases below are timezone-deterministic.
 * 2021-09-06T08:00:00Z == 1_630_915_200_000 ms.
 */
class RssPubDateTest {
    private val ref = 1_630_915_200_000L // 2021-09-06T08:00:00Z

    @Test fun rfc822_withSeconds_offset() =
        assertEquals(ref, parsePubDateMs("Mon, 06 Sep 2021 08:00:00 +0000"))

    @Test fun rfc822_secondsOmitted_offset() =
        assertEquals(ref, parsePubDateMs("Mon, 06 Sep 2021 08:00 +0000"))

    @Test fun iso8601_zulu() =
        assertEquals(ref, parsePubDateMs("2021-09-06T08:00:00Z"))

    @Test fun iso8601_colonOffset() =
        assertEquals(ref, parsePubDateMs("2021-09-06T08:00:00+00:00"))

    @Test fun rfc822_gmt_namedZone() =
        assertEquals(ref, parsePubDateMs("Mon, 06 Sep 2021 08:00:00 GMT"))

    @Test fun offsetShiftsEpoch() =
        // +0100 means 08:00 local = 07:00 UTC → one hour earlier than ref.
        assertEquals(ref - 3_600_000L, parsePubDateMs("Mon, 06 Sep 2021 08:00:00 +0100"))

    @Test fun ordering_isChronological() {
        val earlier = parsePubDateMs("Mon, 06 Sep 2021 08:00:00 +0000")
        val later = parsePubDateMs("Tue, 07 Sep 2021 08:00:00 +0000")
        assertTrue("later > earlier", later > earlier)
        assertTrue("both parsed non-zero", earlier > 0 && later > 0)
    }

    @Test fun garbage_returnsZero() {
        assertEquals(0L, parsePubDateMs("not a date"))
        assertEquals(0L, parsePubDateMs(""))
        assertEquals(0L, parsePubDateMs("   "))
        // a partial match must NOT be accepted (full-string-consume guard)
        assertEquals(0L, parsePubDateMs("Mon, 06 Sep 2021"))
    }
}
