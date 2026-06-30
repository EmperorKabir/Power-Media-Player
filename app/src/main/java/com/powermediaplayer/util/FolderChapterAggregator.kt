package com.powermediaplayer.util

import com.powermediaplayer.service.ChapterInfo
import com.powermediaplayer.ui.library.MediaFileInfo

/**
 * Treats a folder of media files as a single audiobook / podcast where
 * each file becomes one chapter. Folder-wide chapter timestamps are
 * computed by accumulating per-file durations so the chapter picker can
 * display "Chapter 7, 02:14:33" referring to the absolute position
 * across the entire queue.
 */
object FolderChapterAggregator {

    /**
     * Sorts [files] using natural ordering (so file_2 < file_10) via
     * [TextNormalizer.collator] then walks them, emitting one
     * [ChapterInfo] per file with absolute startMs / endMs.
     *
     * Files with non-positive durations are skipped — their durations
     * cannot be measured so they cannot anchor a chapter timeline.
     */
    fun aggregate(files: List<MediaFileInfo>): List<ChapterInfo> {
        if (files.isEmpty()) return emptyList()

        val sorted = files.sortedWith(naturalComparator())
        val chapters = mutableListOf<ChapterInfo>()
        var cursor = 0L

        for ((index, file) in sorted.withIndex()) {
            if (file.duration <= 0L) continue
            val start = cursor
            val end = cursor + file.duration
            chapters.add(
                ChapterInfo(
                    title = TextNormalizer.normalize(file.title),
                    startTimeMs = start,
                    endTimeMs = end,
                    index = index
                )
            )
            cursor = end
        }
        return chapters
    }

    /**
     * Returns the same sorted order [aggregate] uses, exposed for callers
     * that need to feed [files] in the same order to a media queue so
     * chapter index N corresponds to media-item index N.
     */
    fun naturalSort(files: List<MediaFileInfo>): List<MediaFileInfo> =
        files.sortedWith(naturalComparator())

    private fun naturalComparator(): Comparator<MediaFileInfo> {
        val collator = TextNormalizer.collator()
        // Pre-tokenise titles into alternating non-digit / digit chunks so
        // numeric segments compare numerically while text segments use the
        // Unicode collator. This handles "Track 02" < "Track 10" correctly.
        return Comparator { a, b ->
            naturalCompare(a.title, b.title, collator)
        }
    }

    private fun naturalCompare(a: String, b: String, collator: java.text.Collator): Int {
        val na = TextNormalizer.normalize(a)
        val nb = TextNormalizer.normalize(b)
        val ta = tokenise(na)
        val tb = tokenise(nb)
        val limit = minOf(ta.size, tb.size)
        for (i in 0 until limit) {
            val cmp = compareTokens(ta[i], tb[i], collator)
            if (cmp != 0) return cmp
        }
        return ta.size - tb.size
    }

    private data class Token(val text: String, val isNumber: Boolean) {
        val numericValue: Long? = if (isNumber) text.toLongOrNull() else null
    }

    private fun tokenise(s: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < s.length) {
            val isDigit = s[i].isDigit()
            var j = i + 1
            while (j < s.length && s[j].isDigit() == isDigit) j++
            tokens.add(Token(s.substring(i, j), isDigit))
            i = j
        }
        return tokens
    }

    private fun compareTokens(a: Token, b: Token, collator: java.text.Collator): Int {
        if (a.isNumber && b.isNumber) {
            val na = a.numericValue
            val nb = b.numericValue
            if (na != null && nb != null) return na.compareTo(nb)
        }
        return collator.compare(a.text, b.text)
    }
}
