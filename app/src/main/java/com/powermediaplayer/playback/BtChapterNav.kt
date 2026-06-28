package com.powermediaplayer.playback

import com.powermediaplayer.data.preferences.BtMediaKind

/**
 * Pure resolver for the playing item's Bluetooth media kind (M1/M2). Chapter
 * markers win over the http-podcast heuristic so a cloud-streamed .m4b
 * audiobook (http URI WITH chapters) is treated as AUDIOBOOK (chapter nav),
 * not PODCAST (skip). Extracted + unit-tested (the http/chapter conflation is
 * exactly the branch a test catches). Note: cloud-STREAMED music (http, no
 * chapters) still resolves to PODCAST under the bare-http heuristic — a minor
 * skip-vs-track difference, acceptable until a podcast-DB lookup cache lands.
 */
object BtKindResolver {
    fun resolve(isVideo: Boolean, hasChapters: Boolean, isPodcast: Boolean): BtMediaKind = when {
        isVideo -> BtMediaKind.VIDEO
        hasChapters -> BtMediaKind.AUDIOBOOK
        isPodcast -> BtMediaKind.PODCAST
        else -> BtMediaKind.MUSIC
    }
}

/**
 * Pure chapter-navigation maths for the Bluetooth/car path (M2). Single-file
 * M4B audiobooks carry chapter start offsets on the MediaItem metadata; the
 * service reads those and uses these helpers to pick the seek target with no
 * UI / MediaController attached. Kept pure (no Android types) so it is unit
 * tested directly. Mirrors PlaybackConnection.next/previousChapterOrTrack.
 */
object BtChapterNav {

    /**
     * The next chapter start strictly after [posMs] (+ a small epsilon so a
     * tap right on a boundary doesn't no-op). Null when already in/after the
     * last chapter — the caller then advances to the next media item.
     */
    fun nextStart(starts: List<Long>, posMs: Long, epsilonMs: Long = 250L): Long? =
        starts.sorted().firstOrNull { it > posMs + epsilonMs }

    /**
     * Previous-chapter target:
     *  - restart the current chapter if more than [graceMs] into it,
     *  - else the previous chapter's start,
     *  - else null (at/near the first chapter start) → caller falls back to the
     *    previous media item, or position 0.
     */
    fun prevTarget(starts: List<Long>, posMs: Long, graceMs: Long = 3000L): Long? {
        if (starts.isEmpty()) return null
        val s = starts.sorted()
        var cur = 0
        for (i in s.indices) { if (s[i] <= posMs) cur = i else break }
        return when {
            posMs - s[cur] > graceMs -> s[cur]
            cur > 0 -> s[cur - 1]
            else -> null
        }
    }
}
