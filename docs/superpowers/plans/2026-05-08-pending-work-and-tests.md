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
13. External control (Tasker)
14. Privacy / hidden
15. Reset / about

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

## §10 Phase 7 alarm — live verification

**Status:** Implementation shipped (commits `6227dd9`, `0c33279`).
**Live-tested by user once: alarm fired.** Not yet:
- DND override
- Picker for media URI (current input is text field, blank = resume)
- Snooze action on the notification
- Volume ramp / wind-down (Q6 in §C12 was deferred)

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

## Order to execute (proposed)

1. **§13 verification round** — pull a fresh logcat for each owed fix.
   Cheapest, highest information density.
2. **§11 widget re-add + tap test** — remove and re-add widget; tap
   transport buttons; capture logcat.
3. **§7 Settings reorg** — pure UI, low risk, immediate UX win.
4. **§6 Headphone-aware EQ** — bounded scope, clear acceptance.
5. **§5 Smart playlists** — needs schema work but no platform
   permissions.
6. **§1 Phase 5 per-file overrides** — schema bump, careful migration.
7. **§2 OpenSubtitles** — needs an API key from the user.
8. **§3 Drive offline copy** — incremental on existing Drive picker.
9. **§4 Podcasts** — largest scope, leave for last.
10. **§12 Test infra** — interleave Path A unit tests as features land.

Items 1–6 should comfortably fit before any further compaction.
