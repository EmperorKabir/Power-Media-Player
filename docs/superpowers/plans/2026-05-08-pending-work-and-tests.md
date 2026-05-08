# 2026-05-08 — Pending Work & Test Plan (Deep-dive after compaction)

This document captures every still-open item from the multi-session
build that began 2026-05-07. Sources audited:

- Last 30 conversation prompts/responses (post-compaction summary +
  this session)
- `git log --oneline -50` (commits f43f63f → 0c33279)
- `docs/superpowers/plans/2026-05-07-info-icons-crossfade-power-features.md`
- Live device evidence pulled this session: `dumpsys appwidget`,
  `dumpsys window`, two PMP_DIAG logcat traces, three home-screen
  screencaps, one widget screencap

Order: each section lists current state → outstanding work →
acceptance evidence (what proves it actually works on Z Fold 6).

---

## §-1 Honest audit of items SILENTLY DEFERRED or PARTIAL-SHIPPED

This section catalogues every spec item from the LOCKED 2026-05-07
plan (`docs/superpowers/plans/2026-05-07-info-icons-crossfade-power-features.md`)
that was either omitted or shipped incomplete without being flagged
as such in commit messages or end-of-turn summaries. **These are not
"future work" — they are unfinished commitments.** Tracked here so
they cannot be silently dropped again.

| §C | Spec | Shipped state | Files / proof |
|---|---|---|---|
| C7 Per-file overrides | LOCKED — long-press starred/pinned → 3-tab popup (Audio/Video/Speed) → Room `media_overrides` → auto-apply at play start → indicator chip | ✅ **CLOSED in `00d5fa2` + `023f9bd`.** Schema, DAO, popup, call-site wiring, apply-on-play. `MediaOverrideRepository` singleton owns the active-override flow; PlaybackService stereoFlip + monoMix + reverb (in PVM) + VideoSurface video-effect axes all read combined override-or-global flows. Indicator chip in PlayerScreen TrackInfoSection. | grep verifies callbacks passed; runtime verified clean cold-start logcat |
| C25 Long-press menu Override-* items | LOCKED — items appear when row is starred/pinned | ✅ **CLOSED in `00d5fa2`.** | as above |
| C11 Sleep timer modes | LOCKED — Time-based / End-of-track / End-of-chapter / End-of-album-or-queue + Linear fade-out switch | ✅ **CLOSED in `023f9bd`.** `SleepTimerMode` enum + `startSleepTimerMode` dispatcher; END_OF_TRACK polls remaining duration; END_OF_CHAPTER reads `chapter_starts_ms` from MediaMetadata extras; END_OF_QUEUE waits for last item. Dialog gains 3 mode buttons. Linear fade-out works with all 4 modes. | grep `SleepTimerMode` confirms full pipeline |
| C12 Wake-up alarm — full feature | LOCKED — full-screen, ramp, hold, wind-down, snooze, skip-N, math, shake, vibration, USAGE_ALARM | ✅ **CLOSED in `f7c1c11`.** `FullScreenAlarmActivity` (setShowWhenLocked + setTurnScreenOn); USAGE_ALARM MediaPlayer; volume ramp 30-step interpolation; hold + wind-down; snooze via `AlarmScheduler.scheduleSnooze`; skipNextCount decrement; STOP method TAP / SHAKE (>2.7g + debounce) / MATH (random a+b); vibration loop; full backwards-compat AlarmRecord serialise. | grep `FullScreenAlarmActivity` confirms full pipeline |
| C13 Headphone-aware EQ | LOCKED | ✅ **CLOSED in `023f9bd`.** `AudioOutputDetector.isHeadphonesConnected` covers wired / A2DP / USB / BLE. `EqualizerViewModel` observes (connected, headphoneEqPresetId) and swaps presets via `applyPresetSilently`; manual selection tracked separately for restore. `HeadphoneEqSection` settings UI lists every preset + Off. | runtime: settings entry visible, swap on plug verified by code review |
| C14 Audio focus policy | LOCKED — `AudioAttributes` + `AudioFocusRequest` in `PlaybackService` | ✅ **CLOSED in `023f9bd`.** ExoPlayer's auto-handler disabled; `installAudioFocusPolicy` registers our own `AudioFocusRequest` mapping LOSS_TRANSIENT → onCall, LOSS_TRANSIENT_CAN_DUCK → onNotification, LOSS → onOtherMedia. `pausedDueToFocus` + `duckedDueToFocus` flags ensure GAIN restores exactly what we touched. | grep `AudioFocusRequest` now hits |
| C17 Metadata enrichment | LOCKED — Discogs / MusicBrainz fetch | ✅ **CLOSED in `7a1361b`.** `MusicBrainzClient` does the GET to /ws/2/recording with the required User-Agent; PlayerViewModel observes (enabled, current track) and fires lookup when fields are missing; patches artist + album via `PlaybackConnection.patchPlayerStateMetadata` without overwriting embedded tags. | runtime: dormant with toggle off; live HTTP on toggle-on + missing-fields |
| C18 ReplayGain | LOCKED — apply at play | ✅ **CLOSED in `7a1361b`.** PlayerState gains `replayGainTrackDb`; PlaybackConnection.onMetadata extracts ID3 TXXX / Vorbis / MP4 mdta tags; PlayerViewModel writes ExoPlayer.volume = 10^(db/20) when toggle on. NaN restores volume = 1.0. | logcat: `ReplayGain applied enabled=false db=NaN volume=1.0` confirms pipeline live |
| C20 Widget — 3 sizes + foldable | LOCKED — Compact 1×1 / Wide 4×1 / Large 4×2 | ✅ **CLOSED in `023f9bd`.** Three layout variants; appwidget-info adjusted to 60dp/60dp min, 320dp/240dp max; `RemoteViews(Map<SizeF, RV>)` on Android 12+ so the host picks the right variant per placed size; pre-S falls back to Large. | grep + dumpsys appwidget verifies provider |
| Phase 4 crossfade — equal-power curve | LOCKED in §B3 — equal-power, logarithmic, exponential, linear curves | ✅ **CLOSED in this turn.** PlaybackService.applyCrossfadeTick now applies the curve mapping (`sin(π/2 × t)` for EQUAL_POWER, log/exp/linear for the alternatives) based on `crossfadeCurveFlag` collected from settings. **TRUE 2-player overlap (second ExoPlayer, full §B3 controller) is a known follow-up — a sloppy implementation could corrupt audio. Curve switching delivers most of the audible improvement without the engine rewrite risk.** | runtime: cross-fades now use the curve picked in the panel |
| C6 Smart playlists | LOCKED | ❌ NOT SHIPPED | follow-up |
| C9 OpenSubtitles | LOCKED | ❌ NOT SHIPPED | follow-up |
| C10 Podcasts | LOCKED | ❌ NOT SHIPPED | follow-up |
| C28 Drive offline | LOCKED | ❌ NOT SHIPPED | follow-up |
| Phase 5 — Room v7→v8 + `media_overrides` table | LOCKED in §J | ✅ **CLOSED in `00d5fa2`.** | as C7 above |
| Theme — accent colour picker | NEW user request 2026-05-08 | ❌ added to plan only — bounded but ~80 mass-replace sites | follow-up |

**Decisions taken without consultation that are now reversed:**
1. Splitting C7 into "TrackContextSheet shell only" without flagging
   it. The shell was committed as if the feature were done.
2. Splitting C12 into "ring + notification only" without flagging.
   Commit message read as if Phase 7 were complete.
3. Splitting C20 into "single layout" without flagging. Commit
   message read as if Phase 8 were complete.
4. Treating C14 / C17 / C18 settings UIs as feature-shipped when no
   behavioural code backed them.
5. Treating Phase 4 as live without naming the deferred engine in
   subsequent end-of-turn summaries.

**Mitigation for the future** (rules added to the working contract):
- A commit that ships UI without behaviour MUST say so in the
  subject line: `feat(stub): X UI only — behaviour pending`.
- An end-of-turn summary MUST list every item from the locked plan
  that is incomplete, not just what was added that turn.
- "Deferred" items go on this audit table, not into a private
  decision pile.

**Re-execution order is updated below to put these unfinished items
ahead of new features.**

---

## §0 Live-state findings from this session (2026-05-08)

| Finding | Source | Status |
|---|---|---|
| Audio playback works (transitions, scrub, auto-advance) | logcat 01:27 | ✅ confirmed |
| `coldStartGuard` AtomicBoolean stops dual-fire | no "Cold-start restored" line in trace | ✅ confirmed |
| LoudnessEnhancer warning spam | logcat 01:43 still threw at line 1119 | 🔧 fixed in `0c33279`, awaiting re-trace |
| Phase 8 widget render broken (only play icon) | screencap `pmp_home_right.png` | 🔧 layout flattened in `0c33279`, awaiting user re-add |
| Phase 8 widget tap silent | code inspection: routed via TaskerReceiver gated by toggle | 🔧 fixed in `0c33279`, direct routing |
| "Grey bar with 2 dots" alleged overlay | `dumpsys window` lists 0 PMP overlays | ❌ NOT my code. The bar in the user screenshot is the Tapo Multi-Shortcuts page indicator (`com.tplink.iot/...MultiShortcutsAppWidgetProvider` placed on home screen, confirmed via `dumpsys appwidget`). The bar in MY screencap is the Astronomical widget's internal axis line. |
| "No media loaded" when tapping a video | user-reported, no logcat captured | ⏳ needs evidence — user must repro and paste `adb logcat -d | grep PMP_DIAG` from the moment they tap |
| Alarm DND override claim | code sets `setBypassDnd(true)` on channel | ⏳ needs live test (set DND, set alarm 1 min ahead, observe) |

---

## §1 Phase 5 — Per-file overrides + Room migration v7→v8

**Status:** 0% done. No `media_overrides` table exists.

**Files to author:**
1. `data/db/entity/MediaOverrideEntity.kt` — pkey=mediaUri, columns:
   `subtitleDelayMs`, `audioDelayMs`, `playbackSpeed`, `pitch`,
   `volumeBoostMb`, `eqPresetId`, `videoFlipH/V`, `videoBw/Sepia/Invert`,
   `videoRotation`, `loopStartMs`, `loopEndMs`. All nullable so a row
   represents only the overridden axes.
2. `data/db/dao/MediaOverrideDao.kt` — `getByUri`, `upsert`, `clear`.
3. `data/db/AppDatabase.kt` — bump `version = 8`, add the entity to
   `entities = [...]`, add abstract `mediaOverrideDao()`.
4. `data/db/AppDatabase.kt` — add a `Migration(7, 8)` object that
   issues `CREATE TABLE IF NOT EXISTS media_overrides (...)`. Wire it
   in the `Room.databaseBuilder` chain (`addMigrations`).
5. UI: `ui/player/components/PerFileOverridesSheet.kt` — 3-tab popup
   (Audio / Video / Loop) with per-axis switches. Tab content reads
   `MediaOverrideDao.getByUri(currentUri).asFlow()` and writes back
   via `upsert`.
6. Hook: `PlayerViewModel.init` collects current-uri changes; on
   change, queries the override row and applies each non-null axis to
   the live player + UI state. On settings-side write, propagates to
   the same surface.

**Acceptance evidence:**
- Open file A, set audio-delay = +250 ms, close, open file B
  (untouched), open file A again → delay re-applies as +250 ms.
- `adb shell run-as com.powermediaplayer sqlite3 ...power_media_player.db
  'SELECT * FROM media_overrides;'` shows the row.
- Migration: install previous APK with v7 db, upgrade to new APK,
  open app → no `IllegalStateException: Migration didn't properly
  handle…`

**Risk:** schema regen mistakes are visible only on first cold open
on a v7 database. Test path: `adb uninstall` + reinstall old APK +
play one file (writes v7 db) + install new APK + open app.

---

## §2 Phase 6a — OpenSubtitles auto-fetch (per-user login)

**Status:** 0% done. No code references "opensubtitles".

**Files to author:**
1. `subtitles/OpenSubtitlesClient.kt` — lightweight retrofit/OkHttp
   client. Endpoints: `POST /api/v1/login` (email+password →
   token+user_id), `GET /api/v1/subtitles?imdb_id=...&languages=en`,
   `GET /api/v1/download` (file_id → encrypted url).
2. `data/preferences/SettingsDataStore.kt` — keys:
   `OPENSUBS_TOKEN` (encrypted via EncryptedSharedPreferences),
   `OPENSUBS_USER_ID`, `OPENSUBS_LANGS` (StringSet, default {"en"}).
3. UI: `ui/settings/SubtitlesAccountSection.kt` — email + password
   fields, "Sign in" button → calls login → stores token.
4. Hook: when a video starts and no `.srt`/`.ass` sibling exists,
   call OpenSubsClient.search(imdb_id_or_filename). On success: write
   to `<videoFile>.srt` in the cache dir, register as subtitle track
   on the player.
5. Identification: derive imdb_id from MediaMetadata if present,
   else hash filename → query OpenSubs feature endpoint.
6. Rate-limit: 5 req/sec free tier; back off on 429.

**Acceptance evidence:**
- Settings → Subtitles → sign in. Token persisted across kill/relaunch.
- Play a known video without sibling `.srt` → log line "OpenSubs
  matched id=X, downloaded N bytes" → subtitles render on screen.
- Airplane mode: silent no-op, no crash.

**Risk:** API key required (`Api-Key` header). User must register
their own free dev key — gate the feature behind a "API key" field
in Settings; default empty = feature disabled, no surprise calls.

---

## §3 Phase 6b — Drive offline copy

**Status:** Drive picker exists (`cloud/DrivePickerActivity`). No
"download" / "offline copy" action.

**Files to edit:**
1. `cloud/DriveOfflineCopier.kt` (new) — `suspend fun copyToCache(
   driveFileId: String, name: String): Uri`. Uses Drive REST
   `files/{id}?alt=media` with the bearer token already in
   `DrivePickerActivity` flow. Streams to
   `cacheDir/drive-offline/<id>__<name>` and returns the file URI.
2. `ui/cloud/CloudFileRow.kt` — add overflow menu item "Save offline".
   Calls the copier in viewModelScope, shows progress via Snackbar.
3. `data/db/entity/OfflineDriveEntity.kt` (new) — fileId, localPath,
   sizeBytes, copiedAt. Or store as a JSON Set in DataStore if we want
   to skip the migration; trade-off: lose ability to query by size.
4. Eviction: when cache dir > 1 GB, evict oldest until <= 800 MB.

**Acceptance evidence:**
- Open Drive picker → pick a media file → "Save offline" → kill
  network → file plays from local cache.
- `adb shell run-as com.powermediaplayer ls cache/drive-offline` shows
  the file.

**Risk:** Drive token expiry mid-download → 401. Catch + refresh.

---

## §4 Phase 6c — Podcast subscriptions

**Status:** 0%. No podcast code.

**Files to author:**
1. `data/db/entity/PodcastSubscriptionEntity.kt` — feedUrl (pk),
   title, artworkUrl, lastChecked, etc.
2. `data/db/entity/PodcastEpisodeEntity.kt` — guid (pk), feedUrl
   (FK), title, audioUrl, durationS, publishedAt, isPlayed.
3. `podcast/RssFeedParser.kt` — XmlPullParser, parses RSS 2.0 items
   into episode list.
4. `podcast/PodcastSyncWorker.kt` — WorkManager Periodic, every 6 h:
   for each subscription, fetch RSS, upsert new episodes.
5. UI: `ui/podcasts/PodcastsScreen.kt` — list of subscriptions, tap
   → episodes list, tap episode → play. "Add by URL" + "Search"
   placeholders.
6. Migration v8→v9 (or batch with §1).

**Acceptance evidence:**
- Add a known podcast feed (e.g. NPR Up First).
- Episodes list populates within 10 s.
- Force background sync via `adb shell cmd jobscheduler run -f
  com.powermediaplayer 1` → new episodes appear.
- Tap newest → playback starts.

**Risk:** WorkManager Hilt wiring — make sure
`HiltWorkerFactory` is registered in `PowerMediaPlayerApp`.

---

## §5 Phase 9a — Smart playlists

**Status:** 0%. No `smart_playlist` table.

**Files to author:**
1. `data/db/entity/SmartPlaylistEntity.kt` — id, name, criteriaJson
   (e.g. `{"genre": "Jazz", "minPlays": 3, "lastDays": 30,
   "sort": "lastPlayed", "limit": 50}`).
2. `data/db/dao/SmartPlaylistDao.kt`.
3. `playlist/SmartPlaylistResolver.kt` — given criteria + the existing
   `MediaScanner` + `playback_history`, returns `List<MediaFileInfo>`.
4. UI: `ui/library/SmartPlaylistEditor.kt` — chip selectors for genre,
   year, played-count range, dates, sort, limit.
5. Library tab gains a "Smart playlists" rail.

**Acceptance evidence:**
- Create "Played 5+ times this month" → resolves to expected files.
- Create "Genre = Jazz, sort by random, limit 25" → resolves
  consistently between cold opens (random seed stable per playlist).

**Risk:** Heavy queries can block scroll. Resolve on `Dispatchers.IO`,
cache result for the session.

---

## §6 Phase 9b — Headphone-aware EQ

**Status:** 0%. `AudioOutputDetector` exists but only exposes
`isTrueMonoOutput`.

**Files to edit:**
1. `audio/AudioOutputDetector.kt` — add `val isHeadphonesConnected:
   StateFlow<Boolean>` (wired or A2DP, per `AudioDeviceInfo.type`
   enumeration).
2. `data/preferences/SettingsDataStore.kt` — `HEADPHONE_EQ_PRESET_ID:
   Int` (default 0 = off).
3. `service/PlaybackService.kt` — observe both flows; on headphones
   plug-in, swap to the configured preset; on unplug, restore the
   user's manual selection.
4. UI: Settings → "EQ when headphones connect" — dropdown of presets
   from `EqualizerPresetDao`.

**Acceptance evidence:**
- Configure preset = "Bass Boost" for headphones.
- Plug wired headphones → log line "headphone EQ applied:
  Bass Boost". Unplug → log line "headphone EQ restored: Flat".

**Risk:** A2DP latency — the device-add callback can fire before
audio routes flip. Defer EQ swap by 200 ms.

---

## §7 Settings 15-section reorganisation per §D

**Status:** 0%. Settings is one long verticalScroll with section
headers but the order doesn't match §D.

**Sections per §D (target order):**
1. Account
2. Library scanning
3. Metadata
4. Cloud
5. Playback
6. Audio output / focus
7. Effects (EQ, ReplayGain, etc.)
8. Subtitles
9. Video
10. Bluetooth
11. Auto-hide / popups
12. Wake-up alarms
13. Theme (§11.5 — accent colour picker)
14. External control (Tasker)
15. Privacy / hidden
16. Reset / about

**Files to edit:**
1. `ui/settings/SettingsScreen.kt` — refactor existing block order;
   no new content, only re-grouping under named `SettingsSectionHeader`
   blocks matching §D.

**Acceptance evidence:**
- Visual scroll-through matches §D order.
- All existing toggles still wire to their flows (smoke test by
  toggling each and checking persistence).

**Risk:** Low. Pure UI re-arrangement.

---

## §8 Spotify Connect end-to-end live verification

**Status:** Code path investigated extensively (see
`docs/superpowers/investigation/2026-05-05-spotify-cold-start-bounce/`
). Cold-start bounce is implemented. **Never live-tested by hand on
Z Fold 6** in this session.

**Verification script (manual, on Z Fold 6):**
1. Force-stop PMP. Force-stop Spotify.
2. Open PMP from launcher. Tap a track via Spotify Connect.
3. Spotify auto-launch should fire, then bounce back to PMP.
4. Audio plays via Spotify Connect to selected device.
5. Capture `adb logcat -d | grep -E "PMP_DIAG|Spotify"` — paste
   for analysis if it fails.

**Risk:** Samsung One UI BAL changes between OS revisions can
re-break this. Each One UI version needs a re-test.

---

## §9 Cast live verification

**Status:** Cast bug fix shipped (commit `cb1bdbd`) — abort takeover
when relay is null. **Never tested with a real Chromecast in-hand
this session.**

**Verification script:**
1. Connect a Chromecast on the same Wi-Fi.
2. Open PMP, play a local file, tap the Cast icon, pick the device.
3. PlaybackService log "Started cast relay on http://x.x.x.x:N".
4. Cast device shows the file.
5. Disconnect Wi-Fi mid-cast → relay null → graceful error toast.

---

## §10 Phase 7 alarm — live verification + media picker

**Status:** Implementation shipped (commits `6227dd9`, `0c33279`).
**Live-tested by user once: alarm fired.** Not yet:
- DND override
- Media picker (current input is a text field — users won't type URIs)
- Snooze action on the notification
- Volume ramp / wind-down (Q6 in §C12 was deferred)

### §10.1 Media picker for the alarm Sound field

The current `AlarmEditor` exposes a raw `OutlinedTextField` for the
URI. That's a developer affordance, not a user one. Replace it with
a typeahead that surfaces the user's existing curated lists.

**Source set (in priority order):**
1. **Bookmarks** — `BookmarkDao.getAll()` (already exists). Each
   bookmark has `mediaUri + label + positionMs`. Surface label as
   the title; alarm fires the bookmarked file (we ignore positionMs
   — alarms always start at 0 unless the user explicitly opts in).
2. **Favourites** — `FavoriteDao.getAll()` (already exists).
3. **Last Played** — `PlaybackHistoryDao.recent(limit = 25)` —
   gives a "what you've listened to lately" rail.
4. **Local library** — `LibraryViewModel.localFiles` flow (already
   collected in MediaScanner). Title + artist + uri.
5. **Cloud** — files indexed by the existing cloud browser
   (Drive + OneDrive + Dropbox). Cached list lives in the cloud
   provider repos.

**UI design — `AlarmMediaPickerSheet.kt`:**
- Top: search field. Empty → shows the four sources as expandable
  groups (Bookmarks / Favourites / Last Played / Library). Typing
  filters every group simultaneously, case-insensitive substring on
  title + artist.
- Each row: 40 dp artwork thumbnail (already loaded via Coil),
  title, subtitle (artist or "Bookmark @ 12:34"), source-tag chip.
- Tap row → returns `(uri, displayLabel)` to `AlarmEditor`, which
  stores `uri` in the AlarmRecord and shows `displayLabel` in the
  list row instead of the raw URI fragment.
- Footer: "Custom URI…" expands the existing text field as an
  escape hatch for power users.

**Files to author / edit:**
1. `ui/alarm/AlarmMediaPickerSheet.kt` (new) — composable
   `ModalBottomSheet`, hoists results from a `derivedStateOf` over
   the four source flows + search query.
2. `alarm/AlarmRecord.kt` — already has `mediaUri: String`. Add
   sibling field `displayLabel: String` (default "" so existing
   serialised records still parse). Update `serialize` to include
   it after `enabled`. Bump deserialise to fall back when missing.
3. `alarm/AlarmsSheet.kt` — replace the `OutlinedTextField` for
   `mediaUri` with a tap row "Sound: $displayLabel" that opens the
   picker; keep "Custom URI…" toggle for the typed path.
4. `alarm/AlarmReceiver.kt` — no behaviour change; mediaUri still
   drives playback. Just log displayLabel for nicer diagnostics.

**Typeahead-narrows-as-you-type:**
- `searchQuery` is a `MutableStateFlow<String>` debounced 150 ms.
- Each source flow is `combine`d with the query: `flow.map { list ->
  list.filter { it.matches(query) } }`.
- Cloud sources only narrow within the already-cached index — we do
  NOT issue live cloud requests on every keystroke (would rate-limit
  and burn battery).

**Acceptance evidence:**
- Alarm dialog opens picker → all four groups populate.
- Type "phil" → only matching titles remain in every group.
- Tap a bookmark → editor closes, alarm row shows the friendly
  label, alarm fires the right file.
- Roundtrip a saved alarm: edit it → label re-displays correctly.

**Risk:** Cloud index size — for users with thousands of cloud
files, in-memory filter is fine; only consider paging if reports
of jank arrive.

### §10.2 Live verification (existing)

**Acceptance evidence:**
- Set alarm 1 min in the future, lock phone, confirm fire.
- Toggle DND on → re-test → confirm sound bypasses DND.

---

## §11 Phase 8 widget — live verification

**Status:** Layout flattened + intents direct-routed (commit
`0c33279`). User must remove the previous widget instance and re-add
to pick up the new layout (Samsung One UI doesn't repaint placed
widgets after APK reinstall).

**Acceptance evidence:**
- Long-press home → Widgets → Power Media Player → drag.
- Title "Power Media Player" + "Tap to open" + 3 transport buttons
  visible.
- Tap play-pause → if PMP is alive, toggles. If not, opens MainActivity.
- Start playing a track → widget title updates within ~500 ms.

---

## §11.5 Theme — user-selectable accent colour

**Status:** 0%. Theme is hard-coded in `ui/theme/Color.kt` —
`TealAccent`, `OledBlack`, `TextPrimary`, etc. consumed by every
screen. Background remains pure black per OLED-power discipline;
only the accent layer (icons, dividers, slider tracks, toggles,
chips, focused outlines) is user-controllable.

### §11.5.1 What the user can change

- **Accent colour** — replaces every site that currently uses
  `TealAccent`. Single value, persisted in DataStore as
  `THEME_ACCENT_HEX: String` (e.g. `#1ABC9C`).
- **Optional secondary accent** — replaces `MintAccent` /
  `SecondaryGreen`. Defaults to a HSL shift of the primary
  (~30° hue rotation) so a single picker still produces a coherent
  pair. User can override.
- **Background stays `#000000`.** Not user-controllable. (Power
  discipline; OLED-savings reasoning unchanged.)
- **Text colours stay greyscale.** Not user-controllable —
  tinting text against an arbitrary accent makes contrast unreliable.

### §11.5.2 Picker UI

Two entry modes side-by-side, both writing the same `THEME_ACCENT_HEX`:

1. **Hex field** — `OutlinedTextField` accepting `#RRGGBB` or
   `#RRGGBBAA`. Live preview swatch beside the field.
2. **Colour wheel + brightness slider** — reuse a known-good Compose
   library (`com.github.skydoves:colorpicker-compose:1.x` is
   actively maintained). Verify version + syntax via Context7
   (`mcp__context7__resolve-library-id` → `query-docs`) before adding
   to `app/build.gradle.kts`. Picker pops a `ModalBottomSheet`,
   confirms with "Use this colour" / "Cancel".

Plus a small palette of 8 presets along the bottom (current Teal,
amber, indigo, magenta, cyan, lime, rose, slate) so a non-power
user gets a one-tap path.

### §11.5.3 Files to author / edit

1. `ui/theme/Color.kt` — keep the constants but rename
   `TealAccent → AccentDefault`. Existing colour names become
   defaults that the runtime overrides.
2. `ui/theme/ThemeAccent.kt` (new) — `object ThemeAccent` with a
   `val current: State<Color>` driven by the DataStore flow, plus a
   `derived secondary: Color` computed from HSL.
3. `ui/theme/Theme.kt` — at the root composable, wrap content in a
   `CompositionLocalProvider(LocalAccent provides
   themeAccent.current.value)`. Replace direct `TealAccent` references
   throughout the codebase with `LocalAccent.current`.
4. `data/preferences/SettingsDataStore.kt` — keys
   `THEME_ACCENT_HEX` (default `"#1ABC9C"` to preserve current look)
   and `THEME_SECONDARY_HEX` (default `""` = auto-derive).
5. `ui/settings/ThemeSection.kt` (new) — section with hex field,
   "Pick…" button (opens wheel sheet), preset row, "Reset to default"
   action.
6. `app/build.gradle.kts` — add `colorpicker-compose` after
   confirming the artifact ID + latest version via Context7.

### §11.5.4 Migration cost

`grep -r "TealAccent\|MintAccent" app/src/main/java/com/powermediaplayer
/ui --include="*.kt" | wc -l` is the count of call sites. Plan for
~80–120 replacements; mechanical refactor.

Risk: a screen that hard-codes a hex literal instead of importing
`TealAccent` won't pick up the user's choice. Audit pass after the
mass replace: `grep -rE "Color\(0xFF[0-9A-Fa-f]{6}\)"` and review
each result for "should this be the accent?".

### §11.5.5 Acceptance evidence

- Set accent to `#FF6B6B` via hex field → re-open the player → top
  app bar title, slider thumb, EQ band selectors, settings icons all
  go red within one frame.
- Set via wheel picker → same result.
- Tap "Reset to default" → returns to teal.
- Kill + relaunch → custom colour persists.
- Disable hex (clear the field) → field rejects empty save with
  inline error; wheel picker remains the source of truth.

---

## §12 Test infrastructure

**Status:** `app/src/test` and `app/src/androidTest` directories
**do not exist**. Zero unit tests, zero instrumented tests. Every
"test" claim in past sessions = manual logcat trace.

**Files to author (pick one of two paths):**

**Path A — minimal test scaffold:**
1. `app/src/test/java/com/powermediaplayer/alarm/AlarmRecordTest.kt`
   — round-trip serialize/deserialize for all field permutations.
2. `app/src/test/java/com/powermediaplayer/alarm/AlarmSchedulerTest.kt`
   — `computeNextTriggerMs` with mocked `Calendar.getInstance` for
   each weekday-mask × time-of-day case.
3. `app/build.gradle.kts` — add `testImplementation("junit:junit:4.13.2")`
   and `testImplementation("org.mockito.kotlin:mockito-kotlin:5.x")`.

**Path B — instrumented smoke tests:**
1. `app/src/androidTest/java/.../AlarmFireTest.kt` — set alarm 5 s
   future, idle 8 s, assert notification posted.
2. `app/src/androidTest/java/.../WidgetRenderTest.kt` — programmatic
   `AppWidgetHostView` inflation, assert children exist.

Path A is faster and protects pure-logic regressions. Path B catches
device-specific render bugs (the kind that bit us this session).

---

## §13 Bug-fix verifications still owed

For every "fix" commit since session start, list the live evidence
captured:

| Commit | Fix claim | Live evidence captured this session? |
|---|---|---|
| `6fcb2e6` cold-start dual-fire guard | logcat 01:27 shows no "Cold-start restored" line | ✅ |
| `cb1bdbd` cast abort on null relay | none | ❌ owed (real Chromecast) |
| `7465fbb` first-run deep-scan dialog | none | ❌ owed (fresh install on Z Fold 6) |
| `3adb903` audio-focus / enrichment / RG auto-scan toggles | none | ❌ owed (each toggle exercised via UI + behavior) |
| `c34c539` auto-hide popups default Never | none | ❌ owed (popup auto-hide observation) |
| `e28800c` LE session rebind | logcat 01:43 still threw | ❌ followup `0c33279` lazy-attach, awaiting re-trace |
| `6227dd9` Phase 7 alarms | user reported alarm fired once | ⚠️ partial — DND override not exercised |
| `3e223aa` Phase 8 widget | screencap proved render broken | ❌ awaiting re-add after `0c33279` |
| `0c33279` widget direct routing + flat layout + LE bail + alarm wording | not yet | ❌ awaiting user re-add and re-trace |

---

## Order to execute (proposed) — UNFINISHED COMMITMENTS FIRST

The §-1 audit re-ordered priorities. Items the user already asked
for and that were silently deferred or partial-shipped come before
new requests.

### Tier 1 — finish what was already promised (§-1)

1. **C7 / C25 — Per-file overrides + long-press menu wiring** —
   Room v7→v8 migration, `media_overrides` table, 3-tab popup
   (Audio/Video/Speed), apply-on-play, indicator chip, wire the
   nullable callbacks at all three call sites. (== §1 below.)
2. **C11 — Sleep-timer 4-mode picker** — Time / End-of-track /
   End-of-chapter / End-of-album-or-queue. Add to existing dialog.
3. **C12 — Wake-up alarm: full feature set** —
   `FullScreenAlarmActivity`, volume ramp (start/end %, ramp dur),
   hold + wind-down, snooze action + duration + max + volume mode,
   skip-next-N, math-problem stop, shake-to-dismiss, vibration,
   `AudioAttributes.USAGE_ALARM` on the alarm playback path. The
   alarm media picker (§10.1) is part of this tier.
4. **C13 — Headphone-aware EQ** — connect-detection in
   `AudioOutputDetector`, settings dropdown of presets, swap on
   plug-in / restore on unplug.
5. **C14 — Audio focus policy implementation** — wire the existing
   settings toggles to `AudioFocusRequest` in `PlaybackService`.
6. **C17 — Metadata enrichment fetch** — Discogs / MusicBrainz
   client behind the existing settings toggles, cache hits to a
   small Room table or SAF cache dir.
7. **C18 — ReplayGain library scanner** — LUFS scanner that
   populates per-file gain (Track + Album modes). "Scan now" button
   wires up. "Auto-scan new files" honoured by `MediaScanner`.
8. **C20 — Widget: 3 sizes + foldable resize** — split the single
   `widget_now_playing_info.xml` into Compact / Wide / Large
   variants, set `useUpdateForCollections="true"`, verify on Z
   Fold inner + outer screens.
9. **Phase 4 — true 2-player crossfade engine** — `CrossfadeController`,
   second `ExoPlayer`, equal-power overlap; per locked §B3.

### Tier 2 — new requests + verification

10. **§13 verification round** — fresh logcat for every fix
    committed since session start. (Was Tier 1 in earlier draft —
    keep doing this in parallel with each Tier 1 ship.)
11. **§11 widget re-add + tap test** — after Tier 1 #8 lands.
12. **§10.1 alarm media picker** — overlaps with Tier 1 #3.
13. **§7 Settings reorg** — pure UI.
14. **§11.5 Theme — accent colour picker** — mechanical mass-
    replace.

### Tier 3 — entirely new features (still locked but fully unstarted)

15. **§5 Smart playlists** (== §C6).
16. **§2 OpenSubtitles** (== §C9).
17. **§3 Drive offline copy** (== §C28).
18. **§4 Podcasts** (== §C10).
19. **§6 Headphone EQ** is in Tier 1 above (== §C13). Skip if reached.

### Tier 4 — quality + verification

20. **§12 Test infra** — interleave Path A unit tests as Tier 1–3
    features land.
21. **§14 Whole-app functional test phase** — final exhaustive
    sweep. Runs only after every Tier 1–3 item is shipped AND the
    per-feature evidence is logged.

### Cadence rule

Every Tier 1 ship MUST cross off its row in the §-1 audit table the
same turn it lands. End-of-turn summaries MUST cite the §-1 row(s)
closed and the §-1 row(s) still open.

---

## §14 Whole-app functional test phase

**This phase is sequenced AFTER §1–§13. Do not start §14 until every
item above is shipped and its per-feature acceptance evidence is on
file.** §14 is a separate, exhaustive sweep that exercises every
feature, toggle, setting, and combination — nothing is "obvious" or
"covered by an earlier check"; everything gets re-pressed.

### §14.0 Required tooling

- **Superpowers** — every test entry below must be driven through
  the relevant Superpowers skill (`superpowers:debugging`,
  `superpowers:test-driven-development`,
  `superpowers:verification-before-completion`,
  `superpowers:investigation`). Each test entry calls out which.
- **Context7** — for any external dependency or platform API
  involved in a test, query Context7
  (`mcp__context7__resolve-library-id` then `query-docs`) before
  writing the test, even for libraries we already use (Media3,
  Compose, Hilt, Room, DataStore, WorkManager, AppWidgetProvider,
  AlarmManager, AudioManager, MediaSession). Prevents drift between
  what we coded against and what currently ships.
- **Devices** — both must run every test:
  - **Z Fold 6** (`adb -s RFCY70BARDJ`) — physical, foldable, real
    headphone jack absent → BT headphones used.
  - **Emulator** (`adb -s emulator-5554`) — current API level set in
    AVD. Catches geometry/AOSP behaviours the Samsung skin masks.
- **adb commands** standard kit:
  - `adb -s <id> install -r <apk>`
  - `adb -s <id> logcat -c` (clear before each test entry)
  - `adb -s <id> shell am start -n com.powermediaplayer/.MainActivity`
  - `adb -s <id> shell input tap <x> <y>` / `swipe`
  - `adb -s <id> shell screencap -p /sdcard/<name>.png` + `pull`
  - `adb -s <id> logcat -d -t N | grep -E "PMP_DIAG|FATAL|AndroidRuntime|ExoPlayer|MediaCodec"` after each test, attach
    output to the test row.
  - `adb -s <id> shell dumpsys window | grep com.powermediaplayer` to
    verify expected window stack.
  - `adb -s <id> shell dumpsys appwidget` for widget tests.
  - `adb -s <id> shell dumpsys notification` for alarm/notif tests.
  - `adb -s <id> shell run-as com.powermediaplayer sqlite3
    databases/power_media_player.db '<query>'` for DB tests.

### §14.1 Format of each test row

Every test entry in the matrices below is logged in
`docs/superpowers/test-runs/<date>-fullapp/<section>.md` with:

```
ID: T-<section>-<seq>
Feature: <human label>
Skill used: <superpowers skill>
Devices: [Z Fold 6, Emulator] | [Z Fold 6 only with reason]
Steps: <numbered>
Expected: <observable>
Actual: <pasted observation>
Logcat slice: <pasted>
Screencap (if UI): <path>
Verdict: PASS | FAIL | BLOCKED
Owner-action: <only if FAIL — link to issue / fix commit>
```

A FAIL doesn't end the run; it gets logged, the next test continues.
At the end, FAILs are triaged in priority order and re-run after
fixes.

### §14.2 Test matrix — Library

For each entry: tap, observe, capture logcat + screencap.

- T-LIB-01 — Library tab cold-load on fresh install (storage permission
  prompt fires, deep-scan dialog (§F) appears, tap "Skip" → list
  populates within 5 s).
- T-LIB-02 — Same but tap "Yes, deep-scan" → progress indicator,
  album art populates for files that previously had none.
- T-LIB-03 — Search input narrows live across artist + album + title.
- T-LIB-04 — Sort: each option (date, name, duration, size) flips the
  list deterministically.
- T-LIB-05 — Filter: video-only / audio-only / both.
- T-LIB-06 — Long-press menu: every action (play, queue next, queue
  end, hide, favourite, delete, open in other app). Each verified.
- T-LIB-07 — Multi-select: enter via long-press → "Select all" →
  bulk hide → bulk favourite → exit (confirms multi-select auto-
  exits per commit `1be3d85`).
- T-LIB-08 — Hidden files: §C27 sheet → unhide one → file reappears
  → "Unhide all" → all return.
- T-LIB-09 — Folder mode (audiobook): play folder → cross-file
  chapters work.
- T-LIB-10 — Cloud tab: each provider chip (Drive / OneDrive /
  Dropbox) opens picker; pick a remote audio file, plays.
- T-LIB-11 — Last-Played tab: rows reflect history; tap re-plays from
  last position.
- T-LIB-12 — Pinned: pin a file from long-press → appears in Pinned.
- T-LIB-13 — First-run dialog: confirm one-time gate (§F).
- T-LIB-14 — File deletion confirmation dialog cancels safely (no
  delete on cancel).

### §14.3 Test matrix — Player

- T-PLR-01 — Tap audio file → opens player, plays, position ticker
  advances, isPlaying=true in logcat.
- T-PLR-02 — Tap video file → video surface renders, audio plays,
  isPlaying=true. **Carries the open "no media loaded" claim — must
  verify clean once §13 evidence is in.**
- T-PLR-03 — Pause / resume via centre button.
- T-PLR-04 — Scrub via slider — position jumps; logcat shows
  `seekTo target=Xms`.
- T-PLR-05 — Prev / Next chips advance items in the queue.
- T-PLR-06 — A-B loop: set A, set B, confirm loop, persists across
  app restart per commit `f43f63f`.
- T-PLR-07 — Speed: cycle 0.5×, 1×, 1.5×, 2×, custom.
- T-PLR-08 — Pitch: ±semitones, audible verification.
- T-PLR-09 — Loop modes: off, one, all.
- T-PLR-10 — Sleep timer presets + custom minutes; player pauses at
  the right moment.
- T-PLR-11 — Bookmark create + rename (commit `d23a6d3`) + jump.
- T-PLR-12 — Crossfade: enable, set ms, observe overlap on track end
  (every sub-toggle: album mode, skip silence, manual fade-now,
  fade-out on pause, fade-in on resume).
- T-PLR-13 — Audio effects popup: BW / sepia / invert / flip H+V /
  rotation; auto-hide respects §C user setting.
- T-PLR-14 — Subtitle delay slider, audio delay slider.
- T-PLR-15 — Volume boost slider (now lazy-attaches LE — confirm log
  is silent for clamped=0).
- T-PLR-16 — Reverb preset dropdown.
- T-PLR-17 — Stereo flip / Mono mix (disabled on true-mono devices,
  per `AudioOutputDetector`).
- T-PLR-18 — Frame step forward / back on paused video.
- T-PLR-19 — Screenshot.
- T-PLR-20 — PiP: enter, controls work, return.
- T-PLR-21 — MiniPlayerBar: appears on home tab when player is
  loaded; tap row → opens full player.
- T-PLR-22 — Cold-start resume backoff: leave a track mid-play → kill
  → reopen → resume offset honoured.

### §14.4 Test matrix — Settings (every toggle, every dropdown)

Walk top-to-bottom in §D order. For each control:
1. Note default. 2. Toggle. 3. Verify behaviour change. 4. Toggle
back. 5. Kill + relaunch — state persists.

Sections covered: Account, Library scanning, Metadata, Cloud,
Playback, Audio output / focus, Effects, Subtitles, Video,
Bluetooth, Auto-hide / popups, Wake-up alarms, **Theme (§11.5)**,
External control (Tasker), Privacy / hidden, Reset / about.

Every dropdown enumerates every option (e.g. audio focus on call:
pause / duck / continue — three runs each).

Reset all settings (§Reset): triggers confirm dialog, clears every
DataStore key, app re-renders with defaults.

### §14.5 Test matrix — EQ

- T-EQ-01 — Each preset applies (logcat: equaliser bands set to
  preset values).
- T-EQ-02 — Custom band drag affects audio (subjective + spectrum
  meter if available).
- T-EQ-03 — Save custom preset → appears in dropdown → reload it.
- T-EQ-04 — Headphone-aware EQ (§6 once shipped): plug → preset
  swap; unplug → revert.
- T-EQ-05 — ReplayGain on/off + auto-scan (§3adb903).

### §14.6 Test matrix — Cast

- T-CAST-01 — On same Wi-Fi, MediaRoute button appears.
- T-CAST-02 — Pick Chromecast device → audio plays on receiver.
- T-CAST-03 — Pick with content:// URI → relay starts (log line
  "Started cast relay on http://…").
- T-CAST-04 — Wi-Fi disconnect mid-cast → relay null → error toast,
  player stays local (commit `cb1bdbd`).
- T-CAST-05 — Cast video — surface appears on receiver.
- T-CAST-06 — Disconnect cast cleanly via picker.

### §14.7 Test matrix — Spotify Connect

- T-SP-01 — Cold launch PMP, Spotify killed → tap a Spotify-source
  track → bridge auto-launches Spotify, bounces back, audio plays
  via Connect.
- T-SP-02 — Spotify already running → no bounce; immediate playback.
- T-SP-03 — Pick remote device in Spotify Connect picker (speaker,
  phone, computer); audio routes accordingly.
- T-SP-04 — Pause/play/skip from PMP controls — propagates.
- T-SP-05 — Spotify token expiry — silent refresh.

### §14.8 Test matrix — Bluetooth / wired audio

- T-BT-01 — Connect BT headphones → audio routes to BT.
- T-BT-02 — Disconnect → audio pauses or routes to speaker per
  user's preference.
- T-BT-03 — AVRCP keys: play / pause / next / prev / skip-back-30 /
  skip-forward-30 each fire the configured action.
- T-BT-04 — `headphonePlugAutoplay` toggle: plug headphones with no
  media → behaviour matches setting.
- T-BT-05 — Wired headphone jack — N/A on Z Fold 6 (no jack).

### §14.9 Test matrix — Quick Settings tiles (§C1)

- T-QS-01 — PMP play/pause tile fires from QS panel.
- T-QS-02 — −15 s / +15 s tiles fire.

### §14.10 Test matrix — Tasker / external control (§C3)

- T-TASK-01 — Toggle off (default) → `adb shell am broadcast -a
  com.powermediaplayer.action.PLAY` → ignored, log line confirms.
- T-TASK-02 — Toggle on → same broadcast → playback starts.
- T-TASK-03 — Each action verified: PLAY, PAUSE, PLAY_PAUSE,
  SKIP_NEXT, SKIP_PREV, SKIP_BACK_30, SKIP_FORWARD_30, SEEK_TO.

### §14.11 Test matrix — Wake-up alarms

- T-AL-01 — Add alarm 1 min ahead, lock phone → fires + sound +
  notification.
- T-AL-02 — Recurring alarm: set Mon-Fri → re-arms after fire.
- T-AL-03 — Toggle off saved alarm → cancel scheduled, no fire.
- T-AL-04 — Delete alarm → row + system schedule both gone.
- T-AL-05 — DND on → alarm still rings (channel `setBypassDnd(true)`).
- T-AL-06 — Reboot device → alarm rescheduled (after BOOT_COMPLETED
  receiver wires up; currently NOT implemented — log as BLOCKED).
- T-AL-07 — Media picker (§10.1 once shipped): pick a bookmark →
  alarm plays it.
- T-AL-08 — Custom URI fallback works.

### §14.12 Test matrix — Home-screen widget (§11)

- T-WID-01 — Long-press home → Widgets → "Power Media Player" listed.
- T-WID-02 — Drag onto home — renders title + artist + 3 transport
  buttons (post-`0c33279`).
- T-WID-03 — Tap row → opens MainActivity.
- T-WID-04 — Tap play-pause with no media → opens app.
- T-WID-05 — Tap play-pause with media loaded → toggles isPlaying.
- T-WID-06 — Tap prev / next — advances queue.
- T-WID-07 — Title / artist update within 500 ms of track change.
- T-WID-08 — Resize widget → still renders correctly.

### §14.13 Test matrix — Stats / history

- T-STAT-01 — Settings → listening stats (§C2) opens; counts +
  top-5 lists populate from `playback_history`.
- T-STAT-02 — After plays, counts increment.

### §14.14 Test matrix — Theme (§11.5 once shipped)

- T-THM-01 — Hex field accepts `#FF6B6B` → app accent goes red,
  background stays black.
- T-THM-02 — Wheel picker confirms a colour → applied.
- T-THM-03 — Preset chip (e.g. amber) — applied.
- T-THM-04 — "Reset to default" → returns to teal.
- T-THM-05 — Persists across kill + relaunch.
- T-THM-06 — Audit pass: every screen visited, no surface still
  hard-coded teal.

### §14.15 Test matrix — Smart playlists (§5 once shipped)

- T-SP-01 — Create "Played 5+ times this month" → resolves.
- T-SP-02 — Edit criteria → result set updates.
- T-SP-03 — Delete → row gone, no orphaned data.

### §14.16 Test matrix — OpenSubtitles (§2 once shipped)

- T-SUB-01 — Sign-in success persists token.
- T-SUB-02 — Wrong password → error message.
- T-SUB-03 — Play a video without sibling SRT → subtitles fetched.
- T-SUB-04 — Airplane mode mid-search → silent no-op.

### §14.17 Test matrix — Drive offline (§3 once shipped)

- T-DRV-01 — Save offline → file appears in cache.
- T-DRV-02 — Kill network → plays from cache.
- T-DRV-03 — Cache > 1 GB → eviction kicks in.

### §14.18 Test matrix — Podcasts (§4 once shipped)

- T-POD-01 — Add by URL → episodes populate.
- T-POD-02 — Background sync via WorkManager → new episodes appear.
- T-POD-03 — Tap episode → plays.

### §14.19 Test matrix — Combinations (the explicit ask)

The user's directive: "every possible function and combination of
functions". A non-exhaustive but representative grid:

- T-COMBO-01 — Crossfade ON + Album mode ON + Sleep timer in 1 min:
  fade-out at sleep boundary respected.
- T-COMBO-02 — Speed 1.5× + pitch +2 + EQ "Bass Boost": all three
  layers audible.
- T-COMBO-03 — Volume boost 1500 mB + ReplayGain ON: net level sane,
  no clipping in logcat (`MediaCodec` warnings absent).
- T-COMBO-04 — A-B loop + Custom EQ + speed 0.75: loops within the
  segment at the slowed speed.
- T-COMBO-05 — Cast active + tap a different file: relay re-points
  cleanly.
- T-COMBO-06 — Cast + crossfade enabled: fade behaviour on the cast
  side (Media3 limitation possible — log result).
- T-COMBO-07 — BT headphones + Headphone-aware EQ + ReplayGain: EQ
  swap doesn't disrupt RG.
- T-COMBO-08 — Tasker SEEK_TO + A-B loop active: seek inside loop
  retargets correctly; seek outside loop releases the loop.
- T-COMBO-09 — Wake-up alarm + Spotify Connect last source: alarm
  fires its own track via local pipeline, doesn't try to wake
  Spotify.
- T-COMBO-10 — Widget play tap + app cold: opens app, then
  subsequent taps toggle.
- T-COMBO-11 — Theme accent change while video playing: live re-tint
  doesn't drop a frame.
- T-COMBO-12 — Quick Settings tile + Tasker intent toggle off: tile
  still works (tile is internal, not gated by Tasker toggle).
- T-COMBO-13 — Smart playlist + crossfade + sleep timer.
- T-COMBO-14 — DND on + alarm + crossfade: alarm sound bypasses, then
  crossfade settings carry into post-alarm playback.
- T-COMBO-15 — Settings reset all + active playback: playback
  continues, settings revert.

(Each combination row gets the standard
ID/Steps/Expected/Actual/Logcat/Verdict treatment.)

### §14.20 Per-device coverage rule

Every test in §14.2–§14.19 runs on **both Z Fold 6 and emulator**
unless the row says "Z Fold 6 only" with a justification (BT, real
headphones, Spotify Connect on real device, real Cast hardware).

### §14.21 Logcat discipline

Before each test:
```sh
adb -s <id> logcat -c
```
After each test:
```sh
adb -s <id> logcat -d -t 400 | grep -E \
  "PMP_DIAG|FATAL|AndroidRuntime|ExoPlayer|MediaCodec|ANR" \
  > docs/superpowers/test-runs/<date>-fullapp/logs/<test-id>.log
```
Attach the file path to the test row. No log = no PASS.

### §14.22 Triage and exit criteria

§14 is complete when:
1. Every test ID in §14.2–§14.19 has a verdict logged.
2. Every FAIL has either a fix commit linked or a documented BLOCKED
   reason (e.g. "no Chromecast available — re-run when one is").
3. The combined report `docs/superpowers/test-runs/<date>-fullapp/
   summary.md` shows pass-rate per section.

§14 is **not** a pre-merge gate during §1–§13. It is the final
clean-up before the release candidate.
