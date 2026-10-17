package com.powermediaplayer.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.powermediaplayer.data.preferences.AutoButtonConfig
import com.powermediaplayer.data.preferences.AutoButtons

/** One resolved car button: the action token and the Media3 slot it is offered for. */
internal data class AutoButtonSpec(val token: String, val slot: Int)

/** Positions the AOSP Automotive media centre's bar has besides Play (two of them are the
 *  previous/next positions, custom or standard). */
internal const val AUTO_COMPACT_POSITIONS = 4

/**
 * Resolve the user's Android Auto button choice for one media kind into the ordered list
 * Media3 is given (pure; see AutoButtonLayoutTest).
 *  - Left/right of Play: "skip" / "chapter" put a custom button into SLOT_BACK / SLOT_FORWARD
 *    (Media3 then hides the car's standard previous/next); "track" leaves the standard button.
 *  - Extras follow in the user's order as overflow buttons; a button already used by a slot is
 *    dropped so nothing appears twice.
 *  - [compact] (the AOSP media centre, whose ⋮ never renders): only what fits its bar, i.e. the
 *    two slot positions plus the first two extras.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun resolveAutoButtons(config: AutoButtonConfig, compact: Boolean): List<AutoButtonSpec> {
    val out = ArrayList<AutoButtonSpec>(8)
    val used = HashSet<String>()
    fun slotToken(choice: String, back: Boolean): String? = when (choice) {
        AutoButtons.SLOT_SKIP -> if (back) AutoButtons.SKIP_BACK else AutoButtons.SKIP_FORWARD
        AutoButtons.SLOT_CHAPTER -> if (back) AutoButtons.PREV_CHAPTER else AutoButtons.NEXT_CHAPTER
        else -> null
    }
    slotToken(config.leftSlot, back = true)?.let {
        out += AutoButtonSpec(it, CommandButton.SLOT_BACK); used += it
    }
    slotToken(config.rightSlot, back = false)?.let {
        out += AutoButtonSpec(it, CommandButton.SLOT_FORWARD); used += it
    }
    val extras = config.extras.filter { it in AutoButtons.EXTRA_TOKENS && it !in used }.distinct()
    val limit = if (compact) AUTO_COMPACT_POSITIONS - 2 else Int.MAX_VALUE
    extras.take(limit).forEach { out += AutoButtonSpec(it, CommandButton.SLOT_OVERFLOW) }
    return out
}
