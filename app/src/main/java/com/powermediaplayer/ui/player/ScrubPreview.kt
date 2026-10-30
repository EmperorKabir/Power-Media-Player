package com.powermediaplayer.ui.player

import com.powermediaplayer.service.ChapterInfo
import com.powermediaplayer.util.TimeFormatter

/** Which bar is being scrubbed. */
enum class ScrubBar { TRACK, FULL }

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
    base: PositionUi,
    chapters: List<ChapterInfo>,
    preview: Pair<ScrubBar, Float>?
): PositionUi {
    if (preview == null) return base
    val (bar, rawFraction) = preview
    val f = rawFraction.coerceIn(0f, 1f)
    val total = base.totalPlaylistDurationMs
    // Where the current file starts inside the playlist: the playlist position minus the
    // absolute position inside the file (chapter start + chapter-relative position).
    val fileAbsNow = base.chapterStartMs + base.positionMs
    val playlistNow = (base.playlistProgress * total).toLong()
    val itemStart = (playlistNow - fileAbsNow).coerceAtLeast(0L)
    return when (bar) {
        ScrubBar.TRACK -> {
            val dur = base.durationMs
            val pos = (f * dur).toLong().coerceIn(0L, dur)
            val fileAbs = base.chapterStartMs + pos
            val playlistPos = (itemStart + fileAbs).coerceIn(0L, total)
            base.copy(
                trackProgress = f,
                positionFormatted = TimeFormatter.formatDuration(pos),
                remainingFormatted = "-" + TimeFormatter.formatDuration((dur - pos).coerceAtLeast(0L)),
                playlistProgress = if (total > 0) (playlistPos.toFloat() / total).coerceIn(0f, 1f) else base.playlistProgress,
                playlistPositionFormatted = TimeFormatter.formatDuration(playlistPos),
                playlistRemainingFormatted = "-" + TimeFormatter.formatDuration((total - playlistPos).coerceAtLeast(0L)),
                positionMs = pos,
                scrubbing = true
            )
        }
        ScrubBar.FULL -> {
            val playlistPos = (f * total).toLong().coerceIn(0L, total)
            var out = base.copy(
                playlistProgress = f,
                playlistPositionFormatted = TimeFormatter.formatDuration(playlistPos),
                playlistRemainingFormatted = "-" + TimeFormatter.formatDuration((total - playlistPos).coerceAtLeast(0L)),
                scrubbing = true
            )
            val fileAbs = playlistPos - itemStart
            // Inside the current file? Then the Track bar and the chapter chip can preview too.
            if (chapters.isNotEmpty()) {
                val ch = chapters.lastOrNull { it.startTimeMs <= fileAbs }
                if (ch != null && fileAbs <= ch.endTimeMs) {
                    val chDur = (ch.endTimeMs - ch.startTimeMs).coerceAtLeast(0L)
                    val pos = (fileAbs - ch.startTimeMs).coerceIn(0L, chDur)
                    out = out.copy(
                        trackProgress = if (chDur > 0) (pos.toFloat() / chDur).coerceIn(0f, 1f) else 0f,
                        positionFormatted = TimeFormatter.formatDuration(pos),
                        durationFormatted = TimeFormatter.formatDuration(chDur),
                        remainingFormatted = "-" + TimeFormatter.formatDuration((chDur - pos).coerceAtLeast(0L)),
                        scrubChapterTitle = ch.title
                    )
                }
            } else if (base.durationMs > 0 && fileAbs in 0L..base.durationMs) {
                val dur = base.durationMs
                out = out.copy(
                    trackProgress = (fileAbs.toFloat() / dur).coerceIn(0f, 1f),
                    positionFormatted = TimeFormatter.formatDuration(fileAbs),
                    remainingFormatted = "-" + TimeFormatter.formatDuration((dur - fileAbs).coerceAtLeast(0L))
                )
            }
            out
        }
    }
}
