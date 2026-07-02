package com.powermediaplayer.ui.info

/**
 * Per-tab info-sheet content. Bullet copy is verbatim from the user-approved text in
 * `docs/superpowers/plans/2026-05-07-info-icons-crossfade-power-features.md` §A2. Sections
 * grouped per Q7 LOCKED Option B (logical groups).
 *
 * To amend a bullet: edit the string literal here. Label and text convention is
 * `<Label>: <explanation>` separated by a colon.
 */

/** Player tab, 5 logical groups. */
val playerInfo: InfoSheetData = InfoSheetData(
    tab = "Player",
    sections = listOf(
        InfoSection(
            title = "Display & sliders",
            bullets = listOf(
                "Now Playing: cover art, title, artist, album. Tap the cover for full-screen.",
                "Track slider (top): scrubs the current chapter or current track. Drag the dot.",
                "Full slider (below): scrubs the whole file or the whole queue. Useful for audiobooks and long episodes.",
                "Volume and Brightness sliders: volume is on top. Brightness (below) changes your phone's screen brightness, which needs a one-time \"Modify system settings\" permission. Until you grant it, the brightness slider is greyed out; tap \"Tap to grant brightness permission\" beneath it to switch it on. Brightness is applied when you let go of the slider.",
                "Title legibility: the title and details over the cover sit on a soft frosted panel so they stay readable on any artwork. Their colour follows Settings, Appearance, Player text colour: Default picks black or white for contrast, Custom uses a colour you choose, and Dynamic lets the app pick a colour from each cover's own palette."
            )
        ),
        InfoSection(
            title = "Navigation",
            bullets = listOf(
                "Skip 5, 10, 15, 20, 30: jump that many seconds back or forward.",
                "Previous and Next: jumps to the previous or next chapter if the file has chapters; otherwise the previous or next track.",
                "A to B loop: tap once to mark A, again to mark B; playback then loops between them. The Track bar shows live markers: an amber A while only A is set, turning teal with a banded A to B region once the loop is active. Tap a third time to clear. A loop is remembered per file and clears itself when you move to a different track.",
                "Frame step: one frame back or forward. Video only. Pauses the video first.",
                "Shuffle: the shuffle icon (next to the bookmark button) plays the current queue in a random order; Previous and Next then follow the shuffled order. Teal means on, grey means off. It applies to multi-track queues (for example an album or folder played from the Library) and is remembered across restarts. A single track has nothing to shuffle. For a Spotify album it drives Spotify's own shuffle (the queue is re-shuffled the moment you toggle it on).",
                "Download for offline use: the download icon (teal) appears in the controls row whenever the current item can be taken offline (a Google Drive file or a podcast episode, not Spotify, not an already-local file). Tap to download; while downloading it shows live progress, and tapping again stops it (the partial file is removed). Once saved it becomes a delete icon; tap to remove the local copy. A message confirms each result.",
                "Album track list (Spotify): when a Spotify album or playlist is playing, the queue and list icon opens its full track list; tap any track to jump to it within the album."
            )
        ),
        InfoSection(
            title = "Time & position",
            bullets = listOf(
                "Speed: 0.5×, 0.75×, 1×, 1.25×, 1.3×, 1.5×, 1.75×, 2×, 2.5×, 3×. Tap \"Reset\" to go back to 1×.",
                "Sleep timer: auto-pause after a chosen time, or at the end of the track, chapter, or queue. Optional linear fade-out over the last 30 seconds.",
                "Bookmark: saves the current second to come back to later. The chips above the bottom row are your bookmarks for this file. Tap a chip to jump (it lands a few seconds before for context, adjustable in Settings). Tap the × to delete."
            )
        ),
        InfoSection(
            title = "Effects",
            bullets = listOf(
                "Audio effects popup: a quick toggle for reverb (Off, Room, Medium hall, Large hall, Plate, Cave) plus a wet/dry intensity slider, stereo flip (L to R), mono mix, and multi-channel passthrough. Greyed out when casting (audio is on the receiver) and when Spotify Connect is mirroring (audio plays on the Spotify device, not via our chain).",
                "Speed and Pitch: adjusted independently. There is a short delay (about a quarter-second) between moving the slider and hearing the change, and Bluetooth adds a little more. Turn on Settings, 'Faster effect response' for a quicker reaction (with a small risk of brief glitches on a busy phone).",
                "Spotify Connect note: speed, pitch, reverb, EQ, stereo flip, mono mix, and volume boost all run in the local audio chain. With Spotify mirroring, the audio plays on the Connect device, so those controls grey out and an explanation appears. Switch to local or Drive to apply effects.",
                "Reverb over Bluetooth: reverb is added on your phone before the sound is sent out. Bluetooth re-processes the audio on its way to the speaker, which can wash the reverb out, so you may not hear it over Bluetooth. Use a wired connection if it is not coming through.",
                "Crossfade: a true two-player overlap with equal-power, linear, exponential, or logarithmic curves. Tap the icon to open the panel: master ms slider, per-curve picker, skip-silence, pre-fade trigger, gapless. Greyed out for video and audiobooks unless you opt in via the panel."
            )
        ),
        InfoSection(
            title = "Output",
            bullets = listOf(
                "Bluetooth button: tap to open the Bluetooth sheet. It shows whether this app's audio is routing to Bluetooth, lists paired devices, and carries a video and audio sync offset slider (also in Settings, Bluetooth A/V sync offset, the same value): Bluetooth adds audio latency, so slide to delay the video and fix lip-sync. When Bluetooth audio is playing, the sheet offers \"Play on phone speaker (stop Bluetooth audio)\": this moves THIS app's audio to the phone while leaving Bluetooth system-connected (a true device disconnect needs system-level permissions Android withholds from apps); once moved, the same control flips to \"Play on Bluetooth\" to send it back. The button icon dims when Bluetooth is connected but is not the app's active output (for example while casting, or after \"Play on phone speaker\"). You can change what your car's previous and next buttons do under Settings, Bluetooth car controls; this works with both older cars and newer Android Auto systems.",
                "Cast button: opens the Cast picker for Chromecast, Google Home, or a smart TV. It is a single combined button; it only shows \"connected\" for a genuine Cast or remote device. A connected Bluetooth speaker is not a cast and will not light it up. The app quietly shares your phone's files over your home Wi-Fi so the TV or speaker can play them (MP4, M4A, M4B audiobooks, FLAC, MP3, MKV and more; what actually plays depends on the device receiving it). Casting a video to an audio-only device (for example a speaker) keeps the picture playing on your phone while the audio (extracted to a small file for a fast start) goes to the speaker. Use the video and audio sync offset slider (also in Settings, Cast A/V sync offset, the same value) to nudge lip-sync; it is independent of the Bluetooth offset. Stopping a cast returns audio to your connected Bluetooth speaker if you have one, otherwise to the phone.",
                "Spotify Connect: opened from the Cloud tab, Spotify section. Spotify's public API only surfaces SDK-registered devices, so Google Home, Fire Stick, or Sonos may not appear here. Use the Cast button on the Player tab to send local audio to those devices instead.",
                "Spotify controls from your car: when Spotify is playing through this app, your car's steering-wheel and dashboard buttons (previous, next, skip, play, pause) control the actual Spotify track, instead of quietly doing nothing.",
                "Phone calls and interruptions: playback claims audio focus the moment it starts and releases it on stop, so an incoming call, alarm, navigation prompt, or another media app interrupts this app correctly. Video always pauses on any interruption and resumes when focus returns. Audio follows your per-scenario choice in Settings, Playback, Audio focus (Phone call, Other notification, Other media app; each can pause, lower volume, or keep playing). Picture-in-picture audio pauses on a call and resumes in the PiP window when the call ends.",
                "Resume after interruptions (Settings, Playback): on by default. When the call or prompt ends, playback resumes automatically. Turn it off to stay paused until you press play.",
                "Bluetooth versus casting on a call: Bluetooth is local audio, so a call pauses or ducks it per your setting above (you do not want music in your ear during a call). Casting sends audio to a TV or speaker, which is not your phone, so by default a call does not pause the cast (the speaker keeps playing). Turn on 'Interruptions pause casting' (Settings, Playback) if you want calls to pause the cast too."
            )
        ),
        InfoSection(
            title = "Webhooks & Hue lighting",
            bullets = listOf(
                "Webhooks (Settings, Webhooks): paste in a web address from a home-automation app, then choose which events to send: when a track starts, when you pause, resume, skip to the next or previous track, or when a track finishes. Each event carries which event it was, the time, a short anonymous code for the track, how far through you are, and the track's length, never the song's name or location. 'Send a test' lets you check the address works. Works with apps like Home Assistant, IFTTT and Tasker.",
                "Philips Hue: a two-step pair (Discover, press the round button on top of the bridge, Pair within 30 seconds). Optional manual IP entry for networks that block Philips cloud discovery. Find the IP in the Hue app, Settings, Bridge, Network info.",
                "Music-reactive lighting (Hue, the main feature): your lights pulse with the music. Heavy beats flash bright, high notes run cool blue, and the colours shift in time with the tempo so it feels musical. One 'Intensity' slider runs from 0 to 100; 0 switches it off. Set up an 'Entertainment area' in the Philips Hue app first (Settings, Entertainment areas, add your lights).",
                "How the lights 'hear' the music (no microphone needed): the player reads the music directly from inside its own audio engine, rather than listening through the phone's mic. So it needs no microphone permission, reacts quickly, and your audio never leaves the app.",
                "Light and sound sync: the lights can run slightly ahead of the sound, because the player reads the music just before it reaches the speaker. The slider lines them back up: phone speaker or cable, about 150 to 250 ms; Bluetooth, about 300 to 500 ms; a USB-C headphone adapter, near 0 ms. The player also folds in your Audio delay and Bluetooth video offset automatically, so those will not knock the lights out of time.",
                "Source compatibility: reactive lighting works on local and Drive files (audio flows through our chain). It auto-pauses on Spotify Connect and Cast because the audio plays on the remote device and we have nothing to analyse.",
                "Basic Hue controls (secondary): All On, All Off, plus four scene presets (Party, Ambient, Cinema, Reading) applied to every light on the bridge. Useful when you want to set the mood without leaving the player; it duplicates what the Hue app already does."
            )
        ),
        InfoSection(
            title = "Wake-up alarms",
            bullets = listOf(
                "Settings, Wake-up alarms: schedule playback at a chosen time. One-shot or recurring days of the week.",
                "Per alarm: pick the source (any track, playlist, smart playlist, saved bookmark, or favourite), start volume %, end volume %, ramp duration, hold duration, wind-down, snooze settings (continue ramp or restart, max snoozes, snooze minutes), stop method (math problem, shake, or swipe to confirm), vibration.",
                "Edge cases: bypasses Do Not Disturb and silent mode. Re-arms after device reboot. Skip-next lets you skip the next few occurrences of a recurring alarm.",
                "Background activity: if you set an alarm, your phone may list the app as active in the background. That is the alarm waiting to ring, not continuous playback. Playback and podcast syncing stop when you close the app."
            )
        )
    )
)

/** Library tab, 3 logical groups. */
val libraryInfo: InfoSheetData = InfoSheetData(
    tab = "Library",
    sections = listOf(
        InfoSection(
            title = "Browse",
            bullets = listOf(
                "Audio and Video toggle: switch between music files and video files. The total number of files is shown next to each.",
                "Search: filters by title, artist, or album as you type. Clear with the × on the right.",
                "Sort menu: by various options. Tap the same option again to flip the order (A to Z, then Z to A).",
                "Refresh icon: re-scans your phone for any newly added files. Occasionally auto-runs."
            )
        ),
        InfoSection(
            title = "Favourites",
            bullets = listOf(
                "Star (favourite): long press a row to add it to favourites. Favourites show as a strip across the top."
            )
        ),
        InfoSection(
            title = "Actions on a row",
            bullets = listOf(
                "Long press menu: hold a row to open a menu with Favourite, Hide, Add to queue next, Edit tags, per-file Overrides (speed, pitch, EQ preset, ReplayGain mode, reverb as a chip list rather than a slider, volume boost, video flips, A to B loop), Pin this album, Share, Delete. Override options are only available for files you have starred or pinned. Saved overrides persist across plays; the override panel shows a coloured chip on the now-playing screen so you know they are active.",
                "Pin this album: snapshots every track in the album (matched by artist and album) as a single entry in Last Played, Pinned. Shows under the audio rows of the long-press menu when the file has an album tag. Counts against the unified 10-slot pin cap.",
                "Edit tags: manually override the title, artist, or album the app shows for this file. It does not write to the file itself; it lives in app settings (cleared on Reset).",
                "Hidden files: Hide, from the long press menu, removes a file from this list without deleting it. Unhide from Settings, Library, Hidden files.",
                "Multi-select: tap the three-dot menu in the top bar, then \"Select multiple\" to bulk-favourite, bulk-delete, or bulk-add to queue."
            )
        ),
        InfoSection(
            title = "Smart playlists",
            bullets = listOf(
                "What they are: auto-playlists built from rules, not a fixed song list. Each time you open one it re-checks your whole library, so for example \"played in the last 14 days\" stays current.",
                "Play: saved playlists appear above the library list. Tap one to play its tracks.",
                "Create: tap the \"Smart playlists\" header or the \"+\". Pick a name, add one or more rules (for example Favourite is Yes, Play count at least 3), choose how to sort and a max number of tracks. Rules combine with AND (all must match).",
                "Rule fields: Title, Artist, Album, Length, Play count, Last played, Favourite, Bookmarked. Length is in seconds; Last played is in days."
            )
        ),
        InfoSection(
            title = "Getting around",
            bullets = listOf(
                "Tap the active tab again: when you are already on Library, Cloud or Settings, tapping that same tab again jumps you back to its top level (clears the search, returns to the provider picker, or unfilters Settings). Switching between different tabs keeps each one where you left it.",
                "Floating mini-player: the now-playing mini-player at the bottom rises above the on-screen keyboard when it opens (so it stays visible while you type) and drops back down when the keyboard closes. Works on phone, foldable and tablet layouts."
            )
        )
    )
)

/** Last Played tab, 3 logical groups. */
val lastPlayedInfo: InfoSheetData = InfoSheetData(
    tab = "Last Played",
    sections = listOf(
        InfoSection(
            title = "Lists",
            bullets = listOf(
                "Recents: your last 20 things played. Each fresh play makes a new row, even if you played the same file more than once. Swipe a row left to delete it. Tap \"Clear all\" to wipe everything in Recents.",
                "Pinned (unified 10 slots): stores up to 10 things in total. Tap the star on a Recents row to pin a single track. Long-press a track in Library, 'Pin this album', to pin every track of that album as one album entry. Inside a Drive folder, 'Pin folder as album' does the same for cloud content. Track pins and album pins share the same 10-slot cap.",
                "Pinned albums: tap an album row to expand its track list (it does not auto-play). Tap any member track to play that one. Long-press an album row to unpin. Re-pinning is fine; the snapshot at pin time captures the track list."
            )
        ),
        InfoSection(
            title = "Actions",
            bullets = listOf(
                "Resume: tap any row to resume from where you stopped.",
                "Download for offline use: the three-dot menu on a Drive or podcast row offers 'Download for offline use' (or 'Delete from local storage' once it is saved). While it downloads, the row itself shows a live progress bar with a red stop (✕); you can also watch or stop it in Cloud, Manage downloads. Spotify rows cannot be downloaded (DRM).",
                "Bookmarks within Recents and Pinned: each row has a dropdown showing every bookmark you added during that listen. Tap a bookmark to jump straight to that moment.",
                "Reorder: long press and drag a Pinned row to change its order."
            )
        ),
        InfoSection(
            title = "Behaviour",
            bullets = listOf(
                "Auto-mirror: when you add a bookmark while playing, a copy is added to whichever Recents or Pinned row corresponds to that listen, so the dropdown always shows the bookmarks you made during that exact session.",
                "Pin caps: when you have already pinned 10, the star button on the 11th attempt shows a small \"Pin full, unpin one first\" hint."
            )
        )
    )
)

/** Cloud tab, 4 logical groups. */
val cloudInfo: InfoSheetData = InfoSheetData(
    tab = "Cloud",
    sections = listOf(
        InfoSection(
            title = "Sign-in & setup",
            bullets = listOf(
                "Drive: sign in with Google. Pick the folders you want to make available to the app. The app only sees the folders you grant. Your other Drive files stay private.",
                "Spotify: sign in with your Spotify account. Premium is recommended for full track playback. Free accounts only get 30-second previews. Catalogue search is available from the search bar (tracks, albums, artists, playlists, shows, episodes). Sessions older than 6 months (Spotify's new refresh-token policy) prompt a quick re-sign-in.",
                "Drive folder picker, first time: the first time you sign in to Drive, you are asked to pick which folders the app can see. You can change this later in Settings."
            )
        ),
        InfoSection(
            title = "Spotify-specific",
            bullets = listOf(
                "Spotify Connect device: the green card at the top of the Spotify section. Tap it to pick which speaker, phone, or Google Home plays Spotify.",
                "Web API limitation: Spotify's public API only returns devices registered with the Spotify SDK. Google Home, Nest, Fire Stick, or Sonos appear in the official Spotify app via local-network discovery (a channel third-party apps cannot use). The picker shows an amber 'Not yet working' notice for this. As a workaround, use the Cast button on the Player tab to send local audio to those devices instead.",
                "Wake-Spotify button: auto-bounces to the Spotify app for about 1.5 seconds and returns. Sometimes makes more devices appear in our picker after Spotify itself registers them with Connect.",
                "Premium required: Spotify's API rejects full-track playback for Free accounts. Free testers see a 'Spotify Premium required' message on play."
            )
        ),
        InfoSection(
            title = "Discovery",
            bullets = listOf(
                "Favourites: star tracks, albums, podcasts to keep them at the top of this tab.",
                "Cover art while browsing: audio files show their real embedded cover in the list, even before you download or favourite them. The app fetches a small piece of each file to read the picture and remembers the result, so nothing is fetched twice. On mobile data only the smallest piece is fetched and the rest waits for Wi-Fi, unless you turn on 'Download cover art on mobile data' in Settings, Cloud. A downloaded copy always provides the final cover, and playing, favouriting and downloading are never restricted.",
                "Search: searches inside the active provider only. Spotify catalogue search returns tracks, albums, artists, playlists, shows (podcasts) and single episodes. Result rows show 'Title, Artist' (or show publisher). Tap an artist to drill into their top tracks; tap a standalone episode to play the full episode on your Spotify Connect device (Premium). Drive search looks inside the folders you have granted, by file name and by the title or author of items you have already opened or favourited (so searching an author like \"Matt\" finds the book even when the file name does not). It cannot search your whole Drive.",
                "Recent searches: focus an empty search box (Cloud, Library or Podcasts) to see your recent queries; tap one to re-run it, or 'Clear' to wipe them.",
                "Play album, playlist, or series: tapping a Spotify album, playlist, or show row browses its tracks; the ▶ on the row, or the 'Play album, playlist, or series' button shown once you are inside it, plays the whole thing on your Connect device (and loops it). Backing out of an album you opened from a search returns you to those search results.",
                "Pin folder as album: when you are inside a Drive sub-folder containing audio, a 'Pin this folder as album' row appears at the top of the list. Tap to snapshot every audio file in that folder as a single album pin in Last Played, Pinned. Counts against the unified 10-slot cap.",
                "Auto-refresh on return: the Cloud tab now refreshes on every resume (for example after the picker, OAuth, or Drive web returns). Earlier builds got stuck on the sign-in cards even after a successful pick; this is fixed.",
                "Spotify Connect tap failure: if you tap a Spotify recents row and the play fails (no active device, session expired, and so on), a message appears with a clear reason; this replaces an earlier silent-failure bug where the Player screen opened with no audio."
            )
        ),
        InfoSection(
            title = "Content features",
            bullets = listOf(
                "Subtitles auto-fetch: sign in to OpenSubtitles (Settings, Subtitles). The app looks up matching SRTs by filename or by content hash (your choice). Configure: language chip set (12 languages), match-by-hash radio, save next to the video file versus in app cache, override-existing-SRT switch.",
                "Podcasts: add by RSS feed URL or via the iTunes and Apple Podcasts directory search. Per-show settings: auto-download new, retain last N episodes, notify on new, and a per-show download folder. Each episode row has a download button (and a 'downloaded' tick to tap to delete); a downloaded episode plays the local file offline, so progress is kept even if you delete the file. Per show: 'Download latest N', 'Delete downloads'.",
                "Download storage: by default episodes save to app-private storage. Pick any folder per show (in its settings) or a global default folder (Settings, Cloud, Podcast download folder). Drive offline files use one global folder (Settings, Cloud, Google Drive files folder). Spotify content cannot be downloaded (DRM, Connect-only).",
                "Downloads manager: Cloud tab, 'Manage downloads' (also in Settings, Cloud). A live 'Downloading' section at the top shows every in-progress download (started from anywhere: the Player, Last Played, Cloud, or Podcasts) with a progress bar, size, and a stop (✕) button. Below it, completed downloads (podcast episodes and offline Drive files) are listed with size, grouped by source, with total usage versus the cap. Per-row delete, or multi-select: tick rows (or 'Select all', 'Deselect all' per section) and batch-delete with a confirm dialog. The 5 GB cap is the offline storage limit; change it (1, 5, 10 GB or Unlimited) in Settings, Cloud.",
                "Online metadata enrichment: off by default. When on, the app looks up missing track, artist, album, year, and genre fields from MusicBrainz and Discogs. A per-result cache means repeat lookups skip the network. Apply scope: all files, or only files with no embedded tags.",
                "Offline copy: there are several ways to take a Drive file or podcast episode offline: the download icon on its Cloud or podcast row; the Player controls-row download button while it plays; or the Last Played three-dot menu, 'Download for offline use'. Each shows live progress and can be stopped mid-download (the partial file is removed). To remove a local copy, the same button flips to delete, or use 'Delete from local storage' in the three-dot menu, or Manage downloads. Settings, Cloud, Offline storage limit caps the total size; when it is reached, the player removes your oldest saved copies first. Long-press an offline row, 'Protect from auto-cleanup', to pin it. Copies land in your chosen Google Drive files folder, or app cache if none is set."
            )
        )
    )
)

/** Equalizer tab, 3 logical groups. */
val equalizerInfo: InfoSheetData = InfoSheetData(
    tab = "Equalizer",
    sections = listOf(
        InfoSection(
            title = "Presets",
            bullets = listOf(
                "Preset menu: pick from Flat, Classical, Rock, Pop, Jazz, Bass Boost, Treble Boost, Vocal, Electronic, Acoustic.",
                "Save and Delete: save the current sliders as your own preset. Delete only works on user presets; built-in presets cannot be deleted."
            )
        ),
        InfoSection(
            title = "Adjust",
            bullets = listOf(
                "Frequency curve: drag a point up or down to set that band. The two end bands (lowest and highest) have a widened grab area so they are easy to hit.",
                "Band cells: the grid below the curve has 10 frequency bands, each with a value you can type or nudge with + or −. Range ±15 dB.",
                "Reset all: returns every band to 0 dB and clears the active preset."
            )
        ),
        InfoSection(
            title = "Behaviour",
            bullets = listOf(
                "Headphone-aware EQ: when a specific Bluetooth device connects, the app can auto-apply a preset for that device. Configure under EQ, Headphone presets.",
                "Disabled while casting: the EQ runs on your phone's audio chain. When casting, audio is on the receiver so the EQ has no effect.",
                "Disabled while Spotify Connect is mirroring: same reason. The audio plays on the Connect device, so our EQ effect chain has no stream to transform. Switch to local or Drive playback for the EQ to take effect.",
                "Per-track override: if a starred or pinned track has its own audio override, the EQ falls back to the override's preset for the duration of that play."
            )
        )
    )
)
