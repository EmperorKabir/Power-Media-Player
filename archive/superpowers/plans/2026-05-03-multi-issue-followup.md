# Multi-Issue Follow-up Plan (2026-05-03)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline). Steps use checkbox tracking. ZERO IMPLEMENTATION until user answers Open Questions and approves.

**Goal:** Address 20 user-reported issues (A–T) — UI polish, Spotify deepening, video stutter root-cause, EQ/sleep verification, PiP, search, power-user features.

**Architecture:** Each issue is independently addressable; sequential execution. New abstractions kept minimal. Drive metadata pipeline is OFF-LIMITS unless surgical.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 1.6 ExoPlayer, Hilt, Room, AppAuth, Spotify Web API, LRCLib, OkHttp, Coil 3.

**Process bar:** every code change → adb-driven verification → commit. Per-issue PMP_DIAG diagnostic logs added before fix attempts.

---

## Issue index (A → T)

| Letter | Topic | Effort |
|---|---|---|
| A | Speed icon greyed for Spotify | XS |
| B | Reset Speed button (1×) | XS |
| C | Synced lyrics auto-scroll grace +1 s | S |
| D | Spotify list categorisation | M |
| E | Drive favourite tracks + Spotify favourites | M |
| F | Dynamic Prev/Next labels | S |
| G | Video backward-stutter root-cause | L |
| H | Spotify auth persistence on app restart | S |
| I | EQ verification | M |
| J | Sleep timer verification + docs | XS |
| K | Skip buttons closer | XS |
| L | Skip-button label font 25 % larger | XS |
| M | Power-user features | L (offer to user, implement chosen subset) |
| N | Spotify mirror leak when starting local video | M |
| O | Notification panel — full controls / artwork | M |
| P | Software/Hardware decoding toggle verification | S |
| Q | Scrim more opaque | XS |
| R | Tap-to-hide controls fix + 4 s auto-hide | S |
| S | Picture-in-picture (video + audio) | M |
| T | Cloud search (Drive + Spotify) | M |

---

## File-impact map

- `PlayerScreen.kt` — A, B, C, F, K, L, Q, R
- `SecondaryControls.kt` — A, B, K, L
- `PlaybackControls.kt` — F, K, L
- `PlayerViewModel.kt` — F, N
- `PlayerUiState.kt` — F (mediaKind enum), N
- `PlaybackConnection.kt` — F (sniff content type), N (transition events)
- `PlaybackService.kt` — G (LoadControl + ExoPlayer EventLogger), I (audio session ID), O (notification customisation), P (renderer factory wiring), S (PiP enter/exit)
- `MainActivity.kt` — S (PiP lifecycle, picture-in-picture-params)
- `AndroidManifest.xml` — S (`android:supportsPictureInPicture`)
- `SpotifyProvider.kt` — D, E, H, T
- `CloudViewModel.kt` — D, E, H, N, T
- `CloudBrowserScreen.kt` — D, E, T
- `SettingsDataStore.kt` — E (favourites: tracks/albums/podcasts)
- `SettingsScreen.kt` — P (toggle verification UI)
- `EqualizerViewModel.kt` + `EqualizerScreen.kt` — I
- `LibraryScreen.kt` — referenced for search UI parity (T)

---

## Tasks

### Task A1: Grey out the speed running-man icon when speed control is disabled

- **Files:** `app/src/main/java/com/powermediaplayer/ui/player/components/SecondaryControls.kt`
- [ ] **Step 1:** In `PreparedSpeedComponent`, change the `Icons.Filled.DirectionsRun` `tint = TextSecondary` to `tint = if (enabled) TextSecondary else DisabledGrey`.
- [ ] **Step 2:** Build: `./gradlew.bat assembleDebug`. Expect: BUILD SUCCESSFUL.
- [ ] **Step 3:** Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **Step 4:** Verify: launch → Cloud → Spotify → tap track → Player tab → screenshot. Expect: running-man icon visibly grey.
- [ ] **Step 5:** Commit `fix(ui): grey speed icon when disabled (Spotify mirror)`.
- **Rollback:** revert tint conditional.

### Task B1: Add "Reset Speed" button right of speed dropdown

- **Files:** `app/src/main/java/com/powermediaplayer/ui/player/components/SecondaryControls.kt`
- [ ] **Step 1:** Inside `PreparedSpeedComponent`, after the `ExposedDropdownMenuBox`, add a `IconButton` with `Icons.Filled.RestartAlt` (or `Icons.Filled.Refresh`) that calls `onSpeedChange(1.0f)`. Pass `enabled = enabled` to grey it for Spotify.
- [ ] **Step 2:** Build + install + screenshot of player.
- [ ] **Step 3:** Verify: tap reset → speed snaps to 1×; greyed when Spotify active.
- [ ] **Step 4:** Commit `feat(ui): reset-speed button (1×)`.
- **Rollback:** remove the IconButton.

### Task C1: Lyrics manual-scroll grace period +1 s

- **Files:** `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt` (`SyncedLyricsPanel`)
- [ ] **Step 1:** Track `userScrollingUntilMs: Long` state. On `LazyListState.isScrollInProgress` change → set to `now + 1500` (was 500 implicit). Suppress `animateScrollToItem` when `now < userScrollingUntilMs`.
- [ ] **Step 2:** Build + install.
- [ ] **Step 3:** Verify: scroll lyric panel manually → wait → at ~1.5 s after release, auto-recentre fires.
- [ ] **Step 4:** Commit `feat(lyrics): +1s manual-scroll grace before auto-recentre`.

### Task D0: Document what the current Spotify list shows (PRE-CODE EXPLAINER)

- **Files:** none (write into commit message + reply to user).
- Current behaviour:
  - `SpotifyProvider.listFiles` calls `/v1/me/library?type=track,album,playlist` (a 2024 generic endpoint).
  - On 405 falls back to: `/v1/me/tracks?limit=50` (saved tracks), `/v1/me/albums?limit=50` (saved albums), `/v1/me/playlists?limit=50` (saved + followed playlists).
  - Each result is mapped to a `CloudMediaItem`. Tracks → `isFolder=false`. Albums + playlists → `isFolder=true` but tap shows "browsing not implemented" error.
  - The "random tracks" the user sees are the user's saved tracks (the heart-icon "Liked Songs" list in Spotify).
- **Action:** include this in the user-facing reply.

### Task D1: Fetch all Spotify section endpoints in parallel

- **Files:** `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt`
- Sections to support (each is a Spotify Web API endpoint):
  - `Liked Songs` → `/v1/me/tracks` (existing)
  - `Saved Albums` → `/v1/me/albums`
  - `Saved Playlists` → `/v1/me/playlists`
  - `Recently Played` → `/v1/me/player/recently-played`
  - `Top Tracks` → `/v1/me/top/tracks`
  - `Top Artists` → `/v1/me/top/artists`
  - `New Releases` → `/v1/browse/new-releases`
  - `Featured Playlists` → `/v1/browse/featured-playlists`
  - `Saved Episodes` (podcasts) → `/v1/me/episodes`
  - `Saved Shows` (podcast subscriptions) → `/v1/me/shows`
  - **NOT supported by API**: "Made For You" / "Discover Weekly" / "Daily Mix" / "Top Mixes" — Spotify removed these endpoints; only accessible via Spotify-owned clients.
- [ ] **Step 1:** Define `enum class SpotifySection { LIKED_SONGS, SAVED_ALBUMS, SAVED_PLAYLISTS, RECENT, TOP_TRACKS, TOP_ARTISTS, NEW_RELEASES, FEATURED_PLAYLISTS, SAVED_EPISODES, SAVED_SHOWS, FAVOURITES }`.
- [ ] **Step 2:** Add `suspend fun listSection(section: SpotifySection): Result<List<CloudMediaItem>>` that hits the correct endpoint.
- [ ] **Step 3:** Diag log per section call: `PMP_DIAG: Spotify.listSection $section http=$code n=$count`.
- [ ] **Step 4:** Unit-test JSON parsing for each endpoint shape (each has a different envelope: `items[]` vs `tracks.items[]` vs nested).
- **Verification:** `adb logcat -s PMP_DIAG:I` shows each section call returning ≥0 items.
- **Rollback:** keep old `listFiles` and ignore `listSection`.

### Task D2: Spotify section navigation UI

- **Files:** `app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt`, `app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt`
- [ ] **Step 1:** `CloudUiState` gains `spotifySection: SpotifySection?` and `spotifySectionsAvailable: List<SpotifySection>`.
- [ ] **Step 2:** When user enters Spotify with no section selected, show a vertical list of section cards (Liked Songs / Saved Albums / …). Tapping a card calls `viewModel.openSpotifySection(section)`.
- [ ] **Step 3:** Section list view shows `items` from that section. Existing back arrow returns to section picker.
- [ ] **Step 4:** Verify: launch → Cloud → Spotify → see ten section cards → tap "Recently Played" → list populates.
- [ ] **Step 5:** Commit `feat(spotify): section picker + 10 endpoints`.

### Task E1: SettingsDataStore favourite-tracks (Drive + Spotify) + favourite-albums + favourite-podcasts

- **Files:** `app/src/main/java/com/powermediaplayer/data/preferences/SettingsDataStore.kt`
- [ ] **Step 1:** Add 4 new `stringSetPreferencesKey`:
  - `DRIVE_FAVOURITE_TRACKS` → `Set<String>` of `"id|name"`
  - `SPOTIFY_FAVOURITE_TRACKS` → `Set<String>` of `"trackUri|name"`
  - `SPOTIFY_FAVOURITE_ALBUMS` → `Set<String>` of `"albumUri|name"`
  - `SPOTIFY_FAVOURITE_PODCASTS` → `Set<String>` of `"showUri|name"`
- [ ] **Step 2:** Mirror the existing `driveFavouriteFolders` pattern: Flow exposing `List<…>`, plus toggle method.
- [ ] **Step 3:** Add `data class CloudFavourite(val id: String, val name: String, val kind: CloudFavouriteKind)` and `enum class CloudFavouriteKind { DRIVE_FOLDER, DRIVE_TRACK, SPOTIFY_TRACK, SPOTIFY_ALBUM, SPOTIFY_PODCAST }`.

### Task E2: Wire favourite stars onto Spotify and Drive track rows

- **Files:** `CloudViewModel.kt`, `CloudBrowserScreen.kt`
- [ ] **Step 1:** Extend `CloudItemRow` to accept `canFavourite` for tracks too (currently only folders).
- [ ] **Step 2:** Detect kind from `item.sourceProvider` + `item.mimeType`.
- [ ] **Step 3:** Toggle calls into the new SettingsDataStore methods.
- [ ] **Step 4:** Show "Favourites" section at top of each provider's root view (analogous to Drive folder favourites).
- [ ] **Step 5:** Commit.

### Task F1: Content-kind enum + propagation

- **Files:** `PlayerUiState.kt`, `PlaybackConnection.kt`, `PlayerViewModel.kt`, `LibraryViewModel.kt`
- Sniffing rules:
  - Spotify mirror playing → `MediaKind.SPOTIFY_TRACK`
  - Drive item with mimeType `application/vnd.spotify-podcast`-ish or extension `m4b` → `AUDIOBOOK`
  - File extension `m4a/mp3/flac/ogg` and chapters present → `AUDIOBOOK`
  - File extension audio without chapters → `MUSIC`
  - File extension video → `VIDEO`
  - Multi-track queue from same album → `ALBUM`
  - Folder-mode aggregate → `AUDIOBOOK`
- [ ] **Step 1:** Add `enum class MediaKind { MUSIC, ALBUM, AUDIOBOOK, PODCAST, VIDEO, SPOTIFY_TRACK, UNKNOWN }`.
- [ ] **Step 2:** Add `mediaKind: MediaKind = UNKNOWN` to `PlayerUiState`.
- [ ] **Step 3:** Compute in `PlayerViewModel.mapToUiState` from existing `playerState` + folder/local hints.

### Task F2: Dynamic labels in PlaybackControls

- **Files:** `PlaybackControls.kt`
- [ ] **Step 1:** `PrevFileButton` / `NextFileButton` / `PrevChapterTrackButton` / `NextChapterTrackButton` accept `kind: MediaKind`.
- [ ] **Step 2:** Label mapping:
  - `MUSIC` → File="Track", Chapter hidden
  - `ALBUM` → File="Album", Chapter="Track"
  - `AUDIOBOOK` → File="Book", Chapter="Chapter"
  - `PODCAST` → File="Show", Chapter="Episode"
  - `SPOTIFY_TRACK` → File="Track", Chapter hidden
  - `VIDEO` → File="Video", Chapter="Chapter"
  - `UNKNOWN` → fallback to current "File"/"Chapter"
- [ ] **Step 3:** Verify: each kind renders the right label.
- [ ] **Step 4:** Commit `feat(controls): dynamic prev/next labels by media kind`.

### Task G1: Backward-seek root-cause investigation (context7-driven)

- **Files:** none yet (research first).
- [ ] **Step 1:** `context7-mcp` lookups:
  - "media3 1.6 SeekParameters performance backward seek H.264 HEVC"
  - "ExoPlayer LoadControl bufferForPlaybackAfterRebufferMs seek"
  - "MediaCodec asynchronous mode video flush seek"
  - "androidx media3 ExoPlayerImpl seekTo positionUs internal flush"
- [ ] **Step 2:** Add `EventLogger` to ExoPlayer in PlaybackService and capture `seekStarted` / `videoInputFormatChanged` / `videoCodecError` / `videoFrameProcessingOffset` / `loadCanceled` / `loadCompleted` for one back-seek + one forward-seek of equal magnitude. Compare per-event timing.
- [ ] **Step 3:** Capture systrace via `adb shell perfetto -c -` for 10 s containing two back-seeks. Tag categories: `view sched binder_driver wm gfx video`.
- [ ] **Step 4:** From the trace, identify which thread is busy during the perceived stutter window. Hypotheses to confirm/refute:
  - (a) `c2.qti.avc.decoder` is decoding from previous keyframe → unavoidable, only mitigation is shorter GOPs in source content.
  - (b) `MediaCodec` flush is taking > 100 ms — if so, switch to async mode via `setCallback`.
  - (c) `LoadControl` is starving the decoder for samples after seek — if so, increase `targetBufferBytes`.
  - (d) `SurfaceView` is releasing/reattaching — if so, set `setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)`.
- [ ] **Step 5:** Apply the fix indicated by the trace data; commit only if measurable improvement (frame-deadline-missed reduction visible in `dumpsys gfxinfo`).

### Task H1: Spotify token persistence audit

- **Files:** `app/src/main/java/com/powermediaplayer/cloud/SpotifyTokenStore.kt`, `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt`
- [ ] **Step 1:** Verify `SpotifyTokenStore` uses DataStore or EncryptedSharedPreferences (not in-memory).
- [ ] **Step 2:** Verify `_isLoggedIn` is set to `true` on cold-start when `tokenStore.read()` returns non-null.
- [ ] **Step 3:** If not, fix init logic: call `currentAccessToken()` on app start; if returns non-null → `_isLoggedIn.value = true`.
- [ ] **Step 4:** Verify: kill app → relaunch → Cloud → Spotify shows signed-in state immediately, no consent screen.

### Task I1: EQ verification — confirm AudioEffect attached to player audio session

- **Files:** `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt`, `app/src/main/java/com/powermediaplayer/ui/equalizer/EqualizerViewModel.kt`
- [ ] **Step 1:** Read `EqualizerViewModel`. Identify how it acquires the audio session ID. Likely candidates:
  - `player.audioSessionId` exposed via `playbackConnection.getAudioSessionId()`
  - Or globally via `AudioManager.generateAudioSessionId()` — wrong if EQ isn't tied to ExoPlayer's session.
- [ ] **Step 2:** Add diag log: `PMP_DIAG: EQ session=$sessionId enabled=$enabled bandLevels=$levels`.
- [ ] **Step 3:** Capture log while changing presets — confirm sessionId matches `player.audioSessionId`.
- [ ] **Step 4:** If mismatched, wire EQ to use ExoPlayer's session via `ExoPlayer.Listener.onAudioSessionIdChanged`.

### Task J1: Sleep timer documentation

- **Files:** `docs/sleep-timer.md` (new), and inline KDoc in `PlayerViewModel.startSleepTimer`.
- Behaviour to document:
  - User selects N minutes from preset (15/30/45/60/90/120) in the dialog.
  - `_sleepTimerRemainingMs` set to `N * 60_000`.
  - Coroutine ticks every 1 s, decrementing.
  - At expiry: `playbackConnection.pause()` is called; `_sleepTimerRemainingMs.value = 0`.
  - User can cancel anytime via "Cancel Timer" → `cancelSleepTimer()` cancels the Job.
  - Timer state persists only for the lifetime of the ViewModel; killing the app cancels the timer.
- [ ] **Step 1:** Add diag log on tick events at 60 s / 30 s / 10 s remaining.
- [ ] **Step 2:** Verify: set 1-min timer → after 60 s playback pauses → log shows expiry event.

### Task K1: Skip buttons closer

- **Files:** `app/src/main/java/com/powermediaplayer/ui/player/components/PlaybackControls.kt`
- [ ] **Step 1:** In `Row` containing the skip buttons, change `Spacer(modifier = Modifier.width(20.dp))` (centre gap between back-5 and forward-5) to `Modifier.width(8.dp)`.
- [ ] **Step 2:** Verify via screenshot.
- [ ] **Step 3:** Commit `style(controls): skip buttons closer (20→8 dp gap)`.

### Task L1: Skip-button label font 25 % larger

- **Files:** `app/src/main/java/com/powermediaplayer/ui/player/components/PlaybackControls.kt`
- [ ] **Step 1:** `numStyle.fontSize` 15.sp → **19.sp**, `sStyle.fontSize` 11.sp → **14.sp**. Stroke widths bump 5f→6f and 4f→5f.
- [ ] **Step 2:** Verify via screenshot — confirm digits visibly larger, no overlap with arrow icon.
- [ ] **Step 3:** Commit.

### Task M1: Power-user features menu — present options to user before implementing any

Suggested catalogue (each independently feasible unless noted):

- Local files only:
  - **Reverse playback** (audio + video) — needs custom `MediaSource` reading samples backwards; possible but heavy. Greyed for cloud/Spotify.
  - **Video mirror (horizontal flip)** — `Matrix.setScale(-1f,1f)` on SurfaceView via custom GLSurfaceView. Cloud videos: works (it's a render-time effect).
  - **Video upside-down (vertical flip)** — same as above.
  - **Video rotate 90/270** — needs render rotation.
  - **B/W video** — colour-matrix shader on a GLSurfaceView replacement for SurfaceView.
  - **Pitch-preserving slow/fast** — already supported via `PlaybackParameters(speed, pitch)`.
  - **Independent pitch shift** — separate slider for pitch.
  - **A-B loop** — pick start + end timestamps, loop between.
  - **Subtitle delay slider** — ±5 s.
  - **Audio delay slider** — sync against video.
  - **Per-app volume gain** (boost beyond 100 %) via `LoudnessEnhancer`.
  - **Bookmarks** within long files.
  - **Crossfade between tracks**.
  - **Gapless playback** — already on for ExoPlayer playlists; expose toggle.
  - **Per-track replay-gain normalisation**.
  - **Sleep at end of chapter / track** option (vs absolute time).
  - **Wake-up alarm timer** — start playing X file at HH:MM.
  - **Cast to chromecast group** — already partially wired.
  - **Lock screen with custom controls** (foreground custom layout).
  - **Resume on Bluetooth reconnect** option.
  - **Frame-by-frame stepping** for video pause.
  - **Screenshot of current frame** for video.
- Cloud only:
  - **Pre-fetch next track's stream** for seamless transitions.

- [ ] **Step 1:** USER decision — pick a subset to implement now (Open Question 1).

### Task N1: Stop Spotify mirror when local playback starts

- **Files:** `LibraryViewModel.kt` (`playFiles`/`playSingle`/`playFolder`), `PlaybackConnection.kt` (`setMediaItems`)
- [ ] **Step 1:** Inject `SpotifyProvider` into `LibraryViewModel`.
- [ ] **Step 2:** Before calling `playbackConnection.setMediaItems(...)`, call `spotifyProvider.stopPlaybackPolling()` so the mirror state clears and the local player state takes over.
- [ ] **Step 3:** Optionally call Spotify `pause` endpoint so audio doesn't keep playing alongside the local video.
- [ ] **Step 4:** Verify: Spotify track playing → tap a video → Spotify pauses → player UI shows video metadata (no Spotify residue).

### Task O1: Notification — push more controls + artwork via Media3 MediaStyle

- **Files:** `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt`
- [ ] **Step 1:** `context7-mcp` lookup: "media3 MediaSession custom command buttons Notification compact actions 2026".
- [ ] **Step 2:** Use `MediaSession.Builder.setCustomLayout(listOf(commandButton30Back, commandButton30Forward, ...))` so these surface in the notification.
- [ ] **Step 3:** Provide a high-res artwork bitmap via `MediaMetadata.artworkData` for the notification thumbnail.
- [ ] **Step 4:** Pull-down screenshot, expand, confirm new buttons + artwork visible.

### Task P1: Software/Hardware decoding toggle verification

- **Files:** `PlaybackService.kt`, `SettingsViewModel.kt`
- [ ] **Step 1:** Verify `useSoftwareDecoding` flag is read by PlaybackService.
- [ ] **Step 2:** Currently `DefaultRenderersFactory.setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)` — confirm this honors the flag.
- [ ] **Step 3:** If not, conditionally use `EXTENSION_RENDERER_MODE_OFF` (HW only) vs `EXTENSION_RENDERER_MODE_PREFER` (SW preferred when present).
- [ ] **Step 4:** Diag log on init: `PMP_DIAG: PlaybackService renderers extensionMode=$mode swDecodingPref=$flag`.
- [ ] **Step 5:** Verify by toggling Setting → relaunching → log shows new value.

### Task Q1: Scrim more opaque

- **Files:** `PlayerScreen.kt`
- [ ] **Step 1:** Bump video gradient stops:
  - `Transparent → Transparent → OledBlack(0.55) → OledBlack(0.95) → OledBlack(1.0)`
- [ ] **Step 2:** Screenshot to verify.
- [ ] **Step 3:** Commit.

### Task R1: Tap-to-hide controls + 4 s auto-hide

- **Files:** `PlayerScreen.kt` (compact layout)
- [ ] **Step 1:** Re-enable the parent tap detector when controls are visible (currently disabled to fix skip-tap-eating). Reconciliation: use `pointerInput(Unit)` with `awaitEachGesture` that ONLY toggles if the down-event location is outside any IconButton bounding box. Simpler: detect tap on the SurfaceView area itself (not the OverlayContent column).
- [ ] **Step 2:** Place a transparent `Box` covering only the SurfaceView region (above the controls Column) that catches taps and toggles `controlsVisible`.
- [ ] **Step 3:** Auto-hide delay: `delay(32_000)` → `delay(4_000)`.
- [ ] **Step 4:** Verify via adb: tap on top half of video → controls hide; tap again → controls appear; wait 4 s → controls hide.

### Task S1: Picture-in-Picture for video (and audio)

- **Files:** `AndroidManifest.xml`, `MainActivity.kt`, `PlayerScreen.kt`
- [ ] **Step 1:** Manifest: add `android:supportsPictureInPicture="true"`, `android:resizeableActivity="true"`, `configChanges` add `screenLayout|smallestScreenSize`.
- [ ] **Step 2:** `context7-mcp` lookup: "Android Activity enterPictureInPictureMode aspectRatio sourceRectHint Compose 2026".
- [ ] **Step 3:** Override `onUserLeaveHint()` in MainActivity → if `playerActive && (isVideoContent || preferAudioPiP)` → call `enterPictureInPictureMode(PictureInPictureParams.Builder()...build())`.
- [ ] **Step 4:** PiP mode UI: show only video surface + minimal Pause/Play action via `setActions`.
- [ ] **Step 5:** Verify: play video → Home button → PiP appears.

### Task T1: Cloud search — Drive

- **Files:** `GoogleDriveProvider.kt` (read-only — only adding `searchFiles(query)`), `CloudViewModel.kt`, `CloudBrowserScreen.kt`
- [ ] **Step 1:** Add `suspend fun searchFiles(query: String): Result<List<CloudMediaItem>>` to GoogleDriveProvider — uses Drive `files.list` with `q="name contains '${escaped(query)}' and trashed=false"`.
- [ ] **Step 2:** Add a search TextField at top of CloudBrowserScreen identical in pattern to LibraryScreen's search.
- [ ] **Step 3:** Debounce keystrokes (300 ms) before firing the API call.
- [ ] **Step 4:** Verify.

### Task T2: Cloud search — Spotify

- **Files:** `SpotifyProvider.kt`, `CloudViewModel.kt`, `CloudBrowserScreen.kt`
- [ ] **Step 1:** Add `suspend fun searchAll(query: String): Result<List<CloudMediaItem>>` calling `/v1/search?q=&type=track,album,playlist,show,episode&limit=20`.
- [ ] **Step 2:** Same UI as Task T1.
- [ ] **Step 3:** Verify with adb screenshot.

---

## Open Questions (numbered — please answer before any implementation)

1. **Power-user features (Issue M):** which subset to implement now? Options listed in Task M1. Suggest priority pick: video flip H/V, B/W video, A-B loop, bookmarks, sleep-at-end-of-chapter, screenshot-current-frame.
2. **Spotify section ordering (Issue D):** which order should the section cards appear in? My default: Liked Songs, Recently Played, Saved Albums, Saved Playlists, Saved Episodes, Saved Shows, Top Tracks, Top Artists, New Releases, Featured Playlists.
3. **Reset speed icon (Issue B):** prefer `Icons.Filled.RestartAlt`, `Icons.Filled.Refresh`, or text "1×"?
4. **Lyrics scroll grace (Issue C):** confirm 1 s additional delay → 1500 ms total before re-centring is correct, or do you want longer (e.g. 3 s before re-centring)?
5. **Notification controls (Issue O):** which exact actions to surface in the compact notification? Android allows up to 3 in compact (always-visible) and 7 in expanded. Suggest compact = [skip-back-30, play/pause, skip-forward-30]; expanded adds [previous-track, next-track, scrub, sleep-timer].
6. **Power-user video flips (Issue M):** acceptable to replace SurfaceView with TextureView ONLY when an effect is active? TextureView has the perf cost we measured earlier (24 % janky on 4K). Or use a custom GLSurfaceView (more complex, keeps perf).
7. **Reverse playback (Issue M):** acceptable as audio-only initially? Reverse video adds significant complexity (needs sample re-ordering or render-time playback-rate -1 which most decoders refuse).
8. **Picture-in-picture audio (Issue S):** does audio PiP make sense to you? Default Android PiP requires a video surface; an audio-only PiP would need a fake "now-playing card" surface. Suggest: PiP for video only; audio uses the standard MediaSession notification.
9. **Search debounce (Issue T):** 300 ms acceptable, or want instant?
10. **Issue G stutter — final fallback:** if context7 + systrace prove the stutter is decoder-fundamental, are you OK with a UI mitigation (briefly fade the controls / show a "seeking…" indicator) instead of removing the technical freeze?

---

## context7-MCP / MCP-server suggestions

- **context7-mcp** (already available) — use for: media3 1.6 ExoPlayer + MediaSession customisation, MediaCodec async mode, PiP, Spotify Web API endpoint envelope shapes.
- Consider installing **android-docs-mcp** if available — direct Android SDK reference lookup.
- **No new MCPs strictly needed** — context7 covers Media3 and Spotify Web API documentation.

---

## Self-review (post-write)

- **Spec coverage:** A–T all have at least one task. M is intentionally an offer-list pending user pick.
- **Placeholders:** none. Each step has a concrete action + verification.
- **Type consistency:** `MediaKind`, `SpotifySection`, `CloudFavouriteKind`, `CloudFavourite` are defined exactly once.
- **Scope:** plan is large but each task is bounded; Issue G is research-first which avoids speculative code changes.
- **Ambiguity:** Issue M and Issue G have user-decision gates (Open Questions 1, 6, 7, 10).
