package com.powermediaplayer.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks the row title/subtext format: Title primary; subtext = Artist, Album,
 *  Filename (audiobook drops Album); blanks dropped with no superfluous commas;
 *  filename omitted when it IS the primary. */
class MediaRowTextTest {

    @Test fun song_full_fields_artist_album_filename() {
        val d = MediaRowText.of(
            title = "Starlight", artist = "Muse", album = "Black Holes and Revelations",
            fileName = "starlight.mp3", kind = AudioSubKind.SONG
        )
        assertEquals("Starlight", d.primary)
        assertEquals("Muse, Black Holes and Revelations, starlight.mp3", d.subtext)
    }

    @Test fun audiobook_drops_album_keeps_author_and_filename() {
        val d = MediaRowText.of(
            title = "A Gentleman in Moscow", artist = "Amor Towles", album = "Series",
            fileName = "gentleman.m4b", kind = AudioSubKind.AUDIOBOOK
        )
        assertEquals("A Gentleman in Moscow", d.primary)
        assertEquals("Amor Towles, gentleman.m4b", d.subtext)
    }

    @Test fun missing_fields_drop_without_superfluous_commas() {
        // No album → no double comma.
        val d = MediaRowText.of("Song", "Artist", "", "song.mp3", AudioSubKind.SONG)
        assertEquals("Artist, song.mp3", d.subtext)
        // No artist, no album → just filename.
        val d2 = MediaRowText.of("Song", null, null, "song.mp3", AudioSubKind.SONG)
        assertEquals("song.mp3", d2.subtext)
    }

    @Test fun no_title_falls_back_to_cleaned_filename_and_drops_filename_from_subtext() {
        // A raw-filename "title" is treated as absent → primary = cleaned filename,
        // and the filename does NOT repeat in the subtext.
        val d = MediaRowText.of(
            title = null, artist = "Muse", album = "Album",
            fileName = "starlight.mp3", kind = AudioSubKind.SONG
        )
        assertEquals("starlight", d.primary)
        assertEquals("Muse, Album", d.subtext)
    }

    @Test fun raw_filename_title_is_not_used_as_primary() {
        val d = MediaRowText.of(
            title = "Some Book [B07J669VH5].m4b", artist = "Author", album = null,
            fileName = "Some Book [B07J669VH5].m4b", kind = AudioSubKind.AUDIOBOOK
        )
        // primary is the cleaned filename, not the raw ASIN string.
        assertEquals("Author", d.subtext)
        assert(!d.primary.contains(".m4b")) { "primary should be cleaned, was ${d.primary}" }
    }

    @Test fun blank_everything_yields_unknown_primary_empty_subtext() {
        val d = MediaRowText.of(null, null, null, null, AudioSubKind.SONG)
        assertEquals("Unknown", d.primary)
        assertEquals("", d.subtext)
    }
}
