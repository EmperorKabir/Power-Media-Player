package com.powermediaplayer.ui.info

/**
 * Per-tab info-sheet content. Bullet copy is verbatim from the user-
 * approved text in
 * `docs/superpowers/plans/2026-05-07-info-icons-crossfade-power-features.md`
 * §A2. Sections grouped per Q7 LOCKED Option B (logical groups).
 *
 * To amend a bullet: edit the string literal here. Label-and-text
 * convention is `<Label> — <explanation>` separated by an em-dash.
 */

/** Player tab — 5 logical groups. */
val playerInfo: InfoSheetData = InfoSheetData(
    tab = "Player",
    sections = listOf(
        InfoSection(
            title = "Display & sliders",
            bullets = listOf(
                "Now Playing — Cover art, title, artist, album. Tap the cover for full-screen.",
                "Track slider (top) — Scrubs the current chapter or current track. Drag the dot.",
                "Full slider (below) — Scrubs the whole file or the whole queue. Useful for audiobooks and long episodes."
            )
        ),
        InfoSection(
            title = "Navigation",
            bullets = listOf(
                "Skip ±5/10/15/20/30 — Jump that many seconds back or forward.",
                "Previous / Next — Jumps to previous/next chapter if the file has chapters; otherwise the previous/next track.",
                "A-B Loop — Tap once to mark A. Tap again to mark B. Loops between the two. Tap a third time to clear.",
                "Frame step ± — One frame back or forward. Video only. Pauses the video first."
            )
        ),
        InfoSection(
            title = "Time + position",
            bullets = listOf(
                "Speed — 0.5×, 0.75×, 1×, 1.25×, 1.3×, 1.5×, 1.75×, 2×, 2.5×, 3×. Tap \"Reset\" to go back to 1×.",
                "Sleep timer — Auto-pause after a chosen time OR at end-of-track / end-of-chapter / end-of-queue. Optional linear fade-out over the last 30 s.",
                "Bookmark — Saves the current second to come back to later. The chips above the bottom row are your bookmarks for this file. Tap a chip to jump (lands a few seconds before for context — adjustable in Settings). Tap the × to delete."
            )
        ),
        InfoSection(
            title = "Effects",
            bullets = listOf(
                "Audio effects popup — Quick toggle for reverb (Off / Room / Medium hall / Large hall / Plate / Cave), stereo flip (L↔R), mono mix, multi-channel passthrough. Some are greyed out when casting because audio is on the speaker.",
                "Crossfade — True 2-player overlap with equal-power / linear / exponential / logarithmic curves. Tap the icon to open the panel: master ms slider, per-curve picker, skip-silence, pre-fade trigger, gapless. Greyed out for video and audiobooks unless you opt in via the panel."
            )
        ),
        InfoSection(
            title = "Output",
            bullets = listOf(
                "Bluetooth button — Shows the device this app is currently sending audio to (phone speaker, headphones, car, etc.) and lets you switch. Remap a car's prev/next buttons under Settings → Bluetooth Car Controls.",
                "Cast button — Opens the Cast picker for Chromecast / Google Home / smart TV. The app runs an embedded LAN HTTP relay so the receiver can fetch your phone-local content (works for MP4, M4A, M4B audiobooks, FLAC, MP3, MKV, etc — receiver compatibility decides what plays).",
                "Spotify Connect — Opened from the Cloud tab → Spotify section. Spotify's public API only surfaces SDK-registered devices, so Google Home / Fire Stick / Sonos may not appear here. Use the Cast button on the Player tab to send LOCAL audio to those devices instead."
            )
        ),
        InfoSection(
            title = "Wake-up alarms",
            bullets = listOf(
                "Settings → Wake-up alarms — Schedule playback at a chosen time. One-shot or recurring days-of-week.",
                "Per alarm — Pick the source (any track / playlist / smart playlist / saved bookmark or favourite), start volume %, end volume %, ramp duration, hold duration, wind-down, snooze settings (continue ramp or restart, max snoozes, snooze minutes), stop method (math problem / shake / swipe-to-confirm), vibration.",
                "Edge cases — Bypasses Do Not Disturb and silent mode. Re-arms after device reboot. Skip-next-N lets you skip the next N occurrences of a recurring alarm."
            )
        )
    )
)

/** Library tab — 3 logical groups. */
val libraryInfo: InfoSheetData = InfoSheetData(
    tab = "Library",
    sections = listOf(
        InfoSection(
            title = "Browse",
            bullets = listOf(
                "Audio / Video toggle — Switch between music files and video files. Total number of files shown next to each.",
                "Search — Filters by title, artist, or album as you type. Clear with the × on the right.",
                "Sort menu — By various options. Tap the same option again to flip the order (A→Z to Z→A).",
                "Refresh icon — Re-scans your phone for any newly added files. Occasionally auto-runs."
            )
        ),
        InfoSection(
            title = "Favourites",
            bullets = listOf(
                "Star (favourite) — Long press a row to add it to favourites. Favourites show as a strip across the top."
            )
        ),
        InfoSection(
            title = "Actions on a row",
            bullets = listOf(
                "Long press menu — Hold a row to open a menu with Favourite, Hide, Add to queue next, Edit tags, per-file Overrides (speed, pitch, EQ preset, ReplayGain mode, reverb (chip list, not a slider), volume boost, video flips, A-B loop), Share, Delete. Override options are only available for files you've starred or pinned. Saved overrides persist across plays — the override panel shows a coloured chip on the now-playing screen so you know they're active.",
                "Edit tags — Manually override the title / artist / album the app shows for this file. Doesn't write to the file itself; lives in app settings (cleared on Reset).",
                "Hidden files — Hide from the long press menu removes a file from this list without deleting it. Unhide from Settings → Library → Hidden files.",
                "Multi-select — Tap the three-dot menu in the top bar then \"Select multiple\" to bulk-favourite, bulk-delete, or bulk-add to queue."
            )
        ),
        InfoSection(
            title = "Smart playlists",
            bullets = listOf(
                "Strip below the search bar — Saved smart playlists. Tap a chip to play the resolved track list.",
                "Create — Tap the \"+\" on the strip. Form-based editor: name, rules (field × operator × value, AND-combined), sort, limit. No JSON to hand-craft.",
                "Available fields — title, artist, album, duration, playCount, lastPlayedDays, isFavourite, isHidden, mediaKind. Operators include eq / contains / gt / lt / between."
            )
        )
    )
)

/** Last Played tab — 3 logical groups. */
val lastPlayedInfo: InfoSheetData = InfoSheetData(
    tab = "Last Played",
    sections = listOf(
        InfoSection(
            title = "Lists",
            bullets = listOf(
                "Recents — Your last 20 things played. Each fresh play makes a new row, even if you played the same file more than once. Swipe a row left to delete it. Tap \"Clear all\" to wipe everything in Recents.",
                "Pinned — Pinned can store up to 10 files you've starred from the Recents. Tap the star on a Recents row to pin. Pinning freezes the row as a snapshot of any bookmarks you'd added during that listen. Deleting from Recents does NOT touch the pin."
            )
        ),
        InfoSection(
            title = "Actions",
            bullets = listOf(
                "Resume — Tap any row to resume from where you stopped.",
                "Bookmarks within Recents/Pinned — Each row has a dropdown showing every bookmark you'd added during that listen. Tap a bookmark to jump straight to that moment.",
                "Reorder — Long press and drag a Pinned row to change its order."
            )
        ),
        InfoSection(
            title = "Behaviour",
            bullets = listOf(
                "Auto-mirror — When you add a bookmark while playing, a copy is added to whichever Recents/Pinned row corresponds to that listen, so the dropdown always shows the bookmarks you made during that exact session.",
                "Pin caps — When you've already pinned 10, the star button on the 11th attempt shows a small \"Pin full — unpin one first\" hint."
            )
        )
    )
)

/** Cloud tab — 4 logical groups. */
val cloudInfo: InfoSheetData = InfoSheetData(
    tab = "Cloud",
    sections = listOf(
        InfoSection(
            title = "Sign-in & setup",
            bullets = listOf(
                "Drive — Sign in with Google. Pick the folders you want to make available to the app. The app only sees the folders you grant. Your other Drive files stay private.",
                "Spotify — Sign in with your Spotify account. Premium recommended for full track playback. Free accounts only get 30-second previews.",
                "Drive folder picker first time — The first time you sign in to Drive, you'll be asked to pick which folders the app can see. You can change this later in Settings."
            )
        ),
        InfoSection(
            title = "Spotify-specific",
            bullets = listOf(
                "Spotify Connect device — Green card at the top of the Spotify section. Tap it to pick which speaker, phone, or Google Home plays Spotify.",
                "Web API limitation — Spotify's public API only returns devices registered with the Spotify SDK. Google Home / Nest / Fire Stick / Sonos appear in the official Spotify app via local-network discovery (a channel third-party apps cannot use). The picker shows an amber 'Not yet working' notice for this. Workaround: use the Cast button on the Player tab to send LOCAL audio to those devices instead.",
                "Wake-Spotify button — Auto-bounces to the Spotify app for ~1.5 s and returns. Sometimes makes more devices appear in our picker after Spotify itself registers them with Connect.",
                "Premium required — Spotify's API rejects full-track playback for Free accounts. Free testers see a 'Spotify Premium required' toast on play."
            )
        ),
        InfoSection(
            title = "Discovery",
            bullets = listOf(
                "Favourites — Star tracks, albums, podcasts to keep them at the top of this tab.",
                "Search — Searches inside the active provider only (Drive search → Drive only, Spotify search → Spotify only)."
            )
        ),
        InfoSection(
            title = "Content features",
            bullets = listOf(
                "Subtitles auto-fetch — Sign in to OpenSubtitles (Settings → Subtitles). The app looks up matching SRTs by filename or by content hash (your choice). Configure: language chip set (12 languages), match-by-hash radio, save next to the video file vs in app cache, override-existing-.srt switch.",
                "Podcasts — Add to the Podcasts section by RSS feed URL OR via the iTunes / Apple Podcasts directory search. Per-show settings: auto-download new, retain last N episodes, notify on new. Episodes save to Movies/PowerMediaPlayer/podcasts/<showSlug>/.",
                "Online metadata enrichment — Off by default. When on, the app looks up missing track / artist / album / year / genre fields from MusicBrainz and / or Discogs. Per-result cache so repeat lookups skip the network. Apply scope: all files OR only files with no embedded tags.",
                "Offline copy — Pin a Drive track to make a local copy that plays without internet. Settings → Cloud → Offline storage limit caps total size; LRU eviction clears the oldest pin first."
            )
        )
    )
)

/** Equalizer tab — 3 logical groups. */
val equalizerInfo: InfoSheetData = InfoSheetData(
    tab = "Equalizer",
    sections = listOf(
        InfoSection(
            title = "Presets",
            bullets = listOf(
                "Preset menu — Pick from Flat, Classical, Rock, Pop, Jazz, Bass Boost, Treble Boost, Vocal, Electronic, Acoustic.",
                "Save / Delete — Save the current sliders as your own preset. Delete only works on user presets, built-in presets can't be deleted."
            )
        ),
        InfoSection(
            title = "Adjust",
            bullets = listOf(
                "Frequency curve — Visual of the current setting. Tap on the curve to nudge a band quickly.",
                "Band sliders — 10 frequency bands, ±15 dB each.",
                "Reset all — Returns every band to 0 dB and clears the active preset."
            )
        ),
        InfoSection(
            title = "Behaviour",
            bullets = listOf(
                "Headphone-aware EQ — When a specific Bluetooth device connects, the app can auto-apply a preset for that device. Configure under EQ → Headphone presets.",
                "Disabled while casting — The EQ runs on your phone's audio chain. When casting, audio is on the speaker so the EQ has no effect.",
                "Per-track override — If a starred or pinned track has its own audio override, the EQ falls back to the override's preset for the duration of that play."
            )
        )
    )
)
