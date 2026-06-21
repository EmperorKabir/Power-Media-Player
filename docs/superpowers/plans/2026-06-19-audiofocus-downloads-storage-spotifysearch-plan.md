# Audio-Focus + Downloads/Offline Management + Per-Source Storage + Spotify Search — Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. Steps use `- [ ]`. **Plan only — nothing implemented. Power-user app: maximise user control.**

**Goal:** Fix the call-not-pausing bug (content-aware), make podcast downloads real + fully manageable, add user-chosen per-source storage locations, and re-enable a quality Spotify catalogue search.

**Tech Stack:** Kotlin, Media3/ExoPlayer 1.6.0 (audio focus, PiP), Room 2.7.1 (migration), Jetpack Compose, SAF (`ACTION_OPEN_DOCUMENT_TREE` + `DocumentFile`), OkHttp, WorkManager, Spotify Web API (Connect).

---

## Evidence basis (from this session's investigation + Context7)

- **Audio focus:** ExoPlayer built `handleAudioFocus=false` (`PlaybackService.kt:1064`); custom `AudioFocusRequest(AUDIOFOCUS_GAIN)` requested ONCE in `onCreate` (`:1144`), result ignored (`:1918`), never re-requested on play → app often isn't the live focus owner → a call's `LOSS_TRANSIENT` isn't delivered → keeps playing. `oauthInFlight` 60 s blanket suppression (`:1937` / `CloudViewModel.kt:562`) is a secondary call-drop. `setHandleAudioBecomingNoisy(true)` (`:1066`) already handles unplug. Focus pause/resume acts on the shared player (`pausedDueToFocus → p.pause()/p.play()`, `:1959,1971`); PiP is an independent window (MainActivity) → pause/resume works in PiP. Context7: `setAudioAttributes(attrs, handleAudioFocus=true)` is the standard auto-pause-on-call path; alternative = fix the custom lifecycle (preferred — keeps the per-scenario settings + the Spotify-OAuth guard).
- **Podcast downloads:** hard-coded public `Movies/PowerMediaPlayer/podcasts/…` (`PodcastDownloader.kt:51`), plain `File` write, **untracked**, **never played** (`playEpisode` always streams `audioUrl`, `PodcastsSection.kt:103`), **no eviction**.
- **Drive offline:** app-private cache, tracked in `OfflineCopyEntity` + DataStore `OFFLINE_DRIVE_PAIRS`, LRU evicts under `offlineStorageLimitBytes` (5 GB), playback prefers local file (`CloudViewModel.kt:1074`), Save/Remove + "Offline" chip. `isStarred` in schema, **no UI**.
- **SAF today:** read-only grants only (`GoogleDriveProvider.kt:76-97,205`); **no write path** exists. Context7 confirms write path: `ACTION_OPEN_DOCUMENT_TREE` + `FLAG_GRANT_WRITE_URI_PERMISSION` + `takePersistableUriPermission` + `DocumentFile.createFile().openOutputStream()` + `DocumentFile.delete()`.
- **Accounts:** Drive = single (`DriveOAuthProvider.kt:89`); Spotify = single (`SpotifyTokenStore.kt`). No multi-account, no account-id on `CloudMediaItem`.
- **Spotify offline:** impossible — `getMediaStreamUri` returns only the (deprecated, may-be-null) 30 s preview else fails (`SpotifyProvider.kt:603-623`); Connect-only; Context7: preview clips "cannot be offered as a standalone service or product". Nothing downloadable.
- **Spotify search:** backend + VM + play path already built; hidden by `CloudBrowserScreen.kt:347` (commit `fa4e7eb`, "not useful"). Real quality defects found: standalone **episode** results open empty (`listContainer` lacks `episode`, `CloudViewModel.kt:968-973`); track rows carry **no artist** (`jsonToCloudItem`, `:563-597`); **artist** type not searched (`:1067`).

---

## STORAGE-PATH DESIGN (decisions RESOLVED 2026-06-22)

Three independent, user-controlled storage paths. Power-user: **every path is a folder the user picks via SAF — any location** (internal, SD, USB-OTG, Downloads, etc.):

- **A — per podcast SHOW** (NOT per episode): each subscribed show has its own download-folder override; unset → the global default. Set/cleared in the show's settings.
- **B — Google Drive: ONE single global location** for ALL Drive offline files. **No multi-account** — the app is single-account by design, so one location covers it (decision: do NOT build multi-account). Default = app cache; user can repoint to any SAF folder.
- **C — Spotify: not possible** — Spotify audio cannot be downloaded (DRM; Connect-only; no audio bytes ever reach the app). No storage path; the UI states why. (User acknowledged.)

**Maximum control (power-user ethos):** every limit, network policy, auto-download default, per-show override, default folder, eviction toggle and focus policy is user-exposed. App-managed defaults exist only as the *starting* value — the SAF picker lets the user place files anywhere (public/browsable or app-private — their choice by where they pick). Per-EPISODE download/delete actions still exist (control), but the storage PATH is chosen per-show.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `service/PlaybackService.kt` | Audio focus | Modify — focus lifecycle on play/stop, honour result, content-aware policy, bound `oauthInFlight` |
| `util/SafStorage.kt` | SAF write helpers | **Create** — write-grant picker contract, `DocumentFile` create/write/delete, persisted-tree resolution |
| `podcast/PodcastDownloader.kt` | Episode download | Modify — write to a resolved location (default or per-show SAF tree); record localPath |
| `data/db/entity/PodcastShowEntity.kt` | Podcast schema | Modify — add `downloadTreeUri: String?`; episode `localPath: String?`, `downloadedAt: Long` (Room migration) |
| `data/db/dao/PodcastDao.kt` | Podcast queries | Modify — set/clear localPath; observe downloaded; total downloaded bytes |
| `ui/podcast/PodcastsSection.kt` | Podcast UI + play | Modify — play local file when present; download/delete buttons + "downloaded" badge; per-show settings (storage folder, download-all/delete-all) |
| `ui/cloud/CloudViewModel.kt` | Cloud logic | Modify — Spotify search branch ordering; `listContainer` handle `episode`; Drive offline location redirect; downloads aggregation |
| `ui/cloud/CloudBrowserScreen.kt` | Cloud UI | Modify — un-gate Spotify search; `isStarred` pin menu; Downloads entry |
| `ui/cloud/SpotifyProvider.kt` | Spotify | Modify — `jsonToCloudItem` artist string; search types; standalone-episode play |
| `ui/downloads/DownloadsScreen.kt` | Unified downloads view | **Create** — list Drive copies + podcast files, sizes, delete, total usage |
| `ui/settings/SettingsScreen.kt` + DataStore | Global settings | Modify — Downloads & Offline section (limits, network policy, auto-download defaults, focus policy, default folders, Manage Downloads) |
| `podcast/PodcastSyncWorker.kt` | Auto-sync | Modify — network policy from settings; real eviction |
| `ui/info/InfoContent.kt` | Info boxes | Modify — document focus behaviour, offline playback, storage locations, Spotify search/offline |

---

## Verification corrections (superpowers review 2026-06-22 — AUTHORITATIVE; apply before/while executing)

Verdict: **SAFE TO EXECUTE WITH CORRECTIONS.** Architecture validated against code + Android docs. Fold these in:

**Correctness-blocking (must do):**
1. **Wire the migration.** DB is at **version 16** (`AppDatabase.kt:91`); add **`MIGRATION_16_17`** AND register it in `di/AppModule.kt:67-76` `.addMigrations(...)` — there is NO destructive fallback past v6, so a missing migration **crashes on upgrade**. Precedent: `MIGRATION_11_12` (additive `ALTER TABLE`). → add `di/AppModule.kt` to scope.
2. **`CloudMediaItem` has no artist/subtitle field** (`cloud/CloudMediaItem.kt:12-27`) — add one before Part 5.1 can populate it. → add `cloud/CloudMediaItem.kt` to scope.
3. **Part 5 lives in `SpotifyProvider.kt`, NOT `CloudViewModel.kt`:** `jsonToCloudItem` (`SpotifyProvider.kt:563-597`), `listContainer` (`:954-974`, no `episode` branch → returns empty at `:973`), search `type` list (`:1067`, no `artist`). `CloudViewModel` only *calls* `spotifyProvider.listContainer`.
4. **Part 4.3 must ALSO patch the offline-playback READ path** `CloudViewModel.kt:1085-1087` (`java.io.File`/`Uri.fromFile`) to branch on `content://` — else SAF-stored Drive copies silently fall back to streaming (not just the evictor's delete).

**Robustness/accuracy:**
5. **Task 1.2:** the service has no `isVideoContent` symbol — derive video from the current item's mimeType (`startsWith("video/")`, `PlaybackService.kt:1862`) / `TRACK_TYPE_VIDEO`.
6. **Task 1.1:** permanent-loss abandon is NEW (only `onDestroy` abandons today, `:2701`). Harden the `AUDIOFOCUS_REQUEST_FAILED` branch for **targetSdk 35** — a request racing FGS promotion can transiently FAIL; do NOT hard-pause a backgrounded auto-advance (retry/tolerate). Guard the GAIN-driven `p.play()` so it doesn't recursively re-request focus.
7. **Execution order:** Task 2.2's SAF-write sub-path depends on Part 4.1 → do **Part 4.1 (SAF infra) before** Part 2.2's SAF branch. Revised order: Part 1 → Part 2.1+2.3 (DB + play-from-file, default folder only) → **Part 4.1** → Part 2.2 SAF write → Part 3 → Part 4.2/4.3 → Part 5 → Part 6.
8. **Crossfade invariant:** the focus rework MUST keep driving pause/resume through `p.pause()/p.play()` — that transitively pauses the crossfade secondary player (`PlaybackService.kt:2025-2030` → `CrossfadeController.pauseAll/resumeAll`). Any other abandon mechanism would leave the secondary audible during a call.
9. **Line-ref tidies:** loss block is `:1953-1987`; `isStarred` evictor check `:230`; `OFFLINE_DRIVE_PAIRS` is `|`-delimited (`SettingsDataStore.kt:1006`) — ensure a `content://` uri has no raw `|`.

---

# PART 1 — Audio-focus / call-pause fix (the bug)

### Task 1.1: Correct the focus-request lifecycle
**Files:** `PlaybackService.kt` (`:1064`, `:1144`, `:1896-1988`).
- [ ] Remove the one-shot request from `onCreate`; instead request `AUDIOFOCUS_GAIN` when playback STARTS — hook `onIsPlayingChanged(true)` (and the play command path). **Honour the result:** only proceed if `AUDIOFOCUS_REQUEST_GRANTED`; on `DELAYED` wait for the listener's `GAIN`; on `FAILED` pause and surface a brief note. (Today `runCatching{…}` discards the code — `:1918`.)
- [ ] **Abandon** focus on a USER pause/stop and on a PERMANENT `LOSS`; **keep** the request alive on a TRANSIENT loss (so Android re-delivers `GAIN` after the call). Distinguish via the loss type already handled at `:1954-1977`.
- [ ] Keep `setHandleAudioBecomingNoisy(true)` (unplug) and `setAudioAttributes(attrs, handleAudioFocus=false)` (we manage focus ourselves to preserve the per-scenario settings — do NOT switch to Media3's handler, which would drop the settings and risk the Spotify-OAuth auto-resume regression, commit `5e798ad`).

### Task 1.2: Content-aware policy (video vs audio)
- [ ] In `handleAudioFocusChange`, when the current item `isVideoContent`, force **pause** for `LOSS_TRANSIENT`/`LOSS` regardless of the audio "duck/continue" setting (ducking/continuing a video during a call is nonsensical). Audio honours `focusPolicyOnCall/onNotif/onOther`.
- [ ] Verify resume-after-call re-enters `playWhenReady=true` in PiP (it does — `p.play()` on `GAIN`, PiP window persists). No PiP-specific code needed.

### Task 1.3: Bound the `oauthInFlight` suppression
- [ ] Replace the blanket 60 s window (`CloudViewModel.kt:562`) so a call's focus-loss is NOT dropped: only suppress AUTO-RESUME on OAuth return, not the PAUSE on loss. (The OAuth-bounce guard must keep the app from auto-resuming after the Spotify Custom-Tab returns, but must NOT swallow a genuine call pause.)
- [ ] Verify on device: audio bg, video full-screen, video in PiP, app minimised — call pauses + resumes (in PiP if applicable); another media app pauses (no auto-resume); notification ducks audio (pauses video); unplug pauses.

---

# PART 2 — Make podcast downloads real (prerequisite for management)

### Task 2.1: Track downloads in the DB (Room migration)
**Files:** `PodcastShowEntity.kt`, `PodcastDao.kt`, `AppDatabase.kt`.
- [ ] Add to `PodcastEpisodeEntity`: `localPath: String? = null` (file path OR `content://` document uri), `downloadedAt: Long = 0L`. Add to `PodcastShowEntity`: `downloadTreeUri: String? = null` (per-show storage override). Bump DB version + add `MIGRATION_n_n+1` (`ALTER TABLE … ADD COLUMN …`).
- [ ] `PodcastDao`: `setLocalPath(guid, path, at)`, `clearLocalPath(guid)`, `observeDownloaded(feedUrl): Flow<List<…>>`, `totalDownloadedBytes()` (needs file sizes — store size too, add `localBytes: Long = 0L`).

### Task 2.2: Downloader records destination + per-show location
**Files:** `PodcastDownloader.kt`, new `util/SafStorage.kt` (Part 4).
- [ ] Resolve the destination: per-show `downloadTreeUri` → global default folder setting → app-managed `getExternalFilesDir`. Write via `File` for a plain path or `DocumentFile.createFile(...).openOutputStream()` for a SAF tree (Part 4 helper). On success, `podcastDao.setLocalPath(guid, uriOrPath, size)`.
- [ ] Idempotency: check the recorded `localPath` exists (File or SAF `findFile`) before re-downloading.

### Task 2.3: Play the downloaded file
**Files:** `PodcastsSection.kt:103` (`playEpisode`).
- [ ] If `episode.localPath` is non-null and exists, build the `MediaItem` from it (file:// or content://) — offline playback; else stream `audioUrl`. (Same pattern as Drive `CloudViewModel.kt:1074`.) Keep the recents-recording + artwork from earlier work.

---

# PART 3 — Downloads management surfaces

### Task 3.1: Per-episode + per-show podcast management (Cloud tab)
**Files:** `PodcastsSection.kt` (EpisodeRow, ShowSettingsRow).
- [ ] Episode row: a **download** affordance (manual download), a **"Downloaded" badge** when `localPath` set, and a **delete** action (delete file + `clearLocalPath`).
- [ ] Per show: **"Download latest N" / "Delete all downloads"**; the existing `autoDownload`/`retentionLastN`/notify; add the **per-show storage-folder picker** (Part 4) showing the current folder.

### Task 3.2: Drive `isStarred` pin + offline management
**Files:** `CloudBrowserScreen.kt` (long-press menu), `CloudViewModel.kt`, `OfflineCopyDao`.
- [ ] Long-press a Drive offline row → **"Protect from auto-cleanup"** toggling `OfflineCopyEntity.isStarred` (schema + evictor already honour it — `CloudViewModel.kt:233`). A "pinned" chip variant.

### Task 3.3: Unified Downloads view
**Files:** create `ui/downloads/DownloadsScreen.kt`; route from Cloud tab + Settings.
- [ ] List **Drive offline copies** (`OfflineCopyDao.observeAll`) + **downloaded podcast episodes** (`PodcastDao.observeDownloaded`) with title, source, size; per-row delete; **total usage** and the cap; group by source.

---

# PART 4 — User-chosen per-source storage locations

### Task 4.1: SAF write infrastructure (shared)
**Files:** create `util/SafStorage.kt`.
- [ ] A write-grant picker: `ActivityResultContracts.OpenDocumentTree` launched with intent flags `READ|WRITE|PERSISTABLE`; on result `takePersistableUriPermission(uri, READ|WRITE)`. (Context7-confirmed.)
- [ ] Helpers: `createChild(treeUri, subDirs, name, mime): Uri?` (walk/create dirs via `DocumentFile.fromTreeUri` + `createDirectory`/`createFile`), `openOutput(docUri)`, `delete(docUri)`, `exists/find`, `displayName(treeUri)`, and a guard that the persisted permission still holds (`getPersistedUriPermissions`), falling back to default if revoked.

### Task 4.2: Per-podcast storage location
**Files:** `PodcastShowEntity.downloadTreeUri` (Task 2.1), `PodcastsSection.kt` (per-show picker), `PodcastDownloader.kt` (Task 2.2 resolution).
- [ ] Per-show "Download folder" row launches the write-grant picker; persist the tree URI on the show; downloader writes there. Clear → revert to default.

### Task 4.3: Global default podcast folder + single global Drive-files location
**Files:** `SettingsDataStore.kt`, `SettingsScreen.kt`, `CloudViewModel.kt` (Drive offline write).
- [ ] Settings: a **default podcast download folder** (write-grant SAF picker; default = app-managed external files; used by any show without a per-show override — Task 4.2).
- [ ] Settings: **"Google Drive files location"** — ONE single global folder for ALL Drive offline files (write-grant SAF picker; default = app cache). Redirect `downloadFullToCache`'s offline-pin destination to the chosen tree (store the `content://` doc uri in `OfflineCopyEntity.localPath` + the DataStore pair — both already store arbitrary path strings); the LRU evictor branches on scheme — `SafStorage.delete` for `content://`, `File.delete` for plain paths. **No multi-account** (single account by design).
- [ ] **Spotify: no location** — the Settings/Downloads UI states Spotify content can't be downloaded (DRM; Connect-only).

---

# PART 5 — Spotify search: re-enable + quality fixes

### Task 5.1: Fix the result-quality defects (do BEFORE un-hiding)
**Files:** `SpotifyProvider.kt`, `CloudViewModel.kt`.
- [ ] `jsonToCloudItem` (`:563-597`): read `artists[]` → set a subtitle/artist string on track (and album) `CloudMediaItem`s so result rows show "Title · Artist", not bare title.
- [ ] Standalone **episode** results: either (a) `SpotifyProvider.listContainer` (`:954-974`) handle `"episode"` → play the single episode, or (b) map episode results as leaves (`isFolder=false`) and route `openItem` to `playTrackOnConnectDevice(spotify:episode:…)`. Verify Connect plays a bare episode URI (`uris:[…]`).
- [ ] Add `artist` to the search `type` list (`:1067`) → artist results appear; tapping drills to top-tracks (already works via the section path).

### Task 5.2: Un-hide the search UI (branch ordering)
**Files:** `CloudBrowserScreen.kt:344-347` + the Spotify-section branch (`:452-635`).
- [ ] Render the search `OutlinedTextField` for Spotify too; ensure the Spotify-search-results branch is ordered BEFORE the section-picker so a non-empty query shows results (today the section branch returns first). Keep iTunes podcast search (Podcasts tab) separate.
- [ ] Note: search → tap → play needs **Premium** (free → 403 PREMIUM_REQUIRED, already surfaced).

---

# PART 6 — Info boxes + Settings copy

### Task 6.1
**Files:** `InfoContent.kt`, `SettingsScreen.kt`.
- [ ] Player info: focus behaviour (pauses on calls; video always pauses; per-scenario audio settings; resumes in PiP).
- [ ] Cloud info: offline podcast/Drive playback; how downloads + storage folders work; Spotify search exists but plays via Connect (Premium) and **cannot be downloaded** (DRM).
- [ ] Settings "Downloads & Offline" descriptions: limits, network policy, default folders, eviction, "applies to signed-in account".

---

## Self-Review

1. **Spec coverage:** call-pause + video/PiP → Part 1; podcast downloads real → Part 2; full management Cloud-tab + global Settings + Downloads view → Part 3; per-podcast + per-account storage → Part 4 (per-Spotify moot, per-Google deferred-with-reason); Spotify search → Part 5; info boxes → Part 6. All covered.
2. **Account reality respected:** Drive storage = ONE global folder (single-account by design — no multi-account built); per-Spotify documented impossible — no false promises.
3. **No regression to Drive offline:** Part 4.3 redirect keeps the existing registries/evictor (schema stores arbitrary paths); only adds a SAF `delete` branch.
4. **Audio-focus fix preserves the per-scenario settings** (not Media3's handler) → no Spotify-OAuth regression.
5. **Migration:** Part 2.1 adds columns only (additive `ALTER TABLE`), bump version.

## Decisions (RESOLVED 2026-06-22)
- Storage paths: per-show podcast folder + ONE global Drive-files folder + Spotify N/A. **No multi-account built.**
- Maximum user control: expose every setting; app-managed defaults are only the starting value (SAF picker → any location).

## Execution order (suggested)
Part 1 (the bug) → Part 2 (downloads real) → Part 4.1 SAF infra → Part 3 (management UI) → Part 4.2/4.3 (storage locations) → Part 5 (Spotify search) → Part 6 (info boxes).
