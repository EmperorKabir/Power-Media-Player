# Podcast Bug-Fixes + UI Overhaul — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. **Plan only — nothing here has been implemented.**

**Goal:** Fix the five evidence-confirmed podcast defects (recents/resume, artwork, RSS ingestion, scroll gap) and overhaul the Podcasts section UI (artwork, listened/in-progress markers, layout).

**Architecture:** The podcast play path currently records nothing and relies on a gated fallback; we route it through the same `recordPlay` the Library/Cloud tabs use (proven to resume). RSS ingestion gets a browser User-Agent + honest error surfacing. The episode list drops its fixed-height nested `LazyColumn` for content-wrapping + dynamic, mini-player- and inset-aware bottom padding. The UI overhaul reuses the existing Coil 3 loader and the now-recorded history rows for progress.

**Tech Stack:** Kotlin, Media3 1.6.0, Room 2.7.1, Hilt 2.54, Jetpack Compose (Material3), OkHttp 4.12.0, Coil 3.1.0.

---

## Evidence basis (do not re-investigate — see the 2026-06-16 investigation)

- **#4/#5** `PodcastsViewModel.playEpisode` (`PodcastsSection.kt:80‑98`) calls only `setMediaItems` + `setPlayed`; the **only** recents writer is the 5s tick gated on `currentSessionId==null` (`PlaybackSessionCoordinator.kt:398‑422`). Cold-start `adoptSession` (`:586/679/736`) closes that gate for the process → mid-session switches (Fighting Cock `73b5aa70`) never record. Cold-start query `mostRecent()` = `ORDER BY lastPlayedAt DESC` (`PlaybackHistoryDao.kt:30`) is **correct**; wrong-resume is purely the missing write. DiagLog `deeplogs/podcast_diag.txt` lines 36/57/140 confirm.
- **#3** `RssFeedParser` parses only **show** artwork (`:80‑84,113`), `PodcastEpisodeEntity` has **no artwork field** (`PodcastShowEntity.kt:21‑31`), and `playEpisode` builds `MediaMetadata` with **title only** (`:89‑92`) → tick writes `artworkUri=null`.
- **#1** Manual add and search-subscribe share `addByUrl → RssFeedParser.fetch` (`PodcastsSection.kt:139‑141,100‑118`); same OkHttp client, **no User-Agent** (`RssFeedParser.kt:34`, `SharedHttp.kt:26‑36`), default `okhttp/4.12.0`. Non-2xx → silent null → `"Couldn't parse feed at $url"` (`:107`). OkHttp follows redirects + gzip transparently (Context7-confirmed) → those ruled out. Search works because Apple returns the canonical `feedUrl`.
- **#6** Episode/subscription list is a `LazyColumn` pinned to `Modifier.height(360.dp)` (`PodcastsSection.kt:249`) nested in the outer scroll list → dead gap when content < 360 dp; mini-player is a host-scaffold sibling below the screen.
- **#2** Speed delay is **inherent** (Sonic buffer drain + A2DP transport) — apply path is synchronous (`PlayerViewModel:714` → `PlaybackConnection:377`); **not fixed here** (see Task A6).
- **WorkManager** crash `androidx.work.workdb … no_backup doesn't exist` (logcat 06-17) is independent of recents; single unreproduced occurrence.

**Context7 confirmations used:** OkHttp `addInterceptor` adds `User-Agent` for all requests + follows redirects once (`/websites/square_github_io_okhttp`); Compose dynamic bottom space via `WindowInsets.navigationBars` + `windowInsetsBottomHeight`/`contentPadding` (`/websites/developer_android_develop_ui_compose`); Coil 3 `AsyncImage` already used in-app (`LastPlayedScreen.kt:379`, `MiniPlayerBar.kt:111`) with a `SingletonImageLoader.Factory` + `coil-network-okhttp` (`PowerMediaPlayerApp.kt:33,51`).

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `ui/podcast/PodcastsSection.kt` | Podcasts VM + UI | Modify — inject repo/dao; record on play; artwork; dynamic list; row redesign |
| `podcast/RssFeedParser.kt` | RSS fetch + parse | Modify — browser UA, status surfacing, normalisation |
| `data/db/dao/PodcastDao.kt` | Podcast queries | Modify — add counts + episode-progress join query |
| `data/db/entity/PodcastShowEntity.kt` | Entities | Modify (Task B4 only) — optional episode progress fields |
| `ui/podcast/PodcastArtwork.kt` | Reusable artwork thumb composable | **Create** |
| `service/PlaybackSessionCoordinator.kt` | Cold-start restore | Read-only verify (Task A1 step) — no change expected |
| `data/repository/LastPlayedRepository.kt` | Recents writes | No change — reuse `recordPlay` |
| `app/src/test/.../podcast/RssFeedParserTest.kt` | Parser tests | Modify — add UA + status tests |
| `app/src/test/.../podcast/PodcastRecordPlayTest.kt` | Recording test | **Create** |

---

# PART A — Correctness fixes

## Task A1: Record podcast plays in Recents, with artwork (fixes #3, #4, #5)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/podcast/PodcastsSection.kt:63‑98`
- Test: `app/src/test/java/com/powermediaplayer/podcast/PodcastRecordPlayTest.kt` (create)

**Why this works:** Library (`LibraryViewModel:655→658`) and Cloud (`CloudViewModel:242→251`) call `recordPlay` directly and resume correctly. Podcasts are the only path that doesn't. `source="LOCAL"` + a remote HTTP URI is **proven** to cold-start-resume (DiagLog: Extra Inch `ad333117` resumed that way). We mirror `recordCloudPlay`.

- [ ] **Step 1 — Inject the repository + look up show artwork.** Replace the constructor of `PodcastsViewModel` (`PodcastsSection.kt:63‑66`):

```kotlin
@HiltViewModel
class PodcastsViewModel @Inject constructor(
    private val podcastDao: PodcastDao,
    private val playbackConnection: PlaybackConnection,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository
) : ViewModel() {
```

- [ ] **Step 2 — Record the play + carry artwork.** Replace `playEpisode` (`PodcastsSection.kt:80‑98`):

```kotlin
fun playEpisode(episode: PodcastEpisodeEntity) {
    val uri = android.net.Uri.parse(episode.audioUrl)
    viewModelScope.launch(Dispatchers.IO) {
        // Show artwork: episode entity has none, so resolve the show's image.
        val show = podcastDao.getShow(episode.feedUrl)
        val artUri = show?.artworkUrl
        val item = androidx.media3.common.MediaItem.Builder()
            .setMediaId(episode.audioUrl)
            .setUri(uri)
            .setRequestMetadata(
                androidx.media3.common.MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri).build()
            )
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(show?.title ?: "")
                    .apply { if (!artUri.isNullOrBlank()) setArtworkUri(android.net.Uri.parse(artUri)) }
                    .build()
            )
            .build()
        withContext(Dispatchers.Main) { playbackConnection.setMediaItems(listOf(item), 0) }
        // Record into Recents DIRECTLY (not via the gated 5s tick), so a
        // mid-session switch is the most-recent row and cold-start resumes it.
        runCatching {
            lastPlayedRepo.recordPlay(
                com.powermediaplayer.data.db.entity.PlaybackHistoryEntity(
                    mediaUri = episode.audioUrl,
                    title = episode.title,
                    subtitle = show?.title ?: "Podcast",
                    artworkUri = artUri,
                    source = "LOCAL",            // proven to cold-start-resume a remote URL
                    mediaKindOrdinal = 0,
                    lastPositionMs = 0L,
                    durationMs = episode.durationS * 1000L,
                    lastPlayedAt = System.currentTimeMillis()
                )
            )
        }
        // Mark "started" — completion semantics are fixed in Task B2.
        podcastDao.setPlayed(episode.guid, true)
    }
}
```

- [ ] **Step 3 — Write the test.** Create `PodcastRecordPlayTest.kt`: a fake `LastPlayedRepository` capturing `recordPlay`; assert that `playEpisode` calls it once with `mediaUri == episode.audioUrl`, `artworkUri == show.artworkUrl`, `title == episode.title`. (Use the existing Robolectric pattern from `RssFeedParserTest.kt`.)

- [ ] **Step 4 — Run:** `.\gradlew.bat :app:testDebugUnitTest --tests "*PodcastRecordPlayTest*"` → PASS.

- [ ] **Step 5 — Verify cold-start restore handles the podcast row (READ-ONLY check, no edit expected).** Read `PlaybackSessionCoordinator.kt` around `:558‑600` + the restore builder. Confirm a `source="LOCAL"` row with an `http(s)` `mediaUri` builds a `MediaItem` from the URI as-is (it does today — Extra Inch resumed). If — and only if — it routes LOCAL through a MediaStore/file-only resolver that would reject `http`, add a follow-up task; otherwise no change.

- [ ] **Step 6 — Device verify:** play Show A episode → switch to Show B episode → close → reopen → **Show B resumes** (was Show A). Recents shows both with covers.

- [ ] **Step 7 — Commit:** `git commit -m "fix(podcast): record episode plays in Recents with artwork (fixes wrong-resume + blank thumbnail)"`

---

## Task A2: RSS fetch robustness — browser UA + honest errors (fixes #1)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/podcast/RssFeedParser.kt:18‑41`
- Test: `app/src/test/java/com/powermediaplayer/podcast/RssFeedParserTest.kt` (modify)

**Why:** the fetch sends `okhttp/4.12.0` with no UA and swallows the HTTP status. A browser UA defeats the common Megaphone/CDN 403; surfacing the status turns "Couldn't parse" into an actionable message.

- [ ] **Step 1 — Add a User-Agent interceptor (scoped to the parser client only — does NOT touch SharedHttp, so Drive/Spotify behaviour is unchanged).** Replace `RssFeedParser.kt:21‑25`:

```kotlin
companion object {
    private const val UA =
        "Mozilla/5.0 (Linux; Android) PowerMediaPlayer/1.0 (podcast feed reader)"
    private val defaultClient = com.powermediaplayer.util.SharedHttp.base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", UA)
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .build()
            )
        }
        .build()
    private val RFC822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
}
```

- [ ] **Step 2 — Surface the HTTP status (return a typed result, not bare null).** Change `fetch` (`:33‑41`) to capture the status so the VM can show it:

```kotlin
sealed interface FetchResult {
    data class Ok(val show: PodcastShowEntity, val episodes: List<PodcastEpisodeEntity>) : FetchResult
    data class HttpError(val code: Int) : FetchResult
    data class NotFeed(val reason: String) : FetchResult   // 2xx but unparseable / HTML
    data object Network : FetchResult
}

fun fetchResult(feedUrl: String): FetchResult {
    val req = Request.Builder().url(feedUrl).build()
    return runCatching {
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return FetchResult.HttpError(resp.code)
            val xml = resp.body?.string() ?: return FetchResult.NotFeed("empty body")
            val parsed = parse(feedUrl, xml) ?: return FetchResult.NotFeed("not RSS")
            FetchResult.Ok(parsed.first, parsed.second)
        }
    }.getOrElse { FetchResult.Network }
}

// Back-compat shim for existing callers/tests.
fun fetch(feedUrl: String): Pair<PodcastShowEntity, List<PodcastEpisodeEntity>>? =
    (fetchResult(feedUrl) as? FetchResult.Ok)?.let { it.show to it.episodes }
```

- [ ] **Step 3 — VM uses the typed result for a useful message.** In `addByUrl` (`PodcastsSection.kt:100‑118`) replace the null branch:

```kotlin
when (val r = parser.fetchResult(rssUrl)) {
    is RssFeedParser.FetchResult.Ok -> {
        podcastDao.upsertShow(r.show); podcastDao.upsertEpisodes(r.episodes)
        setStatus("Subscribed: ${r.show.title} (${r.episodes.size} episodes)")
    }
    is RssFeedParser.FetchResult.HttpError ->
        setStatus("Feed returned HTTP ${r.code}. Try searching the show name instead.")
    is RssFeedParser.FetchResult.NotFeed ->
        setStatus("That URL isn't an RSS feed (${r.reason}). Paste the RSS feed or search by name.")
    RssFeedParser.FetchResult.Network ->
        setStatus("Network error fetching the feed. Check your connection.")
}
```
(`setStatus` = the existing `withContext(Main){ _status.value = … }`.)

- [ ] **Step 4 — Optional resolution helpers (additive; behind the same add action).** If the pasted URL is an Apple Podcasts page containing `id<digits>`, resolve via the existing iTunes lookup before failing:
```kotlin
// in addByUrl, before fetchResult, if host is podcasts.apple.com and an id is present:
//   val feed = itunes.lookupFeedUrl(appleId)  // new 1-line method on ITunesPodcastSearch using /lookup?id=
//   if (feed != null) return addByUrl(feed)
```
Add `ITunesPodcastSearch.lookupFeedUrl(id: String): String?` hitting `https://itunes.apple.com/lookup?id=$id` and reading `results[0].feedUrl`. (Spotify show pages have no public RSS — leave rejected with the NotFeed message.)

- [ ] **Step 5 — Tests.** In `RssFeedParserTest.kt`: (a) a `MockWebServer` that 403s without a browser UA and 200s with it → assert `fetchResult` returns `Ok` (UA applied); (b) a 404 → `HttpError(404)`; (c) an HTML body with 200 → `NotFeed`. Run `.\gradlew.bat :app:testDebugUnitTest --tests "*RssFeedParserTest*"` → PASS.

- [ ] **Step 6 — Commit:** `git commit -m "fix(podcast): browser UA + typed fetch errors for RSS ingestion (Megaphone feeds), Apple-id resolution"`

---

## Task A3: Dynamic, mini-player-aware episode list (fixes #6)

**Files:** Modify `app/src/main/java/com/powermediaplayer/ui/podcast/PodcastsSection.kt:245‑292`, and host `CloudBrowserScreen.kt:386‑411`.

**Requirement (user):** the spacing must be **dynamic** across display types AND whether the mini-player is active — no hardcoded `360.dp`.

**Approach (Context7-backed):** (1) the episodes/shows list must not be a fixed-height nested `LazyColumn`; (2) the bottom space must derive from `WindowInsets.navigationBars` + the mini-player height **only when active**.

- [ ] **Step 1 — Kill the fixed height; render shows as items of the OUTER list.** The whole `PodcastsSection` is one `item {}` in `CloudBrowserScreen`'s outer `LazyColumn` (`:406`). Convert `PodcastsSection` from "a Column containing an inner `LazyColumn(height=360.dp)`" to a `LazyListScope` extension so its rows join the outer scroll (no nesting, no fixed height):

```kotlin
// New signature — called from CloudBrowserScreen's LazyColumn body:
fun LazyListScope.podcastsSection(vm: PodcastsViewModel, shows: List<PodcastShowEntity>, ...) {
    item { /* header + add/search field + iTunes results + status */ }
    items(shows, key = { it.feedUrl }) { show -> PodcastShowRow(show, vm) /* + expanded episodes */ }
}
```
If a full `LazyListScope` refactor is too invasive in one step, the minimal interim fix is: replace `Modifier.height(360.dp)` (`:249`) with `Modifier.heightIn(max = 560.dp)` **and** wrap the episodes (`EpisodeList`, `:360`) so the box wraps content — but the **preferred** end state is the flattened list above (no nested lazy scroll).

- [ ] **Step 2 — Dynamic bottom padding on the OUTER list.** In `CloudBrowserScreen.kt` where the outer `LazyColumn(Modifier.fillMaxSize())` is declared (`:386`), set `contentPadding` so the last row clears the mini-player + nav bar, and collapses when the mini-player is absent:

```kotlin
val navBars = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
val miniPlayerActive by playerVm.miniPlayerVisible.collectAsState()   // existing/derived visibility
val miniH = if (miniPlayerActive) 72.dp else 0.dp                     // mini-player bar height
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = navBars + miniH + 8.dp)
) { /* … podcastsSection(...) … */ }
```
`navigationBars` adapts per device/orientation/foldable; `miniH` is 0 when nothing is playing → no gap. (If a shared mini-player visibility flow doesn't exist, derive it from `playbackConnection.playerState` `hasMedia`.)

- [ ] **Step 3 — Device verify (form-factor matrix).** On the Z Fold6: folded (compact) + unfolded (expanded) + landscape, with and without something playing. Confirm: list scrolls naturally, last episode sits just above the mini-player (no large gap), nothing hidden behind it. Screenshot each.

- [ ] **Step 4 — Commit:** `git commit -m "fix(podcast): episode list scrolls naturally with dynamic mini-player/inset-aware padding (no fixed 360dp)"`

---

## Task A4: WorkManager crash hardening (independent; repro-gated)

**Files:** `app/src/main/java/com/powermediaplayer/podcast/PodcastSyncWorker.kt`, `PowerMediaPlayerApp.kt:114‑116`, possibly `AppModule`/`AndroidManifest`.

**Evidence gate (no-guesswork):** the `no_backup doesn't exist` crash is a single 06-17 occurrence, absent from the 06-19 session. **Do not blind-fix.**

- [ ] **Step 1 — Reproduce first.** Re-test on a debug build with diagnostic logging; trigger podcast auto-sync (subscribe with `autoDownload`/`notifyOnNewEpisode`, background the app). Pull logs; confirm whether `androidx.work.workdb` open still fails.
- [ ] **Step 2 — If it reproduces:** ensure WorkManager uses **on-demand initialization** via a `Configuration.Provider` (the app already has `hilt-work`); confirm the default `WorkManagerInitializer` provider isn't both removed *and* unreplaced (the classic `no_backup` cause). Verify `Application` implements `Configuration.Provider` with the Hilt `WorkerFactory`. Add a defensive `runCatching` around the enqueue (already present at `:114‑116`) — keep.
- [ ] **Step 3 — If it does NOT reproduce:** record as a transient (likely a one-off corrupted profile state); add a log marker and close. No code change.
- [ ] **Step 4 — Commit only if a real fix landed.**

---

## Task A5: #2 speed delay — documented won't-fix (no code) + optional log label

- [ ] **Step 1 — Document** in `TASKS.md` that #2 is inherent (Sonic buffer drain + A2DP transport; apply path is synchronous). No fix without risking dropouts. **Context7 `/androidx/media` confirmed:** `getPlaybackParameters()` only echoes the SET value; `onPlaybackParametersChanged` fires on internal apply (not when audible); there is **no output-latency / AudioSink-to-speaker API**. So there is no signal to "re-enable when ready" — the control stays live (do NOT gate/disable it).
- [ ] **Step 2 — (Optional, cosmetic)** correct the diag string `"slider speed"` → `"speed change"` in `PlayerViewModel.kt:711` (it's a dropdown, not a slider). One-line, no behaviour change.

---

## Task A7: Readiness-gate the chapter control during metadata load (+ verify A-B / bookmark)

**Files:** Modify the chapter affordance in `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt` (and/or the chapter button composable). Read-only: `PlayerViewModel.kt:1396‑1432` (`ControlsEnabledState`), `PlayerUiState.kt:87` (`cloudFetchInProgress`), `ChapterPickerDialog.kt`.

**Evidence + Context7:** seek/skip/sliders are ALREADY correctly readiness-gated on `hasMedia && isSeekable && duration>0` (`PlayerViewModel.kt:1407‑1432`) = Media3 `isCurrentMediaItemSeekable()` + `duration != C.TIME_UNSET` (Context7-confirmed reliable). Speed/volume/brightness/EQ correctly stay live (parameters/independent; speed has no audible signal — A5). The **one gap**: cloud/Drive audiobook chapters parse AFTER the file loads; the app tracks this with `cloudFetchInProgress` (`PlayerUiState.kt:87`) but the chapter affordance is gated only on `hasChapters`, so it reads "no chapters / Tracks" prematurely until they pop in.

- [ ] **Step 1 — Locate the chapter affordance.** In `PlayerScreen.kt`, find the control that opens `ChapterPickerDialog` (its icon/label + any `enabled`/label conditioned on `hasChapters`). Note current behaviour.
- [ ] **Step 2 — Add a transient loading state.** While `uiState.cloudFetchInProgress && !uiState.hasChapters`, present the chapter control as "Loading chapters…" (greyed + small spinner/indicator) instead of the empty/"Tracks" label; restore to normal once `hasChapters` becomes true OR `cloudFetchInProgress` ends. Pure presentation — no playback wiring change.
- [ ] **Step 3 — Verify A-B / bookmark gating (read-only first).** Confirm the A-B-loop "set A/B" action and bookmark-add derive from a known position (only reachable when `trackSlider` is enabled / `duration>0`). If — and only if — either can fire at position 0 before `READY`, gate it on `controls.trackSlider`. Otherwise no change.
- [ ] **Step 4 — Build green; device verify:** open a Drive audiobook → while chapters load, the chapter control shows "loading", then populates. Normal local files with embedded chapters show chapters immediately (no regression).
- [ ] **Step 5 — Commit:** `git commit -m "feat(player): chapter control shows a loading state while cloud chapters parse"`

---

# PART B — Podcasts UI overhaul

**Assessment of the current UI (evidence, `PodcastsSection.kt`):** no artwork anywhere (`show.artworkUrl` parsed but never shown); show rows print the **raw feed URL** (`:269`) — noise; the only "listened" cue is greyed title text (`:380`) and `isPlayed` is set on **tap, not completion** (`:96`) so everything reads as played; episodes capped at 15 with no count/None of the per-show artwork; add/search results have no artwork. Goal: a recognisable, scannable podcast browser.

## Task B1: Reusable artwork thumbnail + show-row redesign

**Files:** Create `app/src/main/java/com/powermediaplayer/ui/podcast/PodcastArtwork.kt`; modify show row in `PodcastsSection.kt:251‑282`; add count queries to `PodcastDao.kt`.

- [ ] **Step 1 — Artwork composable** (mirrors `LastPlayedScreen.kt:379` Coil usage; network loader already configured):

```kotlin
@Composable
fun PodcastArtwork(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(8.dp)).background(SurfaceElevated)) {
        if (!url.isNullOrBlank()) {
            coil3.compose.AsyncImage(
                model = coil3.request.ImageRequest.Builder(LocalContext.current)
                    .data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.Podcasts, null, tint = TextTertiary,
                modifier = Modifier.align(Alignment.Center).size(size * 0.5f))
        }
    }
}
```

- [ ] **Step 2 — DAO counts.** Add to `PodcastDao.kt`:
```kotlin
@Query("SELECT COUNT(*) FROM podcast_episodes WHERE feedUrl = :url")
fun observeEpisodeCount(url: String): Flow<Int>
@Query("SELECT COUNT(*) FROM podcast_episodes WHERE feedUrl = :url AND isPlayed = 0")
fun observeUnplayedCount(url: String): Flow<Int>
```

- [ ] **Step 3 — Show row.** Replace the row (`:251‑282`): leading `PodcastArtwork(show.artworkUrl, 56.dp)`, title, and a supporting line `"$episodeCount episodes · $unplayed unplayed"` **instead of the raw feed URL**; keep the unsubscribe affordance (move to an overflow/long-press to declutter, optional). Expand chevron indicates tappable.

- [ ] **Step 4 — Device verify + commit:** covers render for subscribed shows; counts correct. `git commit -m "feat(podcast): show rows with artwork + episode/unplayed counts"`

## Task B2: Episode-row redesign + correct listened/in-progress markers

**Files:** modify `EpisodeList` (`PodcastsSection.kt:354‑396`); add a progress join query to `PodcastDao.kt`.

**Fix the semantics:** `isPlayed` is set on tap (`:96`) — wrong. Real states: **Unplayed** (no history, not played), **In-progress** (history `lastPositionMs` between 0 and ~95% of duration), **Played** (`isPlayed` true *or* position ≥ 95%).

- [ ] **Step 1 — Progress source without a migration.** The recording fix (Task A1) writes a `playback_history` row keyed by `mediaUri == audioUrl`, and the 5s tick keeps `lastPositionMs` fresh. Add a DAO read:
```kotlin
// PlaybackHistoryDao
@Query("SELECT lastPositionMs FROM playback_history WHERE mediaUri = :uri ORDER BY lastPlayedAt DESC LIMIT 1")
fun observePositionFor(uri: String): Flow<Long?>
```
Expose per-episode progress in the VM by combining `episode.audioUrl` → position.

- [ ] **Step 2 — Episode row.** Per episode: small `PodcastArtwork` (show art, 40 dp) OR a state glyph; title (2 lines); `"$min min · $date"`; a **trailing status**:
  - Unplayed → filled dot (accent);
  - In-progress → thin `LinearProgressIndicator(progress = pos/durationMs)` under the title + "X min left";
  - Played → check icon, title de-emphasised.
- [ ] **Step 3 — Mark played on near-completion** (not on tap). Move `setPlayed(guid, true)` out of `playEpisode`; instead, in the position observer, when `pos >= 0.95 * durationMs` call `setPlayed`. (Keep tap setting only an "opened" flag if desired, or drop it.)
- [ ] **Step 4 — Device verify:** play part of an episode → row shows progress bar + "left"; finish → check mark; fresh episode → dot. Commit.

## Task B3: Add/search UX + result artwork

**Files:** `PodcastsSection.kt:191‑234`; `ITunesPodcastSearch.kt` (expose `artworkUrl` on `Hit`).

- [ ] **Step 1 —** Add `artworkUrl` to `ITunesPodcastSearch.Hit` (iTunes JSON has `artworkUrl100`). Show it via `PodcastArtwork` in each search result row (`:219‑233`).
- [ ] **Step 2 —** Distinguish the two add modes in the hint: keep "RSS feed URL or search term", but on an HTTP paste that fails, the Task A2 typed message already guides to search.
- [ ] **Step 3 —** Commit.

## Task B4 (OPTIONAL, schema): durable per-episode progress

Only if Task B2's history-join is judged insufficient (e.g. progress for episodes never the most-recent row). Add `lastPositionMs: Long = 0L` + `lastPlayedAt: Long = 0L` to `PodcastEpisodeEntity` with a **Room migration** (increment `AppDatabase` version, add `MIGRATION_n_n+1` with `ALTER TABLE podcast_episodes ADD COLUMN …`). Update on the 5s tick by guid. Defer unless needed — the history-join (B2) avoids a migration.

---

## Self-Review

1. **Spec coverage:** #1→A2; #2→A5 (won't-fix, documented); #3→A1; #4→A1; #5→A1 (symptom of #4); #6→A3; WorkManager→A4; UI overhaul (artwork/markers/layout/listened)→B1–B3. All covered.
2. **Placeholder scan:** Optional helpers (A2 Step 4, B4) are explicitly optional and specify the exact method to add. No "TODO/handle edge cases" left as work.
3. **Type consistency:** `recordPlay(PlaybackHistoryEntity)` fields match `recordCloudPlay` (`CloudViewModel:251‑263`); `source="LOCAL"` matches the `Source` enum (`LastPlayedRepository:166`); `fetchResult`/`FetchResult` names consistent across A2 steps; `PodcastArtwork(url,size,modifier)` signature reused in B1/B2/B3.
4. **Risk gates:** A4 is repro-gated; A1 Step 5 verifies cold-start restore before relying on it; A2 scopes the UA to the parser client (not global `SharedHttp`).

---

## Self-Review Corrections (AI double-check before execution)

1. **A1 threading:** the rewrite must wrap `setMediaItems` in `withContext(Dispatchers.Main)` (MediaController is main-thread-only) while `getShow`/`recordPlay`/`setPlayed` run on IO. Keep `mediaKindOrdinal = 0` (matches `recordCloudPlay`); `source`/`subtitle` are Strings on `PlaybackHistoryEntity` (confirmed `CloudViewModel:251‑263`).
2. **A2 is not optional in part:** implement `ITunesPodcastSearch.lookupFeedUrl(id)` (the user pasted Apple-id URLs) — not deferred. Tests need `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")` added to `app/build.gradle.kts`. Keep the `fetch()` shim so `PodcastSyncWorker` (calls `fetch`) is unbroken.
3. **A3 host layout:** verify how `CloudBrowserScreen`'s outer `LazyColumn` + the host `MiniPlayerBar` are laid out before choosing padding. If the mini-player is a layout sibling (content bounded above it), removing the fixed `360.dp` (inner shows list → content-wrapping `Column`) largely fixes the gap; add `contentPadding(bottom = navigationBars + miniH)` only if it overlays. Derive mini-player visibility from the real signal (no assumed `miniPlayerVisible`).
4. **A4 is implementable without a live repro:** the robust fix for `no_backup workdb` is canonical on-demand WorkManager init — ensure `Application : Configuration.Provider` with the Hilt `HiltWorkerFactory` and the default `WorkManagerInitializer` removed in the manifest. Verify current setup (Context7 WorkManager+Hilt) and apply the canonical pattern; not a blind change.
5. **B1 counts:** use ONE Room `GROUP BY` projection (`FeedCounts(feedUrl,total,unplayed)`) exposed as a `Map` in the VM — not N per-row flows.
6. **B2 marker semantics:** **remove** `podcastDao.setPlayed(guid,true)` from `playEpisode` (it falsely marks played on tap). Derive Unplayed / In-progress / Played purely from the history row's `lastPositionMs` vs `episode.durationS*1000` (Played ≥ 95%), via a new `PlaybackHistoryDao.observePositionFor(uri)`. This needs **no completion hook and no migration** — so **B4 is not needed** (its condition "history-join insufficient" is unmet; this is a scope decision, not a deferral).

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-16-podcast-fixes-and-ui-overhaul-plan.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks.
2. **Inline Execution** — batch with checkpoints.

Suggested order: A1 (unblocks B2 progress) → A2 → A3 → B1 → B2 → B3 → A4 → A5. Which approach?
