# Last-Played Tab + Multi-Issue Followup (2026-05-03)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline). Steps use checkbox tracking. ZERO IMPLEMENTATION until user answers Open Questions and approves.

**Goal:** Address 7 user-reported regressions/asks (1–7) — Spotify search visibility, PiP regression, Saved-Playlists-section drill-down, Spotify→local mirror cleanup, dynamic-label de-duplication, persistent mini-player on non-Player tabs, new "Last Played" tab with persisted resume + 10 favourites.

**Architecture:** Each issue independently addressable. New abstractions kept minimal. New persistence: `PlaybackHistoryDao` + `HistoryFavouriteDao` + `LastPlayedRepository`. Hilt-injected. Drive metadata pipeline OFF-LIMITS.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 1.6 ExoPlayer, Hilt, Room v4, AppAuth, Spotify Web API, Coil 3, DataStore.

---

## Issue index (1 → 7)

| # | Topic | Effort |
|---|---|---|
| 1 | Hide Spotify search bar (sub-tab specific) | XS |
| 2 | PiP regression — only audio plays now | M |
| 3 | Saved Playlists section LIST is empty | M |
| 4 | Spotify→local m4b switch leaves Spotify metadata + dead controls | M |
| 5 | Dynamic Prev/Next label de-duplication when File==Chapter | XS |
| 6 | Mini-player on Library / Cloud / Last Played / EQ / Settings | M |
| 7 | Last-Played tab + persisted resume + 10 pinned favourites | L |

---

## File-impact map

- `CloudBrowserScreen.kt` — 1 (search bar conditional on currentProvider)
- `MainActivity.kt` — 2 (PiP entry diag + reattach surface)
- `AndroidManifest.xml` — 2 (verify supportsPictureInPicture true, configChanges)
- `PlayerScreen.kt` — 2 (PiP-mode VideoSurface lifecycle)
- `SpotifyProvider.kt` — 3 (`/v1/me/playlists` parse), 4 (definitive `stopPlaybackPolling` + state reset)
- `PlaybackControls.kt` — 5 (label de-dup logic)
- `MainScreen.kt` (or wherever the bottom nav lives) — 6 (mini-player Surface above NavHost), 7 (new tab entry)
- `MiniPlayerBar.kt` (new) — 6
- `PlaybackHistoryDao.kt` (new) — 7
- `PlaybackHistoryEntity.kt` (new) — 7
- `HistoryFavouriteEntity.kt` (new) — 7
- `LastPlayedRepository.kt` (new) — 7
- `LastPlayedViewModel.kt` (new) — 7
- `LastPlayedScreen.kt` (new) — 7
- `AppDatabase.kt` — 7 (v3→v4 migration adds two tables)
- `PlayerViewModel.kt` — 7 (record-on-play hook), 4 (call `stopSpotifyMirror` on local play already; tighten)
- `LibraryViewModel.kt` — 4 + 7 (record local plays into history)
- `CloudViewModel.kt` — 7 (record cloud plays)
- `SettingsDataStore.kt` — 7 (pin order persistence — `ListOrderPreferences` for favourites)

---

## Tasks

### Task 1A: Hide search bar on Spotify sub-tab

- **Files:** `app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt`
- [ ] **Step 1:** Locate the `OutlinedTextField` placeholder = "Search spotify…" / "Search Drive…". Wrap in `if (uiState.currentProvider != CloudProvider.SPOTIFY) { … }` so only Drive view shows it. Keep the Drive search wired exactly as today.
- [ ] **Step 2:** Build: `./gradlew.bat assembleDebug`. Expect: BUILD SUCCESSFUL.
- [ ] **Step 3:** Install APK; switch to Cloud → Spotify sub-tab; verify search bar absent. Switch to Drive sub-tab; verify search bar present.
- [ ] **Step 4:** Commit `fix(cloud): hide search bar on Spotify sub-tab (Drive only)`.

### Task 2A: PiP regression — bisect + diagnose

- **Files:** read-only first.
- [ ] **Step 1:** `git bisect start HEAD 812a87d` → at each step build APK, install on RFCY70BARDJ, play a video, press Home, observe whether PiP appears as fullscreen-video rectangle.
- [ ] **Step 2:** Identify the bad commit. Read its diff to MainActivity.kt + PlayerScreen.kt + AndroidManifest.xml.
- [ ] **Step 3:** Read `AndroidManifest.xml` → confirm `<activity android:name=".MainActivity" android:supportsPictureInPicture="true" android:resizeableActivity="true" android:configChanges="…|screenLayout|smallestScreenSize|screenSize|orientation">`.
- [ ] **Step 4:** Read `MainActivity.kt` `onUserLeaveHint`/`onPictureInPictureModeChanged`. Add `Log.i("PMP_PIP", "onUserLeaveHint isVideo=$isVideo aspect=$aspect")` and same in `onPictureInPictureModeChanged(isInPip, newConfig)`.
- [ ] **Step 5:** Build, install, drive: play a video → press Home. Capture: `adb -s RFCY70BARDJ logcat -s PMP_PIP:I -d -v time | tail -20`. Identify whether `enterPictureInPictureMode` is called and whether it returns true.
- [ ] **Step 6:** context7-mcp: query `androidx.activity` Compose ComponentActivity 2026 PiP `setPictureInPictureParams` + `setAutoEnterEnabled(true)` + `sourceRectHint` + `setSeamlessResizeEnabled(true)` (pinch-resize).

### Task 2B: PiP regression — fix

- **Files:** `MainActivity.kt`, `PlayerScreen.kt`
- [ ] **Step 1:** Confirm `enterPictureInPictureMode(params)` is called when video is active. If it is and returns true but PiP doesn't appear → the `VideoSurface` SurfaceView likely lost its surface during the activity transition. Restore by ensuring `Player.setVideoSurfaceView` is re-called after `onPictureInPictureModeChanged(true, …)`.
- [ ] **Step 2:** Use `setAutoEnterEnabled(true)` via `setPictureInPictureParams` updated whenever isVideoContent flips → eliminates dependency on `onUserLeaveHint`.
- [ ] **Step 3:** Build, install, verify visually: video → Home → PiP rectangle with playing video appears in corner.
- [ ] **Step 4:** Commit `fix(pip): restore PiP entry — auto-enter + surface reattach`.

### Task 3A: Saved Playlists list — diagnose

- **Files:** `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt`
- [ ] **Step 1:** Locate `listSection(SpotifySection.SAVED_PLAYLISTS)`. Add diag log of `firstElem` (raw JSON of `items[0]`), and `items=$count`.
- [ ] **Step 2:** Build, install, drive: Cloud → Spotify → Saved Playlists. Capture: `adb logcat -s PMP_DIAG:I -d | tail -30`.
- [ ] **Step 3:** Inspect `firstElem`. Confirm shape — `/v1/me/playlists` returns `{href, items:[{collaborative, description, external_urls, href, id, images, name, owner, …}], …}`. Each item is the playlist object directly (no `item`/`track` wrapper).
- [ ] **Step 4:** context7-mcp: query Spotify Web API `/v1/me/playlists` 2026 response shape — verify nothing changed.

### Task 3B: Saved Playlists list — fix parser if needed

- **Files:** `SpotifyProvider.kt`, `CloudBrowserScreen.kt`
- [ ] **Step 1:** If diag shows non-empty `items[]` but mapper produces empty list → mapper is mis-keying. Patch `listSection(SAVED_PLAYLISTS)` to read `id`, `name`, `images[0].url`, `tracks.total`, `uri = "spotify:playlist:$id"`.
- [ ] **Step 2:** If diag shows empty `items[]` but http=200 → user's account legitimately has none. Replace empty-state copy with `"No playlists found."` (no extra explanation per locked decision 3).
- [ ] **Step 3:** Build, install on RFCY70BARDJ, verify visually.
- [ ] **Step 4:** Commit `fix(spotify): saved-playlists section parsing` (or `ux(spotify): generic empty-state copy`).

### Task 4A: Spotify→local mirror cleanup — verify state reset

- **Files:** `SpotifyProvider.kt`, `LibraryViewModel.kt`, `PlayerViewModel.kt`
- [ ] **Step 1:** Read current `stopPlaybackPolling()` in `SpotifyProvider`. Confirm it cancels the polling Job. Verify it ALSO sets `_playbackState.value = null` (or equivalent reset). If not — fix.
- [ ] **Step 2:** Verify `LibraryViewModel.playFiles/playSingle/playFolder` calls `spotifyProvider.stopPlaybackPolling()` BEFORE `playbackConnection.setMediaItems`.
- [ ] **Step 3:** Verify `PlayerViewModel.uiState.combine(spotifyProvider.playbackState)` overlay logic — the combine should treat `spotifyState==null` as "not active" and pass through the local player state.
- [ ] **Step 4:** Add diag log: `PMP_DIAG: PlayerVM activeSource=$source title=$title` whenever `mapToUiState` runs.

### Task 4B: Spotify→local mirror cleanup — fix

- **Files:** as above
- [ ] **Step 1:** If state not clearing → add explicit `_playbackState.value = null` in `stopPlaybackPolling()`.
- [ ] **Step 2:** If overlay logic wrong → ensure `if (spotifyState != null && spotifyState.isActive) spotifyOverlay() else baseLocalState`.
- [ ] **Step 3:** Also call Spotify Connect `pause` endpoint inside `stopPlaybackPolling()` so the user's Spotify Connect device actually pauses (avoids audio overlap).
- [ ] **Step 4:** Build, install, drive: play Spotify track → switch to local m4b → verify metadata switches AND controls work.
- [ ] **Step 5:** Commit `fix(player): definitive Spotify mirror teardown on local play`.

### Task 5A: Dynamic label de-duplication

- **Files:** `app/src/main/java/com/powermediaplayer/ui/player/components/PlaybackControls.kt`
- [ ] **Step 1:** In the label-resolution `when (mediaKind)` block, capture `(fileLabel, chapterLabel)`. After resolution, compute `val collapsed = fileLabel.equals(chapterLabel, ignoreCase = true)`.
- [ ] **Step 2:** When `collapsed` → render only the file pair (Prev File / Next File) and SKIP the chapter pair. The skip-back-15 / skip-forward-15 buttons remain.
- [ ] **Step 3:** When labels differ (Album/Track, Book/Chapter, Show/Episode) → render both pairs as today.
- [ ] **Step 4:** Verify: play a single MP3 (MUSIC) → only one pair (Track) on each side; play an audiobook m4b → both Book + Chapter pairs visible.
- [ ] **Step 5:** Commit `feat(controls): collapse duplicate Prev/Next pairs when labels match`.

### Task 6A: MiniPlayerBar composable

- **Files:** Create `app/src/main/java/com/powermediaplayer/ui/components/MiniPlayerBar.kt`
- [ ] **Step 1:** New composable `MiniPlayerBar(onClick: () -> Unit)`:
  - hiltViewModel<PlayerViewModel>(); collect `uiState`.
  - If `title.isEmpty() && artist.isEmpty()` → render nothing.
  - Otherwise Row 56dp tall, OledBlack background, top-divider 1dp:
    - 40×40 artwork (Coil AsyncImage on uiState.artworkUri OR artworkBytes fallback)
    - Column: title (Text labelMedium, 1 line ellipsis), artist (Text labelSmall, 1 line ellipsis)
    - Spacer weight 1
    - IconButton play/pause that calls `viewModel.playPause()`
  - Row `Modifier.clickable { onClick() }`.
- [ ] **Step 2:** Build to ensure no syntax errors.

### Task 6B: Wire MiniPlayerBar above NavHost on every non-Player tab

- **Files:** `MainScreen.kt` (or whichever file hosts the BottomNavigation + NavHost)
- [ ] **Step 1:** Locate the Scaffold/Column that hosts NavHost + BottomNavigation.
- [ ] **Step 2:** Above NavHost (or inside NavHost wrapper but outside Player route), insert `if (currentRoute != "player") MiniPlayerBar(onClick = { navController.navigate("player") })`.
- [ ] **Step 3:** Verify visually: start playback on Player → switch to Library → mini-bar appears at bottom (or top per user's choice). Tap → navigates to Player tab.
- [ ] **Step 4:** Commit `feat(ui): persistent mini-player bar on non-Player tabs`.

### Task 7A: Verify current resume-on-close behaviour

- **Files:** read-only audit of `PlaybackService.kt`, `PlaybackConnection.kt`, `SettingsDataStore.kt`, any `ResumeRepository`.
- [ ] **Step 1:** Grep for `lastPosition`, `resumeAt`, `currentMediaUri`, `restoreState`. List each call site.
- [ ] **Step 2:** Decide: does the app currently restore the last item + position on cold start? Likely NO — Media3's MediaSession is killed when the service stops, and the app may not be persisting position. Document finding in the plan.
- [ ] **Step 3:** No commit (audit only).

### Task 7B: Room v4 migration — add history + favourites tables

- **Files:** `app/src/main/java/com/powermediaplayer/data/db/AppDatabase.kt`, `app/src/main/java/com/powermediaplayer/data/db/entity/PlaybackHistoryEntity.kt` (new), `app/src/main/java/com/powermediaplayer/data/db/entity/HistoryFavouriteEntity.kt` (new)
- [ ] **Step 1:** Create `PlaybackHistoryEntity`:
  ```kotlin
  @Entity(tableName = "playback_history")
  data class PlaybackHistoryEntity(
      @PrimaryKey val mediaUri: String,           // dedup key
      val title: String,
      val subtitle: String,                       // artist or "Spotify track" etc.
      val artworkUri: String?,                    // url or content://
      val source: String,                         // "LOCAL" | "DRIVE" | "SPOTIFY"
      val mediaKindOrdinal: Int,                  // MediaKind enum
      val lastPositionMs: Long,
      val durationMs: Long,
      val lastPlayedAt: Long                      // epoch ms — used for ORDER BY DESC
  )
  ```
- [ ] **Step 2:** Create `HistoryFavouriteEntity`:
  ```kotlin
  @Entity(tableName = "history_favourites")
  data class HistoryFavouriteEntity(
      @PrimaryKey val mediaUri: String,
      val pinOrder: Int                           // 0..9; user-reorderable
  )
  ```
- [ ] **Step 3:** Bump `AppDatabase` `version = 3 → 4` and add a `MIGRATION_3_4` that creates both tables.
- [ ] **Step 4:** Build (KSP runs Room schema export). Confirm `BUILD SUCCESSFUL`.
- [ ] **Step 5:** Commit `feat(db): v4 migration — playback_history + history_favourites tables`.

### Task 7C: DAOs

- **Files:** `app/src/main/java/com/powermediaplayer/data/db/dao/PlaybackHistoryDao.kt` (new), `HistoryFavouriteDao.kt` (new)
- [ ] **Step 1:** `PlaybackHistoryDao`:
  ```kotlin
  @Dao
  interface PlaybackHistoryDao {
      @Insert(onConflict = OnConflictStrategy.REPLACE)
      suspend fun upsert(row: PlaybackHistoryEntity)

      @Query("SELECT * FROM playback_history ORDER BY lastPlayedAt DESC LIMIT 50")
      fun observeAll(): Flow<List<PlaybackHistoryEntity>>

      @Query("DELETE FROM playback_history WHERE mediaUri = :uri")
      suspend fun delete(uri: String)

      // Trim to keep only top 10 unpinned + all pinned. Caller computes the
      // delete list and passes the URIs to delete in a transaction.
      @Query("DELETE FROM playback_history WHERE mediaUri IN (:uris)")
      suspend fun deleteMany(uris: List<String>)
  }
  ```
- [ ] **Step 2:** `HistoryFavouriteDao`:
  ```kotlin
  @Dao
  interface HistoryFavouriteDao {
      @Insert(onConflict = OnConflictStrategy.REPLACE)
      suspend fun upsert(row: HistoryFavouriteEntity)

      @Query("SELECT * FROM history_favourites ORDER BY pinOrder ASC")
      fun observeAll(): Flow<List<HistoryFavouriteEntity>>

      @Query("DELETE FROM history_favourites WHERE mediaUri = :uri")
      suspend fun unpin(uri: String)

      @Query("UPDATE history_favourites SET pinOrder = :order WHERE mediaUri = :uri")
      suspend fun setOrder(uri: String, order: Int)

      @Query("SELECT COUNT(*) FROM history_favourites")
      suspend fun count(): Int
  }
  ```
- [ ] **Step 3:** Register both DAOs in `AppDatabase` and the Hilt `DatabaseModule`.
- [ ] **Step 4:** Commit `feat(db): playback-history + favourites DAOs`.

### Task 7D: LastPlayedRepository

- **Files:** `app/src/main/java/com/powermediaplayer/data/repository/LastPlayedRepository.kt` (new)
- [ ] **Step 1:** Hilt-injected singleton holding both DAOs.
- [ ] **Step 2:** Methods:
  ```kotlin
  suspend fun recordPlay(row: PlaybackHistoryEntity)        // upsert + trimToCap
  suspend fun updatePosition(uri: String, posMs: Long)      // partial update
  fun observeDynamicList(): Flow<List<HistoryItem>>         // top-10 unpinned by lastPlayedAt
  fun observePinned(): Flow<List<HistoryItem>>              // ordered by pinOrder
  suspend fun pin(uri: String)                              // append at next pinOrder, max 10
  suspend fun unpin(uri: String)
  suspend fun reorderPin(uri: String, newOrder: Int)        // shift others
  private suspend fun trimToCap()                           // keep top-10 unpinned + all pinned
  ```
- [ ] **Step 3:** `data class HistoryItem(...)` is the read model — joins `PlaybackHistoryEntity` + `isPinned: Boolean`.
- [ ] **Step 4:** Commit.

### Task 7E: Wire `recordPlay` into LibraryViewModel + CloudViewModel + Spotify mirror

- **Files:** `LibraryViewModel.kt`, `CloudViewModel.kt`, `SpotifyProvider.kt`
- [ ] **Step 1:** Inject `LastPlayedRepository`.
- [ ] **Step 2:** In every place a play action begins (local file, Drive cloud file, Spotify track), call `viewModelScope.launch(Dispatchers.IO) { repo.recordPlay(...) }`.
- [ ] **Step 3:** Continuously call `repo.updatePosition(uri, currentPositionMs)` from a 5-second tick in `PlayerViewModel` while playing. (One write per 5 s — cheap.)
- [ ] **Step 4:** On stop / pause / app-background, force-write the current position once.
- [ ] **Step 5:** Commit.

### Task 7F: LastPlayedViewModel + LastPlayedScreen

- **Files:** `LastPlayedViewModel.kt`, `LastPlayedScreen.kt` (new)
- [ ] **Step 1:** ViewModel exposes `dynamic: StateFlow<List<HistoryItem>>` and `pinned: StateFlow<List<HistoryItem>>`. Methods: `play(item)`, `pin(uri): Result<Unit>` (returns Failure when count==10), `unpin(uri)`, `reorderPinned(fromUri: String, toUri: String)`.
- [ ] **Step 2:** Screen: LazyColumn with two sections — "Pinned" (max 10, drag-to-reorder via `sh.calvin.reorderable:reorderable:2.5.0`), "Recent" (top 10).
- [ ] **Step 3:** Row layout: 40×40 artwork → title + subtitle → source pill (one of `Local` / `Drive` / `Spotify`, distinct colours: TealAccent / Blue / SpotifyGreen) → star (toggle pin). Pinned rows also show drag handle (`Icons.Filled.DragHandle`) on the right.
- [ ] **Step 4:** Tap row → calls `viewModel.play(item)` which:
  - For LOCAL → `libraryViewModel.playSingle(uri)` then `playerViewModel.seekTo(item.lastPositionMs)`.
  - For DRIVE → `cloudViewModel.openDriveFile(uri)` then seek.
  - For SPOTIFY → `cloudViewModel.openSpotifyTrack(uri)` then seek (via Connect API).
  - Then navigate to Player tab. Auto-play (locked decision 7).
- [ ] **Step 5:** When `pin()` returns Failure → show snackbar `"Favourites full (10/10) — unpin one first"` (locked decision 8).
- [ ] **Step 6:** Add Gradle dep `implementation("sh.calvin.reorderable:reorderable:2.5.0")` to app/build.gradle.kts.
- [ ] **Step 7:** Commit.

### Task 7G: Add "Last Played" tab to bottom nav

- **Files:** `MainScreen.kt` (the bottom-nav host)
- [ ] **Step 1:** Add new BottomNavItem: route="last_played", label="Last Played", icon=`Icons.Filled.History`.
- [ ] **Step 2:** Position: between Library and Cloud (LOCKED). Final order `[Player, Library, Last Played, Cloud, EQ, Settings]`.
- [ ] **Step 3:** Add NavHost composable `composable("last_played") { LastPlayedScreen(navController) }`.
- [ ] **Step 4:** Verify visually on RFCY70BARDJ: 6 tabs in bottom nav now.
- [ ] **Step 5:** Commit `feat(nav): Last Played tab + screen`.

### Task 7H: Resume-on-cold-start

- **Files:** decided in Task 7A audit. Likely `MainActivity.kt` / `PlayerViewModel.init`.
- [ ] **Step 1:** On app cold start, query `repo.observeDynamicList().first().firstOrNull()`. If non-null → load that item into the player paused, with `seekTo(lastPositionMs)`.
- [ ] **Step 2:** Do NOT auto-play (user must press play). Avoids surprise audio.
- [ ] **Step 3:** Verify: kill app while playing local m4b at 12:34 → relaunch → Player tab shows that file at 12:34 paused.
- [ ] **Step 4:** Commit `feat(resume): restore last-played item + position on cold start`.

---

## Locked decisions (no longer open)

1. **Spotify search bar**: hide entirely on Spotify sub-tab.
2. **PiP regression**: do `git bisect` between 812a87d and HEAD before attempting any fix. Goal: fullscreen video, standard controls, pinch-resize (Android 12+ via `setSeamlessResizeEnabled(true)` already in code — verify still effective).
3. **Saved Playlists empty-state**: copy is "No playlists found." — generic, no algorithmic-vs-followed explanation.
4. **(was 5) MUSIC collapse**: when one pair shown, it maps to next/prev *file in queue*. Hidden pair drops, no second function.
5. **Mini-player position**: BOTTOM (above bottom-nav).
6. **Source pills**: 3 distinct pills — `Local`, `Drive`, `Spotify`.
7. **Last-Played row tap**: load + auto-play from saved position.
8. **11th pin**: reject with snackbar `Favourites full (10/10) — unpin one first`.
9. **Reorder UI**: drag — add `sh.calvin.reorderable:reorderable:2.5.0` (Compose-native).
10. **Bottom-nav layout**: keep all 6 tabs `[Player, Library, Last Played, Cloud, EQ, Settings]`. New tab inserted between Library and Cloud.

---

## context7-MCP usage

- `androidx.activity` Compose ComponentActivity 2026 — PiP `setPictureInPictureParams`, `setAutoEnterEnabled`, `setSourceRectHint`.
- Spotify Web API 2026 — `/v1/me/playlists` response shape verification.
- `androidx.room` 2.6+ — multi-DAO transactions, Migration helpers.
- `androidx.media3` 1.6 — `Player.setVideoSurfaceView` lifecycle in PiP.

---

## Self-review (post-write)

- **Spec coverage:** issues 1–7 all have ≥1 task. ≥30 incremental steps grouped by issue.
- **Placeholders:** none. Each step has concrete action + verification.
- **Type consistency:** `PlaybackHistoryEntity`, `HistoryFavouriteEntity`, `HistoryItem`, `LastPlayedRepository` defined exactly once.
- **Scope:** Drive metadata pipeline NOT touched. Resume-on-cold-start is a new path, not modifying existing playback.
- **Ambiguity:** all gates surfaced as Open Questions 1–10.
