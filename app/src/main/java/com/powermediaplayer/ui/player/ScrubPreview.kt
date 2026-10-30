package com.powermediaplayer.ui.player

import com.powermediaplayer.service.ChapterInfo
import com.powermediaplayer.util.TimeFormatter

/** Which bar is being scrubbed. */
enum class ScrubBar { TRACK, FULL }

/**
 * Position model in raw milliseconds. The view model derives it per tick / per pointer move,
 * applies a scrub preview to it, and formats it ONCE with [format] (AU3 efficiency #3: the old
 * path formatted eight labels and then overwrote up to six of them per pointer event).
 */
data class RawPosition(
    val trackProgress: Float,
    /** Displayed Track-bar position: chapter-relative inside a chapter, else file-relative. */
    val positionMs: Long,
    /** Displayed Track-bar length (chapter or file). */
    val durationMs: Long,
    val playlistProgress: Float,
    val playlistPositionMs: Long,
    val playlistDurationMs: Long,
    val chapterStartMs: Long,
    /** The LIVE displayed position. A TRACK preview moves [positionMs] but not this, so the
     *  synced-lyrics panel keeps following playback instead of chasing the finger (AU3 #2). */
    val livePositionMs: Long = positionMs,
    val scrubbing: Boolean = false,
    val scrubChapterTitle: String? = null
) {
    fun format(): PositionUi = PositionUi(
        trackProgress = trackProgress,
        positionFormatted = TimeFormatter.formatDuration(positionMs),
        durationFormatted = TimeFormatter.formatDuration(durationMs),
        remainingFormatted = "-" + TimeFormatter.formatDuration((durationMs - positionMs).coerceAtLeast(0L)),
        playlistProgress = playlistProgress,
        playlistPositionFormatted = TimeFormatter.formatDuration(playlistPositionMs),
        playlistDurationFormatted = TimeFormatter.formatDuration(playlistDurationMs),
        playlistRemainingFormatted = "-" + TimeFormatter.formatDuration(
            (playlistDurationMs - playlistPositionMs).coerceAtLeast(0L)
        ),
        chapterStartMs = chapterStartMs,
        durationMs = durationMs,
        totalPlaylistDurationMs = playlistDurationMs,
        positionMs = livePositionMs,
        scrubbing = scrubbing,
        scrubChapterTitle = scrubChapterTitle
    )
}

/**
 * 2026-10-30 (user video, issue 3): while the user drags a bar, the time labels only showed the
 * LIVE player position because the slider kept its drag value private and nothing re-derived
 * the labels until the finger lifted. The slider now reports the drag fraction to the view
 * model, and this pure function re-derives the position model from it, so both bars' labels,
 * the time bubble and the chapter chip follow the finger. Seeking still happens on release.
 *
 * TRACK (chapter / file bar): the labels show the previewed point inside the current chapter;
 * the Full bar's labels move with it (same absolute point).
 * FULL (album / book bar): the labels show the previewed absolute point; when it lands inside
 * the current file, the Track bar and the chapter chip show the chapter it would land in.
 */
fun applyScrubPreview(
    base: RawPosition,
    chapters: List<ChapterInfo>,
    preview: Pair<ScrubBar, Float>?
): RawPosition {
    if (preview == null) return base
    val (bar, rawFraction) = preview
    val f = rawFraction.coerceIn(0f, 1f)
    val total = base.playlistDurationMs
    // Where the current file starts inside the playlist: the playlist position minus the
    // absolute position inside the file (chapter start + chapter-relative position).
    val fileAbsNow = base.chapterStartMs + base.positionMs
    val itemStart = (base.playlistPositionMs - fileAbsNow).coerceAtLeast(0L)
    return when (bar) {
        ScrubBar.TRACK -> {
            val dur = base.durationMs
            val pos = (f * dur).toLong().coerceIn(0L, dur)
            val playlistPos = (itemStart + base.chapterStartMs + pos).coerceIn(0L, total)
            base.copy(
                trackProgress = f,
                positionMs = pos,
                playlistProgress = if (total > 0) (playlistPos.toFloat() / total).coerceIn(0f, 1f) else base.playlistProgress,
                playlistPositionMs = playlistPos,
                scrubbing = true
            )
        }
        ScrubBar.FULL -> {
            val playlistPos = (f * total).toLong().coerceIn(0L, total)
            var out = base.copy(playlistProgress = f, playlistPositionMs = playlistPos, scrubbing = true)
            val fileAbs = playlistPos - itemStart
            // Inside the current file? Then the Track bar and the chapter chip can preview too.
            if (chapters.isNotEmpty()) {
                val ch = chapters.lastOrNull { it.startTimeMs <= fileAbs }
                if (ch != null && fileAbs <= ch.endTimeMs) {
                    val chDur = (ch.endTimeMs - ch.startTimeMs).coerceAtLeast(0L)
                    val pos = (fileAbs - ch.startTimeMs).coerceIn(0L, chDur)
                    out = out.copy(
                        trackProgress = if (chDur > 0) (pos.toFloat() / chDur).coerceIn(0f, 1f) else 0f,
                        positionMs = pos,
                        durationMs = chDur,
                        scrubChapterTitle = ch.title
                    )
                }
            } else if (base.durationMs > 0 && fileAbs in 0L..base.durationMs) {
                out = out.copy(
                    trackProgress = (fileAbs.toFloat() / base.durationMs).coerceIn(0f, 1f),
                    positionMs = fileAbs
                )
            }
            out
        }
    }
}

/**
 * FOLDER-CH: the Track bar's position inside the current chapter. Embedded chapters are
 * file-relative, so the file position applies; folder-as-book chapters are queue-absolute, so
 * the playlist position applies — subtracting an absolute chapter start from a file-relative
 * position pinned the bar at 0:00 for every file after the first (pre-existing, found by the
 * 2026-10-30 audit).
 */
fun chapterRelativePosition(
    isFolderBook: Boolean,
    filePositionMs: Long,
    playlistPositionMs: Long,
    chapterStartMs: Long,
    chapterDurationMs: Long
): Long {
    val ref = if (isFolderBook) playlistPositionMs else filePositionMs
    return (ref - chapterStartMs).coerceIn(0L, chapterDurationMs.coerceAtLeast(0L))
}
