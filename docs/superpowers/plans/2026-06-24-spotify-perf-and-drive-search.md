# Plan — Spotify metadata perf (#2) + Drive metadata search (#16)

- **Date:** 2026-06-24
- **Phase:** PLAN (per TASKS.md protocol — both items are post-INVESTIGATE; no
  code in this turn).
- **Spec source:** `docs/superpowers/investigation/2026-06-24-19-item-investigation.md`
  findings **#2** (line 24) and **#16** (line 38).
- **Repo:** `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player`
- **Build/test:** `cmd /c gradlew.bat :app:assembleDebug` and
  `:app:testDebugUnitTest` (Windows; `gradlew.bat`). Robolectric 4.13 +
  MockWebServer 4.12.0 already on the test classpath.
- **Verified libraries (Context7 + in-repo):**
  - kotlinx-coroutines **1.10.1** — `launch(Job())` / a separate
    `SupervisorJob`-backed `CoroutineScope` runs **independently of parent
    cancellation**; `CancellationException` must be re-thrown
    (`/kotlin/kotlinx.coroutines`, cancellation-and-timeouts.md +
    coroutine-context-and-dispatchers.md). `withTimeout` cancels the block at
    the deadline. → #2 lyrics fetch lives on the poll-independent `pollScope`
    (already `SupervisorJob()+Dispatchers.IO`, SpotifyProvider.kt:161).
  - OkHttp **4.12.0** — `client.newBuilder().callTimeout(...).readTimeout(...)
    .build()` derives a per-concern client **sharing the pool + dispatcher +
    cache** (`/square/okhttp` recipes.md "Per-call Configuration"). → #2
    short-timeout LRCLib client off `SharedHttp.base`.
  - Room **2.7.1** — Context7 has no Room entry; precedent is **in-repo**:
    additive `ALTER`/`CREATE INDEX` migrations registered in
    `di/AppModule.kt` (`MIGRATION_13_14` = pure index add, line 244–250;
    `MIGRATION_17_18` = additive column, 215–219). `@Query("… LIKE :pattern")`
    is standard Room SQL. → #16 uses an **indexed `LIKE`** query + a v18→v19
    `CREATE INDEX` migration. FTS assessed and rejected (see Design decisions).

---

## Design decisions (confirm before execution)

> Resolve these before Task 1. Defaults are pre-selected; change here if wrong.

### D1 — #2 banner-clear timing (CHOSEN: clear banner on metadata, never wait on lyrics)
- The "Loading metadata…" banner (`spotifyMetadataFetching`, consumed
  PlayerViewModel.kt:554/566) must clear **as soon as the track
  title/artist/album/art snap is resolved** — i.e. immediately after the
  `_spotifyState.value = snap…` write — and **must not** be gated behind
  `fetchLyricsLrclib`. Lyrics arrive later via a separate state update.
- Rationale: title/artist/art come from `/v1/me/player` (already in `snap`);
  lyrics are an enhancement that can take 10–30 s on an LRCLib miss
  (SharedHttp callTimeout 30 s). The banner means "metadata not ready", which
  is false once the snap lands.
- **Alternative (reject):** keep one banner covering both → re-creates #2.

### D2 — #2 lyrics fetch placement (CHOSEN: fire on pollScope, off the poll loop)
- Lyrics fetched in a child coroutine of the **same `pollScope`** (survives
  the poll-iteration body but is torn down by `stopPlaybackPolling` which
  bumps `pollGen` + cancels `pollJob`, NOT `pollScope`). Result applied to
  `_spotifyState` only if `gen == pollGen` AND the current track URI still
  matches (no stale-track lyric paint).
- Per-call LRCLib timeout shortened to **6 s** (derived client) so a miss
  can't tie up a worker for 30 s. The poll loop never blocks on it regardless.
- **Alternative (reject):** keep it inline but async-without-await → still
  shares the loop's structured-concurrency parent; cleaner to use a tracked
  child job we can cancel per track-change.

### D3 — #2 lyrics cache (CHOSEN: in-memory LRU + negative cache, process-scoped)
- A bounded `LinkedHashMap` (cap 64) keyed on the LRCLib request tuple
  (`title|firstArtist|album|durSec`, lower-cased). Stores `LyricsResult?`
  where `null` = a confirmed miss (negative cache) so Slipknot-style
  permanent misses never re-hit the network on replays within a session.
- Not persisted to disk/Room (lyrics are cheap to refetch across launches and
  not worth a migration). **Alternative:** persist to a new table — rejected
  as over-scope for a perf fix.

### D4 — #16 metadata search: always-on vs toggle (CHOSEN: always-on, merged)
- The DB metadata search runs **unconditionally alongside** the existing
  filename search whenever the active provider is Google Drive and the query
  is non-blank. No setting. Results merged + de-duped, filename hits first.
- Rationale: the whole point (find "Matt" the author) only works if it always
  runs; a hidden toggle defeats discoverability. Cost is one indexed `LIKE`
  over a small local DB (sub-ms) — no reason to gate it.
- **Alternative (reject):** a Settings toggle — adds a control nobody finds.

### D5 — #16 FTS vs LIKE (CHOSEN: indexed LIKE)
- `LIKE '%needle%'` over `playback_history.title`/`subtitle`,
  `offline_copy.displayName`, `enrichment_cache.title`/`artist`/`album`. The
  candidate set is tiny (Recents are capped; offline copies are few;
  enrichment cache is bounded) so a leading-wildcard `LIKE` (which can't use a
  B-tree index for the wildcard but still scans a small table fast) is
  adequate. We still add a covering index on the searched text columns to
  keep the planner honest and to back exact/prefix sub-matches.
- FTS4/FTS5 rejected: needs `@Fts4` shadow tables + content-table triggers +
  a heavier migration, and buys nothing at this row count. Revisit only if the
  history table grows into tens of thousands of rows.

### D6 — #16 favourite-time background enrich (CHOSEN: CORE, always-on, unconditional — per user directive)
- User directive (verbatim): "i dont give a shit about data usage. so can be
  done on wifi and mobile data, no need to condition it. i want the most
  seamless experience for the user and most feature rich." → favourite-time
  enrich is a **core, always-on** feature, NOT a flag and NOT optional. When a
  Drive item is favourited and isn't already enriched, a background job extracts
  its tags (title/artist/album + embedded cover) and writes them to
  `playback_history` + `ArtworkCache` + `enrichment_cache`, so the item is fully
  described AND searchable (#16b) before its first play.
- **No conditioning** (the user explicitly rejected it): runs on Wi-Fi AND
  mobile data, no size cap, no toggle. Robustness replaces gating:
  - (a) **deduped** per Drive id — an in-flight `Set<String>` guard means a
    double-favourite (or favourite during an in-progress enrich) fires once;
  - (b) **reuse a durable offline copy** — if `offline_copy` already holds the
    file locally, extract tags from that local path (zero re-download);
  - (c) **off the UI thread** (`Dispatchers.IO`) → never freezes
    (investigation #16e proved the enrich scope is non-blocking);
  - (d) a **non-blocking "enriching…" hint** on the favourited row so progress
    is visible without blocking interaction;
  - (e) **failure-tolerant** — a failed enrich is retried on next app-start /
    re-favourite, never crashes, never blocks the favourite itself.
- **Alternative (reject):** feature-flag OFF / Wi-Fi-only / size-capped — the
  user explicitly rejected conditioning; any gate defeats "most feature rich".

### D7 — #16 drive.file scope ceiling (CONFIRMED: cannot whole-Drive search)
- App uses non-sensitive **drive.file** scope (DriveOAuthProvider.kt:81) →
  Drive REST `name contains` only sees picker-granted folders; the app already
  does a parallel recursive walk (GoogleDriveProvider + DriveOAuthProvider
  `searchOneFolder`). Whole-Drive search needs `drive.readonly` + Google
  verification + paid CASA → **out of scope** (user already chose to keep
  drive.file, TASKS.md T332). The DB metadata search is the within-scope way
  to surface non-filename matches. The plan adds a one-line UI note only.

### D8 — #2 serial album-launch calls (CHOSEN: parallelise repeat+shuffle, keep play first)
- `playSpotifyAlbum` (CloudViewModel.kt:1159–1167) runs
  `playRequest` → `setRepeat` → `setShuffle` serially. `playRequest` must
  stay first (it establishes the context). `setRepeat`+`setShuffle` are
  independent of each other and of the poll start → fire them concurrently
  with `async`/`awaitAll` (or launch both, then start polling). Saves ~1 round
  trip of perceived latency. Low risk; both already `runCatching`-wrapped.

---

## File structure

New files:
```
app/src/test/java/com/powermediaplayer/cloud/SpotifyLyricsDecoupleTest.kt   (#2 — banner+lyrics seam unit test)
app/src/test/java/com/powermediaplayer/cloud/DriveMetadataSearchMergeTest.kt (#16 — merge/dedup pure-fn unit test)
app/src/test/java/com/powermediaplayer/data/db/MetadataSearchDaoTest.kt      (#16 — Room in-memory LIKE query test, Robolectric)
app/src/test/java/com/powermediaplayer/cloud/FavouriteEnrichPlannerTest.kt   (#16 D6 — favourite-enrich decision pure-fn test)
app/src/main/java/com/powermediaplayer/cloud/FavouriteEnrichPlanner.kt       (#16 D6 — favourite-enrich decision planner)
```

Changed files:
```
#2:
app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt   (decouple lyrics; short-timeout client; lyrics cache; banner-on-snap)
app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt (parallelise playSpotifyAlbum repeat+shuffle)

#16:
app/src/main/java/com/powermediaplayer/data/db/dao/PlaybackHistoryDao.kt       (searchTitleSubtitle LIKE)
app/src/main/java/com/powermediaplayer/data/db/dao/OfflineCopyDao.kt           (searchDisplayName LIKE)
app/src/main/java/com/powermediaplayer/data/db/dao/EnrichmentCacheDao.kt       (searchEnriched LIKE)
app/src/main/java/com/powermediaplayer/data/db/AppDatabase.kt                  (v18→v19 CREATE INDEX migration; bump version)
app/src/main/java/com/powermediaplayer/di/AppModule.kt                         (register MIGRATION_18_19)
app/src/main/java/com/powermediaplayer/cloud/DriveMetadataSearch.kt            (NEW pure merge/dedup + row→CloudMediaItem mapper) [new file, listed under structure below]
app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt             (inject playbackHistoryDao + enrichmentCacheDao; run DB search; merge)
```
> The merge/dedup pure logic lives in a NEW small file
> `app/src/main/java/com/powermediaplayer/cloud/DriveMetadataSearch.kt`
> (top-level functions, no Android deps except `CloudMediaItem`) so it is
> JVM-unit-testable without Room — mirroring the `shouldEmitSnap` seam pattern
> (SpotifyProvider.kt:1701, tested by SpotifyBannerGraceTest).

Add to new-files block:
```
app/src/main/java/com/powermediaplayer/cloud/DriveMetadataSearch.kt          (#16 pure merge/dedup + mapper)
```

---

# PART A — #2 Spotify metadata slow (decouple lyrics from banner + poll loop)

Root (investigation #2): on every track change the 1 Hz poll loop sets
`_spotifyMetadataFetching=true` (SpotifyProvider.kt:1335), runs a **blocking**
`fetchLyricsLrclib` (1338, up to 30 s on a miss), and only clears the banner at
1347 after it returns — blocking the loop and showing a long banner.

### Task A1 — Failing test: banner clears without waiting on lyrics (TDD seam) [test]
- **Files:** `app/src/test/java/com/powermediaplayer/cloud/SpotifyLyricsDecoupleTest.kt` (new)
- **Steps:**
  1. The decoupling logic must be expressed as **pure top-level functions** in
     `SpotifyProvider.kt` so they are testable without the provider (precedent:
     `shouldEmitSnap`, line 1701). Plan two seams:
     - `fun resolveBannerOnSnap(): Boolean` is trivial → instead express the
       invariant as a **state-transition helper** the test can drive. Add a
       pure data holder:
       ```kotlin
       internal data class TrackResolveStep(
           val emitState: Boolean,      // write _spotifyState now?
           val clearBanner: Boolean,    // set fetching=false now?
           val fetchLyrics: Boolean     // kick the async lyrics job?
       )
       /** On a resolved snap for a (possibly new) track, metadata is ready
        *  immediately; lyrics are fetched only on a track CHANGE and never
        *  gate the banner. */
       internal fun trackResolveStep(isNewTrack: Boolean): TrackResolveStep =
           TrackResolveStep(emitState = true, clearBanner = true, fetchLyrics = isNewTrack)
       ```
  2. Write tests asserting the invariant the bug violated:
     ```kotlin
     @Test fun bannerClearsOnSnap_regardlessOfLyrics() {
         val s = trackResolveStep(isNewTrack = true)
         assertTrue(s.emitState); assertTrue(s.clearBanner)   // banner off NOW
         assertTrue(s.fetchLyrics)                            // lyrics async, separately
     }
     @Test fun sameTrackSnap_noLyricsRefetch_stillClears() {
         val s = trackResolveStep(isNewTrack = false)
         assertTrue(s.clearBanner); assertFalse(s.fetchLyrics)
     }
     ```
  3. **Run (expect RED — function not yet present):**
     `cmd /c gradlew.bat :app:testDebugUnitTest --tests "*SpotifyLyricsDecoupleTest*"`
     Expected: compile failure / unresolved `trackResolveStep`.
- **Commit:** `test(#2): failing seam test for banner-decoupled lyrics`

### Task A2 — Add the pure seam + lyrics cache scaffolding [impl]
- **Files:** `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt`
- **Steps:**
  1. Add the `TrackResolveStep` data class + `trackResolveStep(...)` next to
     `shouldEmitSnap` (after line 1710, file-level `internal`).
  2. Add a process-scoped lyrics cache near the other `@Volatile` fields
     (after line 159):
     ```kotlin
     private data class LyricsResult(val plain: String, val synced: List<LyricLine>)
     // Bounded LRU; value null = confirmed LRCLib miss (negative cache).
     private val lyricsCache = object : LinkedHashMap<String, LyricsResult?>(16, 0.75f, true) {
         override fun removeEldestEntry(e: Map.Entry<String, LyricsResult?>) = size > 64
     }
     private fun lyricsKey(title: String, artist: String, album: String, durMs: Long) =
         (title + "|" + artist.substringBefore(',').trim() + "|" + album + "|" + (durMs / 1000))
             .lowercase()
     ```
  3. Add a **short-timeout** derived client (per OkHttp recipe — shares pool):
     ```kotlin
     private val lyricsHttp = com.powermediaplayer.util.SharedHttp.base.newBuilder()
         .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
         .build()
     ```
  4. **Run (expect GREEN):**
     `cmd /c gradlew.bat :app:testDebugUnitTest --tests "*SpotifyLyricsDecoupleTest*"`
     Expected: `BUILD SUCCESSFUL`, 2 tests pass.
- **Commit:** `feat(#2): decouple seam + bounded lyrics LRU + 6s LRCLib client`

### Task A3 — Rewire the poll loop: clear banner on snap, fetch lyrics async [impl]
- **Files:** `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt`
  (poll loop body 1329–1348)
- **Steps:**
  1. Replace the inline blocking fetch. New shape of the `snap.trackUri != lastTrackUri`
     block:
     ```kotlin
     val newTrack = snap.trackUri != lastTrackUri
     if (newTrack) {
         lastTrackUri = snap.trackUri
         // cached lyrics (incl. negative) attach immediately; no network on a hit
         val cached = synchronized(lyricsCache) {
             if (lyricsCache.containsKey(lyricsKey(snap.title, snap.artist, snap.album, snap.durationMs)))
                 lyricsCache[lyricsKey(snap.title, snap.artist, snap.album, snap.durationMs)] else MISS_SENTINEL
         }
         if (cached !== MISS_SENTINEL) {            // cache hit (value may be null)
             lastLyrics = cached?.plain; lastSynced = cached?.synced.orEmpty()
         } else {
             lastLyrics = null; lastSynced = emptyList()
             fetchLyricsAsync(gen, snap)            // ← async, does NOT block the loop
         }
     }
     val step = trackResolveStep(newTrack)
     _spotifyState.value = snap.copy(lyrics = lastLyrics, syncedLyrics = lastSynced)
     provisionalActive = false
     bannerGraceUntilMs = 0L
     if (step.clearBanner) _spotifyMetadataFetching.value = false  // ← banner off NOW, not after lyrics
     ```
     (Use a `private val MISS_SENTINEL = Any()` field to distinguish "absent"
     from "cached null".)
  2. Add `fetchLyricsAsync` — a child of `pollScope` (survives the loop body,
     dies with `stopPlaybackPolling` via the `gen` guard):
     ```kotlin
     private fun fetchLyricsAsync(gen: Int, snap: SpotifyPlaybackState) {
         pollScope.launch {
             val res = try {
                 fetchLyricsLrclib(snap.title, snap.artist, snap.album, snap.durationMs)
                     ?.let { LyricsResult(it.first, it.second) }
             } catch (ce: kotlinx.coroutines.CancellationException) { throw ce
             } catch (_: Exception) { null }
             synchronized(lyricsCache) {
                 lyricsCache[lyricsKey(snap.title, snap.artist, snap.album, snap.durationMs)] = res
             }
             // Only paint if still the same generation AND still the same track.
             if (gen == pollGen && _spotifyState.value?.trackUri == snap.trackUri) {
                 _spotifyState.value = _spotifyState.value?.copy(
                     lyrics = res?.plain, syncedLyrics = res?.synced.orEmpty()
                 )
             }
         }
     }
     ```
  3. Point `fetchLyricsLrclib` at the short-timeout client: change
     `http.newCall(req)` (line 1404) → `lyricsHttp.newCall(req)`.
  4. Remove the now-dead banner-on-track-change set at line 1335 (the banner is
     already true from `startPlaybackPolling`/`armProvisionalMirror`; a
     same-track snap shouldn't re-raise it, and a new track resolves within one
     poll so we keep it raised only until this snap). Keep the initial banner
     raise at 1298.
  5. **Run:** `cmd /c gradlew.bat :app:testDebugUnitTest --tests "*SpotifyLyricsDecoupleTest*" --tests "*SpotifyBannerGraceTest*"`
     Expected: all pass (no regression of the existing grace tests).
- **Commit:** `fix(#2): banner clears on metadata snap; LRCLib lyrics fetched off the poll loop`

### Task A4 — Parallelise the album-launch calls [impl]
- **Files:** `app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt`
  (playSpotifyAlbum 1159–1167)
- **Steps:**
  1. After `playTrackOnConnectDevice` succeeds, fire `setRepeat` + `setShuffle`
     concurrently rather than serially:
     ```kotlin
     if (r.isSuccess) {
         val shuffleWanted = settingsDataStore.shuffleEnabled.first()
         kotlinx.coroutines.coroutineScope {
             launch { runCatching { spotifyProvider.setRepeat("context") } }
             launch { runCatching { spotifyProvider.setShuffle(shuffleWanted) } }
         }
         spotifyProvider.startPlaybackPolling(expectPlayback = true, expectedTrack = contextUri)
         recordCloudPlay(item)
         ...
     ```
  2. **Run:** `cmd /c gradlew.bat :app:assembleDebug`
     Expected: `BUILD SUCCESSFUL`.
- **Commit:** `perf(#2): parallelise Spotify album repeat+shuffle on launch`

### Task A5 — Device/log verification (#2 network path — not unit-testable) [verify]
- **Files:** none (DiagLog observation).
- **Steps:**
  1. Build + install: `cmd /c gradlew.bat :app:installDebug`.
  2. Settings → enable Diagnostic logging. Play the Slipknot album from Spotify
     (the exact repro in #2). Skip 3 tracks.
  3. Pull + grep DiagLog:
     `bash tools/deeplog/pull_logs.sh` then search `diag/log-current.txt` for
     `Spotify.lyrics LRCLib` and the banner-state transitions.
  4. **Expected:** the "Loading metadata" banner clears within ~1 poll
     (≤1 s) of each track change, **before** any `Spotify.lyrics LRCLib` line;
     a track with no lyrics (LRCLib miss) shows the banner clear promptly and
     the `LRCLib synced=0 plain=0` line arrives later (≤6 s) without re-blocking
     subsequent track changes. Confirm no per-track 10–30 s banner.
- **Commit:** none (verification step; record evidence in TASKS.md row).

---

# PART B — #16 Drive search: also search local enriched metadata

Root (investigation #16): Drive search is **filename-only** —
`DriveOAuthProvider.searchOneFolder` (324–325 `name.lowercase().contains`) +
`GoogleDriveProvider.walkForSearch` (356). The DB is never queried, so an author
"Matt" enriched into `playback_history.title/subtitle` is invisible. Fix: add an
**indexed `LIKE`** DB search over enriched metadata, merge into the Cloud
results, keep filename search, accept the drive.file ceiling.

### Task B1 — Failing test: merge/dedup pure logic (TDD) [test]
- **Files:** `app/src/test/java/com/powermediaplayer/cloud/DriveMetadataSearchMergeTest.kt` (new)
- **Steps:**
  1. Define the pure merge contract in the test against a not-yet-written
     `DriveMetadataSearch.mergeDriveResults(filenameHits, metadataHits)`:
     - Order: filename hits first, then metadata-only hits.
     - Dedup key: `CloudMediaItem.id` (Drive file id / OAuth url), then
       `downloadUrl` as fallback (a metadata row reconstructs the same id).
     - A metadata hit whose id already appears in the filename hits is dropped.
     ```kotlin
     @Test fun filenameFirst_thenMetadataOnly_deduped() {
         val fn = listOf(item("id1","A.m4b"), item("id2","B.m4b"))
         val meta = listOf(item("id2","B.m4b"), item("id3","Matt book.m4b"))
         val out = DriveMetadataSearch.mergeDriveResults(fn, meta)
         assertEquals(listOf("id1","id2","id3"), out.map { it.id })   // id2 not duplicated
     }
     @Test fun emptyMetadata_returnsFilenameUnchanged() {
         val fn = listOf(item("id1","A.m4b"))
         assertEquals(fn, DriveMetadataSearch.mergeDriveResults(fn, emptyList()))
     }
     ```
     (`item(...)` = a small CloudMediaItem builder local to the test.)
  2. **Run (expect RED):**
     `cmd /c gradlew.bat :app:testDebugUnitTest --tests "*DriveMetadataSearchMergeTest*"`
     Expected: unresolved `DriveMetadataSearch`.
- **Commit:** `test(#16): failing merge/dedup test for filename+metadata Drive search`

### Task B2 — Pure merge/dedup + row→item mapper [impl]
- **Files:** `app/src/main/java/com/powermediaplayer/cloud/DriveMetadataSearch.kt` (new)
- **Steps:**
  1. Create the file with the pure functions (no Room, no Android beyond
     `CloudMediaItem`):
     ```kotlin
     package com.powermediaplayer.cloud

     /** Pure, JVM-testable helpers for the enriched-metadata Drive search (#16). */
     object DriveMetadataSearch {
         /** Filename hits first, metadata-only hits appended, de-duped by id
          *  (downloadUrl fallback). Filename results win on collision. */
         fun mergeDriveResults(
             filenameHits: List<CloudMediaItem>,
             metadataHits: List<CloudMediaItem>
         ): List<CloudMediaItem> {
             val seen = HashSet<String>()
             val out = ArrayList<CloudMediaItem>(filenameHits.size + metadataHits.size)
             for (i in filenameHits) { if (seen.add(dedupKey(i))) out.add(i) }
             for (i in metadataHits) { if (seen.add(dedupKey(i))) out.add(i) }
             return out
         }
         private fun dedupKey(i: CloudMediaItem): String =
             i.id.ifBlank { i.downloadUrl }
     }
     ```
  2. **Run (expect GREEN):**
     `cmd /c gradlew.bat :app:testDebugUnitTest --tests "*DriveMetadataSearchMergeTest*"`
     Expected: 2 tests pass.
- **Commit:** `feat(#16): pure merge/dedup for filename+metadata Drive results`

### Task B3 — DAO LIKE queries over enriched metadata [impl]
- **Files:** `PlaybackHistoryDao.kt`, `OfflineCopyDao.kt`, `EnrichmentCacheDao.kt`
- **Steps:**
  1. `PlaybackHistoryDao` — add a search over DRIVE-source rows
     (title/subtitle). Distinct by mediaUri, most-recent wins:
     ```kotlin
     /** #16 — enriched-metadata search. mediaUri here is the Drive
      *  downloadUrl (recordCloudPlay stores it), so the result maps back to a
      *  playable CloudMediaItem. ESCAPE handles literal % / _ in the needle. */
     @Query(
         "SELECT * FROM playback_history " +
             "WHERE source = 'DRIVE' AND id IN (" +
             "  SELECT MAX(id) FROM playback_history WHERE source='DRIVE' GROUP BY mediaUri" +
             ") AND (title LIKE :pattern ESCAPE '\\' OR subtitle LIKE :pattern ESCAPE '\\') " +
             "ORDER BY lastPlayedAt DESC LIMIT 50"
     )
     suspend fun searchDriveMetadata(pattern: String): List<PlaybackHistoryEntity>
     ```
  2. `OfflineCopyDao` — search `displayName`:
     ```kotlin
     @Query("SELECT * FROM offline_copy WHERE displayName LIKE :pattern ESCAPE '\\' LIMIT 50")
     suspend fun searchDisplayName(pattern: String): List<OfflineCopyEntity>
     ```
  3. `EnrichmentCacheDao` — search the full enriched field set
     (title/artist/album/genre):
     ```kotlin
     @Query(
         "SELECT * FROM enrichment_cache " +
             "WHERE title LIKE :pattern ESCAPE '\\' OR artist LIKE :pattern ESCAPE '\\' " +
             "OR album LIKE :pattern ESCAPE '\\' OR genre LIKE :pattern ESCAPE '\\' LIMIT 50"
     )
     suspend fun searchEnriched(pattern: String): List<EnrichmentCacheEntity>
     ```
     (Author lands in `artist`, series in `album`, narrator/genre in `genre` per
     Part C's rich write → the "Matt" author search hits here too.)
  4. **Run:** `cmd /c gradlew.bat :app:compileDebugKotlin` (Room KSP validates
     the SQL at build time).
     Expected: `BUILD SUCCESSFUL` (Room would fail the build on a bad column).
- **Commit:** `feat(#16): indexed LIKE search queries over enriched metadata DAOs`

### Task B4 — v18→v19 covering-index migration [impl]
- **Files:** `AppDatabase.kt`, `di/AppModule.kt`
- **Steps:**
  1. In `AppDatabase.kt`: bump `version = 18` → `version = 19`; add a versioned
     comment ("v19: #16 — indexes backing the enriched-metadata Drive search
     LIKE queries"); add the migration in the companion object (precedent
     `MIGRATION_13_14`, line 244):
     ```kotlin
     val MIGRATION_18_19: Migration = object : Migration(18, 19) {
         override fun migrate(db: SupportSQLiteDatabase) {
             db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_title ON playback_history(title)")
             db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_history_subtitle ON playback_history(subtitle)")
             db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_copy_displayName ON offline_copy(displayName)")
             db.execSQL("CREATE INDEX IF NOT EXISTS index_enrichment_cache_title ON enrichment_cache(title)")
         }
     }
     ```
     > Note: leading-wildcard `LIKE '%x%'` won't use these for the wildcard scan;
     > they back exact/prefix sub-predicates and keep the schema explicit. The
     > tables are small so the scan is fast regardless (D5). To make Room's
     > generated schema match, also add the indices to the entities'
     > `@Entity(indices=[...])` so KSP doesn't flag a schema mismatch — add
     > `Index("title")`,`Index("subtitle")` to `PlaybackHistoryEntity`,
     > `Index("displayName")` to `OfflineCopyEntity`, `Index("title")` to
     > `EnrichmentCacheEntity`. (Room compares the declared schema to the DB;
     > the migration + the `@Entity` indices must agree.)
  2. In `di/AppModule.kt`: append `AppDatabase.MIGRATION_18_19` to the
     `.addMigrations(...)` list (after line 78).
  3. **Run:** `cmd /c gradlew.bat :app:compileDebugKotlin`
     Expected: `BUILD SUCCESSFUL` (Room validates entity-vs-migration schema).
- **Commit:** `feat(#16): v18→v19 migration — indices backing the metadata search`

### Task B5 — Room in-memory DAO test (Robolectric) [test]
- **Files:** `app/src/test/java/com/powermediaplayer/data/db/MetadataSearchDaoTest.kt` (new)
- **Steps:**
  1. Robolectric + in-memory Room (precedent: ChapterCacheTest /
     SmartPlaylistResolverTest use `@RunWith(RobolectricTestRunner)` +
     `ApplicationProvider`; this adds `Room.inMemoryDatabaseBuilder`):
     ```kotlin
     @RunWith(RobolectricTestRunner::class)
     class MetadataSearchDaoTest {
         private lateinit var db: AppDatabase
         @Before fun setup() {
             val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
             db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
                 .allowMainThreadQueries().build()
         }
         @After fun tearDown() = db.close()

         @Test fun searchDriveMetadata_matchesAuthorInSubtitle() = runBlocking {
             db.playbackHistoryDao().insertSession(PlaybackHistoryEntity(
                 mediaUri="https://drive/file1", title="This Inevitable Ruin",
                 subtitle="Matt Dinniman", artworkUri=null, source="DRIVE",
                 mediaKindOrdinal=0, lastPositionMs=0, durationMs=0, lastPlayedAt=1))
             // a non-Drive row must NOT match
             db.playbackHistoryDao().insertSession(PlaybackHistoryEntity(
                 mediaUri="content://x", title="Matt local", subtitle="", artworkUri=null,
                 source="LOCAL", mediaKindOrdinal=0, lastPositionMs=0, durationMs=0, lastPlayedAt=2))
             val hits = db.playbackHistoryDao().searchDriveMetadata("%matt%")
             assertEquals(1, hits.size)
             assertEquals("https://drive/file1", hits.first().mediaUri)
         }

         @Test fun searchDisplayName_offlineCopy() = runBlocking {
             db.offlineCopyDao().upsert(OfflineCopyEntity(
                 driveFileId="id1", localPath="/x", byteSize=1,
                 displayName="This Inevitable Ruin — Matt Dinniman.m4b"))
             assertEquals(1, db.offlineCopyDao().searchDisplayName("%matt%").size)
             assertEquals(0, db.offlineCopyDao().searchDisplayName("%zzz%").size)
         }

         @Test fun searchEnriched_titleArtistAlbum() = runBlocking {
             db.enrichmentCacheDao().put(EnrichmentCacheEntity(
                 cacheKey="k", provider="mb", title="Ruin", artist="Matt Dinniman",
                 album="DCC", year=null, genre=null, artworkUrl=null, fetchedAtMs=1))
             assertEquals(1, db.enrichmentCacheDao().searchEnriched("%matt%").size)
         }
     }
     ```
  2. **Run:** `cmd /c gradlew.bat :app:testDebugUnitTest --tests "*MetadataSearchDaoTest*"`
     Expected: 3 tests pass (LIKE is case-insensitive for ASCII in SQLite by
     default — "%matt%" matches "Matt").
- **Commit:** `test(#16): Room in-memory tests for the metadata LIKE queries`

### Task B6 — Wire DB search into CloudViewModel + merge [impl]
- **Files:** `app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt`
- **Steps:**
  1. Inject the two missing DAOs into the constructor (CloudViewModel already
     injects `offlineCopyDao`, line 97):
     ```kotlin
     private val playbackHistoryDao: com.powermediaplayer.data.db.dao.PlaybackHistoryDao,
     private val enrichmentCacheDao: com.powermediaplayer.data.db.dao.EnrichmentCacheDao,
     ```
     (Both are already `@Provides @Singleton` in AppModule — lines 109, 158 —
     so no DI wiring needed.)
  2. Add a private helper that runs the DB search and maps rows →
     `CloudMediaItem` (Drive provider, downloadUrl = mediaUri/reconstructed):
     ```kotlin
     private suspend fun searchDriveMetadata(query: String): List<CloudMediaItem> {
         val needle = query.trim()
         if (needle.length < 2) return emptyList()   // avoid trivially-broad scans
         val pattern = "%" + needle
             .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
             .lowercase() + "%"
         return withContext(Dispatchers.IO) {
             val hist = playbackHistoryDao.searchDriveMetadata(pattern).map { row ->
                 CloudMediaItem(
                     id = row.mediaUri, name = row.title, mimeType = "audio/*",
                     size = 0L, downloadUrl = row.mediaUri,
                     thumbnailUri = row.artworkUri?.let { android.net.Uri.parse(it) },
                     sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                     subtitle = row.subtitle
                 )
             }
             val offline = offlineCopyDao.searchDisplayName(pattern).map { row ->
                 CloudMediaItem(
                     id = row.driveFileId, name = row.displayName, mimeType = "audio/*",
                     size = row.byteSize,
                     downloadUrl = "https://www.googleapis.com/drive/v3/files/${row.driveFileId}?alt=media",
                     sourceProvider = CloudProviderType.GOOGLE_DRIVE, subtitle = "Offline"
                 )
             }
             // enrichment_cache has no media id → only used to BROADEN the history
             // match set is unnecessary here; include only if it adds a NEW name.
             DriveMetadataSearch.mergeDriveResults(hist, offline)
         }
     }
     ```
     > Decision note: `enrichment_cache` rows carry no mediaUri/downloadUrl, so
     > they can't be turned into a playable item on their own. Use them only as
     > a *signal* — if the future favourite-enrich (Task 12) writes enrichment
     > rows keyed to a Drive id, revisit. For now history + offline_copy cover
     > the "already-enriched item" requirement. (Keep `searchEnriched` + its
     > test — it's exercised by Task 12 and harmless.)
  3. In `setSearchQuery` (1583–1596), for the `GOOGLE_DRIVE` branch run the DB
     search concurrently with the existing SAF+OAuth filename search, then
     merge:
     ```kotlin
     provider == CloudProviderType.GOOGLE_DRIVE ->
         coroutineScope {
             val saf   = async { driveProvider.searchFiles(query).getOrDefault(emptyList()) }
             val oauth = async { driveOAuthProvider.searchFiles(query).getOrDefault(emptyList()) }
             val meta  = async { searchDriveMetadata(query) }
             val filename = saf.await() + oauth.await()
             DriveMetadataSearch.mergeDriveResults(filename, meta.await())
         }
     ```
  4. **Run:** `cmd /c gradlew.bat :app:assembleDebug`
     Expected: `BUILD SUCCESSFUL`.
- **Commit:** `feat(#16): merge enriched-metadata DB search into Drive Cloud search`

### Task B7 — UI note for the scope ceiling (within scope, one line) [impl]
- **Files:** the Cloud search-bar surface (`CloudBrowserScreen.kt`) +/or the
  Cloud info content (`InfoContent.kt` Drive entry).
- **Steps:**
  1. Locate the existing "Searching your Drive folders…" string (TASKS.md T332)
     and the Cloud Drive info bullet. Add a layman line:
     "Drive search looks inside the folders you've granted, by file name and by
     the title/author of items you've already opened. It can't search your
     whole Drive." (No jargon — supports investigation #11's layman drive.)
  2. **Run:** `cmd /c gradlew.bat :app:assembleDebug`
     Expected: `BUILD SUCCESSFUL`.
- **Commit:** `docs(#16): plain-language note on Drive search scope`

### Task B8 — Device/log verification (#16 — the "Matt" repro) [verify]
- **Files:** none.
- **Steps:**
  1. `cmd /c gradlew.bat :app:installDebug`.
  2. Ensure the v18→v19 migration runs cleanly on the existing on-device DB:
     launch, then `bash tools/deeplog/pull_logs.sh` and confirm **no**
     `IllegalStateException` / `Room cannot verify` / migration error in logcat
     (precedent: T317 device-verified an additive migration this way).
  3. Open a Drive audiobook once (so it enriches into playback_history with the
     author in subtitle). Then Cloud → Drive → search the **author** ("Matt"),
     which is NOT in the filename.
  4. **Expected:** the audiobook appears in the results (filename hits, if any,
     listed first; the author-matched item appears even though its filename is
     "This Inevitable Ruin…"). Tapping it plays. Filename search for a literal
     filename fragment still works (no regression).
- **Commit:** none (verification; record evidence in TASKS.md).

---

# PART C — CORE (always-on, unconditional) — favourite-time background enrich

> Per D6 (user directive): a favourited Drive item is enriched in the background
> with no flag, no Wi-Fi gate, no size cap. Robustness (dedup, offline reuse,
> off-thread, retry) replaces conditioning. This makes favourited items fully
> described + searchable (#16b) before first play — "most feature rich".

### Task C1 — Failing test: favourite-enrich decision (pure seam, TDD) [test]
- **Files:** `app/src/test/java/com/powermediaplayer/cloud/FavouriteEnrichPlannerTest.kt` (new)
- **Steps:**
  1. Express the decision as a pure function so the dedup / offline-reuse /
     skip-if-enriched logic is JVM-testable without Android. Test against a
     not-yet-written `FavouriteEnrichPlanner.plan(...)`:
     ```kotlin
     package com.powermediaplayer.cloud

     import org.junit.Assert.assertEquals
     import org.junit.Test

     class FavouriteEnrichPlannerTest {
         @Test fun alreadyEnriched_skips() {
             assertEquals(
                 EnrichPlan.Skip,
                 FavouriteEnrichPlanner.plan(alreadyEnriched = true, offlineLocalPath = null, inFlight = false)
             )
         }
         @Test fun notEnriched_noOffline_downloads() {
             assertEquals(
                 EnrichPlan.FromDownload,
                 FavouriteEnrichPlanner.plan(alreadyEnriched = false, offlineLocalPath = null, inFlight = false)
             )
         }
         @Test fun notEnriched_withOffline_reusesLocal() {
             assertEquals(
                 EnrichPlan.FromLocal("/data/offline/x.m4b"),
                 FavouriteEnrichPlanner.plan(alreadyEnriched = false, offlineLocalPath = "/data/offline/x.m4b", inFlight = false)
             )
         }
         @Test fun inFlight_skips_evenIfNotEnriched() {   // dedup guard
             assertEquals(
                 EnrichPlan.Skip,
                 FavouriteEnrichPlanner.plan(alreadyEnriched = false, offlineLocalPath = null, inFlight = true)
             )
         }
     }
     ```
  2. **Run (expect RED):**
     `cmd /c gradlew.bat :app:testDebugUnitTest --tests "*FavouriteEnrichPlannerTest*"`
     Expected: unresolved `FavouriteEnrichPlanner` / `EnrichPlan`.
- **Commit:** `test(#16): failing favourite-enrich planner (dedup/offline-reuse/skip)`

### Task C2 — Pure planner [impl]
- **Files:** `app/src/main/java/com/powermediaplayer/cloud/FavouriteEnrichPlanner.kt` (new)
- **Steps:**
  1. Create the sealed decision + planner (no Android deps):
     ```kotlin
     package com.powermediaplayer.cloud

     sealed interface EnrichPlan {
         object Skip : EnrichPlan
         object FromDownload : EnrichPlan
         data class FromLocal(val path: String) : EnrichPlan
     }

     /** #16 — decide how to enrich a just-favourited Drive item. Dedup + an
      *  already-enriched check skip needless work; an existing durable offline
      *  copy is extracted in place (no re-download). No size/network gate (D6). */
     object FavouriteEnrichPlanner {
         fun plan(alreadyEnriched: Boolean, offlineLocalPath: String?, inFlight: Boolean): EnrichPlan =
             when {
                 alreadyEnriched || inFlight -> EnrichPlan.Skip
                 offlineLocalPath != null    -> EnrichPlan.FromLocal(offlineLocalPath)
                 else                        -> EnrichPlan.FromDownload
             }
     }
     ```
  2. **Run (expect GREEN):**
     `cmd /c gradlew.bat :app:testDebugUnitTest --tests "*FavouriteEnrichPlannerTest*"`
     Expected: 4 tests pass.
- **Commit:** `feat(#16): pure favourite-enrich planner`

### Task C3 — Wire always-on enrich into the favourite path [impl]
- **Files:** `app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt`
  (the Drive-favourite add path — `toggleDriveFavouriteTrack`, ~526–530)
- **Steps:**
  1. Add an in-flight dedup set + an `enrichingIds` StateFlow near the other VM
     state:
     ```kotlin
     private val enrichInFlight = java.util.Collections.synchronizedSet(HashSet<String>())
     private val _enrichingIds = MutableStateFlow<Set<String>>(emptySet())
     val enrichingIds: StateFlow<Set<String>> = _enrichingIds.asStateFlow()
     ```
  2. On a favourite ADD (not on un-favourite), after the existing pin write,
     compute the plan and fire the enrich unconditionally when not Skip:
     ```kotlin
     // #16 D6 — favourited Drive items are enriched in the background so their
     // tags + cover are searchable/visible before first play. No flag, no Wi-Fi
     // gate, no size cap (user directive). Deduped; reuses an offline copy.
     viewModelScope.launch(Dispatchers.IO) {
         val id = item.id
         val alreadyEnriched = playbackHistoryDao.searchDriveMetadata(/*exact id probe*/
             "%").any { it.mediaUri == item.downloadUrl } // see note
         val offlinePath = offlineCopyDao.getByDriveId(id)?.localPath
         val decision = FavouriteEnrichPlanner.plan(
             alreadyEnriched = alreadyEnriched,
             offlineLocalPath = offlinePath,
             inFlight = !enrichInFlight.add(id)
         )
         when (decision) {
             EnrichPlan.Skip -> { /* nothing */ }
             is EnrichPlan.FromLocal -> {
                 _enrichingIds.update { it + id }
                 try { enrichFromLocalFile(item, decision.path) }
                 finally { enrichInFlight.remove(id); _enrichingIds.update { it - id } }
             }
             EnrichPlan.FromDownload -> {
                 _enrichingIds.update { it + id }
                 try { driveTagEnricher.enrich(this, item, item.downloadUrl) } // existing download+MMR+write path
                 finally { enrichInFlight.remove(id); _enrichingIds.update { it - id } }
             }
         }
     }
     ```
     > **Two impl notes resolved at execution (no guess):**
     > - Replace the `alreadyEnriched` probe with the real existence check — add
     >   `@Query("SELECT COUNT(*) FROM playback_history WHERE source='DRIVE' AND mediaUri=:uri")
     >   suspend fun countDriveRow(uri: String): Int` to `PlaybackHistoryDao` and use
     >   `countDriveRow(item.downloadUrl) > 0`. (Cleaner than the placeholder above;
     >   add it in this task.)
     > - `driveTagEnricher.enrich(...)` — match the enricher's actual public
     >   signature (the same entry point used at play/last-played time,
     >   `DriveTagEnricher.kt`); the call above is the shape, confirm the params.
  3. Add `enrichFromLocalFile(item, path)` — a small local-source extract that
     reuses the enricher's write half (MMR `setDataSource(path)` → title/artist/
     album + `embeddedPicture` → `fixMojibake` → `ArtworkCache.write` +
     `playback_history`/`enrichment_cache` upsert). If `DriveTagEnricher` already
     exposes a private write path, extract it to an internal `writeEnrichment(...)`
     reused by both download + local sources (DRY — do not duplicate the tag→DB
     mapping).
  4. **Rich searchable metadata (the "Matt" example, end-to-end).** The enrich
     MUST write the broadest field set so author/narrator/series searches all hit
     (#16b). Concretely, every enrich (download AND local) writes:
     - `playback_history`: `title` = tag title; `subtitle` = a composite of the
       people/series fields the file carries — `albumArtist`/`artist` (author),
       `METADATA_KEY_WRITER`/`composer` (narrator), `album` (series). Build it as
       `listOfNotNull(author, narrator, series).distinct().joinToString(" · ")`
       (`fixMojibake`'d), so "Matt Dinniman" (author) AND a narrator AND the
       series name are ALL present in the searched `subtitle` column.
     - `enrichment_cache`: `title`, `artist` (author), `album` (series), `genre`.
     This guarantees the Part B `LIKE` search (which scans title/subtitle +
     enrichment title/artist/album/genre — see B3 note) finds the item by author,
     narrator, OR series even though none is in the filename.
  4. **Run:** `cmd /c gradlew.bat :app:assembleDebug`
     Expected: `BUILD SUCCESSFUL`.
- **Commit:** `feat(#16): always-on background enrich on Drive favourite (dedup + offline reuse)`

### Task C4 — "Enriching…" non-blocking UI hint [impl]
- **Files:** the favourite/Drive row surface that shows favourited Drive items
  (`CloudBrowserScreen.kt` and/or the Last Played favourite row), driven by
  `CloudViewModel.enrichingIds`.
- **Steps:**
  1. Collect `enrichingIds` with `collectAsStateWithLifecycle()`; for a row whose
     `item.id ∈ enrichingIds`, show a small trailing indicator (a 16dp
     `CircularProgressIndicator` or a muted "Updating…" label). It must NOT block
     taps, scrolling, or playback — purely informational (seamlessness, D6(d)).
  2. **Run:** `cmd /c gradlew.bat :app:assembleDebug`
     Expected: `BUILD SUCCESSFUL`.
- **Commit:** `feat(#16): non-blocking enriching indicator on favourited Drive rows`

### Task C5 — Device/log verification (favourite-enrich — network path) [verify]
- **Files:** none.
- **Steps:**
  1. `cmd /c gradlew.bat :app:installDebug`; Settings → enable Diagnostic logging.
  2. Favourite a Drive audiobook you have **never played** (so no prior enrich).
     Do not open it.
  3. Confirm: the favourite registers instantly (no freeze — the UI stays
     responsive, D6(c)/#16e); the "Updating…" hint appears then clears; DiagLog
     shows the enrich download/extract + a `playback_history` write for its id.
  4. Then Cloud → Drive → search the **author** (not in the filename) → the
     just-favourited, never-played item appears (proves enrich made it
     searchable before first play). Favourite a second item that already has a
     durable offline copy → confirm DiagLog shows the **local** extract (no
     re-download).
- **Commit:** none (verification; record evidence in TASKS.md row).

---

## Final gate (run before reporting either item done)

- **Build:** `cmd /c gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`.
- **Unit tests:** `cmd /c gradlew.bat :app:testDebugUnitTest` → 0 failures;
  the 4 new suites present:
  `SpotifyLyricsDecoupleTest`, `DriveMetadataSearchMergeTest`,
  `MetadataSearchDaoTest`, `FavouriteEnrichPlannerTest`, and the existing
  `SpotifyBannerGraceTest` still green.
- **#16 favourite-enrich device evidence:** favouriting a never-played Drive
  item makes it author-searchable + cover-visible before first play, with no UI
  freeze; an item with an offline copy enriches locally (no re-download) (Task C5).
- **Migration safety:** logcat after `installDebug` shows clean launch, no Room
  migration exception (Task B8.2).
- **#2 device evidence:** banner clears ≤1 s after track change, before any
  `Spotify.lyrics LRCLib` line (Task A5).
- **#16 device evidence:** author search surfaces an enriched item whose
  filename doesn't contain the needle (Task B8.4).
- **TASKS.md:** add rows for #2 and #16 with these as the DONE Evidence lines;
  report the table, not prose.

## Commit cadence
- One commit per task (A1–A5, B1–B8, C1–C5 — all core). After the final gate
  passes, push + adb-install per the standing auto-push/auto-install memory.

## Out of scope (explicit)
- Whole-Drive search (needs drive.readonly + Google verification + paid CASA;
  user chose drive.file — D7/T332).
- Persisting lyrics to disk (D3).
- A Settings toggle for the metadata search (D4). (Favourite-time enrich is NOT
  out of scope — it is core + always-on per D6.)
