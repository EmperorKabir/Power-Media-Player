# Info Icons + Crossfade DJ + Power-User Features Implementation Plan

> **Execution mode (LOCKED):** INLINE in main thread. No subagents — too much context risk for this codebase (theme quirks, AGP downgrade, field-init-order race, Cast invariants). Use `superpowers:executing-plans` with phase checkpoints. Use `superpowers:systematic-debugging` if any test fails. Use `superpowers:test-driven-development` for any new logic file. Query Context7 BEFORE touching unfamiliar API surface (alarm full-screen intent, Media3 dual-player, AppWidget resize). Forbid guesswork: every behaviour must be evidence-locked from a Read/Grep, the existing code, or a Context7 doc.

---

## Section M — Resumption brief (READ THIS FIRST in a fresh session)

**Last interaction date:** 2026-05-07.
**Conversation status:** plan locked except 3 outstanding follow-up questions (Q2, Q3, Q5 in §K). User said they'll answer later. Phase 1 cannot start until Q2 + Q3 answered (Q5 only blocks Phase 10 testing scope).

**What is locked:**
- Plan execution = INLINE in main thread, NO subagents.
- Use superpowers:executing-plans + systematic-debugging + test-driven-development.
- Use Context7 MCP before unfamiliar APIs (Media3 dual-player, AlarmManager full-screen-intent, AppWidget resize).
- Forbid guesswork — every behaviour evidence-locked.
- Test on emulator (`Pixel_6_API_34` Android 14) AND Z Fold 6 (USB-debug) for every commit.
- 19 power-user features (C1, C2, C3, C6, C7, C9, C10, C11, C12, C13, C14, C16, C17, C18, C20, C22, C25, C26, C27, C28). User-selected from 25-list.
- All info-icon copy verbatim (§A2). Square-box icon shape Q1 LOCKED.
- True 2-player crossfade. 9 toggles (Auto-DJ dropped). Equal-power default. Auto-revert on incompatible source. Switch metadata at crossfade START. Mid-crossfade interactions defined in B3.
- Bookmark mirror universalised in all 3 paths (cold-start resume, Spotify mirror first-emit, notification resume) per A3.
- Frame-step Q4 LOCKED (icons swapped, hidden on audio mode, both PlayerScreen.kt locations).
- OpenSubtitles: bake API key + per-user login (Context7-confirmed combined-auth model).
- Drive offline copy: build it. Spotify excluded (TOS).
- Multi-select: build (3-dot menu, NOT long-press). Hidden files: build (DataStore + Auto Backup, manifest already has allowBackup="true").
- Alarm: full-screen wake on locked screen. Volume ramp + snooze with duration/max + days-of-week + skip-N + math-problem stop. Settings → Alarms own section.
- Long-press menu = bottom sheet. 9 items per row. Override-* only on starred/pinned.
- Settings sub-section order: see §D (15 sections; reviewable in Q3).

**OUTSTANDING QUESTIONS — none. All locked.** (Q1, Q2, Q3, Q4, Q5, Q6, Q7 all answered.)

**Status: ready to begin Phase 1 Task 1.1 immediately.**

**LOCKED answers from user (all decided):**

| Q | Decision (verbatim user wording where relevant) |
|---|---|
| Q1 | "square box for i logo" → rounded-square `Surface(RoundedCornerShape(6.dp))` 24 dp blue + white "i". |
| Q2 | "2: A" → gradient hides with controls (current behaviour preserved). User clarified concern was about info-icon NOT breaking the gradient, which it doesn't. |
| Q3 | "3: fine" → 15-section settings order accepted as proposed in §D. |
| Q4 | "4: yes" → frame-step IconButtons hidden on audio mode at PlayerScreen.kt:486+509 + :682+705. |
| Q5 | "5: a" → lightweight Spotify check per phase + full 10-step E2E in Phase 10 only. |
| Q6 | "6: a and b" → keep start→end ramp AND add separate wind-down (hold-then-fade-to-silence). §C12 expanded. |
| Q7 | "7: b" → info-sheet logical groups, accordion-style. Groupings drafted in §K Q7 across all 5 tabs (5 / 3 / 3 / 4 / 3 sections respectively). |
| NEW | "additionally at the end of all of this, add an additional investigation into the casting bug" → Phase 11 added in §J with full diagnostic protocol (9 tasks, 6 hypotheses, evidence-locked bisect). |

**Next action when the user returns:**

1. Read this Resumption Brief.
2. All questions locked — proceed straight to Phase 1 Task 1.1 (universalise `currentSessionId` cold-start path in `PlayerViewModel.kt`).
3. Use TaskCreate to track Phase 1's 12 tasks. Mark each `in_progress` when started, `completed` when commit + push + adb-install + tests passing on emu + Z Fold 6.
4. If user has new requests mid-phase, treat as plan amendments — re-run §L self-review against the original 2026-05-07 00:01:59 + 00:43:11 + 01:31:53 + 01:40:46 prompts and add to plan before deviating.
5. Phase 11 (Cast bug investigation) is the FINAL phase, runs at the very end after all 19 power-user features + Spotify Connect E2E test pass.

**Critical files / line numbers locked from evidence:**

| Reference | Where |
|---|---|
| Bookmark gate (`if sessionId != null`) | `PlayerViewModel.kt:81-103` |
| 5-second tick (where to wire cold-start recordPlay) | `PlayerViewModel.kt:163` |
| Frame-step functions | `PlayerViewModel.kt:809` (`stepFrameForward`) and `:815` (`stepFrameBack`) |
| Frame-step UI (4 IconButton render sites) | `PlayerScreen.kt:486, 509, 682, 705` |
| Scrim brush | `PlayerScreen.kt:323` |
| Scrim render Box | `PlayerScreen.kt:402` (inside `OverlayContent` wrapped in `AnimatedVisibility`) |
| Refresh button confirmed present | `LibraryScreen.kt:153-157` |
| `allowBackup="true"` confirmed | `AndroidManifest.xml:59` |
| Deep scan default = OFF | `SettingsDataStore.kt:193` |
| MainActivity = `FragmentActivity` | (changed in earlier session for MediaRouteChooserDialog requirement) |
| Theme parent = `Theme.AppCompat.NoActionBar` | (NOT android:Theme.Material — broke MediaRouterThemeHelper) |
| Migration policy | `AppModule.kt`: `.fallbackToDestructiveMigrationFrom(false, 1, 2, 3, 4, 5, 6)` |

**User's durable instructions (load into mental model from start of session):**

- After every turn with commits: `git push` + `adb install -r` debug APK to Z Fold 6. No need to ask.
- No-guesswork bug-fix policy: evidence-locked diagnosis before any video/player fix.
- Apply maximum semantic compression in responses. Bulleted lists, no paragraphs.
- Use Context7 MCP for any external library question.
- Use superpowers CLI slash commands for multi-agent workflows (here = inline only, but other skills still apply).
- All scripts and modifications must be idempotent.
- Don't exceed 50% context window — execute manual `/compact` to prevent degradation.
- Don't commit secrets, .env files, or bypass defensive security parameters.
- Image processing rule: check size with Bash `stat`/`du`, resize via PIL if >500 KB before Read.

---

**Goal:** Add per-tab info-icon dialogs, a crossfade-with-DJ-options panel under the player's Audio Effects, and 19 power-user features the user prioritised, with all UI greyed-out / disabled per source-and-media-type compatibility.

**Architecture:** Compose-only additions. Reuses `ModalBottomSheet`, `ExposedDropdownMenuBox`, `ControlsEnabledState`. Crossfade options live in `SettingsDataStore` keyed individually. Power-user toggles likewise. Settings tab reorganised into nested expandable sections. **Two-player crossfade** owned by new `CrossfadeController` class.

**Tech Stack:** Compose BOM 2025.04.00, Material3, Media3 1.6.0, Room 2.7.1, DataStore 1.1.7, Hilt 2.54, Coil 3, NanoHTTPD 2.3.1, AppAuth.

**Test devices (LOCKED — every step on BOTH):**
- Emulator: AVD `Pixel_6_API_34` (Android 14, x86_64).
- Phone: Samsung Z Fold 6 over USB-debug (serial in `dist/devices.txt`).
- Friend's S25 Ultra: out-of-band via shared APK once a phase passes both above.

---

## Section A — Information icons (per tab)

### A1. The icon component

- Top-right of each tab's app-bar.
- Shape & colour (LOCKED): rounded-square box. `Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF1E88E5))` 24×24 dp with a centred white "i" letter. 48 dp touch target. Tinted distinct from app accent so it reads as "help".
- Tap → `ModalBottomSheet` with collapsible sections (one per logical group).
- Video tab: hides with the transport-controls auto-hide, reappears with controls.
- Audio / non-video: always visible.
- **Gradient invariant:** `OverlayContent`'s scrim (`PlayerScreen.kt:323`, rendered at line 402) currently hides with controls. **PENDING USER CONFIRMATION** — see follow-up Q2 in §K. Two paths:
  - Path A (current behaviour preserved): info icon sits inside the same `AnimatedVisibility` as scrim+controls. All hide together. My implementation has zero visual regression.
  - Path B (scrim made independent): scrim moved out of `AnimatedVisibility` into a sibling Box that always renders. Info icon stays inside `AnimatedVisibility` with controls. The bottom strip stays slightly darkened even when controls hide.

### A2. Per-tab info content (FINAL — user-approved verbatim)

#### Player tab

- **Now Playing** — Cover art, title, artist, album. Tap the cover for full-screen.
- **Track slider (top)** — Scrubs the current chapter or current track. Drag the dot.
- **Full slider (below)** — Scrubs the whole file or the whole queue. Useful for audiobooks and long episodes.
- **Skip ±5/10/15/20/30** — Jump that many seconds back or forward.
- **Previous / Next** — Jumps to previous/next chapter if the file has chapters; otherwise the previous/next track.
- **Speed** — 0.5× to 2.0×. Tap "Reset" to go back to 1×.
- **Sleep timer** — Auto-pause after a chosen time, with optional fade-out.
- **A-B Loop** — Tap once to mark A. Tap again to mark B. Loops between the two. Tap a third time to clear.
- **Frame step ±** — One frame back or forward. Video only. Pauses the video first.
- **Bookmark** — Saves the current second to come back to later. The chips above the bottom row are your bookmarks for this file. Tap a chip to jump. Tap the × to delete.
- **Audio effects** — Quick toggle for reverb, stereo flip, mono mix. Some are greyed out when casting because audio is on the speaker.
- **Crossfade** — Smooth transitions between tracks. Tap the icon to open the crossfade settings. Greyed out when the active source can't crossfade.
- **Bluetooth button** — Shows the device this app is currently sending audio to (phone speaker, headphones, car, etc.) and lets you switch. Settings → Bluetooth Car Controls is where you'd remap a car's prev/next buttons — configured in settings.
- **Cast button** — Opens the Cast picker for Google Home / TV / Chromecast. Greyed out when the current file isn't in a format the cast device can play (MP4 and WebM work; MKV / AVI / MOV don't).

#### Library tab

- **Audio / Video toggle** — Switch between music files and video files. Total number of files shown next to each.
- **Search** — Filters by title, artist, or album as you type. Clear with the × on the right.
- **Sort menu** — By various options. Tap the same option again to flip the order (A→Z to Z→A).
- **Refresh icon** — Re-scans your phone for any newly added files. Occasionally auto-runs.
- **Star (favourite)** — Long press a row to add it to favourites. Favourites show as a strip across the top.
- **Long press menu** — Hold a row to open a menu with Favourite, Hide, Add to queue next, Edit tags, Override speed, Override audio effects, Override video effects, Share, Delete. Override options are only available for files you've starred or pinned.
- **Hidden files** — Hide from the long press menu removes a file from this list without deleting it. Unhide from Settings → Library → Hidden files.
- **Multi-select** — Tap the three-dot menu in the top bar then "Select multiple" to bulk-favourite, bulk-delete, or bulk-add to queue.

#### Last Played tab

- **Recents** — Your last 20 things played. Each fresh play makes a new row, even if you played the same file more than once. Swipe a row left to delete it. Tap "Clear all" to wipe everything in Recents.
- **Pinned** — Pinned can store up to 10 files you've starred from the Recents. Tap the star on a Recents row to pin. Pinning freezes the row as a snapshot of any bookmarks you'd added during that listen. Deleting from Recents does NOT touch the pin.
- **Resume** — Tap any row to resume from where you stopped.
- **Bookmarks within Recents/Pinned** — Each row has a dropdown showing every bookmark you'd added during that listen. Tap a bookmark to jump straight to that moment.
- **Reorder** — Long press and drag a Pinned row to change its order.
- **Auto-mirror** — When you add a bookmark while playing, a copy is added to whichever Recents/Pinned row corresponds to that listen, so the dropdown always shows the bookmarks you made during that exact session.
- **Pin caps** — When you've already pinned 10, the star button on the 11th attempt shows a small "Pin full — unpin one first" hint.

#### Cloud tab

- **Drive** — Sign in with Google. Pick the folders you want to make available to the app. The app only sees the folders you grant. Your other Drive files stay private.
- **Spotify** — Sign in with your Spotify account. Premium recommended for full track playback. Free accounts only get 30-second previews.
- **Spotify Connect device** — Green card at the top of the Spotify section. Tap it to pick which speaker, phone, or Google Home plays Spotify.
- **Favourites** — Star tracks, albums, podcasts to keep them at the top of this tab.
- **Search** — Searches inside the active provider only (Drive search → Drive only, Spotify search → Spotify only).
- **Subtitles auto-fetch** — When playing a Drive video, the app can look up matching subtitles from OpenSubtitles. Toggle in Settings → Video.
- **Podcasts** — Subscribe to RSS feeds in the Podcasts section. New episodes auto-download to a chosen folder. Tap a podcast row to see its episodes.
- **Offline copy** — Pin a Drive track to make a local copy that plays without needing an internet connection.
- **Drive folder picker first time** — The first time you sign in to Drive, you'll be asked to pick which folders the app can see. You can change this later in Settings.

#### Equalizer tab

- **Preset menu** — Pick from Flat, Classical, Rock, Pop, Jazz, Bass Boost, Treble Boost, Vocal, Electronic, Acoustic.
- **Save / Delete** — Save the current sliders as your own preset. Delete only works on user presets, built-in presets can't be deleted.
- **Frequency curve** — Visual of the current setting. Tap on the curve to nudge a band quickly.
- **Band sliders** — 10 frequency bands, ±15 dB each.
- **Headphone-aware EQ** — When a specific Bluetooth device connects, the app can auto-apply a preset for that device. Configure under EQ → Headphone presets.
- **Disabled while casting** — The EQ runs on your phone's audio chain. When casting, audio is on the speaker so the EQ has no effect.
- **Per-track override** — If a starred or pinned track has its own audio override, the EQ falls back to the override's preset for the duration of that play.
- **Reset all** — Returns every band to 0 dB and clears the active preset.

#### Settings tab

- No info box. Each setting gets a one-line explanation underneath the control in `bodySmall` / `TextTertiary`.

### A3. `currentSessionId` universalisation (LOCKED bookmark fix)

- File: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt:81-103` (`addBookmarkHere`).
- Gap: `addBookmarkHere` only writes `history_bookmarks` row when `currentSessionId != null`. Set only by Library/Cloud/LastPlayed taps.
- Fix paths (3):
  1. PlayerViewModel listens for `playerState`'s first transition from "no media" → "media playing" — if no `currentSessionId`, fire `lastPlayedRepo.recordPlay(...)` synthesised from the current MediaItem.
  2. CloudViewModel: when `_spotifyState.value` first emits a non-null fresh `trackUri`, call `recordCloudPlay(...)` automatically.
  3. Cold-start resume: the existing 5-second tick at `PlayerViewModel.kt:163` checks for current MediaItem + null sessionId → fires `recordPlay`.

### A4. Frame-step polish (LOCKED — replace ONLY frame-step icons)

- Confirmed wired at `PlayerViewModel.kt:809` (`stepFrameForward`), `:815` (`stepFrameBack`).
- UI: `PlayerScreen.kt:486+509` (one set), `:682+705` (second set) — TWO render points (portrait + landscape variants). BOTH must swap to new icons.
- New drawables: `app/src/main/res/drawable/ic_frame_step_back.xml` + `ic_frame_step_forward.xml`. Vector path resembling a rewind/fast-forward triangle with a vertical bar.
- Visibility: only when `uiState.isVideoContent && !uiState.isCasting`.
- Tooltip: `contentDescription = "Step one frame back"` / `"Step one frame forward"`.
- Auto-pause on first tap if currently playing.

### A5. Bluetooth-button info text fix

- BT button = device-output picker. Car-control remap = Settings tab. Final text in §A2 above.

---

## Section B — Crossfade panel + DJ options (LOCKED)

### B1. Where it lives

- New button in the player's bottom transport row: right after Audio Effects, before Bluetooth.
- Tap → `ModalBottomSheet` mirroring `AudioEffectsButton.kt` pattern.

### B2. The 9 sub-toggles

(Auto-DJ removed as redundant.)

1. **Master crossfade** — Switch (default OFF). *Smooths the transition between two tracks instead of a hard cut.*
2. **Crossfade duration** — Slider 0..15 s (default 5 s). *How long the smooth transition lasts. 0 = off, 5 s is typical.*
3. **Fade curve** — Dropdown: Equal-power (default) / Linear / Exponential / S-curve. *Equal-power keeps perceived loudness constant — what real DJs use. Linear is a constant-rate drop. Exponential lingers then drops fast. S-curve eases in and out.*
4. **Album mode** — Switch (default ON). *Skip the crossfade between consecutive tracks of the same album so you keep the artist's intended gaps.*
5. **Skip silence** — Switch. *Trims leading and trailing silence from each track so the crossfade actually overlaps loud audio.*
6. **Pre-fade trigger** — Slider, "Start fade at last X seconds" 1..30 s. *When to start fading the current track out. Smaller = later, bigger = earlier.*
7. **Manual fade-now** — Switch + button. *Fast-skip with a fade rather than a hard cut. Useful when you're done with the current track.*
8. **Fade-out on pause** — Switch. *A short fade when you tap pause instead of an instant cut.*
9. **Fade-in on resume** — Switch. *A short fade-up when you tap play again.*

### B3. True two-player crossfade — design (LOCKED)

- New: `app/src/main/java/com/powermediaplayer/service/CrossfadeController.kt`.
- Holds:
  - `playerA: ExoPlayer` (the long-lived primary).
  - `playerB: ExoPlayer?` (lazy, created when crossfade window opens).
- Lifecycle:
  - Idle: only `playerA`. Memory baseline = today.
  - Crossfade window opens at `(trackEnd - preFadeTrigger)`: build `playerB`, give it the next `MediaItem`, prepare, hold at volume 0.
  - Pre-fade fires: `playerA` ramps DOWN per chosen curve; `playerB` plays from time 0 ramping UP per the matched curve. Equal-power: `vA² + vB² = 1`.
  - Crossfade midpoint: roles swap in audio-focus / EQ / metadata-display layer. Audio-effect chain hot-swaps with 50 ms cubic smoothing on each effect.
  - Crossfade completes: `playerA.pause()`, queue moves, `playerB` released, `playerA` becomes primary.
- Memory budget: each ExoPlayer ≈ 3-5 MB instance + 5-10 MB buffers during active decode. Peak crossfade overhead ≈ 10-20 MB. Outside window ≈ 0.
- Touch input: ALL transport controls bind to `CrossfadeController` rather than directly to ExoPlayer. Mid-crossfade interactions (LOCKED behaviour):
  - **Pause** — both players pause synchronously, current crossfade pauses in-place. Resume continues from same volume positions.
  - **Scrub track slider** — abort crossfade. `playerA` jumps to scrub position. `playerB` released.
  - **Tap Next** — abort crossfade. `playerB` (already loaded with next track) becomes `playerA` immediately at full volume; old `playerA` released.
  - **Tap Previous** — abort crossfade. `playerA` jumps back. `playerB` released.
  - **Tap Stop** — both players stop. `playerB` released.
- Audio-effect chain handover (LOCKED):
  - EQ, reverb, stereo-flip, mono-mix all attach to whichever player is the audible majority (vol > 50%).
  - At midpoint, all four effects hot-swap in a single transaction, each with 50 ms cubic smoothing on its parameter.
- Metadata handoff (LOCKED — switch on crossfade START):
  - Notification + lockscreen + BT car-display flips to the **incoming** track's title/artist/artwork the moment the crossfade begins.
  - Reason: media-control surfaces hate flicker; users perceive the crossfade as the start of the new track.
- Cast / Spotify Connect interaction:
  - Activating Cast or Spotify Connect → `playerB` released immediately. Master crossfade auto-greys (B5).
  - Deactivating either → normal flow resumes; next crossfade window will rebuild `playerB`.
- Audio output:
  - Both players share `AudioAttributes` and one `AudioFocusRequest`. OS mixes at AudioTrack level.
- Drive interaction:
  - Drive offline-copy items use the local cached file path. Streamed Drive items use the existing CastRelayServer-style path. `playerB` build budget = 800 ms; if Drive cold prefetch exceeds this, the crossfade for that pair degrades to a hard cut with a logcat warning.
- **Context7 query before coding:** `Media3 ExoPlayer dual instance audio focus and AudioAttributes shared`. Mark as TASK in §J Phase 4.

### B4. Per-source / per-media-type compatibility matrix

| Source / context | Master crossfade | Album mode | Skip silence | Manual fade-now | Fade on pause/resume |
|---|---|---|---|---|---|
| Local audio → local audio | ✅ | ✅ | ✅ | ✅ | ✅ |
| Drive (OAuth) audio → Drive audio | ✅ | ✅ | ✅ | ✅ | ✅ |
| SAF audio → SAF audio | ✅ | ✅ | ✅ | ✅ | ✅ |
| Mixed local + cloud queue | ✅ | ✅ | ✅ | ✅ | ✅ |
| Single-track queue (no next) | ✅ for config but no live overlap | ✅ | ✅ | ✅ | ✅ |
| Audiobook with chapters | greyed default; per-toggle override "Allow on audiobooks" | irrelevant | ✅ | ✅ | ✅ |
| Local video → local video | greyed | greyed | greyed | greyed | greyed |
| Drive video | greyed | greyed | greyed | greyed | greyed |
| Cast active | **whole panel greyed** + banner | — | — | — | — |
| Spotify Connect active | **whole panel greyed** + banner | — | — | — | — |

### B5. Auto-revert behaviour (LOCKED)

- `effectiveCrossfadeEnabled: StateFlow<Boolean>` derived from `(persistedCrossfadeEnabled && uiState.isCrossfadePossible)` in `PlayerViewModel`.
- Audio chain reads `effectiveCrossfadeEnabled`.
- Persisted preference untouched.
- Snackbar on auto-revert: *"Crossfade paused — Spotify manages its own playback."*

---

## Section C — Power-user features (LOCKED 19 items)

### C1. Lock-screen / Quick Settings tile

- New `TileService` `PlaybackQuickSettingsTile`. play/pause + skip-back-15 + skip-forward-15.
- Manifest: `BIND_QUICK_SETTINGS_TILE`.
- Lockscreen controls already work via Media3 MediaSession.

### C2. Listening stats dashboard

- Settings → Stats sub-screen.
- Reads `playback_history` table (already records every play).
- Shows: total tracks played this week / month / all-time, total listening time, top-5 artists, top-5 albums, longest session, most-replayed track.
- Files: `StatsScreen.kt`, `StatsViewModel.kt`.

### C3. Tasker / Intent integration

- Settings → External integrations sub-page.
- Toggles: "Allow play/pause via intent", "Allow skip via intent", "Allow seek-to-position via intent".
- `BroadcastReceiver`s in manifest with toggleable exported flag.
- Documented intents: `com.powermediaplayer.action.PLAY`, `…PAUSE`, `…SKIP_NEXT`, `…SKIP_PREV`, `…SKIP_BACK_30`, `…SKIP_FORWARD_30`, `…SEEK_TO`.

### C6. Smart playlists (capped 20, lazy refresh)

- New section under Library tab: "Smart playlists" expandable.
- Rules combine via AND. Available: Title contains/not, Artist matches/contains, Album matches/contains, Genre matches, Year between, Duration between, Source (Local/Drive/Spotify), Media kind, Last played: more/less than X days ago, Play count: more/less than X, Has bookmark, Is favourite.
- Sort: name / date added / last played / play count / duration / random. Limit: top N.
- Cap 20. UI shows "Limit reached — delete one to add another" on 21st attempt.
- Refresh: lazy — only when user taps INTO a specific smart playlist (NOT on Library tab open).
- Year + genre rules: warning banner "Run deep-scan first" when deep-scan is OFF and rule references year/genre.
- New Room table `smart_playlist` (id, name, rules-json, sort, limit, createdAt).

### C7. Per-file playback overrides (starred/pinned only, NOT smart-playlist matches)

- Long-press menu on a starred-or-pinned row → "Override settings".
- Pop-up with three sub-tabs: Audio / Video / Speed.
  - Audio: reverb preset, stereo flip, mono mix, EQ preset, ReplayGain mode.
  - Video: mirror H, mirror V, B&W, sepia, invert, rotation.
  - Speed: speed (0.5..2.0×), pitch (0.5..2.0×).
- New Room table `media_overrides` (mediaUri PK, audio-effects-json, video-effects-json, speed, pitch, updatedAt).
- Auto-apply at play start. Indicator chip in player: "Custom audio/video/speed for this file".
- Override values clear when the file is unstarred / unpinned.

### C9. OpenSubtitles auto-fetch (LOCKED — bake key + per-user login)

- Settings → Video → "Subtitles":
  - Auto-download subtitles — master switch.
  - Sign in to OpenSubtitles — opens browser to `https://www.opensubtitles.com/users/sign_in` for free account creation; credentials stored in `EncryptedSharedPreferences`.
  - Languages — multi-select chip set.
  - Match by hash / Fall back to filename — radio.
  - Save next to video / Save in app cache — radio.
  - Override existing .srt — switch.
- API key baked into `BuildConfig` from `local.properties`. (Key only identifies our app, not the user — Context7-confirmed via `dusking/opensubtitles-com` evidence.)
- Master switch greyed until user signs in. Banner: *"Sign in to opensubtitles.com (free) to download subtitles."*
- Implementation: `OpenSubtitlesProvider.kt` follows the per-user-login pattern: `OpenSubtitles(appName, apiKey).login(username, password)` per Context7 docs.

### C10. Podcast subscription manager

- New section in Cloud tab below Spotify favourites.
- "Add podcast by RSS URL" + a search field hitting iTunes/Apple-Podcasts directory.
- Subscribed shows → episode list per show.
- Per-show settings: auto-download new episodes, retention (last N), notify when new episode.
- Storage: `podcast_show`, `podcast_episode` tables.
- Download folder: fixed = `Movies/PowerMediaPlayer/podcasts/<showSlug>/`.

### C11. Sleep timer extensions

- Existing dialog gains 4 mode buttons: Time-based / End of track / End of chapter / End of album-or-queue.
- Plus "Linear fade-out over last 5 minutes" switch (works with all 4 modes).
- Extend `_sleepTimerRemainingMs` to a `SleepTimerState` sealed class.

### C12. Wake-up alarm — full-screen morning alarm

- Settings → Alarms sub-page (own section).
- Per-alarm config:

  **Schedule:**
  - Time picker.
  - Days-of-week multi-pick (Mon..Sun) OR "Once".

  **Audio:**
  - Track / playlist / smart playlist to play.
  - Start volume % (default 10%).
  - End volume % (default 80%).
  - Ramp duration in seconds (default 60 s).
  - Direction is implicit: start < end → volume **rises** over the ramp (gentle wake). start > end → volume **falls** over the ramp (uncommon but supported).
  - **Hold duration after ramp** (Q6 = B locked): 1 / 5 / 10 / 15 / 30 / 60 / "indefinite" minutes. Default 30. Alarm rings at end-volume for this duration before winding down.
  - **Wind-down fade duration**: 0 / 30 / 60 / 120 / 300 s. Default 60 s. After hold, alarm fades from end-volume to 0 over this period then auto-stops. 0 = abrupt cut at hold-end.
  - **Indefinite hold** disables wind-down — alarm rings at end-volume until user taps Stop or Snooze (or hits max snoozes if set).
  - UX: alarm screen shows live countdown text — "Auto-stops in 28 min" during hold, "Winding down…" during fade.

  **Snooze:**
  - Enable/disable.
  - Snooze duration: 1 / 5 / 10 / 15 / 30 min.
  - Max snoozes: 1 / 3 / 5 / unlimited.
  - Volume on snooze: continue ramp / restart from start volume.

  **Skip controls:**
  - "Skip next N alarms" — numeric picker (1..7). Decrements each fire.
  - Whole alarm enable/disable switch.

  **Stop method:**
  - Tap dismiss / Shake to dismiss / Solve a math problem to dismiss.
  - Vibration on/off.

  **Full-screen wake (Android 14+ compliant):**
  - Permissions:
    - `SCHEDULE_EXACT_ALARM` (auto-granted under `USE_EXACT_ALARM` for alarm-clock-class apps).
    - `USE_FULL_SCREEN_INTENT` (Android 14+ user-grant via Settings → Apps → Special access). Onboarding banner walks user through grant on first alarm save.
  - Scheduling: `AlarmManager.setAlarmClock(AlarmClockInfo, PendingIntent)`. Exempt from doze; system shows alarm icon in status bar.
  - Fire path: `AlarmReceiver` (BroadcastReceiver) → starts `FullScreenAlarmActivity` via high-priority notification with `setFullScreenIntent`.
  - `FullScreenAlarmActivity` flags: `setShowWhenLocked(true)`, `setTurnScreenOn(true)`. Dismisses keyguard after auth if device secured.
  - DND override: `AudioAttributes.USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`. Bypasses DND/silent.

  **Visual design (LOCKED):**
  - Edge-to-edge dark theme.
  - Top: large 12-hour digital time + AM/PM, day-of-week underneath.
  - Middle: album art (large square), track title, artist.
  - Volume ramp: thin progress bar showing current ramp position.
  - Bottom: large rounded "Snooze" button (70% width); "Stop" button below requires swipe-to-confirm.
  - Math-problem mode: "Solve to dismiss: 47 + 28 = ?" + numeric input + Confirm button.

- Files: `AlarmsScreen.kt`, `AlarmsViewModel.kt`, `AlarmScheduler.kt`, `AlarmReceiver.kt`, `FullScreenAlarmActivity.kt`. New entity `ScheduledAlarmEntity`.
- **Context7 query before coding:** `Android 14 USE_FULL_SCREEN_INTENT permission grant flow + setShowWhenLocked AlarmClockInfo`. Already confirmed during planning.

### C13. Headphone-aware EQ

- New section "Headphone presets" at the bottom of EQ tab.
- Lists every Bluetooth audio device the phone has paired with (`BluetoothAdapter.bondedDevices`).
- Each row: dropdown of EQ presets ("Always Flat", …, "Don't auto-switch").
- On device-connect (existing `AudioOutputDetector`), chosen preset auto-applies.

### C14. Audio focus policy

- Settings → Playback → "When other audio plays":
  - Pause (default ON for alarms / phone calls).
  - Duck (default ON for other media notifications, voice navigation).
  - Ignore (default OFF).
- Three sub-radios per scenario.
- Implementation: `AudioAttributes` + `AudioFocusRequest` in `PlaybackService`.

### C16. Library refresh-on-tab-switch

- `LaunchedEffect(Unit)` in `LibraryScreen` calls `viewModel.refreshIfStale()` on tab open. Stale = >30 s.
- Same pattern for Cloud tab.
- Re-uses existing refresh icon at `LibraryScreen.kt:153-157` for explicit manual refresh (verified present).
- Zero battery drain, zero memory overhead.

### C17. Discogs / MusicBrainz metadata enrichment

- Settings → Library → "Online metadata enrichment".
- Master switch + sub-radio: Discogs / MusicBrainz / Both.
- Sub-toggles: Fetch missing artwork, Fetch missing year, Fetch missing genre.
- Apply to: All files / Only files with no embedded tags.
- Cache results.

### C18. ReplayGain library scanner

- Settings → Library → "ReplayGain scan".
- Switch + button "Scan now".
- Two modes: Track gain / Album gain (each to -14 LUFS).
- "Auto-scan new files on import" switch.

### C20. Home-screen widget — robust across all sizes

- New `AppWidgetProvider` `NowPlayingWidget`.
- Three layouts via `app:resizeMode="horizontal|vertical"` + `appWidgetMaxWidth/MaxHeight`:
  - Compact (1×1): play/pause only.
  - Wide (4×1): transport row.
  - Large (4×2): cover art + title/artist + transport row.
- Foldable: same widget responds to size changes via `useUpdateForCollections="true"` (Android 12+) — handles fold/unfold transitions without rebuild.
- Updates on `MediaSession.PlaybackStateChanged`.
- Tap → opens `MainActivity` deep-linked to Player tab.
- **Context7 query before coding:** `AppWidgetProvider responsive layouts Android 12 + setStateDescription + foldable`. Mark as TASK in §J Phase 8.

### C22. Plug-in resume

- Settings → Playback → "Auto-play on headphone connect".
- Switch (default OFF — opt-in).
- `BroadcastReceiver` for `Intent.ACTION_HEADSET_PLUG`.

### C25. Long-press track menu — bottom-sheet

- 9 menu items: Favourite / Hide / Add to queue next / Edit tags / Override speed / Override audio effects / Override video effects / Share / Delete.
- Override-* items only appear when row is starred or pinned.
- New: `TrackContextSheet.kt` — `ModalBottomSheet`. Used from Library, Last Played, Cloud screens.
- Activation: `combinedClickable(onLongClick = { showSheet = true })`. **NOT** activated via 3-dot menu — that's reserved for multi-select.

### C26. Multi-select (LOCKED — Library + Cloud)

- 3-dot menu in tab top bar → "Select multiple" → enters tick-box mode.
- Each row gains a Material Checkbox on the left; tap row to toggle tick.
- Top bar replaced with `SelectionTopAppBar`: Delete / Favourite / Hide / Add to queue / Share / Cancel.
- Activation only via 3-dot → "Select multiple". Long-press is reserved for context sheet.
- Selection state in viewmodel: `selectedUris: Set<String>`.

### C27. Hidden files

- Long-press → "Hide" → row vanishes from Library / Cloud lists. File stays on phone.
- Hidden state in DataStore (`hidden_uris: Set<String>`). Survives reinstalls via Auto Backup (`allowBackup="true"` confirmed at manifest:59).
- Settings → Library → "Hidden files" sub-screen — list of hidden URIs with "Unhide" button per row.
- Filter applied in `LibraryViewModel.filterVisible(items, hiddenUris)` after existing filters.

### C28. Drive offline copy (Spotify excluded — TOS)

- Long-press a Drive track → "Make offline copy".
- App downloads audio file once into `app-private-storage/offline/<driveFileId>.<ext>` via existing OAuth flow.
- "Downloaded" badge appears on the row.
- Subsequent plays use local copy; no internet needed.
- Settings → Cloud → "Offline storage limit": 1 GB / 5 GB / 10 GB / Unlimited (default 5 GB). LRU eviction of oldest unstarred copy.
- Spotify rows: "Make offline copy" item never appears (TOS — Spotify DRM-protected stream forbids third-party caching).
- Storage: Room table `offline_copy` (driveFileId PK, localPath, byteSize, createdAt, lastPlayedAt).
- File: `DriveOfflineDownloader.kt`. Coexists with existing Drive prefetch cache: prefetch is best-effort metadata + first-chunk; offline copy is full-file deliberate download. Different code paths, no conflict.

---

## Section D — Settings tab reorganisation (LOCKED)

Order: most-used near top, About at bottom (standard Android pattern).

### 1. Display
- Cover-art sizing (Fit / Fill).
- Theme (always dark for v1.0).

### 2. Auto-hide controls
- Video controls auto-hide: 1/2/3/4/6/8 s / Never. Default: 4 s (current).
- Audio controls auto-hide (folded/phone): same range. Default: Never.
- Audio controls auto-hide (unfolded/tablet): same range. Default: Never.
- Sub-popup auto-hide (audio effects): 1/2/3/5/8 s. Default: 3 s.
- Sub-popup auto-hide (video effects): same. Default: 3 s.
- Sub-popup auto-hide (crossfade panel): same. Default: 5 s.

### 3. Playback
- Audio focus policy (§C14).
- Auto-play on headphone connect (§C22).
- Resume on Bluetooth (existing).
- Gapless playback (existing).
- Reverse audio (existing).
- Independent pitch (existing).
- Volume boost (existing).
- Audio delay (existing).

### 4. Audio effects
- Reverb preset (existing).
- Stereo flip (existing).
- Mono mix (existing).
- Multi-channel passthrough (existing).
- ReplayGain toggle (existing).

### 5. Crossfade
- Master crossfade switch (mirrors player popup).
- All 9 crossfade settings.

### 6. Video
- Mirror H / V (existing).
- B&W / Sepia / Invert (existing).
- Rotation (existing).
- Subtitles auto-fetch (§C9).
  - Auto-download switch.
  - Sign in to OpenSubtitles.
  - Languages.
  - Match-by-hash / Filename.
  - Save next to video / In app cache.
  - Override existing .srt.

### 7. Subtitles (global config)
- Format (existing).
- Subtitle delay (existing).

### 8. Library
- Deep scan — *"Reads each file's full header for accurate metadata. Slow on big libraries."*
- Hidden files sub-screen (§C27).
- ReplayGain scan (§C18).
- Online metadata enrichment (§C17).
- Library auto-refresh on tab open (default ON, §C16).

### 9. Cloud
- Drive picked folders (existing).
- Drive Picker first-pick warning (existing).
- Spotify-not-installed message (existing).
- Offline storage limit (§C28).

### 10. Bluetooth Car Controls
- Previous-button / Next-button mapping (existing).
- Skip-back / skip-forward seconds (existing).

### 11. Alarms (NEW — §C12)
- List of alarms.
- Add alarm.
- Onboarding banner if `USE_FULL_SCREEN_INTENT` not granted.

### 12. External integrations (§C3)
- Tasker / Intent toggles.

### 13. Stats (§C2)
- Listening dashboard.

### 14. About + Diagnostics
- Version / build number.
- Privacy policy.
- First-run prompt: "Allow library deep scan?" (see §F).

### 15. Crash diagnostics
- Share crash logs (existing).

Each setting gets a one-liner *underneath* the control in `bodySmall` / `TextTertiary`.

---

## Section E — Spotify Connect end-to-end test (run AFTER all features)

Run on emulator (with Spotify installed, account signed in) AND Z Fold 6. Capture logcat.

1. Reset state: open Spotify app, log out, log back in.
2. Open our app's Cloud tab → Spotify section.
3. Verify "Spotify Connect device" green card at top.
4. Tap card; capture device list.
5. Parallel verify via `curl -H "Authorization: Bearer <token>" https://api.spotify.com/v1/me/player/devices`. Compare. Must match.
6. Tap a device. Verify subsequent `playTrackOnConnectDevice` lands on it. Capture logcat: expect `Spotify.transferPlayback http=204` then `Spotify.playRequest http=204`.
7. Disconnect Spotify on device. Tap a pinned Spotify track. Verify cold-start bounce via `SpotifyBounceBridgeActivity`.
8. Capture timing: tap → audio-out-of-speaker. Should be < 4 s.
9. Negative test: tap Spotify track with NO devices anywhere. Expect: bounce flow opens Spotify, ~2 s wait, returns to our app, plays.
10. Mid-playback test: while Spotify Connect playing, tap pause/skip in our app. Verify Spotify responds.

---

## Section F — First-run deep-scan opt-in prompt

- Fresh install, after media permissions granted, on first Library-tab open: one-time `AlertDialog`:
  > **Find more accurate album art and metadata?**
  > We can re-read every track's full header for richer titles, artists, and artwork. Takes a few seconds the first time. You can change this anytime in Settings → Library.
  > [Skip] [Yes, deep-scan]
- "Skip" → DataStore `first_run_seen=true`.
- "Yes" → DataStore `useDeepScan=true` + `first_run_seen=true`, kicks one-shot scan immediately.
- Permissions `READ_MEDIA_AUDIO` + `READ_MEDIA_VIDEO` already requested in MainActivity / LibraryViewModel; popup shows AFTER grant.

---

## Section G — Files modified / created

```
NEW (~33 files):
  app/src/main/java/com/powermediaplayer/ui/info/InfoIcon.kt
  app/src/main/java/com/powermediaplayer/ui/info/InfoSheet.kt
  app/src/main/java/com/powermediaplayer/ui/info/InfoContent.kt
  app/src/main/java/com/powermediaplayer/ui/player/components/CrossfadeButton.kt
  app/src/main/java/com/powermediaplayer/ui/player/components/TrackContextSheet.kt
  app/src/main/java/com/powermediaplayer/ui/library/SelectionTopAppBar.kt
  app/src/main/java/com/powermediaplayer/ui/library/HiddenFilesScreen.kt
  app/src/main/java/com/powermediaplayer/ui/stats/StatsScreen.kt
  app/src/main/java/com/powermediaplayer/ui/stats/StatsViewModel.kt
  app/src/main/java/com/powermediaplayer/ui/podcasts/PodcastsScreen.kt
  app/src/main/java/com/powermediaplayer/ui/podcasts/PodcastsViewModel.kt
  app/src/main/java/com/powermediaplayer/ui/smartplaylists/SmartPlaylistEditorScreen.kt
  app/src/main/java/com/powermediaplayer/ui/smartplaylists/SmartPlaylistViewModel.kt
  app/src/main/java/com/powermediaplayer/ui/overrides/MediaOverridesPopup.kt
  app/src/main/java/com/powermediaplayer/alarm/AlarmsScreen.kt
  app/src/main/java/com/powermediaplayer/alarm/AlarmsViewModel.kt
  app/src/main/java/com/powermediaplayer/alarm/AlarmScheduler.kt
  app/src/main/java/com/powermediaplayer/alarm/AlarmReceiver.kt
  app/src/main/java/com/powermediaplayer/alarm/FullScreenAlarmActivity.kt
  app/src/main/java/com/powermediaplayer/cloud/OpenSubtitlesProvider.kt
  app/src/main/java/com/powermediaplayer/cloud/PodcastRssClient.kt
  app/src/main/java/com/powermediaplayer/cloud/DriveOfflineDownloader.kt
  app/src/main/java/com/powermediaplayer/audio/ReplayGainScanner.kt
  app/src/main/java/com/powermediaplayer/integration/TaskerReceiver.kt
  app/src/main/java/com/powermediaplayer/widget/NowPlayingWidget.kt
  app/src/main/java/com/powermediaplayer/widget/PlaybackQuickSettingsTile.kt
  app/src/main/java/com/powermediaplayer/service/CrossfadeController.kt
  app/src/main/res/xml/widget_provider_info.xml
  app/src/main/res/layout/widget_now_playing_compact.xml
  app/src/main/res/layout/widget_now_playing_wide.xml
  app/src/main/res/layout/widget_now_playing_large.xml
  app/src/main/res/drawable/ic_frame_step_back.xml
  app/src/main/res/drawable/ic_frame_step_forward.xml

MODIFIED (~14 files):
  app/src/main/AndroidManifest.xml — widget + tile + tasker + alarm components, USE_FULL_SCREEN_INTENT, USE_EXACT_ALARM
  app/src/main/java/com/powermediaplayer/data/preferences/SettingsDataStore.kt — ~30 new keys
  app/src/main/java/com/powermediaplayer/data/db/AppDatabase.kt — bump v7 → v8 + Migration_7_8
  app/src/main/java/com/powermediaplayer/data/db/entity/SmartPlaylistEntity.kt
  app/src/main/java/com/powermediaplayer/data/db/entity/MediaOverrideEntity.kt
  app/src/main/java/com/powermediaplayer/data/db/entity/PodcastShowEntity.kt
  app/src/main/java/com/powermediaplayer/data/db/entity/PodcastEpisodeEntity.kt
  app/src/main/java/com/powermediaplayer/data/db/entity/HeadphoneEqPresetEntity.kt
  app/src/main/java/com/powermediaplayer/data/db/entity/ScheduledAlarmEntity.kt
  app/src/main/java/com/powermediaplayer/data/db/entity/OfflineCopyEntity.kt
  app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt
  app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt
  app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt
  app/src/main/java/com/powermediaplayer/ui/library/LibraryViewModel.kt
  app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedScreen.kt
  app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt
  app/src/main/java/com/powermediaplayer/ui/equalizer/EqualizerScreen.kt
  app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt
  app/src/main/java/com/powermediaplayer/ui/settings/SettingsViewModel.kt
  app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt
  app/src/main/java/com/powermediaplayer/service/PlaybackService.kt
  app/src/main/java/com/powermediaplayer/data/repository/LastPlayedRepository.kt
```

Schema migration v7 → v8: adds 7 new tables (`smart_playlist`, `media_overrides`, `podcast_show`, `podcast_episode`, `headphone_eq_preset`, `scheduled_alarm`, `offline_copy`).

---

## Section H — Decisions locked

| ID | Decision |
|---|---|
| Crossfade overlap | True two-player overlap |
| Wake-up alarm placement | Settings → Alarms own section |
| Stats placement | Settings → Stats sub-screen |
| Long-press menu look | Bottom sheet (`ModalBottomSheet`) |
| Smart playlists cap | 20 max, refresh on tap-into |
| Podcast download folder | Fixed: `Movies/PowerMediaPlayer/podcasts/<showSlug>/` |
| OpenSubtitles | Bake API key + per-user login (combined auth, Context7-confirmed) |
| First-run dialog | Single popup on first Library-tab open |
| Default fade curve | Equal-power |
| Per-file overrides scope | Manually starred/pinned only |
| Multi-select | Build it (3-dot only, NOT long-press) |
| Hidden files | Build it (DataStore + AutoBackup) |
| Drive offline copy | Build it (Spotify excluded — TOS) |
| Frame-step icons | Replace ONLY frame-step icons in BOTH portrait + landscape sets |
| Auto-DJ | Dropped |
| Metadata handoff timing | Switch on crossfade START |
| Alarm features | Volume ramp, snooze (duration + max count), days, skip-N, math-problem stop, full-screen lock-screen wake (Android 14+) |
| Folder watcher | Refresh on tab open |
| Spotify Connect test | After all features built |
| Bookmark mirror | Universalised — `recordPlay` on cold-start resume + Spotify mirror first emit + notification resume |
| Mid-crossfade interactions | Pause: both pause; Scrub: abort, jump A; Next: abort, B becomes A; Prev: abort, jump A back |
| Audio-effect chain handover | All 4 effects (EQ/reverb/stereo-flip/mono-mix) hot-swap at midpoint with 50 ms cubic smoothing |
| Mid-crossfade Cast/Spotify activation | `playerB` released immediately, master auto-greys |
| Execution mode | INLINE main thread, no subagents |
| Q2 video gradient | Option A — gradient hides with controls (current behaviour preserved) |
| Q3 settings order | Fine — 15 sections per §D as-proposed |
| Q5 Spotify test scope | Lightweight per phase + full E2E only at Phase 10 |
| Q6 alarm volume direction | A + B — start→end ramp PLUS separate hold-then-wind-down auto-fade |
| Q7 info-icon groupings | Logical groups (accordion). Groupings drafted: Player 5 / Library 3 / Last Played 3 / Cloud 4 / EQ 3 |
| Phase 11 | Cast bug diagnostic — 9 tasks, 6 hypotheses, evidence-locked bisect on emu + Z Fold 6 |

---

## Section I — Testing protocol (vigorous, every commit)

### I.1. Before every commit (mandatory checklist)

- [ ] Build succeeds: `./gradlew assembleDebug` exits 0.
- [ ] No new lint regressions: `./gradlew lintDebug` passes (or only adds pre-existing warnings).
- [ ] Unit tests pass: `./gradlew testDebugUnitTest`.
- [ ] Install on emulator: `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Install on Z Fold 6: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Run feature smoke test on emulator + Z Fold 6 (per-feature, see I.3).
- [ ] Capture logcat to `dist/logs/<phase>-<task>-emu.log` and `<phase>-<task>-zfold.log`. Grep for `FATAL EXCEPTION`, `AndroidRuntime`, `E/`, our package's `W/`. Zero tolerance for new fatals.
- [ ] Re-run Phase regression suite (I.2).
- [ ] Commit with conventional message + push.

### I.2. Phase regression suite (run at end of each Phase)

For every phase, before declaring it complete:

- [ ] **Launch stress** — fresh install + cold launch + 5 rotations + `am kill com.powermediaplayer` + relaunch + `adb shell input keyevent KEYCODE_BACK` 20× + foreground/background cycle. Zero crashes.
- [ ] **Memory pressure** — `adb shell am send-trim-memory com.powermediaplayer COMPLETE` after each tab visit. Relaunch. Zero crashes.
- [ ] **Cast regression** — connect a Chromecast on the same WiFi (or `cast-debug-logger`-installed emulator with Chromecast Sender SDK). Verify: Cast button visible, taps open picker without crash, picker lists devices, tap a device transfers playback, then disconnect, all features still work.
- [ ] **Bluetooth regression** — connect a paired BT speaker. Verify: media output routes, BT car-control buttons remap correctly, audio focus duck on incoming notification.
- [ ] **Spotify regression** — sign in to Spotify, play a track, verify mirror reaches Player tab with metadata + album art + lyrics where available.
- [ ] **Drive regression** — sign in to Drive, browse a picked folder, play a Drive audio file, verify CastRelayServer chunk-streams.
- [ ] **EQ during cast** — start Cast, open EQ tab, verify all sliders disabled and a "Casting — EQ disabled" hint visible.
- [ ] **Audiobook nav** — play a chapterised m4b, verify prev/next jumps chapters and the chapter slider behaves.
- [ ] **Sleep timer** — set a 10s timer, verify auto-pause on expiry.
- [ ] **A-B loop** — set loop, verify it loops cleanly.
- [ ] **Notification + lockscreen** — play, lock device, verify lockscreen controls + tap-to-resume work.
- [ ] **Headphone plug** — toggle ON the auto-play setting, plug headphones during paused playback, verify auto-resume.

### I.3. Per-feature smoke tests

Each phase has a feature-specific smoke set. Defined inline in Section J before each phase's task list.

### I.4. When a test fails

- DO NOT silently move on or guess the cause.
- Engage `superpowers:systematic-debugging`:
  1. Reproduce reliably.
  2. Capture full logcat from launch to crash.
  3. Bisect via Read/Grep against the latest changes.
  4. Identify root cause from code evidence + logcat.
  5. Fix + re-test.
  6. Save logcat as evidence in `dist/logs/<phase>-<task>-rootcause.log`.

### I.5. After each Phase passes regression

- [ ] Push to GitHub.
- [ ] adb-install debug APK to Z Fold 6 (per durable instruction).
- [ ] Build a release APK + sideload to emulator + Z Fold 6: `./gradlew bundleRelease` AND `assembleRelease`. Verify launch works (release-only crashes such as ProGuard rules occasionally hide here).

---

## Section J — Granular task list (phase-by-phase)

Use `TodoWrite` (TaskCreate) at the start of each phase. Mark each task in_progress when started, completed when its commit is pushed AND tests pass on both devices.

### Phase 1 — Foundation (no audio chain risk)

**Goal:** universal bookmark mirror + frame-step icon swap + info-icon framework + per-tab info content.

**Tasks:** 12. **Estimated wall-clock:** ~3 hours.

#### Task 1.1: Universalise `currentSessionId` — PlayerViewModel cold-start path

- Files: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt`.
- [ ] Read PlayerViewModel.kt:81-103 (`addBookmarkHere`) and :160-175 (5-second tick) to confirm current state. Anchor evidence.
- [ ] Add a flow listener inside the existing 5-s tick: if `playbackConnection.playerState.value.currentMediaItem != null && currentSessionId == null` → call `lastPlayedRepo.recordPlay(synthesisedFromMediaItem(it))`.
- [ ] Build: `./gradlew assembleDebug`.
- [ ] Install + launch on emulator + Z Fold 6.
- [ ] Smoke: install over a previous build that had a saved-resume position, launch, verify `Diag.i("PlayerViewModel", "recordPlay synthesised from cold-start MediaItem")` appears in logcat. Resume then add a bookmark; verify it appears in Last Played → Recents.
- [ ] Commit: `feat(player): universalise sessionId on cold-start resume`.

#### Task 1.2: Universalise `currentSessionId` — Spotify mirror path

- Files: `app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt`.
- [ ] Grep for `_spotifyState` writers; locate the first-emit path.
- [ ] Add a side-effect: when `_spotifyState.value` transitions from null → non-null with a fresh `trackUri`, fire `lastPlayedRepo.recordCloudPlay(uri=trackUri, title=..., artist=..., source="spotify")`.
- [ ] Build + install + Spotify-Connect smoke: tap a Spotify track in our app, verify `recordCloudPlay` fires (logcat).
- [ ] Commit: `feat(cloud): record Spotify mirror first-emit as a session`.

#### Task 1.3: Frame-step icons — drawables + UI swap

- Files: `app/src/main/res/drawable/ic_frame_step_back.xml`, `ic_frame_step_forward.xml`. Modify `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt:486+509+682+705`.
- [ ] Author both vector drawables (24dp viewport, white tint via `android:tint="?attr/colorOnSurface"`). Visual: triangle pointing in direction + thin vertical bar.
- [ ] Replace both `Icons.Filled.SkipPrevious` references on lines 486 + 682 with `painterResource(R.drawable.ic_frame_step_back)`.
- [ ] Replace both `Icons.Filled.SkipNext` references on lines 509 + 705 with `painterResource(R.drawable.ic_frame_step_forward)`.
- [ ] Add `contentDescription = "Step one frame back"` / `"Step one frame forward"` on the two `IconButton`s.
- [ ] Modify `IconButton` `onClick` to also call `viewModel.pause()` first if `uiState.isPlaying`.
- [ ] Wrap each frame-step `IconButton` with `if (uiState.isVideoContent && !uiState.isCasting) {...}` so they only show in the video case.
- [ ] Build + install + smoke: open a video file, pause, tap frame-step, verify the seekbar moves ~33 ms. Open an audio file, verify buttons hidden.
- [ ] Commit: `feat(player): distinct frame-step icons + video-only visibility`.

#### Task 1.4: Info-icon component — `InfoIcon.kt`

- Files: `app/src/main/java/com/powermediaplayer/ui/info/InfoIcon.kt`.
- **PENDING USER CONFIRMATION (Q1):** shape = circle vs rounded-square. Defer this task until answered. Default plan = filled blue circle-i if no answer.
- [ ] Implement composable `InfoIcon(onClick: () -> Unit, modifier: Modifier = Modifier)` that renders the circle/rounded-square per chosen shape.
- [ ] Tint = `Color(0xFF1E88E5)` (blue-600).
- [ ] Tap = trigger `onClick`. Touch target = 48dp (Material accessibility minimum).
- [ ] Build (no UI integration yet).
- [ ] Commit: `feat(info): InfoIcon composable`.

#### Task 1.5: Info-sheet component — `InfoSheet.kt`

- Files: `app/src/main/java/com/powermediaplayer/ui/info/InfoSheet.kt`.
- [ ] Implement `InfoSheet(sections: List<InfoSection>, onDismiss: () -> Unit)`.
- [ ] Uses `ModalBottomSheet`. Each section is a `Surface(clickable)` with a chevron that rotates on expand.
- [ ] Expanded state per section in `rememberSaveable(mutableStateOf(false))`.
- [ ] Inside the section: bullet list rendered as `Text` per item.
- [ ] Build.
- [ ] Commit: `feat(info): InfoSheet collapsible bottom sheet`.

#### Task 1.6: Info-content data — `InfoContent.kt`

- Files: `app/src/main/java/com/powermediaplayer/ui/info/InfoContent.kt`.
- [ ] Define `data class InfoSection(val title: String, val bullets: List<String>)`.
- [ ] Define `data class InfoSheetData(val tab: String, val sections: List<InfoSection>)`.
- [ ] Add `val playerInfo: InfoSheetData = InfoSheetData("Player", listOf(InfoSection("Now Playing", listOf("Cover art, title, artist, album. Tap the cover for full-screen.")), …))` for ALL Player-tab bullets from §A2.
- [ ] Repeat for `libraryInfo`, `lastPlayedInfo`, `cloudInfo`, `equalizerInfo`. Each list pulled verbatim from §A2.
- [ ] Build.
- [ ] Commit: `feat(info): per-tab info content`.

#### Task 1.7: Wire info icon into Player tab

- Files: `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt`.
- **PENDING USER CONFIRMATION (Q2):** scrim independence. If Path A: place `InfoIcon` inside `OverlayContent` (line 393-410) at the top-right. If Path B: refactor scrim to a sibling Box outside `AnimatedVisibility` AND place `InfoIcon` inside `OverlayContent`. Deferred until answered.
- [ ] Add a top-right-anchored `Box(Modifier.align(Alignment.TopEnd))` with `InfoIcon { showInfoSheet = true }` inside the chosen layer.
- [ ] Add `var showInfoSheet by remember { mutableStateOf(false) }`.
- [ ] When `showInfoSheet`: render `InfoSheet(sections = playerInfo.sections, onDismiss = { showInfoSheet = false })`.
- [ ] Smoke on emulator + Z Fold 6:
  - [ ] Open audio file. Info icon visible top-right. Tap. Sheet shows. Verify all 14 Player-tab bullets render.
  - [ ] Open video file. Info icon visible top-right. Wait 4 s. Verify icon hides with controls. Tap-screen to bring controls back. Verify icon reappears.
  - [ ] If Path B: with controls hidden on a video, verify scrim still renders (visual inspection).
- [ ] Commit: `feat(player): info icon + sheet`.

#### Task 1.8: Wire info icon into Library tab

- Files: `app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt`.
- [ ] Add `InfoIcon` to the top app-bar `actions = { ... }` slot, right of the existing refresh icon.
- [ ] State: `var showInfoSheet by remember { mutableStateOf(false) }`.
- [ ] Render `InfoSheet(libraryInfo.sections, onDismiss = ...)`.
- [ ] Smoke: open Library tab, verify icon visible top-right. Tap. All 8 Library-tab bullets render.
- [ ] Commit: `feat(library): info icon`.

#### Task 1.9: Wire info icon into Last Played tab

- Files: `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedScreen.kt`.
- [ ] Same pattern as 1.8, with `lastPlayedInfo.sections`.
- [ ] Smoke: tap, verify all 7 Last Played bullets render.
- [ ] Commit: `feat(lastplayed): info icon`.

#### Task 1.10: Wire info icon into Cloud tab

- Files: `app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt`.
- [ ] Same pattern, with `cloudInfo.sections`.
- [ ] Smoke: tap, verify all 9 Cloud-tab bullets render.
- [ ] Commit: `feat(cloud): info icon`.

#### Task 1.11: Wire info icon into Equalizer tab

- Files: `app/src/main/java/com/powermediaplayer/ui/equalizer/EqualizerScreen.kt`.
- [ ] Same pattern, with `equalizerInfo.sections`.
- [ ] Smoke: tap, verify all 8 EQ bullets render.
- [ ] Commit: `feat(eq): info icon`.

#### Task 1.12: Phase 1 regression sweep

- [ ] Run full Section I.2 regression suite on emulator + Z Fold 6.
- [ ] Capture logs.
- [ ] Build + sideload release APK to both devices. Launch. Zero crashes.
- [ ] Document phase completion in `dist/logs/phase-1-complete.md`.

### Phase 2 — Settings tab reorganisation (UI-only, no audio risk)

**Goal:** Reorganise Settings into 15 sub-sections per §D + add per-setting one-liners.

**Tasks:** 6. Per-task pattern: read SettingsScreen.kt, identify which settings live where, Edit to move into nested expandable sections, add the one-liner string, build + smoke.

(Task list expansion done just-in-time before Phase 2 starts.)

### Phase 3 — Library improvements

**Goal:** refresh-on-tab-switch + long-press menu + multi-select + hidden files.

**Sub-features:** C16, C25, C26, C27.

**Tasks:** ~14.

### Phase 4 — Crossfade + true two-player

**Goal:** B1 + B2 + B3 (whole crossfade subsystem).

**HIGH-RISK PHASE.** Audio chain rewrite. Must run full I.2 regression suite + extra audio tests.

**Pre-flight Context7 query:** `Media3 ExoPlayer dual instance audio focus AudioAttributes shared`.

**Tasks:** ~22.

**Per-feature smoke tests:**
- Crossfade between two local MP3s. Audible overlap at midpoint.
- Crossfade between local → Drive. Verify Drive prefetch.
- Crossfade off → on → off mid-playback. No audio glitch.
- Tap pause mid-crossfade. Both fade pauses.
- Tap Next mid-crossfade. Skip immediately to next track at full volume.
- Tap Previous mid-crossfade. Jump back, B released.
- Scrub mid-crossfade. Crossfade aborts cleanly.
- Cast on mid-crossfade. B released, master greyed.
- Spotify Connect mid-crossfade. Same.
- Album mode ON: same-album consecutive tracks have no fade.
- Audiobook with chapters: master greyed by default. Override applied: fade between chapters.
- Each fade curve audibly different.
- Equal-power: no perceived dip.
- Linear: noticeable dip.
- Memory: monitor RSS via `adb shell dumpsys meminfo com.powermediaplayer` before/during/after a crossfade. Confirm 10-20 MB peak, returns to baseline.

### Phase 5 — Per-file overrides (depends on long-press menu)

**Sub-features:** C7.

**Tasks:** ~8.

### Phase 6 — Cloud features

**Sub-features:** C9 (OpenSubtitles), C28 (Drive offline), C10 (Podcast).

**Pre-flight Context7 query:** `OpenSubtitles REST API authentication and download` (already done).

**Tasks:** ~16.

### Phase 7 — Alarms

**Sub-features:** C12 (Wake-up alarm).

**HIGH-RISK PHASE.** Permission flow + lockscreen behaviour + DND.

**Pre-flight Context7 query:** `Android 14 USE_FULL_SCREEN_INTENT permission grant flow setShowWhenLocked AlarmClockInfo` (already partially done).

**Tasks:** ~18.

**Per-feature tests (manual, on Z Fold 6 + emulator):**
- Alarm fires at scheduled time with screen off + device locked. Full-screen activity shows. Audio plays.
- Alarm overrides DND.
- Alarm overrides silent ringer.
- Snooze: tap snooze, verify next fire is exactly N minutes later.
- Max snoozes: snooze N times, verify (N+1)th attempt does not snooze (alarm dismisses).
- Days-of-week: schedule for Mon/Wed/Fri only, verify Tue/Thu/Sat/Sun no fire.
- Skip-N: set skip=2, verify next 2 fires skipped, 3rd fires.
- Volume ramp: 30% start → 80% end over 60 s. Verify audible.
- Math-problem stop: solve correctly to dismiss; wrong answer doesn't dismiss.
- Permission denial: revoke USE_FULL_SCREEN_INTENT, schedule alarm, verify falls back to high-priority notification (no full-screen).

### Phase 8 — Widget

**Sub-features:** C20.

**Pre-flight Context7 query:** `AppWidgetProvider responsive layouts foldable Android 12 setStateDescription`.

**Tasks:** ~10.

**Per-feature tests:**
- Add widget at 1×1, 4×1, 4×2 sizes. All render correct layout.
- On Z Fold 6: add widget on cover, fold open, verify widget responds to size change without rebuild.
- On rotation: portrait + landscape. Widget stays correctly sized.
- Tap widget → opens MainActivity at Player tab.
- Play/pause from widget while screen locked.
- Background app, change track via widget. Verify track changes.

### Phase 9 — Remaining toggles

**Sub-features:** C1, C2, C3, C6, C11, C13, C14, C17, C18, C22.

**Tasks:** ~24 (smaller toggles, individually low-risk).

### Phase 10 — First-run + Spotify Connect end-to-end test

**Sub-features:** F + E.

**Tasks:** ~6.

### Phase 11 — Cast bug investigation (NEW — added 2026-05-07)

**Bug as reported by user (verbatim):**

> currently when i cast, it connects to a device but then nothing actually plays, the player wipes all tracks and metadata and it looks like you just opened the app with nothing playing visually but for the cast icon suggesting connected.

**Symptoms decoded:**
- Cast session establishes (cast icon shows "connected").
- Receiver displays nothing OR plays nothing.
- Local Player UI clears: track title, artist, artwork, queue → all empty.
- Looks like a fresh-app-open state visually.

**Hypotheses (each must be evidence-tested via logcat + receiver logs, NOT guessed):**

| H | Hypothesis | Evidence to gather |
|---|---|---|
| H1 | `CastPlayer` takeover wipes the local `MediaItem` queue but the receiver's own queue is never loaded | Logcat for `setMediaItems` calls in `PlaybackService.onCastSessionAvailable` AND on the receiver side |
| H2 | The relay URL produced by `CastRelayServer` is unreachable from the receiver's network (e.g. captive portal, IPv6-only WiFi, AP isolation) | Receiver logs from cast-debug-logger; `curl http://<phoneIP>:<port>/<relayPath>` from another device on the same WiFi |
| H3 | MIME type passed to receiver is wrong / unsupported (e.g. we set `audio/mp4` but file is `audio/mpeg`) | Outgoing `MediaInfo.contentType` field; receiver's loadMedia error code |
| H4 | Drive OAuth token expired by the time receiver requests bytes; relay returns 401 | `CastRelayServer` logs for HTTP 401/403 responses |
| H5 | `MediaItemConverter` (the Compose-Compose-Cast bridge) returns null/empty `MediaInfo` for the current item | `Diag.i("CastPlayer", "convert: ${MediaInfo}")` injected at relevant point |
| H6 | The local Player is intentionally cleared (we delegate to `CastPlayer`) but UI binding still references local `playerState` instead of the active player. UI-only bug — playback might actually work, the user just sees an empty player UI | Audible test: insert a long audio file, cast, listen — does sound come out of the receiver? If yes = UI binding bug. If no = real load failure. |

**Pre-flight Context7 query:** `Media3 CastPlayer queue handover AddCastSession local-to-receiver MediaItemConverter`. Mandatory before touching the bridge.

**Tools to set up (Phase 11 Task 11.1):**

- `cast-debug-logger` library on the sender side (already in `build.gradle.kts` per existing Cast work) — if not, add it.
- Open Chrome → `chrome://inspect` → "Cast: ..." device → inspect → Console pane shows receiver-side logs in real time.
- `dist/logs/cast-bug-<date>.log` for sender logcat.
- `dist/logs/cast-bug-<date>-receiver.log` for receiver console (copy-paste from Chrome devtools).

**Diagnostic protocol — bite-sized tasks:**

#### Task 11.1: Setup logging infrastructure

- [ ] Verify `cast-debug-logger` dependency in `app/build.gradle.kts`. Add if missing: `implementation("com.google.android.gms:play-services-cast-tv:21.1.0")`. If still missing, alternative: enable `CastReceiverContext.setLogLevel(VERBOSE)` in `PlaybackService.onCreate` after Cast init.
- [ ] Add a `Diag.d("Cast", ...)` line in every method of `CastRelayServer` (request received, range header, response code, byte count).
- [ ] Add `Diag.d("Cast", ...)` at every `MediaItemConverter.toMediaQueueItem` call site logging the final `MediaInfo.contentType` and `MediaInfo.contentUrl`.
- [ ] Build + install + commit: `chore(cast): VERBOSE logging for bug bisect`.

#### Task 11.2: Reproduce on Z Fold 6 with a real Chromecast

- [ ] Connect Chromecast device + Z Fold 6 to the same 2.4 GHz WiFi.
- [ ] `adb logcat -s Cast:V CastPlayer:V CastRelayServer:V MediaSession:V PlayerViewModel:V > dist/logs/cast-bug-zfold-$(date +%H%M).log &`
- [ ] In our app: Library → tap a known-good local MP3 → Cast button → pick the Chromecast.
- [ ] Capture: time-to-icon-connect, time-to-(no-)audio, screenshot of player UI showing empty state.
- [ ] In Chrome devtools (`chrome://inspect`), copy the receiver console output to `dist/logs/cast-bug-zfold-receiver-$(date +%H%M).log`.
- [ ] Stop logcat. Save logs.
- [ ] Document the exact symptom sequence in `dist/logs/cast-bug-symptoms.md`.

#### Task 11.3: Confirm or reject H6 (UI vs real)

- [ ] Cast a 10-minute audio file. Wait 60 s. Press the receiver's volume buttons to see if it has volume control = audio playing.
- [ ] If audio is audible from the receiver: H6 confirmed (UI-only bug). Skip to Task 11.7 (UI binding fix).
- [ ] If silence: continue to H1-H5 evidence.

#### Task 11.4: Bisect H1-H5 by reading the captured logs

- [ ] Open `dist/logs/cast-bug-zfold-$(date).log`.
- [ ] Search for `MediaInfo.contentType=`. Should match the file's MIME (e.g. `audio/mpeg` for MP3).
- [ ] Search for `setMediaItems` and `loadMedia`. Both must fire after Cast session connects.
- [ ] Search for `CastRelayServer` HTTP responses. Look for 4xx/5xx codes.
- [ ] Open `dist/logs/cast-bug-zfold-receiver-$(date).log`.
- [ ] Search for `LOAD_FAILED`, `MEDIA_NOT_LOADED`, `LOAD_CANCELLED`, `INVALID_PARAM`.
- [ ] Match sender-side log timestamps to receiver-side error timestamps.
- [ ] Identify root cause from evidence.

#### Task 11.5: Implement fix

- [ ] Based on root cause from Task 11.4, write the smallest fix that addresses it.
- [ ] Use `superpowers:test-driven-development`: write a unit/instrumented test that reproduces the bug; fix code; test passes; regression test added.
- [ ] If H1: `CastPlayer.setMediaItems(queue, startIndex, startPositionMs)` must mirror the local queue at session takeover.
- [ ] If H2: print phone IP at app start; document for user; add a "Cast not working? Tap to check WiFi" diagnostic banner.
- [ ] If H3: build a MIME→cast-content-type map and verify against the Cast Default Receiver supported types matrix (MP4 + WebM + MP3 + AAC + FLAC + Opus per earlier Cast work).
- [ ] If H4: refresh Drive OAuth token before opening Cast session; relay rebuilds with the fresh token.
- [ ] If H5: harden `MediaItemConverter` to return a non-null `MediaInfo` with sensible defaults; fail loudly in logcat if conversion fails.
- [ ] If H6: the UI binding in `PlayerScreen.kt` reads `viewModel.uiState` which combines local `playerState`. When `CastPlayer` is active, `uiState.title/artist/artwork` should source from `castPlayer.currentMediaItem.mediaMetadata`. Fix the combine block in `PlayerViewModel.uiState`.

#### Task 11.6: Verify fix on emulator

- [ ] Install Cast Debug Logger emulator config (or use Chromecast device on same WiFi as emulator).
- [ ] Repeat Task 11.2 on emulator. Same logs captured.
- [ ] Confirm: audio plays, UI shows track metadata correctly while casting.

#### Task 11.7: Verify fix on Z Fold 6

- [ ] Repeat Task 11.2 on Z Fold 6 with the fix. Same logs captured.
- [ ] Confirm: audio plays, UI shows track metadata correctly while casting.
- [ ] Test all four content types: local MP3, local MP4 video, Drive MP3, Drive MP4 video.
- [ ] Test transitions: cast on → off → on. No regressions.

#### Task 11.8: Test edge cases

- [ ] Cast a non-castable video (MKV) — verify Cast button is greyed (per existing whitelist) and tap is no-op.
- [ ] Cast → switch tracks via Next button — verify next track plays on receiver.
- [ ] Cast → pause/play from notification — verify works.
- [ ] Cast → disconnect device manually from Google Home → verify our app falls back to local player smoothly.
- [ ] Cast → kill our app process via `am kill` → verify receiver stops cleanly.
- [ ] Cast → swipe app from recents → verify receiver stops.

#### Task 11.9: Final regression

- [ ] Run §I.2 phase regression suite.
- [ ] Push to GitHub. APK to Z Fold 6.
- [ ] Document fix in `dist/logs/cast-bug-fix-summary.md` with: root cause, evidence (logcat lines), code change, test results.

---

## Section K — Follow-up questions (answer before Phase 1 starts)

I need explicit answers on these before Task 1.4 (icon component) and Task 1.7 (icon wiring) can begin.

### Q1. Info icon shape — LOCKED rounded-square box (24 dp blue, white "i", 6 dp corner radius).

### Q2. Video-controls gradient — LOCKED Option A

User picked **Option A**: gradient hides together with the controls when auto-hide fires (current behaviour preserved). User clarified their concern was about the new info-icon NOT breaking the existing gradient — which I confirmed it won't (the icon sits inside the same `AnimatedVisibility` layer as scrim+controls; render order untouched).

Implementation note: in Phase 1 Task 1.7, place `InfoIcon` inside `OverlayContent` (PlayerScreen.kt:393-410) at top-right. No scrim refactor.

### Q3. Settings tab section ordering — LOCKED "fine"

User accepted the proposed 15-section order verbatim:

1. Display
2. Auto-hide controls
3. Playback
4. Audio effects
5. Crossfade
6. Video
7. Subtitles
8. Library
9. Cloud
10. Bluetooth Car Controls
11. Alarms
12. External integrations
13. Stats
14. About + Diagnostics
15. Crash diagnostics

This is what `SettingsScreen.kt` will render top-to-bottom in Phase 2.

---

### Q3 (original prompt — kept for transcript trace):

You earlier asked for the Settings tab to be reorganised "into something sensible and easy to read and follow, perhaps with sub sections." I picked the order below.

```
Settings tab — top to bottom

 1. Display                     theme, cover-art Fit/Fill
 2. Auto-hide controls          6 timer settings (video / audio-folded / audio-unfolded / 3 sub-popups)
 3. Playback                    audio focus, plug-in resume, BT resume, gapless, reverse, pitch, volume boost, audio delay
 4. Audio effects               reverb, stereo flip, mono mix, multi-channel passthrough, ReplayGain toggle
 5. Crossfade                   master + 9 toggles
 6. Video                       mirror H/V, B&W, sepia, invert, rotation, subtitles auto-fetch
 7. Subtitles                   format, delay (global config separate from auto-fetch)
 8. Library                     deep scan, hidden files, ReplayGain scan, online metadata, auto-refresh
 9. Cloud                       Drive folders, Spotify message prefs, offline storage limit
10. Bluetooth Car Controls      button mappings, skip seconds
11. Alarms                      list + add
12. External integrations       Tasker / Intent toggles
13. Stats                       listening dashboard
14. About + Diagnostics         version, privacy policy, deep-scan first-run prompt
15. Crash diagnostics           share crash logs
```

**Logic of the ordering:**
- Display first — most-used quick-tweaks at the top.
- Auto-hide next — closely related (display behaviour).
- Playback → Audio effects → Crossfade → Video → Subtitles — playback grouped, increasingly specialised.
- Library → Cloud — content-source config.
- BT Car Controls → Alarms — peripheral / scheduled.
- External / Stats / About → bottom (power-user + housekeeping).

**You can:**
- Move sections up / down — give me the new order.
- Merge — e.g. Subtitles into Video, Cloud's Drive folders into Library.
- Split — e.g. Audio effects closer to EQ-related stuff.
- Or say "fine".

**LOCKED:** awaiting user confirmation or amendments.

### Q4. Frame-step buttons hidden on audio mode — LOCKED yes. Both IconButton sets at PlayerScreen.kt:486+509 and :682+705 wrapped with `if (uiState.isVideoContent && !uiState.isCasting) { ... }`.

### Q6. Alarm volume direction — LOCKED Option A + Option B (both)

User picked BOTH:
- **A retained:** start→end ramp covers both directions (start < end → rises; start > end → falls). Used for the wake-up ramp.
- **B added:** separate "wind-down" feature on top. After the ramp completes, alarm rings at the end-volume for `holdDurationMinutes`, THEN auto-fades over `windDownDurationSeconds` to silence. Gives up if user doesn't snooze or stop.

§C12 audio block expanded:
- Hold duration after ramp: 1 / 5 / 10 / 15 / 30 / 60 / "indefinite" minutes (default 30 min).
- Wind-down fade: 0 / 30 / 60 / 120 / 300 s (default 60 s; 0 = abrupt cut at hold-end).

UX detail: on the alarm screen, a small text shows "Auto-stops in 28 min" / "Winding down…" so user knows the alarm is timing out.

### Q7. Info-icon groupings — LOCKED Option B (logical groups)

Each tab has multiple expandable sections, each section being a logical group of related features. Default state of each section: collapsed (chevron pointing right). Tap a section header to expand. One section open at a time? — yes, accordion behaviour (tapping a new section auto-collapses the previous), unless user holds shift / multi-tap (no — Compose `ModalBottomSheet` can't intercept that on touch). Default = accordion = one open at a time.

Drafted groupings below — `InfoSheetData(tab, sections=List<InfoSection>)` for each tab. **User to confirm groupings or amend before Phase 1 Task 1.6 (`InfoContent.kt`).**

#### Player tab — 5 groups

1. **Display & sliders** (3 bullets)
   - Now Playing
   - Track slider (top)
   - Full slider (below)
2. **Navigation** (4 bullets)
   - Skip ±5/10/15/20/30
   - Previous / Next
   - A-B Loop
   - Frame step ±
3. **Time + position** (3 bullets)
   - Speed
   - Sleep timer
   - Bookmark
4. **Effects** (2 bullets)
   - Audio effects
   - Crossfade
5. **Output** (2 bullets)
   - Bluetooth button
   - Cast button

#### Library tab — 3 groups

1. **Browse** (4 bullets)
   - Audio / Video toggle
   - Search
   - Sort menu
   - Refresh icon
2. **Favourites** (1 bullet)
   - Star (favourite)
3. **Actions on a row** (3 bullets)
   - Long press menu
   - Hidden files
   - Multi-select

#### Last Played tab — 3 groups

1. **Lists** (2 bullets)
   - Recents
   - Pinned
2. **Actions** (3 bullets)
   - Resume
   - Bookmarks within Recents/Pinned
   - Reorder
3. **Behaviour** (2 bullets)
   - Auto-mirror
   - Pin caps

#### Cloud tab — 4 groups

1. **Sign-in & setup** (3 bullets)
   - Drive
   - Spotify
   - Drive folder picker first time
2. **Spotify-specific** (1 bullet)
   - Spotify Connect device
3. **Discovery** (2 bullets)
   - Favourites
   - Search
4. **Content features** (3 bullets)
   - Subtitles auto-fetch
   - Podcasts
   - Offline copy

#### Equalizer tab — 3 groups

1. **Presets** (2 bullets)
   - Preset menu
   - Save / Delete
2. **Adjust** (3 bullets)
   - Frequency curve
   - Band sliders
   - Reset all
3. **Behaviour** (3 bullets)
   - Headphone-aware EQ
   - Disabled while casting
   - Per-track override

**LOCKED unless user amends.** If user wants different grouping, edit and respond.

### Q5. Spotify Connect test scope — LOCKED Option A

User picked Option A: lightweight Spotify check per phase + full 10-step E2E in Phase 10 only.

Phase regression suite (§I.2) keeps the lightweight Spotify check ("sign in, play a track, verify mirror reaches Player tab"). §E full E2E runs only in Phase 10.

---

## Section L — Self-review against original spec

(Self-check by me to confirm spec coverage; not a question for you.)

| Original ask | Section in plan | Covered? |
|---|---|---|
| Bookmark explanation via info icon | A2 Player + Last Played | ✅ |
| Last Played explanation via info icon | A2 Last Played | ✅ |
| Info icon every tab, expandable per group | A1, A2, J Phase 1 | ✅ |
| Layman language, concise short sentences | A2 (verbatim user-approved) | ✅ |
| Evidence-based, no guesswork | Header, I.4, J | ✅ |
| Crossfade with sub-options | B2 (9 toggles) | ✅ |
| Differs per file/source/media type | B4 matrix | ✅ |
| Greyed out / disabled appropriately | B4 + B5 + I.2 regression | ✅ |
| Spotify Connect explanation + on-device test | A2 Cloud + E + Phase 10 | ✅ |
| Deep scan default question + first-run prompt | F | ✅ |
| 20+ power-user features | C (19 selected by user from 25-list) | ✅ (per user's selection) |
| Bookmark mirror works in ALL scenarios | A3 | ✅ |
| Info text for OTHER tabs | A2 (Library, Last Played, Cloud, EQ) | ✅ |
| Frame step working | A4 (confirmed wired, polish only) | ✅ |
| Bluetooth = device connect not car-control | A2 + A5 | ✅ |
| Settings: brief explanation under each setting | D + Phase 2 task pattern | ✅ |
| Crossfade = own button under audio effects | B1 | ✅ |
| 1-liner explanations under each toggle | B2 | ✅ |
| Auto-revert when source changes | B5 | ✅ |
| Power-user 1, 2 sub of settings | C1 (tile not Settings sub), C2 | ⚠ Note: C1 = Quick Settings tile (system) + lockscreen controls; the Settings sub-page is C2 only. Confirm if intended. |
| Power-user 3 toggles + explanations under settings | C3 | ✅ |
| 6, plenty of options | C6 | ✅ |
| 7, suggest how + where | C7 (long-press → popup) | ✅ |
| 9 subtitles in video settings + extras | C9 + D6 | ✅ |
| 10 in cloud section | C10 | ✅ |
| 11 sleep timer extensions | C11 | ✅ |
| 12 wake-up alarm placement | C12 + D11 | ✅ |
| 13 under EQ tab | C13 | ✅ |
| 14 settings with sensible defaults | C14 | ✅ |
| 16 battery/memory drain explained | C16 | ✅ |
| 17 toggle in settings | C17 + D8 | ✅ |
| 18 toggle in settings | C18 + D8 | ✅ |
| 20 widget all sizes/orientations/folded | C20 | ✅ |
| 22 toggle in settings | C22 + D3 | ✅ |
| 25 long-press menu confirm | C25 | ✅ |
| Auto-hide settings (video, audio-folded, audio-unfolded) | D2 | ✅ |
| Sub-popup auto-hide times | D2 | ✅ |
| Reorder all settings into sub-sections | D | ✅ |
| Initial defaults from current settings | D2 (defaults locked) | ✅ |
| Gradient invariant on video controls | A1 + Q2 | ⚠ PENDING USER ANSWER |
| True 2-player crossfade explanation + decision | B3 | ✅ |
| Auto-DJ default question | (dropped) | ✅ |
| Smart-playlist cap | C6 | ✅ |
| Per-file overrides starred/pinned only | C7 | ✅ |
| Multi-select explanation + build | C26 | ✅ |
| Hidden files explanation + build | C27 | ✅ |
| Drive offline copy explanation + Spotify legality | C28 | ✅ |
| Test on both emulator + phone | I + per-task | ✅ |
| Vigorous tests per implementation | I + Phase per-feature smoke | ✅ |
| Granular tasks | J Phase 1 step-level + Phase 2-9 task-level | ✅ |
| Inline execution, no subagents | Header | ✅ |
| Use superpowers + context7 | Header + Phase pre-flight queries | ✅ |
| Forbid guesswork | Header | ✅ |

Self-check finds 2 outstanding items: Q1 (icon shape) and Q2 (gradient independence). Plan can't proceed past Task 1.4 without these.
