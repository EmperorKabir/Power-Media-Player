package com.powermediaplayer.util

/**
 * Shared "Title + descriptive subtext" formatter for media rows (Cloud browse /
 * favourites, the Library "Downloaded" section, the Downloads manager).
 *
 * Primary  = the enriched TITLE (falls back to the cleaned filename when there is
 *            no real title tag). Rendered in the theme accent colour by callers.
 * Subtext  = comma-joined descriptive fields, blank fields dropped with NO
 *            superfluous commas, the raw filename last:
 *              - Audiobook           → Author, Filename            (album omitted)
 *              - Song / podcast / etc → Artist, Album, Filename
 * The raw filename is omitted from the subtext when the primary was derived FROM
 * it (a tagless file), so it never shows twice.
 *
 * Pure logic (no Android imports) → JVM-unit-testable, mirroring [MediaClassifier].
 */
object MediaRowText {
    data class Display(val primary: String, val subtext: String)

    fun of(
        title: String?,
        artist: String?,
        album: String?,
        fileName: String?,
        kind: AudioSubKind = AudioSubKind.SONG
    ): Display {
        val t = title?.trim().orEmpty()
        // A raw-filename "title" (…[ASIN].m4b) is NOT a real title — treat as absent
        // so the primary falls back to the cleaned filename, not the junk string.
        val hasTitle = t.isNotBlank() && !MediaClassifier.looksLikeRawMediaFilename(t)
        val rawFile = fileName?.trim().orEmpty()
        val primary = if (hasTitle) t
            else TextNormalizer.cleanFileTitle(rawFile).trim().ifBlank { rawFile }.ifBlank { "Unknown" }

        val parts = ArrayList<String>(3)
        artist?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (kind != AudioSubKind.AUDIOBOOK) {
            album?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        }
        // Raw filename last — only when a real title exists (else the primary already
        // IS the filename, so repeating it is noise).
        if (hasTitle) rawFile.takeIf { it.isNotBlank() && it != primary }?.let { parts.add(it) }

        return Display(primary, parts.joinToString(", "))
    }
}
