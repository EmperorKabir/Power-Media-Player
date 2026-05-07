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
                "Speed — 0.5× to 2.0×. Tap \"Reset\" to go back to 1×.",
                "Sleep timer — Auto-pause after a chosen time, with optional fade-out.",
                "Bookmark — Saves the current second to come back to later. The chips above the bottom row are your bookmarks for this file. Tap a chip to jump. Tap the × to delete."
            )
        ),
        InfoSection(
            title = "Effects",
            bullets = listOf(
                "Audio effects — Quick toggle for reverb, stereo flip, mono mix. Some are greyed out when casting because audio is on the speaker.",
                "Crossfade — Smooth transitions between tracks. Tap the icon to open the crossfade settings. Greyed out when the active source can't crossfade."
            )
        ),
        InfoSection(
            title = "Output",
            bullets = listOf(
                "Bluetooth button — Shows the device this app is currently sending audio to (phone speaker, headphones, car, etc.) and lets you switch. Settings → Bluetooth Car Controls is where you'd remap a car's prev/next buttons — configured in settings.",
                "Cast button — Opens the Cast picker for Google Home / TV / Chromecast. Greyed out when the current file isn't in a format the cast device can play (MP4 and WebM work; MKV / AVI / MOV don't)."
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
                "Long press menu — Hold a row to open a menu with Favourite, Hide, Add to queue next, Edit tags, Override speed, Override audio effects, Override video effects, Share, Delete. Override options are only available for files you've starred or pinned.",
                "Hidden files — Hide from the long press menu removes a file from this list without deleting it. Unhide from Settings → Library → Hidden files.",
                "Multi-select — Tap the three-dot menu in the top bar then \"Select multiple\" to bulk-favourite, bulk-delete, or bulk-add to queue."
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
                "Spotify Connect device — Green card at the top of the Spotify section. Tap it to pick which speaker, phone, or Google Home plays Spotify."
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
                "Subtitles auto-fetch — When playing a Drive video, the app can look up matching subtitles from OpenSubtitles. Toggle in Settings → Video.",
                "Podcasts — Subscribe to RSS feeds in the Podcasts section. New episodes auto-download to a chosen folder. Tap a podcast row to see its episodes.",
                "Offline copy — Pin a Drive track to make a local copy that plays without needing an internet connection."
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
