package com.powermediaplayer

import com.powermediaplayer.util.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit 4.1 — the library sort moved from per-comparison
 * Collator.getInstance + double normalisation to precomputed
 * CollationKeys. Key ordering must match the old compare() ordering.
 */
class LibrarySearchSortTest {

    @Test
    fun `collation keys order like collator compare`() {
        val locale = java.util.Locale.UK
        val names = listOf("Émile", "emil", "Zebra", "apple", "Ápple", "zebra 2")
        val byKey = names.sortedBy {
            TextNormalizer.collationKey(TextNormalizer.normalize(it), locale)
        }
        val byCompare = names.sortedWith { a, b -> TextNormalizer.compare(a, b, locale) }
        assertEquals(byCompare, byKey)
    }

    @Test
    fun `cached collator is reused for the same locale`() {
        val a = TextNormalizer.collator(java.util.Locale.UK)
        val b = TextNormalizer.collator(java.util.Locale.UK)
        org.junit.Assert.assertSame(a, b)
    }
}
