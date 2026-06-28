package com.powermediaplayer.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/** M1 — per-file-type BT mapping defaults + kind resolution. */
class BtMappingDefaultsTest {

    @Test fun audiobookDefaultsToChapterNav() {
        val m = DEFAULT_BT_MAPPINGS.getValue(BtMediaKind.AUDIOBOOK)
        assertEquals(BluetoothMediaActions.PREV_CHAPTER, m.prevAction)
        assertEquals(BluetoothMediaActions.NEXT_CHAPTER, m.nextAction)
    }

    @Test fun podcastDefaultsToSkip_15back_30fwd() {
        val m = DEFAULT_BT_MAPPINGS.getValue(BtMediaKind.PODCAST)
        assertEquals(BluetoothMediaActions.SKIP_BACK, m.prevAction)
        assertEquals(BluetoothMediaActions.SKIP_FORWARD, m.nextAction)
        assertEquals(15, m.skipBackSeconds)
        assertEquals(30, m.skipForwardSeconds)
    }

    @Test fun musicAndVideoDefaultToTrackNav() {
        for (k in listOf(BtMediaKind.MUSIC, BtMediaKind.VIDEO)) {
            val m = DEFAULT_BT_MAPPINGS.getValue(k)
            assertEquals(BluetoothMediaActions.PREV_TRACK, m.prevAction)
            assertEquals(BluetoothMediaActions.NEXT_TRACK, m.nextAction)
        }
    }

    @Test fun forKindResolvesEachKindIndependently() {
        val set = BtMappingSet.DEFAULT
        assertEquals(DEFAULT_BT_MAPPINGS.getValue(BtMediaKind.AUDIOBOOK), set.forKind(BtMediaKind.AUDIOBOOK))
        assertEquals(DEFAULT_BT_MAPPINGS.getValue(BtMediaKind.PODCAST), set.forKind(BtMediaKind.PODCAST))
        assertEquals(DEFAULT_BT_MAPPINGS.getValue(BtMediaKind.MUSIC), set.forKind(BtMediaKind.MUSIC))
        assertEquals(DEFAULT_BT_MAPPINGS.getValue(BtMediaKind.VIDEO), set.forKind(BtMediaKind.VIDEO))
        // audiobook (chapter) must differ from music (track) — proves the split.
        assertEquals(BluetoothMediaActions.NEXT_CHAPTER, set.forKind(BtMediaKind.AUDIOBOOK).nextAction)
        assertEquals(BluetoothMediaActions.NEXT_TRACK, set.forKind(BtMediaKind.MUSIC).nextAction)
    }
}
