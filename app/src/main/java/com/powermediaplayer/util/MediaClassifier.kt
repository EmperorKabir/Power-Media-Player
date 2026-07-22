package com.powermediaplayer.util

/** Audio sub-kind for display/labels only. Not used to gate playback or BT. */
enum class AudioSubKind { PODCAST, AUDIOBOOK, SONG }

/**
 * Pure-logic media classification. Single source of truth for:
 *  - whether a cloud row is video (#13 — the .m4b-in-video/mp4 false-positive);
 *  - the audio sub-kind for player labels (#8 — podcast/audiobook/song);
 *  - whether a Library row may attempt a video-frame thumbnail (#12).
 *
 * No Android imports → JVM-unit-testable. The extension is authoritative over
 * the container mime because Drive labels MP4-container files (including .m4b
 * audiobooks) "video/mp4": the container CAN hold video so the mime is
 * ambiguous; the file extension is not. Mirrors the play-path override in
 * CloudViewModel.openItemInternal.
 */
object MediaClassifier {

    /** Audio container/codec extensions — verbatim from the play-path set. */
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "m4b", "m4a", "m4p", "m4r", "mp3", "flac", "ogg", "oga",
        "opus", "wav", "wave", "aac", "aiff", "aif", "ape", "wma"
    )

    /** Audiobook-by-extension set (extension-authoritative). */
    private val AUDIOBOOK_EXTENSIONS: Set<String> = setOf("m4b")

    /** Video container extensions — raw-filename detection only. */
    private val VIDEO_CONTAINER_EXTENSIONS: Set<String> = setOf(
        "mp4", "mkv", "webm", "m4v", "avi", "mov", "3gp", "ts",
        "wmv", "flv", "mpg", "mpeg"
    )

    /**
     * Every extension that marks a title as a RAW media filename (audio + video
     * containers). TextNormalizer.cleanFileTitle DERIVES its strip-regex from
     * THIS set, so the flag-set and the strip-set can never drift — a file whose
     * ext is flagged here but not stripped there would loop the raw-filename heal
     * forever (e.g. was the case for .mpg/.wmv/.ape/.aiff/.m4p before this).
     */
    val RAW_MEDIA_EXTENSIONS: Set<String> = AUDIO_EXTENSIONS + VIDEO_CONTAINER_EXTENSIONS

    /** Lower-cased file extension after the last dot, or "" when none. */
    private fun extOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    /**
     * 2026-07-22: is this a playable media file by EITHER its name extension OR
     * its mime prefix? Cloud stores (Drive) frequently label audiobooks and even
     * some audio as `application/octet-stream`, so a mime-only test drops them.
     * The extension set is authoritative; the mime prefix is a fallback for
     * extensionless names.
     */
    fun isMediaByName(name: String, mimeType: String): Boolean {
        if (extOf(name) in RAW_MEDIA_EXTENSIONS) return true
        val m = mimeType.lowercase()
        return m.startsWith("audio/") || m.startsWith("video/")
    }

    /**
     * True when [title] is still a RAW media filename ("…[B0F14RFHS6].m4b")
     * rather than an embedded-tag title. Drives the cold-start heal-re-enrich
     * (PlaybackSessionCoordinator): a stored title that ends in a media
     * extension means the row never got its embedded title, so re-enrich even
     * when chapters are already cached. A clean embedded title never ends in a
     * media extension, so a healthy item returns false and skips the re-download.
     * Strips a trailing ?query / #fragment first (URL-derived names).
     */
    fun looksLikeRawMediaFilename(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val ext = title.trim()
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase()
        return ext in RAW_MEDIA_EXTENSIONS
    }

    /**
     * Pick the first NON-raw-filename, non-blank title from [candidates]
     * (already in priority / recency order). The enriched title of an item is
     * the same across every play session, so a raw-filename title can be healed
     * from a clean sibling Last Played row / enrichment_cache entry WITHOUT a
     * re-download. Returns null when every candidate is blank or still a raw
     * filename (then a real enrich download is the only source). Trims the winner.
     */
    fun firstNonRawTitle(candidates: List<String?>): String? =
        candidates.firstOrNull { !it.isNullOrBlank() && !looksLikeRawMediaFilename(it) }
            ?.trim()

    /**
     * True iff [name]/[mimeType] denote a VIDEO file. Extension wins: an audio
     * extension (incl. .m4b) forces false even when the mime starts with
     * "video/"; otherwise fall back to the mime.
     */
    fun isVideoByName(name: String, mimeType: String): Boolean {
        val ext = extOf(name)
        return when {
            ext in AUDIO_EXTENSIONS -> false
            else -> mimeType.lowercase().startsWith("video/")
        }
    }

    /**
     * #12 gate — should this LIBRARY row attempt a video-frame thumbnail?
     * Requires the authoritative MediaStore video flag AND that the name is not
     * an audio extension (defence-in-depth so an .m4b can never be frame-decoded
     * even if a future path mis-sets [mediaStoreIsVideo]).
     */
    fun shouldThumbnailVideo(name: String, mediaStoreIsVideo: Boolean): Boolean =
        mediaStoreIsVideo && extOf(name) !in AUDIO_EXTENSIONS

    /**
     * Podcast wins (a known episode is a podcast regardless of chapters); else
     * .m4b OR parsed chapters → audiobook; else a song.
     *
     * @param isPodcast caller-resolved membership (e.g.
     *   PodcastDao.episodeByAudioUrl(uri) != null). Plain Boolean → pure/DB-free.
     */
    fun classifyAudioSubKind(
        name: String,
        hasChapters: Boolean,
        isPodcast: Boolean
    ): AudioSubKind = when {
        isPodcast -> AudioSubKind.PODCAST
        extOf(name) in AUDIOBOOK_EXTENSIONS || hasChapters -> AudioSubKind.AUDIOBOOK
        else -> AudioSubKind.SONG
    }
}
