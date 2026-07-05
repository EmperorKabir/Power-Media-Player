# Plan — items #19 / #18 / #3 / #17 (starred-position UX · no-resume-after-star · storage hygiene · background activity)

Date: 2026-06-24 · Phase: PLAN (no code in this turn). Source spec:
`docs/superpowers/investigation/2026-06-24-19-item-investigation.md` (items 3, 17, 18, 19).
Methodology: superpowers `writing-plans` — File Structure first, then bite-sized
TDD/verify tasks (exact **Files** + 2-5 min Steps + exact command + expected
output). Frequent commits. NO deferral (CLAUDE.md hard rule): every named/implied
sub-item is implemented + verified before "done".

---

## Goal

- **#19 (FEATURE)** — per-star "Fixed position / Follow live" choice. Default
  `followLive=false` → byte-identical behaviour for existing pins. When
  `followLive=true`, playing a starred row resolves the LIVE position from
  `playback_history` (by `mediaUri`) at play time, instead of the frozen
  snapshot copied at pin time.
- **#18 (FIX + VERIFY)** — the star is causally inert to resume (proven:
  `pinSession` only inserts into `history_favourites`), but the user hit a REAL
  failure: on reopen the app did not surface/resume what was playing. Per the
  no-downgrade directive the plan UNCONDITIONALLY implements warm-reopen session
  surfacing so the Player tab + mini-bar always reflect the live (or last)
  session on every reopen (the candidate gap), and actively chases the
  audio-stopped case if the device trace shows it. The cold-start adoption guard
  and `pinSession` stay untouched (additive surfacing only). Device repro
  confirms the user's complaint is resolved.
- **#3 (storage hygiene — actionable part)** — `OfflineMediaManager.deleteLocal`
  orphans `ArtworkCache` (600 KB-1 MB each, **no cap**) + `ChapterCache` entries.
  Plan: evict the matching ArtworkCache + ChapterCache entries on offline-remove
  **by key**, and add an LRU **cap/cleanup** to ArtworkCache (it has none).
- **#17 (VERIFY-HARDEN + NOTE)** — background activity is legitimate/bounded
  (strongest "when closed" = a user-set `setAlarmClock` alarm; no leaked loop).
  Per the no-downgrade directive this is not doc-only: the plan (a) actively
  instruments every background vector with explicit shutdown logging and PROVES
  on a device trace that each terminates on app close, (b) adds a hard
  stop-on-close safeguard for any vector the trace shows still running, and
  (c) ships an UNCONDITIONAL plain-language Settings note explaining the alarm
  attribution. Code + verification, not just documentation.

---

## Evidence base (code-grounded, read this turn)

- **#19 snapshot is set once at pin, never updated.**
  `HistoryFavouriteEntity.lastPositionMs` (`…/data/db/entity/HistoryFavouriteEntity.kt:27`).
  `pinSession` copies `src.lastPositionMs` (`…/data/repository/LastPlayedRepository.kt:356-368`,
  specifically `lastPositionMs = src.lastPositionMs` at :364). `observePinned`
  surfaces `f.lastPositionMs` (`LastPlayedRepository.kt:323-341`, field at :334).
  Play uses the snapshot: `playLocalAt` → `targetPos = atPositionMs ?: item.lastPositionMs`
  (`…/ui/lastplayed/LastPlayedViewModel.kt:320` Spotify branch, :465 local/Drive
  branch). No `favDao.updatePosition` exists (`HistoryFavouriteDao.kt` has only
  insert/observeAll/delete/setOrder/count/snapshot).
- **Live position is already queryable by uri**: `PlaybackHistoryDao.observePositionFor(uri)`
  (`…/data/db/dao/PlaybackHistoryDao.kt:93-97`) returns
  `SELECT lastPositionMs … WHERE mediaUri = :uri ORDER BY lastPlayedAt DESC LIMIT 1`.
  A one-shot suspend sibling is the cleanest play-time resolve (see Task 19.5).
- **Star tap site** (where the dialog hooks in):
  `…/ui/lastplayed/LastPlayedScreen.kt:327-336` — `IconButton(onClick { scope.launch { viewModel.pinSession(item.id) … } })`.
- **#18 cold-start adoption guard**: `…/playback/PlaybackSessionCoordinator.kt:521-530`
  (`session-already-adopted + media loaded → skip`). `pinSession` writes ONLY
  `history_favourites` (+ snapshot bookmarks) — never `adoptSession/recordPlay/
  currentSessionId/playback_history` (`LastPlayedRepository.kt:347-383`).
- **#3 orphan site**: `OfflineMediaManager.deleteLocal` (`…/offline/OfflineMediaManager.kt:147-163`)
  deletes durable file + `offline_copy` row + DataStore pair, but never touches
  ArtworkCache/ChapterCache. Cache key contracts:
  - `ArtworkCache.write(context, key, bytes)` / `uriFor(context, key)` keyed by the
    **play mediaUri** (`DriveTagEnricher.kt:144` writes `ArtworkCache.write(context, stableKey, …)`
    where `stableKey` = stream-url mediaUri; `PlaybackSessionCoordinator.kt:657-658`
    reads `ArtworkCache.uriFor(context, recent.mediaUri)`). File = `cacheDir/coverart/<sha256(key)>.img`
    (`ArtworkCache.kt:28-29`). **No cap** (`ArtworkCache.kt` — `write` only skips a
    same-size rewrite; nothing trims).
  - `ChapterCache.shared.get/put(uri, "?")` keyed by `uri.toString()` (`M4bChapterParser.kt:39-52,77-80`).
    Disk file = `chapter-cache/<sha256(uri).take(24)>-<sha256("?").take(16)>.json`
    (`ChapterCache.kt:69-72`). Disk LRU cap already 5 MB (`ChapterCache.kt:139-140`).
  - The `deleteLocal(uri)` argument IS the play mediaUri == the ArtworkCache/ChapterCache
    key → eviction is a direct key delete (no mapping needed).
- **#17 vectors** (all bounded): `mediaPlayback` FGS `onTaskRemoved` honours
  `stopOnTaskRemoved` (default true); `AlarmManager.setAlarmClock`
  (`…/alarm/AlarmScheduler.kt:24-49`, re-armed on boot, only if a clock alarm is
  set) = strongest "running when closed"; `PodcastSyncWorker` periodic 6 h Wi-Fi-only;
  Spotify 1 Hz poll bounded 30 s bg-stop; Hue DTLS gated on playback. No leaked loop.

### Hard constraint discovered (decides #19's test shape)

- The DB is `@Database(… exportSchema = false)` (`AppDatabase.kt:96`); there is
  **no `schemas/` directory**, **no `androidx.room:room-testing` dependency**, and
  **no `app/src/androidTest/` source set at all** (only Robolectric `src/test`).
  Context7 (`/androidx/androidx`) confirms `MigrationTestHelper.createDatabase(v)`
  ALWAYS `loadSchema(version)` from an exported schema directory — it **cannot run
  without an exported JSON schema**. The repo's own audit recorded this:
  `docs/superpowers/plans/2026-05-08-deep-audit-and-closeout.md:172` — "`exportSchema = false`
  blocks migration column-by-column tests."
- DECISION (carried into Task 19.2): the JVM-unit-testable migration test is a
  **schema-free Robolectric test** that hand-builds the v18 `history_favourites`
  table shape via raw SQL on a `SupportSQLiteOpenHelper`, applies `MIGRATION_18_19`,
  and asserts the new column + default + row-survival. This is genuinely
  JVM-runnable (Robolectric SQLite, same runner as `ChapterCacheTest`) and needs
  **zero schema export / zero androidTest harness**. The standard
  `MigrationTestHelper` route is deliberately NOT used (would force
  `exportSchema=true` + regenerating 11 historical schema JSONs + a new instrumented
  test source set — heavy, out of scope, and unnecessary for an additive ALTER).
  Recorded as a Design decision for confirmation.

---

## File Structure (every file touched, with role)

### New files
- `app/src/test/java/com/powermediaplayer/data/db/HistoryFavouriteMigration18to19Test.kt`
  — Robolectric, schema-free `MIGRATION_18_19` test (#19, Task 19.2).
- `app/src/test/java/com/powermediaplayer/lastplayed/StarPositionResolverTest.kt`
  — pure-JVM fixed-vs-live position-resolver test (#19, Task 19.4).
- `app/src/test/java/com/powermediaplayer/offline/OfflineCacheEvictionTest.kt`
  — Robolectric ArtworkCache + ChapterCache delete-by-key + ArtworkCache LRU-cap
    test (#3, Task 3.1).
- `app/src/test/java/com/powermediaplayer/player/SessionSurfaceTest.kt`
  — pure-JVM `shouldSurfaceSession(...)` seam test (#18, Task 18.3).
- `docs/superpowers/specs/2026-06-24-star-resume-ui-repro.md`
  — #18 device-repro protocol + outcome record (Task 18.1).
- `docs/superpowers/specs/2026-06-24-background-activity.md`
  — #17 vector inventory + device trace proving each terminates (Task 17.1/17.2).

### Modified files (#19)
- `…/data/db/entity/HistoryFavouriteEntity.kt` — add `val followLive: Boolean = false`.
- `…/data/db/AppDatabase.kt` — `version = 18 → 19`; add `MIGRATION_18_19` (additive
  `ALTER TABLE history_favourites ADD COLUMN followLive INTEGER NOT NULL DEFAULT 0`);
  version-history comment.
- `…/di/AppModule.kt` — chain `AppDatabase.MIGRATION_18_19` into `.addMigrations(…)`.
- `…/data/db/dao/PlaybackHistoryDao.kt` — add suspend `positionForUri(uri): Long?`
  (one-shot sibling of `observePositionFor`).
- `…/data/repository/LastPlayedRepository.kt` — `pinSession(historyId, followLive)`;
  carry `followLive` into the `HistoryFavouriteEntity`; surface `followLive` on the
  pinned `HistoryItem`; add `livePositionForUri(uri)` pass-through; add a small
  `StarPositionResolver` (pure fn) so the play-time resolve is unit-testable.
- `…/data/repository/LastPlayedRepository.kt` `HistoryItem` data class — add
  `followLive: Boolean = false`.
- `…/ui/lastplayed/LastPlayedViewModel.kt` — `pinSession(historyId, followLive)`;
  in `playLocalAt`, when `item.followLive` resolve the live position before
  building the MediaItem (local/Drive AND Spotify branches).
- `…/ui/lastplayed/LastPlayedScreen.kt` — star `IconButton onClick` opens a
  "Fixed position / Follow live" `AlertDialog` (precedent: `SleepTimerDialog`)
  → `viewModel.pinSession(item.id, followLive)`; existing-pin star stays a plain
  toggle-off (unpin path unchanged).

### Modified files (#3)
- `…/util/ArtworkCache.kt` — add `evict(context, key)` + `trimToCap(context)`
  (LRU by `lastModified()` down to a byte budget); call `trimToCap` at the tail of
  `write`.
- `…/util/ChapterCache.kt` — add `evict(context, uri)` (memory remove + disk
  delete of the `<sha256(uri).take(24)>-*` sibling(s)).
- `…/offline/OfflineMediaManager.kt` — in `deleteLocal`, after the existing Drive
  / podcast delete, evict `ArtworkCache` + `ChapterCache` for `uri`.

### Modified files (#17) — ships (per no-downgrade directive)
- The five vector owners (`…/playback/PlaybackService.kt`,
  `…/cloud/SpotifyProvider.kt`, the Hue collector/disconnect path,
  `…/sync/PodcastSyncWorker.kt`, the alarm scheduler) — a uniform `DiagLog.bg`
  shutdown line (17.2); any vector the trace shows lingering gets an explicit
  teardown (17.3).
- `…/ui/settings/InfoContent.kt` — UNCONDITIONAL transparency note (17.4).

### Modified files (#18) — warm-reopen surfacing fix (ships)
- `…/ui/player/PlayerViewModel.kt` (+/or `…/playback/PlaybackSessionCoordinator.kt`)
  — additive: on reopen, surface the live/last service session into the Player UI
  state + mini-bar (18.3). A small pure `shouldSurfaceSession(...)` helper carries a
  unit test. NO change to `pinSession` / cold-start adoption / the
  `session-already-adopted` guard.

### Ledger
- `TASKS.md` — add the four rows (this turn, protocol rule 5) and keep statuses live.

---

## Tasks

> Build/test commands assume repo root `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player`.
> Use the Gradle wrapper. Unit tests: `./gradlew testDebugUnitTest`. Compile-only
> gate: `./gradlew compileDebugKotlin`. Full APK gate: `./gradlew assembleDebug`.
> Each "expected output" below is the literal success signal to confirm before
> moving on.

---

### #19 — Starred position: Fixed snapshot vs Follow live

#### Task 19.1 — Ledger + entity column (no behaviour change yet)
**Files:** `TASKS.md`, `…/data/db/entity/HistoryFavouriteEntity.kt`
**Steps:**
1. Add to `TASKS.md` section J four rows: T347 (#19), T348 (#18), T349 (#3),
   T350 (#17), each `Phase P → M`/`V`/`Doc`, status `ACTIVE`.
2. In `HistoryFavouriteEntity.kt` add field after `lastPositionMs`:
   `val followLive: Boolean = false,` (keep trailing comma order; default makes it
   additive). Update the KDoc to note the snapshot-vs-follow axis.
3. Compile only: `./gradlew compileDebugKotlin`
   **Expected:** `BUILD SUCCESSFUL`. (Room will now demand a v19 migration at
   runtime; that lands in 19.3 — compile is unaffected.)
4. Commit: `chore(star): add followLive column to HistoryFavouriteEntity (#19 scaffold)`.

#### Task 19.2 — TDD: failing migration test FIRST (#19, schema-free Robolectric)
**Files:** `app/src/test/java/com/powermediaplayer/data/db/HistoryFavouriteMigration18to19Test.kt`
**Steps:**
1. Create the test (schema-free — does NOT use `MigrationTestHelper`; see
   "Hard constraint" above). Full content:
   ```kotlin
   package com.powermediaplayer.data.db

   import android.content.Context
   import androidx.sqlite.db.SupportSQLiteDatabase
   import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
   import androidx.sqlite.db.SupportSQLiteOpenHelper
   import androidx.test.core.app.ApplicationProvider
   import org.junit.Assert.assertEquals
   import org.junit.Assert.assertTrue
   import org.junit.Test
   import org.junit.runner.RunWith
   import org.robolectric.RobolectricTestRunner
   import org.robolectric.annotation.Config

   /**
    * #19 — v18→v19 adds history_favourites.followLive as an additive,
    * NOT-NULL-DEFAULT-0 column. Existing rows must survive with followLive=0.
    * Schema-free: builds the v18 row shape by raw SQL (the repo runs
    * exportSchema=false, so MigrationTestHelper — which loadSchema()s an
    * exported JSON — cannot run here).
    */
   @RunWith(RobolectricTestRunner::class)
   @Config(sdk = [33])
   class HistoryFavouriteMigration18to19Test {

       private fun openV18(): SupportSQLiteDatabase {
           val ctx = ApplicationProvider.getApplicationContext<Context>()
           ctx.deleteDatabase("migr-test.db")
           val helper = FrameworkSQLiteOpenHelperFactory().create(
               SupportSQLiteOpenHelper.Configuration.builder(ctx)
                   .name("migr-test.db")
                   .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                       override fun onCreate(db: SupportSQLiteDatabase) {
                           // v18 shape of history_favourites (no followLive).
                           db.execSQL(
                               """
                               CREATE TABLE history_favourites (
                                   id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                                   mediaUri TEXT NOT NULL,
                                   title TEXT NOT NULL,
                                   subtitle TEXT NOT NULL,
                                   artworkUri TEXT,
                                   source TEXT NOT NULL,
                                   mediaKindOrdinal INTEGER NOT NULL,
                                   lastPositionMs INTEGER NOT NULL,
                                   durationMs INTEGER NOT NULL,
                                   pinOrder INTEGER NOT NULL,
                                   pinnedAtMs INTEGER NOT NULL
                               )
                               """.trimIndent()
                           )
                       }
                       override fun onUpgrade(db: SupportSQLiteDatabase, o: Int, n: Int) {}
                   })
                   .build()
           )
           return helper.writableDatabase
       }

       @Test
       fun migrate18to19_addsFollowLive_preservesRows_default0() {
           val db = openV18()
           db.execSQL(
               "INSERT INTO history_favourites " +
                   "(mediaUri,title,subtitle,artworkUri,source,mediaKindOrdinal," +
                   "lastPositionMs,durationMs,pinOrder,pinnedAtMs) " +
                   "VALUES ('u','t','s',NULL,'DRIVE',0,12345,99999,0,1)"
           )
           // Apply the production migration body directly.
           AppDatabase.MIGRATION_18_19.migrate(db)

           // Column exists.
           val cols = db.query("PRAGMA table_info(history_favourites)").use { c ->
               buildList { while (c.moveToNext()) add(c.getString(1)) }
           }
           assertTrue("followLive column added", cols.contains("followLive"))

           // Existing row preserved with default 0.
           db.query("SELECT lastPositionMs, followLive FROM history_favourites WHERE mediaUri='u'")
               .use { c ->
                   assertTrue(c.moveToFirst())
                   assertEquals(12345L, c.getLong(0))
                   assertEquals(0, c.getInt(1)) // default 0 = fixed
               }
       }
   }
   ```
2. Run (RED — `MIGRATION_18_19` does not exist yet → compile error is the
   expected failing state):
   `./gradlew testDebugUnitTest --tests "*HistoryFavouriteMigration18to19Test*"`
   **Expected:** compile failure `unresolved reference: MIGRATION_18_19`
   (RED proves the test is wired to the not-yet-written migration).
3. Commit: `test(star): RED migration test for history_favourites.followLive (#19)`.

#### Task 19.3 — Implement `MIGRATION_18_19` + register (turn 19.2 GREEN)
**Files:** `…/data/db/AppDatabase.kt`, `…/di/AppModule.kt`
**Steps:**
1. In `AppDatabase.kt` bump `version = 18` → `version = 19`; add to the version
   comment block:
   `// v19: #19 starred-position UX — history_favourites gains followLive`
   `//      (per-star "follow live position" flag). Additive.`
2. Add the migration next to `MIGRATION_17_18`:
   ```kotlin
   // v18 → v19 (#19): per-star follow-live flag. Additive ALTER; existing
   // pins default to 0 (= fixed snapshot, no behaviour change).
   val MIGRATION_18_19: Migration = object : Migration(18, 19) {
       override fun migrate(db: SupportSQLiteDatabase) {
           db.execSQL(
               "ALTER TABLE history_favourites ADD COLUMN followLive INTEGER NOT NULL DEFAULT 0"
           )
       }
   }
   ```
   (Verified against Context7 `/androidx/androidx`: an additive
   `ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT …` is the canonical Room manual
   migration body; the same idiom the repo already uses at `MIGRATION_11_12`,
   `MIGRATION_16_17`, `MIGRATION_17_18`.)
3. In `AppModule.kt` `.addMigrations(…)` append `AppDatabase.MIGRATION_18_19,`
   after `AppDatabase.MIGRATION_17_18`.
4. Run GREEN: `./gradlew testDebugUnitTest --tests "*HistoryFavouriteMigration18to19Test*"`
   **Expected:** `BUILD SUCCESSFUL`, `HistoryFavouriteMigration18to19Test > migrate18to19_… PASSED`.
5. Commit: `feat(star): MIGRATION_18_19 adds followLive (GREEN) (#19)`.

#### Task 19.4 — TDD: position-resolver (fixed vs live), pure JVM
**Files:** `app/src/test/java/com/powermediaplayer/lastplayed/StarPositionResolverTest.kt`
**Steps:**
1. Create the test against a pure function to be added in 19.5:
   ```kotlin
   package com.powermediaplayer.lastplayed

   import com.powermediaplayer.data.repository.StarPositionResolver
   import org.junit.Assert.assertEquals
   import org.junit.Test

   class StarPositionResolverTest {
       @Test fun fixed_usesSnapshot_ignoresLive() {
           // followLive=false → always the pinned snapshot, even if live moved on.
           assertEquals(
               10_000L,
               StarPositionResolver.resolve(
                   followLive = false, snapshotMs = 10_000L, liveMs = 73_419L
               )
           )
       }
       @Test fun live_usesLive_whenPresent() {
           assertEquals(
               73_419L,
               StarPositionResolver.resolve(
                   followLive = true, snapshotMs = 10_000L, liveMs = 73_419L
               )
           )
       }
       @Test fun live_fallsBackToSnapshot_whenNoLiveRow() {
           // followLive=true but the playback_history row was deleted (null) →
           // fall back to the snapshot, never to 0.
           assertEquals(
               10_000L,
               StarPositionResolver.resolve(
                   followLive = true, snapshotMs = 10_000L, liveMs = null
               )
           )
       }
       @Test fun explicitOverride_alwaysWins() {
           // A bookmark tap passes an explicit position → wins over both.
           assertEquals(
               42L,
               StarPositionResolver.resolve(
                   followLive = true, snapshotMs = 10_000L, liveMs = 73_419L,
                   explicitMs = 42L
               )
           )
       }
   }
   ```
2. Run (RED): `./gradlew testDebugUnitTest --tests "*StarPositionResolverTest*"`
   **Expected:** compile failure `unresolved reference: StarPositionResolver` (RED).
3. Commit: `test(star): RED fixed-vs-live position resolver (#19)`.

#### Task 19.5 — Implement resolver + DAO live-position query + repo wiring (19.4 GREEN)
**Files:** `…/data/db/dao/PlaybackHistoryDao.kt`, `…/data/repository/LastPlayedRepository.kt`
**Steps:**
1. Add a one-shot suspend sibling to `observePositionFor` in `PlaybackHistoryDao.kt`:
   ```kotlin
   /** One-shot resume position of the most-recent row for a uri (live value
    *  used by a follow-live starred row at play time). Null = never played. */
   @Query(
       "SELECT lastPositionMs FROM playback_history WHERE mediaUri = :uri " +
           "ORDER BY lastPlayedAt DESC LIMIT 1"
   )
   suspend fun positionForUri(uri: String): Long?
   ```
   (Mirrors the existing `observePositionFor` Flow at `:93-97`; suspend variant so
   the resolve is a single point-read, no Flow subscription on the play path.)
2. Add the pure resolver (own top-level object so it is trivially unit-testable —
   no Android, no DB) in `LastPlayedRepository.kt` (top-level, same file):
   ```kotlin
   /** #19 — decides the resume position for a starred row: explicit tap wins;
    *  else live playback_history position when the star follows live (falling
    *  back to the snapshot if no live row exists); else the pinned snapshot. */
   object StarPositionResolver {
       fun resolve(
           followLive: Boolean,
           snapshotMs: Long,
           liveMs: Long?,
           explicitMs: Long? = null
       ): Long = explicitMs
           ?: if (followLive) (liveMs ?: snapshotMs) else snapshotMs
   }
   ```
3. Add a repo pass-through `suspend fun livePositionForUri(uri: String): Long? =
   historyDao.positionForUri(uri)`.
4. Run GREEN: `./gradlew testDebugUnitTest --tests "*StarPositionResolverTest*"`
   **Expected:** `BUILD SUCCESSFUL`, 4 tests PASSED.
5. Commit: `feat(star): StarPositionResolver + positionForUri live query (GREEN) (#19)`.

#### Task 19.6 — Thread `followLive` through pin + pinned HistoryItem
**Files:** `…/data/repository/LastPlayedRepository.kt`, `…/ui/lastplayed/LastPlayedViewModel.kt`
**Steps:**
1. In `LastPlayedRepository.HistoryItem` add `val followLive: Boolean = false`
   (additive; Recents map sets it `false`, pinned map sets it from the entity).
2. In `observePinned` map, set `followLive = f.followLive`.
3. Change `pinSession(historyId: Long)` →
   `pinSession(historyId: Long, followLive: Boolean = false)`; pass
   `followLive = followLive` into the `HistoryFavouriteEntity(...)` builder
   (`LastPlayedRepository.kt:356-368`). Default keeps every existing caller behaviour
   identical.
4. In `LastPlayedViewModel.pinSession`, mirror the signature:
   `suspend fun pinSession(historyId: Long, followLive: Boolean = false): Boolean =
   repo.pinSession(historyId, followLive).isSuccess`.
5. Compile: `./gradlew compileDebugKotlin` **Expected:** `BUILD SUCCESSFUL`.
6. Commit: `feat(star): persist followLive through pinSession + pinned HistoryItem (#19)`.

#### Task 19.7 — Resolve live position at play time in `playLocalAt`
**Files:** `…/ui/lastplayed/LastPlayedViewModel.kt`
**Steps:**
1. LOCAL/Drive branch — replace `val targetPos = atPositionMs ?: item.lastPositionMs`
   (`:465`) with a follow-live-aware resolve computed inside the existing IO scope
   (so the DB read is off-Main):
   ```kotlin
   // #19 — a follow-live star resumes from the LIVE playback_history position
   // (resolved now), not the snapshot frozen at pin time. Fixed stars + Recents
   // (followLive=false) keep the snapshot. An explicit bookmark tap wins.
   val liveMs = if (item.followLive)
       withContext(Dispatchers.IO) { /* repo */ liveForUri(item.mediaUri) } else null
   val targetPos = com.powermediaplayer.data.repository.StarPositionResolver.resolve(
       followLive = item.followLive,
       snapshotMs = item.lastPositionMs,
       liveMs = liveMs,
       explicitMs = atPositionMs
   ).coerceAtLeast(0L)
   ```
   (Add a tiny private `suspend fun liveForUri(uri) = repo.livePositionForUri(uri)`
   or call the repo directly. The resolve sits BEFORE `setMediaItems`; the existing
   `setMediaItems(... startPositionMs = if (reversed) 0L else targetPos)` is unchanged.)
2. SPOTIFY branch — same resolve replaces `val targetPos = atPositionMs ?: item.lastPositionMs`
   (`:320`). The live read must run inside `viewModelScope.launch(Dispatchers.IO)`
   that already wraps the Spotify play; resolve before `armProvisionalMirror`/`seekTo`
   so the provisional mirror's `positionMs` and the `/seek` both use the live value.
   (NOTE: the provisional mirror is armed on Main before the coroutine in the current
   code; restructure minimally so the resolve precedes the arm — compute `targetPos`
   in a small `viewModelScope.launch` that then arms + plays, or read the live value
   synchronously via `runBlocking` ONLY if Main-safe; prefer moving the arm inside the
   IO coroutine. Keep all existing diag lines.)
3. Add a one-line DiagLog so the choice is traceable:
   `DiagLog.resume("star resolve followLive=${item.followLive} snapshot=${item.lastPositionMs} live=${liveMs} → $targetPos")`.
4. Compile + full unit suite:
   `./gradlew compileDebugKotlin && ./gradlew testDebugUnitTest`
   **Expected:** `BUILD SUCCESSFUL`; all suites green incl. the two new #19 tests.
5. Commit: `feat(star): resolve live position at play for follow-live stars (#19)`.

#### Task 19.8 — Star-tap dialog (Fixed / Follow live)
**Files:** `…/ui/lastplayed/LastPlayedScreen.kt`
**Steps:**
1. Add a screen-level `var pinChoiceFor by remember { mutableStateOf<HistoryItem?>(null) }`.
2. Replace the Recents star `IconButton(onClick { scope.launch { pinSession(item.id) … } })`
   (`:327-336`) so that, for an UNPINNED row, the tap sets `pinChoiceFor = item`
   (open the dialog) instead of pinning immediately; for an already-pinned row keep
   the current behaviour (the star is the live "is pinned" hint — leave its tap as a
   no-op or route to the existing context-menu unpin; do NOT change unpin semantics).
3. Add an `AlertDialog` (precedent: `SleepTimerDialog`, referenced in spec item 4)
   shown when `pinChoiceFor != null`:
   - Title: "Save to Favourites".
   - Body: two radio rows — **"Fixed position"** ("Always resume from where you
     starred it") and **"Follow live"** ("Resume from wherever you last left off").
     Default selection = Fixed (followLive=false).
   - Confirm: `scope.launch { val ok = viewModel.pinSession(item.id, followLive);
     if (!ok) snackbar.showSnackbar("Favourites full (10/10) — unpin one first") };
     pinChoiceFor = null`.
   - Dismiss: `pinChoiceFor = null`.
4. Use plain-English copy only (CLAUDE.md writing style — no jargon).
5. Compile + assemble: `./gradlew assembleDebug`
   **Expected:** `BUILD SUCCESSFUL`.
6. Commit: `feat(star): Fixed/Follow-live dialog on star tap (#19)`.

#### Task 19.9 — #19 gate (machine-checkable)
**Steps:**
1. `./gradlew testDebugUnitTest` **Expected:** all suites PASS incl.
   `HistoryFavouriteMigration18to19Test` (1) + `StarPositionResolverTest` (4).
2. `./gradlew assembleDebug` **Expected:** `BUILD SUCCESSFUL`.
3. Grep proofs:
   - `rg -n "followLive" app/src/main` → entity + migration + DAO/repo + VM + screen hits.
   - `rg -n "MIGRATION_18_19" app/src/main` → defined (AppDatabase) + registered (AppModule).
   - `rg -n "version = 19" app/src/main/.../AppDatabase.kt` → 1 hit.
4. Update `TASKS.md` T347 → `DONE(<evidence: test names + assembleDebug + greps>)`.
5. `[DEVICE] AWAITING-USER`: star a mid-played item → choose **Follow live** →
   listen further on another path → tap the starred row → resumes from the NEWER
   position (not the pin-time snapshot). Star another → choose **Fixed** → it always
   returns to the starred spot. Existing pins (pre-v19) behave exactly as before.

---

### #18 — No auto-resume after star: FIX (warm-reopen surfacing) + VERIFY

> Investigation (spec item 18): the star is causally inert (`pinSession` writes
> only `history_favourites`); the cold-start adoption guard is correct. But the
> user experienced a real "no resume on reopen". Per the no-downgrade directive
> this is FIXED, not just verified: the warm-reopen Player/mini-bar surfacing fix
> (18.3) ships UNCONDITIONALLY so the UI always reflects the live/last session on
> reopen. The device repro (18.1) + verdict (18.2) confirm it and catch any
> residual audio-stopped case (18.3b). The cold-start adoption guard and
> `pinSession` are untouched (additive surfacing only).

#### Task 18.1 — Device repro protocol + capture
**Files:** `docs/superpowers/specs/2026-06-24-star-resume-ui-repro.md` (new)
**Steps:**
1. Write the protocol doc with the exact repro:
   - Enable Settings → Diagnostic logging (DiagLog → `diag/log-current.txt`).
   - Play a Drive audiobook; mid-play, tap the star (pin it).
   - Send the app to background (Home), do NOT swipe-kill; reopen.
   - Record, per reopen: (a) does audio keep playing? (b) does the **Player tab**
     show the item (title/cover/transport), or "nothing's playing"? (c) does the
     mini-bar show it? Screenshot each.
2. `[DEVICE] AWAITING-USER` to run it (this is the genuine external blocker — needs
   the user's phone + their Drive session; everything automatable is done).
3. Pull + line up by wall-clock timestamp:
   `python tools/deeplog/parse_logs.py … --category DEC,RESUME,PLAYER` and correlate
   with the screenshots. Look specifically for the `session-already-adopted → skip`
   DEC line co-occurring with a Player tab that shows empty.

#### Task 18.2 — Verdict + documentation (always runs)
**Files:** `docs/superpowers/specs/2026-06-24-star-resume-ui-repro.md`
**Steps:**
1. Record the trace verdict with evidence (both the DEC `session-already-adopted`
   line and the screenshots), classifying the observed reopen state:
   - **Engine playing + UI showed it** → surfacing already correct on this path;
     18.3 still ships as a hardening guarantee (it must hold on EVERY reopen path,
     not just the one traced).
   - **Engine playing + UI showed empty** → the confirmed surfacing gap 18.3 fixes.
   - **Engine STOPPED** → additionally triggers 18.3b (audio-resume robustness).
2. Document that `pinSession` (writes only `history_favourites`) and the
   cold-start adoption guard are correct and stay untouched; the fix is purely
   additive UI/session surfacing.

#### Task 18.3 — Warm-reopen session-surfacing fix (UNCONDITIONAL)
**Files:** (located by 18.3 step 1) the Player-tab state source +/or the
warm-reopen state push — likely `…/ui/player/PlayerViewModel.kt` empty-state gate
and/or `…/playback/PlaybackSessionCoordinator.kt` reopen hand-off to UI state.
**Steps:**
1. systematic-debugging: locate the exact state-source the Player tab + mini-bar
   read on warm reopen vs the live service session. Add a DiagLog line capturing
   `(reopenKind, serviceHasSession, uiShowsSession)` so the seam is observable.
2. Implement the guarantee (ALWAYS, not gated): on every reopen, if the service
   holds an active or last session, the Player UI state + mini-bar reflect it
   (title/cover/transport/position). Push the live session into the UI state on
   reopen. Keep it ADDITIVE — no change to `pinSession`, cold-start adoption, or
   the `session-already-adopted` guard. Express the decision as a small pure
   helper (e.g. `fun shouldSurfaceSession(serviceHasSession, uiHasSession)`) so it
   carries a JVM unit test (RED→GREEN) like the other seams in this plan.
3. `./gradlew testDebugUnitTest --tests "*SessionSurface*"` then
   `./gradlew assembleDebug` **Expected:** test GREEN, `BUILD SUCCESSFUL`; commit.

#### Task 18.3b — Audio-resume robustness (runs iff 18.2 found audio STOPPED)
**Files:** (decided by the trace) the cold-start / process-death resume path
(`PlaybackSessionCoordinator`), NOT the working warm path.
**Steps (entered only if 18.2 classed the reopen as engine-STOPPED):**
1. systematic-debugging from the trace: identify why a genuinely-killed session
   did not auto-resume on next open; write a failing log/assertion.
2. Fix the real resume gap (minimal, evidence-locked — per the no-guesswork
   memory). Do not touch the correct `session-already-adopted` skip for the
   still-alive case.
3. `./gradlew assembleDebug` **Expected:** `BUILD SUCCESSFUL`; commit;
   `[DEVICE] AWAITING-USER` re-verify.

#### Task 18.4 — #18 gate
**Steps:**
1. `./gradlew testDebugUnitTest` → the new surfacing seam test PASSES.
2. `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
3. `TASKS.md` T348 → `DONE(surfacing fix <commit> + repro <doc path>)`; if the
   device repro is still pending the user's phone, append
   `[DEVICE] AWAITING-USER(run repro per docs/superpowers/specs/2026-06-24-star-resume-ui-repro.md)` —
   the CODE fix ships regardless; only the on-device confirmation is user-gated.

---

### #3 — Storage hygiene: evict orphaned caches on offline-remove + cap ArtworkCache

#### Task 3.1 — TDD: failing eviction + cap test FIRST (Robolectric)
**Files:** `app/src/test/java/com/powermediaplayer/offline/OfflineCacheEvictionTest.kt`
**Steps:**
1. Create the test (mirrors `ChapterCacheTest`'s Robolectric pattern; uses
   `ApplicationProvider` context for the real `cacheDir`):
   ```kotlin
   package com.powermediaplayer.offline

   import android.content.Context
   import androidx.test.core.app.ApplicationProvider
   import com.powermediaplayer.util.ArtworkCache
   import com.powermediaplayer.util.ChapterCache
   import org.junit.Assert.assertNotNull
   import org.junit.Assert.assertNull
   import org.junit.Assert.assertTrue
   import org.junit.Test
   import org.junit.runner.RunWith
   import org.robolectric.RobolectricTestRunner
   import org.robolectric.annotation.Config

   @RunWith(RobolectricTestRunner::class)
   @Config(sdk = [33])
   class OfflineCacheEvictionTest {
       private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

       @Test fun artwork_evict_deletesByKey() {
           val key = "content://drive/file-A"
           assertNotNull(ArtworkCache.write(ctx, key, ByteArray(1024) { 7 }))
           assertNotNull(ArtworkCache.uriFor(ctx, key))
           ArtworkCache.evict(ctx, key)
           assertNull("artwork file gone after evict", ArtworkCache.uriFor(ctx, key))
       }

       @Test fun chapter_evict_deletesByUri() {
           val cache = ChapterCache(maxEntries = 4)
           val dir = java.nio.file.Files.createTempDirectory("evict").toFile()
           cache.attachDiskStore(dir)
           cache.put("uriX", "?", android.os.Bundle())
           assertNotNull(cache.get("uriX", "?"))
           cache.evict(dir, "uriX")
           assertNull("chapter entry gone after evict", cache.get("uriX", "?"))
           val store = java.io.File(dir, "chapter-cache")
           assertTrue((store.listFiles()?.size ?: 0) == 0)
       }

       @Test fun artwork_trimToCap_evictsOldestOverBudget() {
           // Write enough covers to exceed the cap; oldest must be trimmed.
           // Cap is exposed for the test via ArtworkCache.CAP_BYTES.
           val big = ByteArray((ArtworkCache.CAP_BYTES / 4).toInt()) { 1 }
           ArtworkCache.write(ctx, "k1", big); Thread.sleep(5)
           ArtworkCache.write(ctx, "k2", big); Thread.sleep(5)
           ArtworkCache.write(ctx, "k3", big); Thread.sleep(5)
           ArtworkCache.write(ctx, "k4", big); Thread.sleep(5)
           ArtworkCache.write(ctx, "k5", big) // total 5×(cap/4)=1.25×cap → trim
           // After trim the dir total is <= cap and the oldest (k1) is gone.
           assertNull("oldest cover trimmed when over cap", ArtworkCache.uriFor(ctx, "k1"))
       }
   }
   ```
2. Run (RED): `./gradlew testDebugUnitTest --tests "*OfflineCacheEvictionTest*"`
   **Expected:** compile failure — `unresolved reference: evict` (ArtworkCache /
   ChapterCache) + `CAP_BYTES` (RED).
3. Commit: `test(storage): RED cache-evict + ArtworkCache-cap (#3)`.

#### Task 3.2 — Implement `ArtworkCache.evict` + LRU cap (3.1 partial GREEN)
**Files:** `…/util/ArtworkCache.kt`
**Steps:**
1. Add a public cap constant + LRU trim + evict:
   ```kotlin
   /** Unbounded cover-art growth was a real footprint leak (600 KB-1 MB each,
    *  no cap). LRU-trim to this budget on every write. */
   const val CAP_BYTES: Long = 32L * 1024 * 1024 // 32 MB ≈ 32-50 covers

   /** Delete the cached cover for [key] (called on offline-remove so the
    *  600 KB-1 MB file does not orphan in cacheDir until the OS evicts it). */
   fun evict(context: Context, key: String) {
       runCatching { fileFor(context, key).delete() }
   }

   /** Keep coverart/ under [CAP_BYTES]; delete oldest-by-lastModified first.
    *  Mirrors ChapterCache's disk sweep. */
   fun trimToCap(context: Context) {
       runCatching {
           val files = dir(context).listFiles() ?: return
           var total = files.sumOf { it.length() }
           if (total <= CAP_BYTES) return
           for (f in files.sortedBy { it.lastModified() }) {
               if (total <= CAP_BYTES) break
               total -= f.length()
               runCatching { f.delete() }
           }
       }
   }
   ```
2. Call `trimToCap(context)` at the end of `write` (after the `Uri.fromFile(f)`
   success path — trim runs once per new cover, cheap; do it inside the `runCatching`
   so a trim failure never fails the write).
3. Run: `./gradlew testDebugUnitTest --tests "*OfflineCacheEvictionTest*"`
   **Expected:** the two ArtworkCache tests PASS; the ChapterCache test still RED
   (`unresolved reference: evict`).
4. Commit: `feat(storage): ArtworkCache evict + 32 MB LRU cap (#3)`.

#### Task 3.3 — Implement `ChapterCache.evict` (3.1 GREEN)
**Files:** `…/util/ChapterCache.kt`
**Steps:**
1. Add (memory remove under `mapLock` + disk sibling delete by the uri-hash prefix,
   reusing the existing `sha`/`fileFor` naming):
   ```kotlin
   /** Drop [uri]'s entry from memory + disk (called on offline-remove). The disk
    *  file name is "<sha(uri).take(24)>-<sha(token).take(16)>.json"; deleting by
    *  the uri-hash prefix removes whatever token the entry was stored under. */
   fun evict(baseDir: java.io.File, uri: String) {
       synchronized(mapLock) { map.remove(uri) }
       val store = java.io.File(baseDir, "chapter-cache")
       val prefix = sha(uri).take(24) + "-"
       store.listFiles { f -> f.name.startsWith(prefix) }
           ?.forEach { runCatching { it.delete() } }
   }
   ```
   (The production call site passes `context.cacheDir` as `baseDir` — same arg the
   parser passes to `attachDiskStore`; the test passes its temp dir.)
2. Run GREEN: `./gradlew testDebugUnitTest --tests "*OfflineCacheEvictionTest*"`
   **Expected:** `BUILD SUCCESSFUL`, all 3 tests PASSED.
3. Commit: `feat(storage): ChapterCache evict by uri (GREEN) (#3)`.

#### Task 3.4 — Wire eviction into `OfflineMediaManager.deleteLocal`
**Files:** `…/offline/OfflineMediaManager.kt`
**Steps:**
1. In `deleteLocal(uri)` (`:147-163`), after the Drive branch's `Result.success`
   and the podcast branch's `Result.success` (i.e. for ANY successful local
   removal), evict the orphan caches for `uri` BEFORE returning success. Cleanest:
   compute success, then in a single tail block:
   ```kotlin
   // #3 — the durable file + DB row + DataStore pair are gone; also evict the
   // cover-art (600 KB-1 MB, uncapped) and chapter-cache entries keyed by this
   // uri, which deleteLocal previously orphaned in cacheDir until OS eviction.
   com.powermediaplayer.util.ArtworkCache.evict(context, uri)
   com.powermediaplayer.util.ChapterCache.shared.evict(context.cacheDir, uri)
   ```
   Place it so it runs on the Drive-success and podcast-success paths (not on the
   "nothing to delete" failure). Eviction is best-effort (`runCatching` inside the
   cache fns) so it can never turn a successful delete into a failure.
2. Compile + assemble: `./gradlew assembleDebug` **Expected:** `BUILD SUCCESSFUL`.
3. Commit: `feat(storage): evict orphaned art+chapter caches on offline remove (#3)`.

#### Task 3.5 — #3 gate
**Steps:**
1. `./gradlew testDebugUnitTest` **Expected:** all suites PASS incl.
   `OfflineCacheEvictionTest` (3).
2. `./gradlew assembleDebug` **Expected:** `BUILD SUCCESSFUL`.
3. Grep proofs:
   - `rg -n "ArtworkCache.evict|ChapterCache.shared.evict" app/src/main/.../OfflineMediaManager.kt` → both present in `deleteLocal`.
   - `rg -n "CAP_BYTES|fun trimToCap|fun evict" app/src/main/.../ArtworkCache.kt` → present.
   - `rg -n "fun evict" app/src/main/.../ChapterCache.kt` → present.
4. `TASKS.md` T349 → `DONE(<test names + assembleDebug + greps>)`.
5. `[DEVICE] AWAITING-USER` (optional, not blocking): download a Drive audiobook,
   confirm a `coverart/*.img` exists, remove offline, confirm the matching
   `coverart/*.img` + `chapter-cache/*.json` are gone.

---

### #17 — Background activity: VERIFY-HARDEN every vector + unconditional note

> Investigation: background activity is legitimate/bounded; no leaked loop; the
> strongest "when closed" attribution is a user-set `setAlarmClock` alarm. Per the
> no-downgrade directive the plan does real work: instrument + PROVE each vector
> terminates on close (17.2), hard-stop any that doesn't (17.3), and ship an
> unconditional transparency note (17.4).

#### Task 17.1 — Document the vectors (always runs)
**Files:** `docs/superpowers/specs/2026-06-24-background-activity.md` (new, dedicated)
**Steps:**
1. Record the five vectors + why each is bounded (FGS
   `onTaskRemoved`/`stopOnTaskRemoved`; `AlarmManager.setAlarmClock` — the
   legitimate "when closed" cause; `PodcastSyncWorker` 6 h Wi-Fi-only; Spotify
   1 Hz poll 30 s bg-stop; Hue DTLS playback-gated), each with its file:line.
2. Commit: `docs(background): record #17 vector inventory (#17)`.

#### Task 17.2 — Instrument + PROVE each vector terminates on close [impl + verify]
**Files:** the five vector owners — `…/playback/PlaybackService.kt`
(FGS `onTaskRemoved`), `…/cloud/SpotifyProvider.kt` (`stopPlaybackPolling`),
the Hue collector/disconnect path, `…/sync/PodcastSyncWorker.kt`, and the alarm
scheduler — add a single uniform DiagLog shutdown line per vector.
**Steps:**
1. Add `DiagLog.bg("<vector> stopped reason=<onTaskRemoved|background|cancel>")`
   at the exact teardown point of each vector (FGS stop, poll cancel, Hue
   disconnect, worker end). One line each; no behaviour change yet.
2. Build + install: `./gradlew installDebug`. Exercise: play → background → swipe
   the app from Recents; separately play → close while a clock alarm is set.
3. `bash tools/deeplog/pull_logs.sh` then
   `python tools/deeplog/parse_logs.py … --category BG` — confirm EVERY vector
   emits its stop line on close, and that after close no vector keeps logging
   (no orphaned poll ticks / collector frames). Record the trace in 17.1's doc.
4. **Expected:** each of the five logs a clean stop on close; only a set
   `setAlarmClock` remains scheduled (by design). Commit:
   `feat(background): uniform shutdown instrumentation to prove bounded vectors (#17)`.

#### Task 17.3 — Hard stop-on-close safeguard for any lingering vector [impl]
**Files:** whichever vector(s) 17.2 shows still active after close (expected: none,
on current evidence — but the task SHIPS the guarantee, not an assumption).
**Steps:**
1. For any vector whose stop line did NOT appear on close in 17.2, add an explicit
   teardown: e.g. ensure `PlaybackService.onTaskRemoved` calls
   `stopPlaybackPolling()` + Hue `disconnect()` when not actively playing; ensure
   the Spotify poll's 30 s bg-stop fires on `ON_STOP`; ensure the Hue collector is
   cancelled when playback ends. Evidence-locked per the trace — do not pre-empt a
   leak the trace didn't show (no-guesswork memory).
2. If 17.2 proved all five already terminate, record that explicitly: the
   safeguard is "already enforced by <file:line>" — no new code, with the trace as
   proof. (This is verification, not a downgrade: the guarantee is established.)
3. `./gradlew assembleDebug` **Expected:** `BUILD SUCCESSFUL`; commit if code added.

#### Task 17.4 — Transparency Settings note (UNCONDITIONAL) [impl]
**Files:** `…/ui/settings/InfoContent.kt` (near the alarm/sleep-timer/battery copy)
**Steps:**
1. Add one plain-English line (ships regardless): "If you set an alarm clock in
   the app, your phone may list the app as active in the background — that's the
   alarm waiting to ring, not continuous playback. Playback and syncing stop when
   you close the app."
2. Plain English only (CLAUDE.md style; supports the #11 layman drive).
3. `./gradlew assembleDebug` **Expected:** `BUILD SUCCESSFUL`; commit:
   `feat(background): plain-language note on alarm background attribution (#17)`.

#### Task 17.5 — #17 gate
**Steps:**
1. `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
2. Grep proofs: `rg -n "DiagLog.bg" app/src/main` → all five vectors instrumented;
   `rg -n "alarm waiting to ring" app/src/main` → the note present.
3. `TASKS.md` T350 → `DONE(instrumented+proved bounded <trace path>; note <commit>;
   safeguard <commit or 'already-enforced @file:line'>)`.
4. `[DEVICE]` evidence (from 17.2) recorded in the doc.

---

## Final anti-skip gate (run before any "done" report)

1. `./gradlew testDebugUnitTest` → ALL suites PASS, including the 4 new test
   classes (`HistoryFavouriteMigration18to19Test`, `StarPositionResolverTest`,
   `OfflineCacheEvictionTest`, `SessionSurfaceTest`). Paste the pass lines.
2. `./gradlew assembleDebug` → `BUILD SUCCESSFUL`. Paste the line.
3. Enumerate every promised sub-item and confirm implemented + verified:
   - #19: entity column ✓, v19 migration + registration ✓, DAO live query ✓,
     resolver ✓, repo/VM wiring ✓, dialog ✓, tests ✓. `[DEVICE]` follow-live behaviour.
   - #18: surfacing seam test ✓, warm-reopen surfacing fix ✓ (ships, additive),
     repro doc ✓, verdict ✓; 18.3b audio-resume fix iff trace shows audio stopped.
     `[DEVICE]` repro confirms.
   - #3: ArtworkCache evict + cap ✓, ChapterCache evict ✓, deleteLocal wiring ✓,
     tests ✓.
   - #17: vector doc ✓, shutdown instrumentation ✓, termination proved on trace ✓,
     hard-stop safeguard (or 'already-enforced' w/ trace proof) ✓, transparency note ✓.
4. `TASKS.md` table updated for T347-T350 with Evidence lines; report THE TABLE.
5. Auto-push + adb-install the debug APK (per MEMORY: after every turn with commits).

---

## Design decisions (confirm before execution)

1. **#19 default + dialog UX.** Plan defaults `followLive=false` (existing pins
   unchanged) and shows a 2-radio dialog (Fixed / Follow live, default Fixed) on the
   star tap of an UNPINNED Recents row. Confirm: (a) default Fixed acceptable?
   (b) dialog on every star, or only show it once + remember a per-user default?
   (c) should an already-pinned star expose a "switch this pin to Follow live"
   toggle, or is re-star-after-unpin enough?
2. **#19 — also keep updating the snapshot row when followLive?** Plan resolves the
   live position at PLAY time by querying `playback_history` and does NOT add a write
   path that mutates `history_favourites.lastPositionMs` during playback. This keeps
   the snapshot immutable and the live value always-current with zero new tick work.
   Confirm: read-at-play (planned) vs also persisting the live value back onto the
   favourite row (extra writes, redundant given the join). Recommendation: read-at-play.
3. **#19 migration test shape.** Because `exportSchema=false` (no schemas dir, no
   `room-testing`/`androidTest`), the migration test is a **schema-free Robolectric**
   test (hand-built v18 table + apply `MIGRATION_18_19` + assert). Confirm this over
   the alternative (flip `exportSchema=true`, regenerate 11 historical schema JSONs,
   add a `room-testing` instrumented `androidTest` harness) — the latter is heavier
   and unnecessary for an additive ALTER. Recommendation: schema-free Robolectric.
4. **#3 ArtworkCache cap size + eviction policy.** Plan = **LRU by `lastModified()`**,
   cap **32 MB** (~32-50 covers), trimmed on every write (mirrors ChapterCache's disk
   sweep). Confirm the 32 MB budget and LRU (vs a count cap, or size-tiered). Note:
   `write` currently bumps mtime only on a real (size-changed) write; LRU is
   "least-recently-WRITTEN", not "least-recently-READ" — acceptable for cover art.
   If true read-LRU is wanted, `write`/`uriFor` would need to `setLastModified(now)`
   on access (extra IO) — recommend NOT, write-LRU is sufficient.
5. **#17 / #18 — scope (per the no-downgrade directive, both now SHIP code).**
   - **#17**: instrument all five vectors + prove termination on a device trace
     (17.2), hard-stop any lingering one (17.3, evidence-locked per the trace), and
     ship the transparency note UNCONDITIONALLY (17.4). Confirm only the note wording.
   - **#18**: the warm-reopen surfacing fix (18.3) ships UNCONDITIONALLY (additive —
     no `pinSession` / cold-start change); 18.3b runs only if the trace shows audio
     genuinely stopped. The CODE ships; only the on-device confirmation is gated on
     your phone + Drive session. Confirm you're happy to run the repro afterward.
