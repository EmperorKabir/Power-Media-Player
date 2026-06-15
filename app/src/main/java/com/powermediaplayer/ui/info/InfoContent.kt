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
                "A-B Loop — Tap once to mark A, again to mark B; playback then loops between them. The Track bar shows live markers — an amber A while only A is set, turning teal with a banded A-B region once the loop is active. Tap a third time to clear. A loop is remembered per file and clears itself when you move to a different track.",
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
                "Audio effects popup — Quick toggle for reverb (Off / Room / Medium hall / Large hall / Plate / Cave) + wet/dry intensity slider, stereo flip (L↔R), mono mix, multi-channel passthrough. Greyed out when casting (audio is on the receiver) AND when Spotify Connect is mirroring (audio plays on the Spotify device, not via our chain).",
                "Speed / Pitch — Independent. Sonic time-stretch processor adds ~250 ms of inherent buffer latency between dragging the slider and hearing the change; BT A2DP codec adds another ~100-300 ms. Turn on Settings → Low-latency audio buffer for snappier response (some dropout risk under CPU load).",
                "Spotify Connect note — Speed / pitch / reverb / EQ / stereo flip / mono mix / volume boost all run in the LOCAL audio chain. With Spotify mirroring, the audio plays on the Connect device — those controls grey out + an explanation appears. Switch to local or Drive to apply effects.",
                "Reverb on Bluetooth — EnvironmentalReverb attaches to our local audio chain. A2DP-routed audio is re-encoded by the BT codec downstream, so reverb can be inaudible over BT. Use wired output if you can't hear it.",
                "Crossfade — True 2-player overlap with equal-power / linear / exponential / logarithmic curves. Tap the icon to open the panel: master ms slider, per-curve picker, skip-silence, pre-fade trigger, gapless. Greyed out for video and audiobooks unless you opt in via the panel."
            )
        ),
        InfoSection(
            title = "Output",
            bullets = listOf(
                "Bluetooth button — Shows the device this app is currently sending audio to (phone speaker, headphones, car, etc.) and lets you switch. Includes a Video/audio sync offset slider (also in Settings → Bluetooth A/V sync offset — they're the same value): Bluetooth adds audio latency, so slide to delay the video and fix lip-sync. Remap a car's prev/next buttons under Settings → Bluetooth Car Controls — applies to both AVRCP key-event cars (older BMWs etc.) and MediaController-style ones (Android Auto), via two intercept paths.",
                "Cast button — Opens the Cast picker for Chromecast / Google Home / smart TV. Single combined button. The app runs an embedded LAN HTTP relay so the receiver can fetch your phone-local content (works for MP4, M4A, M4B audiobooks, FLAC, MP3, MKV, etc — receiver compatibility decides what plays). Casting a VIDEO to an audio-only device (e.g. a speaker) now keeps the picture playing on your phone — the audio goes to the speaker, the video stays on screen. Use the Video/audio sync offset slider (also in Settings → Cast A/V sync offset — same value) to nudge lip-sync; it's independent of the Bluetooth offset.",
                "Spotify Connect — Opened from the Cloud tab → Spotify section. Spotify's public API only surfaces SDK-registered devices, so Google Home / Fire Stick / Sonos may not appear here. Use the Cast button on the Player tab to send LOCAL audio to those devices instead.",
                "Spotify BT-remote on car HU — car steering-wheel prev/next/skip/play/pause route to SpotifyProvider.skipNext/skipPrevious/seekTo/pause/resume when the Connect mirror is active, so the car remote actually moves the Spotify song instead of silently re-driving a muted local Player."
            )
        ),
        InfoSection(
            title = "Webhooks + Hue lighting",
            bullets = listOf(
                "Webhooks (Settings → Webhooks) — Single URL + six per-event toggles (play / pause / resume / skip-next / skip-prev / track-end). On each event we fire a JSON POST: event name, timestamp ms, 8-char SHA-256 track hash, position ms, duration ms. Privacy: no titles or paths leave the device. 'Test' button sends a synthetic payload so you can verify your endpoint accepts our shape. Works with IFTTT, n8n, Home Assistant, Pushover, Google Apps Script, etc.",
                "Philips Hue — Two-step pair (Discover → press the round button on top of the bridge → Pair within 30 seconds). Optional manual IP entry for networks that block Philips cloud discovery. Find IP in Hue app → Settings → Bridge → Network info.",
                "Audio-reactive lighting (Hue, primary feature) — Lights pulse with the music: bass kicks flash bright, treble runs cool blues, mids track melody. The BPM is tracked from audio onsets and drives the colour-cycle rate so transitions feel musical, not metronomic. Single 'Intensity' slider (0-100); 0 turns the reactive engine off. Needs an Entertainment area in your Hue mobile app first (Settings → Entertainment areas → drag your lights in).",
                "How the audio gets there (no mic permission) — We tap the PCM stream inside ExoPlayer's own AudioProcessor chain, not Android's Visualizer. So no microphone permission, lower latency, and audio data the user owns stays in-app.",
                "Light/sound sync offset — Lights would otherwise lead the sound because the PCM tap runs BEFORE the AudioTrack output buffer. The slider compensates: phone speaker / wired ~150-250 ms; Bluetooth A2DP ~300-500 ms; USB-C DAC near 0 ms. The app auto-adds your Audio delay + BT video audio offset sliders to this number, so changing those doesn't break Hue alignment.",
                "Source compatibility — Reactive works on LOCAL and Drive files (audio flows through our chain). It auto-pauses on Spotify Connect and Cast because the audio plays on the remote device and we have nothing to analyse.",
                "Basic Hue controls (secondary) — All On / All Off + four scene presets (Party / Ambient / Cinema / Reading) applied to every light on the bridge. Useful when you want to set the mood without leaving the player — duplicates what the Hue app already does."
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
                "Long press menu — Hold a row to open a menu with Favourite, Hide, Add to queue next, Edit tags, per-file Overrides (speed, pitch, EQ preset, ReplayGain mode, reverb (chip list, not a slider), volume boost, video flips, A-B loop), Pin this album, Share, Delete. Override options are only available for files you've starred or pinned. Saved overrides persist across plays — the override panel shows a coloured chip on the now-playing screen so you know they're active.",
                "Pin this album — Snapshots every track in the album (matched by artist + album) as a single entry in Last Played → Pinned. Shows under the audio rows of the long-press menu when the file has an album tag. Counts against the unified 10-slot pin cap.",
                "Edit tags — Manually override the title / artist / album the app shows for this file. Doesn't write to the file itself; lives in app settings (cleared on Reset).",
                "Hidden files — Hide from the long press menu removes a file from this list without deleting it. Unhide from Settings → Library → Hidden files.",
                "Multi-select — Tap the three-dot menu in the top bar then \"Select multiple\" to bulk-favourite, bulk-delete, or bulk-add to queue."
            )
        ),
        InfoSection(
            title = "Smart playlists",
            bullets = listOf(
                "What they are — Auto-playlists built from rules, not a fixed song list. Each time you open one it re-checks your whole library, so e.g. \"played in the last 14 days\" stays current.",
                "Play — Saved playlists appear above the library list. Tap one to play its tracks.",
                "Create — Tap the \"Smart playlists\" header or the \"+\". Pick a name, add one or more rules (e.g. Favourite = Yes, Play count at least 3), choose how to sort and a max number of tracks. Rules combine with AND (all must match).",
                "Rule fields — Title, Artist, Album, Length, Play count, Last played, Favourite, Bookmarked. Length is in seconds; Last played is in days."
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
                "Pinned (unified 10 slots) — Stores up to 10 things total. Tap the star on a Recents row to pin a single track. Long-press a track in Library → 'Pin this album' pins every track of that album as ONE album entry. Inside a Drive folder → 'Pin folder as album' does the same for cloud content. Track pins + album pins share the same 10-cap.",
                "Pinned albums — Tap an album row to EXPAND its track list (does NOT auto-play). Tap any member track to play that one. Long-press an album row to unpin. Re-pinning is fine; the snapshot at pin time captures the track list."
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
                "Search — Searches inside the active provider only (Drive search → Drive only, Spotify search → Spotify only).",
                "Pin folder as album — When you're INSIDE a Drive sub-folder containing audio, a 'Pin this folder as album' row appears at the top of the list. Tap to snapshot every audio file in that folder as a single album pin in Last Played → Pinned. Counts against the unified 10-cap.",
                "Auto-refresh on return — The Cloud tab now refreshes on every resume (e.g. after the picker / OAuth / Drive web returns). Earlier builds got stuck on the sign-in cards even after a successful pick; this is fixed.",
                "Spotify Connect tap failure — If you tap a Spotify recents row and the play fails (no active device, session expired, etc.), a Toast appears with a clear reason — replaces an earlier silent-fail bug where the Player screen opened with no audio."
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
                "Frequency curve — Drag a point up or down to set that band. The two end bands (lowest / highest) have a widened grab area so they're easy to hit.",
                "Band cells — The grid below the curve: 10 frequency bands, each with a value you can type or nudge with +/−. Range ±15 dB.",
                "Reset all — Returns every band to 0 dB and clears the active preset."
            )
        ),
        InfoSection(
            title = "Behaviour",
            bullets = listOf(
                "Headphone-aware EQ — When a specific Bluetooth device connects, the app can auto-apply a preset for that device. Configure under EQ → Headphone presets.",
                "Disabled while casting — The EQ runs on your phone's audio chain. When casting, audio is on the receiver so the EQ has no effect.",
                "Disabled while Spotify Connect is mirroring — Same reason: the audio plays on the Connect device, our EQ effect chain has no stream to transform. Switch to local or Drive playback for the EQ to take effect.",
                "Per-track override — If a starred or pinned track has its own audio override, the EQ falls back to the override's preset for the duration of that play."
            )
        )
    )
)
