package com.powermediaplayer.service

import androidx.media3.session.CommandButton
import com.powermediaplayer.data.preferences.AutoButtonConfig
import com.powermediaplayer.data.preferences.AutoButtonSet
import com.powermediaplayer.data.preferences.AutoButtons
import com.powermediaplayer.data.preferences.BtMediaKind
import com.powermediaplayer.data.preferences.DEFAULT_AUTO_BUTTONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoButtonLayoutTest {

    private fun tokens(specs: List<AutoButtonSpec>) = specs.map { it.token }

    @Test
    fun audiobook_default_puts_skips_in_the_slots_and_chapters_first_behind_them() {
        val specs = resolveAutoButtons(AutoButtonSet.DEFAULT.audiobook, compact = false)
        assertEquals(AutoButtonSpec(AutoButtons.SKIP_BACK, CommandButton.SLOT_BACK), specs[0])
        assertEquals(AutoButtonSpec(AutoButtons.SKIP_FORWARD, CommandButton.SLOT_FORWARD), specs[1])
        assertEquals(
            listOf(AutoButtons.PREV_CHAPTER, AutoButtons.NEXT_CHAPTER, AutoButtons.SPEED,
                AutoButtons.FAVOURITE, AutoButtons.BOOKMARK),
            tokens(specs.drop(2))
        )
        assertTrue(specs.drop(2).all { it.slot == CommandButton.SLOT_OVERFLOW })
    }

    @Test
    fun music_default_keeps_the_standard_track_buttons() {
        val specs = resolveAutoButtons(AutoButtonSet.DEFAULT.music, compact = false)
        assertTrue(specs.none { it.slot == CommandButton.SLOT_BACK || it.slot == CommandButton.SLOT_FORWARD })
        assertEquals(
            listOf(AutoButtons.FAVOURITE, AutoButtons.SPEED, AutoButtons.SKIP_BACK,
                AutoButtons.SKIP_FORWARD, AutoButtons.BOOKMARK),
            tokens(specs)
        )
    }

    @Test
    fun compact_client_gets_only_what_fits_the_five_slot_bar() {
        val book = resolveAutoButtons(AutoButtonSet.DEFAULT.audiobook, compact = true)
        assertEquals(
            listOf(AutoButtons.SKIP_BACK, AutoButtons.SKIP_FORWARD, AutoButtons.PREV_CHAPTER, AutoButtons.NEXT_CHAPTER),
            tokens(book)
        )
        val music = resolveAutoButtons(AutoButtonSet.DEFAULT.music, compact = true)
        assertEquals(listOf(AutoButtons.FAVOURITE, AutoButtons.SPEED), tokens(music))
    }

    @Test
    fun a_button_used_by_a_slot_is_never_repeated_in_the_extras() {
        val config = AutoButtonConfig(
            AutoButtons.SLOT_CHAPTER, AutoButtons.SLOT_SKIP,
            listOf(AutoButtons.PREV_CHAPTER, AutoButtons.SKIP_FORWARD, AutoButtons.SPEED, AutoButtons.SPEED)
        )
        val specs = resolveAutoButtons(config, compact = false)
        assertEquals(AutoButtonSpec(AutoButtons.PREV_CHAPTER, CommandButton.SLOT_BACK), specs[0])
        assertEquals(AutoButtonSpec(AutoButtons.SKIP_FORWARD, CommandButton.SLOT_FORWARD), specs[1])
        assertEquals(listOf(AutoButtons.SPEED), tokens(specs.drop(2)))
    }

    @Test
    fun unknown_tokens_are_ignored_and_the_list_can_be_empty() {
        val config = AutoButtonConfig(AutoButtons.SLOT_TRACK, "nonsense", listOf("bogus"))
        assertEquals(emptyList<AutoButtonSpec>(), resolveAutoButtons(config, compact = false))
    }

    @Test
    fun extras_codec_round_trips_order_drops_unknowns_and_keeps_empty_distinct_from_unset() {
        val extras = listOf(AutoButtons.BOOKMARK, AutoButtons.SPEED, "junk", AutoButtons.SPEED)
        val encoded = AutoButtons.encodeExtras(extras)
        assertEquals(listOf(AutoButtons.BOOKMARK, AutoButtons.SPEED), AutoButtons.decodeExtras(encoded))
        assertEquals("", AutoButtons.encodeExtras(emptyList()))
        assertEquals(emptyList<String>(), AutoButtons.decodeExtras(""))
    }

    @Test
    fun every_kind_has_a_default_and_every_default_token_is_known() {
        BtMediaKind.values().forEach { kind ->
            val d = DEFAULT_AUTO_BUTTONS.getValue(kind)
            assertEquals(d, AutoButtonSet.DEFAULT.forKind(kind))
            assertTrue(d.leftSlot in AutoButtons.SLOT_TOKENS)
            assertTrue(d.rightSlot in AutoButtons.SLOT_TOKENS)
            assertTrue(d.extras.all { it in AutoButtons.EXTRA_TOKENS })
            assertEquals(d.extras.size, d.extras.distinct().size)
        }
    }
}
