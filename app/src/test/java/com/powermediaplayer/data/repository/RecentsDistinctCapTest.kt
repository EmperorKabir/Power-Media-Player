package com.powermediaplayer.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [rowsBeyondDistinctCap] (bug 2026-08-25): Recents
 * must cap on DISTINCT items, so replaying one item never evicts other distinct entries.
 * Rows are (rowId, mediaUri) in lastPlayedAt-DESC order.
 */
class RecentsDistinctCapTest {

    @Test
    fun replayedItem_doesNotEvictOtherDistinctItem() {
        // [A×20, B]: the old drop(20) deleted B. Distinct-cap keeps A + B → deletes nothing.
        val rows = (1L..20L).map { it to "A" } + (21L to "B")
        val toDelete = rowsBeyondDistinctCap(rows, 20)
        assertTrue("B (and all A rows) retained", toDelete.isEmpty())
    }

    @Test
    fun distinctItemsBeyondCap_areDeleted() {
        // 25 distinct items → keep the 20 most-recent, delete the 5 oldest.
        val rows = (1L..25L).map { it to "item$it" }
        val toDelete = rowsBeyondDistinctCap(rows, 20)
        assertEquals(5, toDelete.size)
        assertEquals(setOf(21L, 22L, 23L, 24L, 25L), toDelete.toSet())
    }

    @Test
    fun keptItemsSessionRows_allRetained() {
        // 20 distinct kept; an OLD extra session row of a KEPT uri is retained (bookmark anchor).
        val rows = (1L..20L).map { it to "item$it" } + (99L to "item1")
        val toDelete = rowsBeyondDistinctCap(rows, 20)
        assertTrue("old session row of a kept item retained", 99L !in toDelete)
        assertTrue(toDelete.isEmpty())
    }

    @Test
    fun oldSessionRowOfEvictedItem_deleted() {
        // 21 distinct → item21 evicted; a stray old row of item21 also goes.
        val rows = (1L..21L).map { it to "item$it" } + (99L to "item21")
        val toDelete = rowsBeyondDistinctCap(rows, 20)
        assertEquals(setOf(21L, 99L), toDelete.toSet())
    }

    @Test
    fun underCap_deletesNothing() {
        val rows = (1L..5L).map { it to "item$it" }
        assertTrue(rowsBeyondDistinctCap(rows, 20).isEmpty())
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(rowsBeyondDistinctCap(emptyList(), 20).isEmpty())
    }

    @Test
    fun mixedReplayAndDistinct_keepsExactly20Distinct() {
        // A replayed 10× interleaved, plus 25 other distinct items.
        val replay = (1L..10L).map { it to "A" }
        val distinct = (11L..35L).map { it to "d$it" }
        val rows = (replay + distinct).sortedByDescending { it.first } // DESC by id
        val toDelete = rowsBeyondDistinctCap(rows, 20)
        // 26 distinct uris (A + d11..d35). Keep 20 most-recent distinct, delete the rest's rows.
        val keptUris = rows.filter { it.first !in toDelete.toSet() }.map { it.second }.toSet()
        assertEquals(20, keptUris.size)
    }
}
