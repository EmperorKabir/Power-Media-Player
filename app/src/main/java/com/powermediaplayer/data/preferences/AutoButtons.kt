package com.powermediaplayer.data.preferences

/**
 * String tokens for the user's Android Auto button choice (stored verbatim in DataStore, so a
 * future schema change is grep-able). One [AutoButtonConfig] per [BtMediaKind]:
 *  - [AutoButtonConfig.leftSlot] / [AutoButtonConfig.rightSlot]: what sits either side of Play.
 *    That pair is also what the car's small player bar and dashboard card show. "track" keeps
 *    the car's own previous/next track buttons; "chapter" and "skip" put a custom button there
 *    (Media3 then hides the standard one).
 *  - [AutoButtonConfig.extras]: the remaining buttons in the user's order. The car shows the
 *    first ones beside the slot pair and puts the rest in its three dot menu.
 */
object AutoButtons {
    const val SLOT_SKIP = "skip"
    const val SLOT_CHAPTER = "chapter"
    const val SLOT_TRACK = "track"
    val SLOT_TOKENS: List<String> = listOf(SLOT_SKIP, SLOT_CHAPTER, SLOT_TRACK)

    const val SPEED = "speed"
    const val PREV_CHAPTER = "prev_chapter"
    const val NEXT_CHAPTER = "next_chapter"
    const val SKIP_BACK = "skip_back"
    const val SKIP_FORWARD = "skip_forward"
    const val FAVOURITE = "favourite"
    const val BOOKMARK = "bookmark"
    const val SHUFFLE = "shuffle"
    const val REPEAT = "repeat"
    val EXTRA_TOKENS: List<String> =
        listOf(SPEED, PREV_CHAPTER, NEXT_CHAPTER, SKIP_BACK, SKIP_FORWARD, FAVOURITE, BOOKMARK,
            SHUFFLE, REPEAT)

    // Ordered list → one string (a StringSet would lose the order); unknown tokens are dropped
    // on both sides so a stale value from an older build can never crash the car layout.
    private const val SEP = "\u001F"
    fun encodeExtras(extras: List<String>): String =
        extras.filter { it in EXTRA_TOKENS }.distinct().joinToString(SEP)
    fun decodeExtras(raw: String): List<String> =
        if (raw.isEmpty()) emptyList() else raw.split(SEP).filter { it in EXTRA_TOKENS }.distinct()
}

/** The Android Auto button choice for one media kind. */
data class AutoButtonConfig(
    val leftSlot: String,
    val rightSlot: String,
    val extras: List<String>,
)

/** The four per-kind choices, snapshotted for the binder-thread session callbacks. */
data class AutoButtonSet(
    val audiobook: AutoButtonConfig,
    val podcast: AutoButtonConfig,
    val music: AutoButtonConfig,
    val video: AutoButtonConfig,
) {
    fun forKind(kind: BtMediaKind): AutoButtonConfig = when (kind) {
        BtMediaKind.AUDIOBOOK -> audiobook
        BtMediaKind.PODCAST -> podcast
        BtMediaKind.MUSIC -> music
        BtMediaKind.VIDEO -> video
    }
    companion object {
        val DEFAULT = AutoButtonSet(
            audiobook = DEFAULT_AUTO_BUTTONS.getValue(BtMediaKind.AUDIOBOOK),
            podcast = DEFAULT_AUTO_BUTTONS.getValue(BtMediaKind.PODCAST),
            music = DEFAULT_AUTO_BUTTONS.getValue(BtMediaKind.MUSIC),
            video = DEFAULT_AUTO_BUTTONS.getValue(BtMediaKind.VIDEO),
        )
    }
}

/**
 * Defaults = the vc118 layout: books and podcasts get the skips either side of Play with
 * chapter nav, speed, favourite and bookmark behind them; music and video keep the car's own
 * track buttons with favourite, speed, the skips and bookmark behind them.
 */
val DEFAULT_AUTO_BUTTONS: Map<BtMediaKind, AutoButtonConfig> = mapOf(
    BtMediaKind.AUDIOBOOK to AutoButtonConfig(
        AutoButtons.SLOT_SKIP, AutoButtons.SLOT_SKIP,
        listOf(AutoButtons.PREV_CHAPTER, AutoButtons.NEXT_CHAPTER, AutoButtons.SPEED,
            AutoButtons.FAVOURITE, AutoButtons.BOOKMARK),
    ),
    BtMediaKind.PODCAST to AutoButtonConfig(
        AutoButtons.SLOT_SKIP, AutoButtons.SLOT_SKIP,
        listOf(AutoButtons.SPEED, AutoButtons.FAVOURITE, AutoButtons.BOOKMARK,
            AutoButtons.PREV_CHAPTER, AutoButtons.NEXT_CHAPTER),
    ),
    BtMediaKind.MUSIC to AutoButtonConfig(
        AutoButtons.SLOT_TRACK, AutoButtons.SLOT_TRACK,
        listOf(AutoButtons.FAVOURITE, AutoButtons.SPEED, AutoButtons.SKIP_BACK,
            AutoButtons.SKIP_FORWARD, AutoButtons.BOOKMARK),
    ),
    BtMediaKind.VIDEO to AutoButtonConfig(
        AutoButtons.SLOT_TRACK, AutoButtons.SLOT_TRACK,
        listOf(AutoButtons.FAVOURITE, AutoButtons.SPEED, AutoButtons.SKIP_BACK,
            AutoButtons.SKIP_FORWARD, AutoButtons.BOOKMARK),
    ),
)
