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

    /** Lower-cased file extension after the last dot, or "" when none. */
    private fun extOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

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
