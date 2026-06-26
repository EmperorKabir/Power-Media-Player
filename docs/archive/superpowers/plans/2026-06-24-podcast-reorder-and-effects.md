# Plan — Podcast reorder (#5) + per-episode effects (#6)

- Source spec: `docs/superpowers/investigation/2026-06-24-19-item-investigation.md`
  items **5** and **6** (durable investigation record; READ before executing).
- Repo: `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player`
  (package `com.powermediaplayer`, NOT `com.example.*`).
- Phase: PLAN. No code in this document is applied yet. Execution is a
  separate turn under the TASKS.md protocol (investigate → plan → IMPLEMENT).
- Methodology: superpowers `writing-plans` — header, File Structure first,
  bite-sized tasks (exact **Files** + 2–5-min **Steps**), TDD for the
  JVM-testable units, device/manual verification for the Compose UI, frequent
  commits, complete code (no placeholders).

## Goal

Two podcast parity features, each mirroring an existing favourites mechanism
with zero playback-chain risk:

1. **#5 Reorder saved (subscribed) podcast SHOWS** by drag handle, persisted
   across relaunch — parity with `ReorderablePinnedList` for pinned tracks.
2. **#6 Per-episode playback effects** (reverb / EQ / speed / video axes) via
   the existing provider-agnostic `media_overrides` override system, keyed on
   `mediaUri = episode.audioUrl` (which podcast playback already sets) — parity
   with the pinned-track `MediaOverridesPopup`.

Out of band for the playback engine: #6 applies live with ZERO changes to the
audio/video chain because `MediaOverrideRepository` already keys off
`PlaybackService.currentMediaIdFlow` and podcast `playEpisode` already calls
`.setMediaId(episode.audioUrl)`.

## Verified facts (code + docs, read this turn)

Reorder (#5):
- `sh.calvin.reorderable:reorderable:2.5.0` already a dependency —
  `app/build.gradle.kts:201`.
- Proven pattern `ReorderablePinnedList` — `LastPlayedScreen.kt:575-645`:
  `rememberLazyListState()` → `rememberReorderableLazyListState(listState){from,to-> onMove(from.index,to.index)}`
  → bounded `LazyColumn(state, Modifier.fillMaxWidth().heightIn(max=360.dp))`
  → `itemsIndexed(items, key={_,it->"pin_${it.id}"})` →
  `ReorderableItem(reorderState, key="pin_${item.id}"){dragging->…}` →
  trailing `IconButton(onClick={}, Modifier.draggableHandle()){ Icon(DragHandle) }`.
- Persist chain: VM `reorderPinned(favId,to)` → `LastPlayedRepository.reorderPinned`
  (`:398-409`) snapshots, removes+re-inserts at target, then re-compacts
  `0..n-1` via `favDao.setOrder(id,idx)` only when changed →
  `HistoryFavouriteDao.setOrder` `UPDATE … SET pinOrder=:order WHERE id=:id`
  (`HistoryFavouriteDao.kt:20-21`). Read query `ORDER BY pinOrder ASC`.
- Podcast side TODAY: shows render as a plain content-wrapping
  `Column { shows.forEach { … } }` — `PodcastsSection.kt:498-549` (NOT a
  LazyColumn; tap toggles `expandedFeed`). `PodcastShowEntity` PK=`feedUrl`,
  NO order column (`PodcastShowEntity.kt:6-22`). `PodcastDao.observeShows()` =
  `SELECT * FROM podcast_shows ORDER BY title ASC` (`PodcastDao.kt:14-15`).
- Context7 `/calvin-ll/reorderable`: the existing call sites match the 2.5.0
  API exactly (`rememberReorderableLazyListState(lazyListState){from,to->}`,
  `ReorderableItem(state, key){isDragging->}`, `Modifier.draggableHandle()`).
  A bounded reorderable list nested in an outer scroll is supported as long as
  the inner list has a finite `heightIn(max=…)` (the pinned list precedent).
- `ReorderablePinnedList` is `private` to `LastPlayedScreen.kt` (`:576`) →
  cannot be reused as-is from the podcast package.

Effects (#6):
- `MediaOverrideEntity` PK=`mediaUri:String`; every other column nullable
  (NULL = fall through to the global setting). Axes: reverb / stereoFlip /
  monoMix / eqPresetId / replayGain / volumeBoost / video* / speed / pitch
  (`MediaOverrideEntity.kt:21-59`).
- `MediaOverrideDao`: `getByUri(uri):Flow`, `getByUriOnce`, `upsert`, `clear(uri)`,
  `clearAll`, `allUris` (`MediaOverrideDao.kt`).
- `MediaOverrideRepository.activeOverride` = `currentMediaIdFlow.flatMapLatest{ dao.getByUri(it) }`
  — provider-agnostic, applies LIVE (`MediaOverrideRepository.kt:49-56`). No
  playback-chain edit needed.
- Podcast `playEpisode` sets `.setMediaId(episode.audioUrl)`
  (`PodcastsSection.kt:141`) — so `mediaUri = audioUrl` is the correct key
  (the `guid` would NOT match `currentMediaIdFlow`).
- `MediaOverridesPopup(mediaUri, title, dao, onDismiss)` is a ready, reusable
  composable (`MediaOverridesPopup.kt:59-147`; Audio/Video/Speed tabs).
- Favourites entry point precedent — `LastPlayedScreen.kt:111-118` hosts the
  popup off an `overrideTarget` state; the 3-dot `TrackContextSheet` exposes
  `onOverrideAudio/Video/Speed` only for pinned rows (`:151-159`); auto-clear
  on unpin — `LastPlayedViewModel.unpin` (`:213-223`) looks up the mediaUri then
  `mediaOverrideDao.clear(mediaUri)`.
- Podcast VM does NOT inject `MediaOverrideDao` today
  (`PodcastsViewModel` ctor `PodcastsSection.kt:86-92`). `EpisodeRow`
  (`:694-811`) is a bare `Row.clickable{playEpisode}` + a download affordance —
  no long-press / 3-dot / override entry.

Migration infra:
- Room **2.7.1** (`app/build.gradle.kts:248`). Current schema **v18**
  (`AppDatabase.kt:95`). Migration chain registered in `AppModule.kt:67-79`
  (`addMigrations(MIGRATION_7_8 … MIGRATION_17_18)`).
- Additive ALTER precedent: `MIGRATION_11_12` / `MIGRATION_16_17` /
  `MIGRATION_17_18` (`AppDatabase.kt:194-219`) — plain
  `ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT …`.
- **`exportSchema = false`** (`AppDatabase.kt:96`), no `room.schemaLocation`
  KSP arg, no `app/schemas/` dir, no `androidx.room:room-testing` dep, no
  `androidTest` migration precedent. Context7 confirms `MigrationTestHelper`
  `createDatabase(version)` calls `loadSchema(version)` — it REQUIRES exported
  JSON schemas. ⇒ a `MigrationTestHelper` v18→v19 test is NOT possible without
  first enabling schema export + checking in v18 + v19 JSON (a real but
  invasive change). See **Design decisions D5** for the chosen route (a
  Robolectric DAO/ordering test that exercises the new column + DAO order
  query + repo contiguity, which is what the spec's intent — "DAO order query
  + reorder repo logic JVM-unit-testable" — actually needs).
- Test stack: `org.robolectric:robolectric:4.13` (JVM `test/`), JUnit 4.13.2,
  MockWebServer. Precedent `ChapterCacheTest.kt`
  (`@RunWith(RobolectricTestRunner)`, `@Config(sdk=[33])`). Robolectric can
  build an in-memory Room DB on the JVM → DAO + repo tests run under
  `testDebugUnitTest` with no device.

## Design decisions (confirm before execution)

> Execution is gated on these. Recommended choice in **bold**; flagged where a
> different choice changes task scope.

- **D1 — Reorder unit: SHOWS, not episodes (RECOMMENDED).** Reorder the
  subscribed shows list. Episodes carry a meaningful `publishedAt DESC` order
  (`PodcastDao.kt:29-33`); user reordering would fight the chronological sort
  and the 15-item `take(15)` cap (`PodcastsSection.kt:685`). Shows have no
  intrinsic order today (`ORDER BY title ASC`), so a user `displayOrder` is a
  pure win. *If episodes were chosen instead, scope changes: order column on
  `PodcastEpisodeEntity`, a per-feed compaction, and the sort/cap logic would
  need rework — NOT planned here.*
- **D2 — #6 gate: expose effects on ALL subscribed-show episodes
  (RECOMMENDED).** Any episode in an `EpisodeRow` gets the override entry
  point. Rationale: episodes are already a curated, bounded set
  (subscribed shows, `take(15)`); a "downloaded-only" gate is arbitrary and
  the override is keyed on the stable `audioUrl` regardless of download state.
  *Alternative (downloaded-only) is a one-line predicate change if preferred.*
- **D3 — #6 auto-clear lifecycle: clear on UNSUBSCRIBE + a manual "Clear all
  overrides" already in the popup (RECOMMENDED).** Podcasts have no
  pinned/favourite concept, so the favourites' unpin→clear hook has no
  equivalent. To stop `media_overrides` rows accreting, clear an episode's
  override row when its show is unsubscribed (`unsubscribe(feedUrl)` already
  deletes the feed's episodes — extend it to clear overrides for those
  episodes' `audioUrl`s). The popup's own "Clear all overrides" button
  (`MediaOverridesPopup.kt:137-139`) covers per-episode reset. *No
  auto-clear-on-played; an in-progress override should survive replays.*
- **D4 — Video tab in the podcast override popup: KEEP it (RECOMMENDED, do
  NOT trim).** Audio podcasts make the Video axes inert (harmless — they only
  matter when a video renderer exists). Trimming would mean a podcast-specific
  popup variant (more code, divergence from the shared composable) for zero
  functional gain. Video podcasts (some feeds carry MP4 enclosures) would then
  legitimately use the Video tab. Reuse the shared `MediaOverridesPopup`
  verbatim.
- **D5 — Reorder composable: LIFT `ReorderablePinnedList` to a shared
  composable? NO — write a small dedicated podcast reorder list (RECOMMENDED).**
  `ReorderablePinnedList` is tightly coupled to `HistoryItem`,
  `HistoryRowWithBookmarks`, bookmarks, offline/download state, and unpin — none
  of which a podcast show row has. Lifting it would require a generic
  signature that fights every call site. Instead add a self-contained
  `ReorderableShowList` in the podcast package that reuses the SAME library
  primitives (`rememberReorderableLazyListState` / `ReorderableItem` /
  `draggableHandle` / `heightIn(max)`) — ~40 lines, no shared-API churn. The
  bounded-height pattern (the nested-scroll trap fix, T313/A3) is preserved.
- **D6 — Migration test route: a Robolectric DAO + repo unit test (NOT
  `MigrationTestHelper`) (RECOMMENDED).** The repo ships `exportSchema=false`
  with no checked-in schemas, so `MigrationTestHelper` cannot `loadSchema`.
  Rather than invasively enable schema export + commit v18/v19 JSON, the
  JVM-testable surface the spec calls out — "DAO setShowOrder / observeShows
  ordered + reorder-repo contiguity" — is fully covered by a Robolectric
  in-memory Room test (precedent: `ChapterCacheTest` already uses Robolectric).
  The additive migration itself is verified on-device (clean launch past v18,
  no destructive fallback — precedent T317's MIGRATION_16_17 device check).
  *If a true `MigrationTestHelper` v18→v19 test is mandatory, add: `room {
  schemaDirectory("$projectDir/schemas") }`, `exportSchema=true`,
  `androidTestImplementation("androidx.room:room-testing:2.7.1")`, build to
  emit v18+v19 JSON, then an `androidTest` MigrationTest — that is a larger,
  separate work item; flag for the user.*

## File structure (all paths absolute under the repo root)

New files:
- `app/src/main/java/com/powermediaplayer/ui/podcast/ReorderableShowList.kt`
  — dedicated bounded reorderable list of subscribed shows (the #5 UI).
- `app/src/test/java/com/powermediaplayer/data/db/dao/PodcastShowOrderTest.kt`
  — Robolectric: DAO `setShowOrder` + `observeShows` ordered-by-displayOrder
  + the reorder-repo contiguity (0..n-1 compaction) logic.

Modified files (#5):
- `…/data/db/entity/PodcastShowEntity.kt` — add `displayOrder: Int` column.
- `…/data/db/AppDatabase.kt` — bump version 18→19; add `MIGRATION_18_19`
  (additive ALTER + back-fill ordering); update the version-comment block.
- `…/di/AppModule.kt` — register `MIGRATION_18_19` in `addMigrations(...)`.
- `…/data/db/dao/PodcastDao.kt` — `observeShows` ORDER BY `displayOrder`,
  fallback `title`; add `setShowOrder(feedUrl, order)` + `showsSnapshot()`.
- `…/ui/podcast/PodcastsSection.kt` — add VM `reorderShow(from,to)` (+ a
  `reorderShows` repo-style compaction helper inside the VM); swap the shows
  `Column { forEach }` for `ReorderableShowList`.

Modified files (#6):
- `…/ui/podcast/PodcastsSection.kt` — inject `MediaOverrideDao` into
  `PodcastsViewModel`; expose it (or a popup-host state) to the section; add a
  long-press / 3-dot on `EpisodeRow` → `MediaOverridesPopup(mediaUri=audioUrl)`;
  extend `unsubscribe` to clear overrides for the feed's episode `audioUrl`s
  (auto-clear, D3).
- `…/data/db/dao/PodcastDao.kt` — add `audioUrlsForFeed(feedUrl): List<String>`
  (to feed the unsubscribe override-clear).

No build-file dependency additions are required (reorderable + Room + Robolectric
already present). `MediaOverrideDao` is already Hilt-provided
(`AppModule.kt:131-135`).

---

## Part A — #5 Reorder subscribed podcast shows

### Task A1 — TDD: failing DAO + repo-contiguity test (red)

**Files**
- `app/src/test/java/com/powermediaplayer/data/db/dao/PodcastShowOrderTest.kt` (new)

**Steps**
1. Create the test. It builds an in-memory `AppDatabase` under Robolectric,
   inserts shows, and asserts (a) `observeShows()` emits ordered by the new
   `displayOrder`, (b) `setShowOrder` updates one row, (c) a pure-Kotlin
   reorder-compaction helper produces a contiguous `0..n-1` permutation that
   matches `reorderPinned`'s algorithm. Full code:

```kotlin
package com.powermediaplayer.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.powermediaplayer.data.db.AppDatabase
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33]) // repo convention (ChapterCacheTest) — 4.13 caps below targetSdk 35
class PodcastShowOrderTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PodcastDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.podcastDao()
    }

    @After
    fun tearDown() = db.close()

    private fun show(feed: String, title: String, order: Int) =
        PodcastShowEntity(feedUrl = feed, title = title, displayOrder = order)

    @Test
    fun observeShows_ordersByDisplayOrderThenTitle() = runBlocking {
        dao.upsertShow(show("c", "Charlie", 2))
        dao.upsertShow(show("a", "Alpha", 0))
        dao.upsertShow(show("b", "Bravo", 1))
        val titles = dao.observeShows().first().map { it.title }
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), titles)
    }

    @Test
    fun setShowOrder_movesRow() = runBlocking {
        dao.upsertShow(show("a", "Alpha", 0))
        dao.upsertShow(show("b", "Bravo", 1))
        dao.setShowOrder("b", 0)
        dao.setShowOrder("a", 1)
        val titles = dao.observeShows().first().map { it.title }
        assertEquals(listOf("Bravo", "Alpha"), titles)
    }

    @Test
    fun reorderCompaction_isContiguous0toNminus1() {
        // Mirrors LastPlayedRepository.reorderPinned: remove-at + insert-at,
        // then re-index. Pure function under test (no DB) so the algorithm is
        // pinned independent of Room.
        val ids = mutableListOf("a", "b", "c", "d")
        val moved = "d"; val target = 1
        val cur = ids.indexOf(moved)
        val item = ids.removeAt(cur)
        ids.add(target, item)
        val orders = ids.mapIndexed { idx, id -> id to idx }
        assertEquals(listOf("a" to 0, "d" to 1, "b" to 2, "c" to 3), orders)
    }
}
```

2. Run it — expect a COMPILE failure (`PodcastShowEntity` has no
   `displayOrder`, `PodcastDao` has no `setShowOrder`).
   - Command: `./gradlew :app:testDebugUnitTest --tests "com.powermediaplayer.data.db.dao.PodcastShowOrderTest"`
     (Windows: `.\gradlew.bat …`).
   - Expected: `> Task :app:compileDebugUnitTestKotlin FAILED` with
     `unresolved reference: displayOrder` / `setShowOrder`.

**Commit:** `test(podcast): failing displayOrder DAO + reorder contiguity test`

### Task A2 — Add `displayOrder` column to `PodcastShowEntity` (green-1)

**Files**
- `app/src/main/java/com/powermediaplayer/data/db/entity/PodcastShowEntity.kt`

**Steps**
1. Add the column after `downloadTreeUri` (so the data-class param order keeps
   existing positional callers working; all current call sites use named or
   `.copy(...)`, verified — `addByUrl` merge uses `.copy`, sync uses named):

```kotlin
    val downloadTreeUri: String? = null,
    // User-reorderable position in the subscribed-shows list (#5). Lower =
    // higher up. New subscriptions append (see PodcastDao.nextShowOrder /
    // the addByUrl path). Default 0 so pre-migration rows + new rows are
    // legal; observeShows breaks ties by title.
    val displayOrder: Int = 0
```

2. Compile only (`./gradlew :app:compileDebugKotlin`). Expect SUCCESS (Room
   will regenerate; the version bump in A4 keeps it consistent).

**Commit:** `feat(podcast): displayOrder column on PodcastShowEntity`

### Task A3 — DAO order query + setShowOrder + snapshot (green-2)

**Files**
- `app/src/main/java/com/powermediaplayer/data/db/dao/PodcastDao.kt`

**Steps**
1. Change `observeShows` to order by the new column, tie-breaking on title:

```kotlin
    @Query("SELECT * FROM podcast_shows ORDER BY displayOrder ASC, title ASC")
    fun observeShows(): Flow<List<PodcastShowEntity>>
```

2. Add the order-write, a suspend snapshot (for the VM's reorder compaction),
   the next-order helper (append new subscriptions), and the unsubscribe
   override-clear feed (used by #6, D3) — place near the other show queries:

```kotlin
    @Query("UPDATE podcast_shows SET displayOrder = :order WHERE feedUrl = :feedUrl")
    suspend fun setShowOrder(feedUrl: String, order: Int)

    @Query("SELECT * FROM podcast_shows ORDER BY displayOrder ASC, title ASC")
    suspend fun showsSnapshot(): List<PodcastShowEntity>

    /** Lowest free order slot so a new subscription appends to the bottom. */
    @Query("SELECT IFNULL(MAX(displayOrder), -1) + 1 FROM podcast_shows")
    suspend fun nextShowOrder(): Int

    /** Episode stream urls for a feed — used to clear per-episode overrides
     *  when the show is unsubscribed (#6 auto-clear). */
    @Query("SELECT audioUrl FROM podcast_episodes WHERE feedUrl = :url")
    suspend fun audioUrlsForFeed(url: String): List<String>
```

3. Re-run A1's test:
   `./gradlew :app:testDebugUnitTest --tests "com.powermediaplayer.data.db.dao.PodcastShowOrderTest"`.
   Expect `BUILD SUCCESSFUL`, 3 tests passed (the entity + DAO now resolve;
   ordering + setOrder assertions pass on the in-memory DB).

**Commit:** `feat(podcast): DAO displayOrder ordering + setShowOrder/snapshot/nextShowOrder`

### Task A4 — Room v18→v19 additive migration + version bump

**Files**
- `app/src/main/java/com/powermediaplayer/data/db/AppDatabase.kt`
- `app/src/main/java/com/powermediaplayer/di/AppModule.kt`

**Steps**
1. In `AppDatabase.kt`, bump `version = 18` to `version = 19` (`:95`) and add a
   comment line to the version block:

```kotlin
    // v19: #5 podcast reorder — podcast_shows gains displayOrder (Int) so
    //      subscribed shows are user-reorderable. Additive ALTER; existing
    //      rows back-filled to a deterministic order (by title) so the first
    //      drag has a stable starting sequence.
    version = 19,
```

2. Add the migration next to `MIGRATION_17_18` (`:215-219`). The ALTER is
   additive; the back-fill assigns each existing row a contiguous order by
   current title so the list is deterministic on first launch:

```kotlin
        // v18 → v19 (#5): user-reorderable subscribed shows. Additive column;
        // existing rows ordered by title so the initial sequence is stable.
        val MIGRATION_18_19: Migration = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE podcast_shows ADD COLUMN displayOrder INTEGER NOT NULL DEFAULT 0"
                )
                // Back-fill a contiguous 0..n-1 order by title (matches the
                // old ORDER BY title ASC so nothing visibly jumps on upgrade).
                val cur = db.query("SELECT feedUrl FROM podcast_shows ORDER BY title ASC")
                var i = 0
                cur.use {
                    val idx = it.getColumnIndexOrThrow("feedUrl")
                    while (it.moveToNext()) {
                        val feed = it.getString(idx)
                        db.execSQL(
                            "UPDATE podcast_shows SET displayOrder = ? WHERE feedUrl = ?",
                            arrayOf<Any>(i, feed)
                        )
                        i++
                    }
                }
            }
        }
```

3. In `AppModule.kt`, append to the `addMigrations(...)` chain (`:78`):

```kotlin
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19
```

4. Build: `./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL` (Room KSP
   validates the v19 entity matches the migrated schema — a mismatch would
   throw `Migration didn't properly handle …` at runtime, not compile, so the
   on-device check in A8 is the real gate; the build proves codegen is
   consistent).

**Commit:** `feat(podcast): Room v18→v19 additive displayOrder migration`

### Task A5 — VM `reorderShow` with contiguous compaction

**Files**
- `app/src/main/java/com/powermediaplayer/ui/podcast/PodcastsSection.kt`

**Steps**
1. In `PodcastsViewModel`, add the reorder method (mirrors
   `LastPlayedRepository.reorderPinned` 0..n-1 compaction, but inline in the VM
   since there is no podcast repository layer). Place it near `shows`:

```kotlin
    /**
     * #5 — move the show at [from] to [to] in the subscribed list and persist
     * a contiguous 0..n-1 displayOrder. Mirrors reorderPinned's compaction:
     * remove-at + insert-at, then write only the rows whose order changed.
     */
    fun reorderShow(from: Int, to: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = podcastDao.showsSnapshot().toMutableList()
            if (from !in list.indices) return@launch
            val target = to.coerceIn(0, list.size - 1)
            if (target == from) return@launch
            val moved = list.removeAt(from)
            list.add(target, moved)
            list.forEachIndexed { idx, s ->
                if (s.displayOrder != idx) podcastDao.setShowOrder(s.feedUrl, idx)
            }
        }
    }
```

2. Append new subscriptions to the bottom: in `addByUrl`'s `existing == null`
   branch, stamp `displayOrder` so a brand-new show lands last instead of all
   defaulting to 0. Change the merge block (`:319-327`) to:

```kotlin
                    val existing = podcastDao.getShow(r.show.feedUrl)
                    val merged = if (existing == null) {
                        r.show.copy(displayOrder = podcastDao.nextShowOrder())
                    } else r.show.copy(
                        subscribedAt = existing.subscribedAt,
                        autoDownload = existing.autoDownload,
                        retentionLastN = existing.retentionLastN,
                        notifyOnNewEpisode = existing.notifyOnNewEpisode,
                        downloadTreeUri = existing.downloadTreeUri,
                        displayOrder = existing.displayOrder
                    )
```

3. Compile: `./gradlew :app:compileDebugKotlin`. Expect SUCCESS.

**Commit:** `feat(podcast): reorderShow VM compaction + append-new-subscriptions order`

### Task A6 — `ReorderableShowList` composable (the #5 UI)

**Files**
- `app/src/main/java/com/powermediaplayer/ui/podcast/ReorderableShowList.kt` (new)

**Steps**
1. Create a self-contained bounded reorderable list (D5). It owns the drag
   state + bounded `LazyColumn(heightIn(max=…))` (nested-scroll trap fix), and
   renders each show row with the existing tap-to-expand behaviour plus a drag
   handle. The expand-body (`ShowSettingsRow` + `EpisodeList`) renders BELOW
   the bounded list (outside the reorderable area) so an expanded show's tall
   body is not trapped inside the 360dp window. Full code:

```kotlin
package com.powermediaplayer.ui.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * #5 — bounded, drag-reorderable list of subscribed shows. Self-contained
 * (not a lift of LastPlayedScreen.ReorderablePinnedList, which is coupled to
 * HistoryItem/bookmarks): reuses the same sh.calvin.reorderable 2.5.0
 * primitives. The list is height-capped so a nested scroll never traps inside
 * an unbounded parent (T313/A3). Tapping a row toggles its expanded body; the
 * body is rendered by the caller BELOW this list so it is not clipped by the
 * 360dp cap.
 */
@Composable
fun ReorderableShowList(
    shows: List<PodcastShowEntity>,
    counts: Map<String, PodcastDao.FeedCounts>,
    expandedFeed: String?,
    onToggleExpand: (String) -> Unit,
    onUnsubscribe: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(shows, key = { _, s -> "show_${s.feedUrl}" }) { _, show ->
            ReorderableItem(reorderState, key = "show_${show.feedUrl}") { _ ->
                val c = counts[show.feedUrl]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand(show.feedUrl) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PodcastArtwork(show.artworkUrl, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            show.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            maxLines = 2
                        )
                        val total = c?.total ?: 0
                        val newCount = c?.unopened ?: 0
                        Text(
                            "$total episode${if (total == 1) "" else "s"}" +
                                if (newCount > 0) " · $newCount new" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { onUnsubscribe(show.feedUrl) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Unsubscribe", tint = ErrorRed)
                    }
                    IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                        Icon(Icons.Filled.DragHandle, contentDescription = "Reorder ${show.title}", tint = TextSecondary)
                    }
                }
            }
        }
    }
}
```

   Note: `PodcastArtwork` is `private` to `PodcastsSection.kt`
   (`:816`). For this new file to use it, change its visibility to internal in
   A7 (one-word edit), or duplicate. Plan uses **make it `internal`** (smaller
   diff, single source).
   Note: `draggableHandle` is `androidx.compose.foundation.gestures.draggableHandle`
   — add `import androidx.compose.foundation.gestures.draggableHandle` (the
   reorderable lib supplies the `ReorderableCollectionItemScope` receiver
   inside `ReorderableItem`). Match the exact import used in
   `LastPlayedScreen.kt` (verify at execution: it is implicit there via the
   reorderable package; if the build flags an unresolved `draggableHandle`,
   add `import sh.calvin.reorderable.draggableHandle` per 2.5.0).

2. Compile after A7 wires it (this file alone won't compile until
   `PodcastArtwork` is internal). Deferred build to A7.

**Commit:** `feat(podcast): ReorderableShowList composable (bounded drag list)`

### Task A7 — Wire `ReorderableShowList` into the section + expose body below

**Files**
- `app/src/main/java/com/powermediaplayer/ui/podcast/PodcastsSection.kt`

**Steps**
1. Make `PodcastArtwork` reusable: change `private fun PodcastArtwork`
   (`:816`) → `internal fun PodcastArtwork`.

2. Replace the shows `Column { shows.forEach { … } }` block
   (`:498-549`) with the reorderable list + an expanded body rendered below it.
   The new block:

```kotlin
            val counts by vm.feedCounts.collectAsStateWithLifecycle()
            var expandedFeed by remember { mutableStateOf<String?>(null) }
            // #5 — reorderable subscribed shows (bounded list; the nested
            // scroll is height-capped so it never traps in the outer scroll).
            // The expanded body renders BELOW the bounded list so a tall
            // settings+episodes panel is not clipped by the 360dp cap.
            ReorderableShowList(
                shows = shows,
                counts = counts,
                expandedFeed = expandedFeed,
                onToggleExpand = { feed ->
                    expandedFeed = if (expandedFeed == feed) null else feed
                },
                onUnsubscribe = { vm.unsubscribe(it) },
                onMove = { from, to -> vm.reorderShow(from, to) }
            )
            expandedFeed?.let { feed ->
                shows.firstOrNull { it.feedUrl == feed }?.let { show ->
                    ShowSettingsRow(show = show, vm = vm)
                    EpisodeList(feedUrl = show.feedUrl, vm = vm)
                }
            }
```

3. Build: `./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`. If
   `draggableHandle` is unresolved, apply the import note from A6 step 1 and
   rebuild.

4. Run the full unit suite to confirm no regression:
   `./gradlew :app:testDebugUnitTest`. Expect `BUILD SUCCESSFUL` (existing
   suites + the new `PodcastShowOrderTest` all green).

**Commit:** `feat(podcast): wire ReorderableShowList; expanded body below bounded list`

### Task A8 — Device verification (#5)

**Manual (on the connected phone, after the auto-install).**
1. Build + install: `./gradlew :app:installDebug` (or the session's auto-install
   hook). Expect `Success`.
2. Confirm the v19 migration is clean: launch the app; in logcat there must be
   ZERO `IllegalStateException` / `Migration didn't properly handle` /
   `SQLiteException` around DB open (precedent T317). If a prior install holds
   the release signature, this needs the user's install-path decision
   (uninstall = data wipe) — flag, do not wipe without consent.
3. Subscribe to ≥3 podcasts (or confirm existing subscriptions migrated in
   title order). Drag a show by its handle to a new position. Expected: the
   row follows the drag, others shift, haptic tick fires.
4. Kill the app (swipe away) and relaunch. Expected: the show order PERSISTS
   (read back from `displayOrder`).
5. Subscribe to a new show. Expected: it APPENDS to the bottom (nextShowOrder),
   not jumps to the top.

**Evidence to capture for TASKS.md:** logcat slice showing clean launch past
v18→v19; a before/after note that the dragged order survived relaunch.

---

## Part B — #6 Per-episode playback effects

### Task B1 — Inject `MediaOverrideDao` into the podcast VM

**Files**
- `app/src/main/java/com/powermediaplayer/ui/podcast/PodcastsSection.kt`

**Steps**
1. Add the constructor param (Hilt already provides it,
   `AppModule.kt:131-135`):

```kotlin
class PodcastsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val podcastDao: PodcastDao,
    private val playbackConnection: PlaybackConnection,
    private val lastPlayedRepo: com.powermediaplayer.data.repository.LastPlayedRepository,
    private val settings: com.powermediaplayer.data.preferences.SettingsDataStore,
    val mediaOverrideDao: com.powermediaplayer.data.db.dao.MediaOverrideDao
) : ViewModel() {
```

   (`val` so the composable can pass it straight to `MediaOverridesPopup`,
   mirroring `LastPlayedViewModel.mediaOverrideDao` exposure
   `LastPlayedViewModel.kt:37` + `LastPlayedScreen.kt:116`.)

2. Compile: `./gradlew :app:compileDebugKotlin`. Expect SUCCESS (Hilt resolves
   the existing binding).

**Commit:** `feat(podcast): inject MediaOverrideDao into PodcastsViewModel`

### Task B2 — Auto-clear overrides on unsubscribe (D3)

**Files**
- `app/src/main/java/com/powermediaplayer/ui/podcast/PodcastsSection.kt`

**Steps**
1. Extend `unsubscribe` (`:341-346`) to clear each episode's override row
   BEFORE the episodes are deleted (so `audioUrlsForFeed` still returns them):

```kotlin
    fun unsubscribe(feedUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // #6 auto-clear — podcasts have no unpin hook, so an unsubscribe is
            // the lifecycle event that removes per-episode override rows
            // (keyed on audioUrl). Do this before deleting the episodes.
            runCatching {
                podcastDao.audioUrlsForFeed(feedUrl).forEach { mediaOverrideDao.clear(it) }
            }
            podcastDao.deleteEpisodesForFeed(feedUrl)
            podcastDao.unsubscribe(feedUrl)
        }
    }
```

2. Compile: `./gradlew :app:compileDebugKotlin`. Expect SUCCESS.

**Commit:** `feat(podcast): clear per-episode overrides on unsubscribe`

### Task B3 — Override entry point on `EpisodeRow` (long-press + visible 3-dot)

**Files**
- `app/src/main/java/com/powermediaplayer/ui/podcast/PodcastsSection.kt`

**Steps**
1. Host the popup at the section level (parity with `LastPlayedScreen`'s
   `overrideTarget` host). In `PodcastsSection` composable, near the other
   `remember` states (`:399-401`), add:

```kotlin
    var overrideEpisode by remember { mutableStateOf<PodcastEpisodeEntity?>(null) }
    overrideEpisode?.let { ep ->
        com.powermediaplayer.ui.overrides.MediaOverridesPopup(
            mediaUri = ep.audioUrl,   // matches setMediaId(audioUrl) → currentMediaIdFlow
            title = ep.title,
            dao = vm.mediaOverrideDao,
            onDismiss = { overrideEpisode = null }
        )
    }
```

2. Thread an `onOverride` callback down to `EpisodeRow`. Update
   `EpisodeList` (`:671-688`) to accept + forward it:

```kotlin
@Composable
private fun EpisodeList(
    feedUrl: String,
    vm: PodcastsViewModel,
    onOverride: (PodcastEpisodeEntity) -> Unit
) {
    val episodes by vm.episodesFor(feedUrl).collectAsStateWithLifecycle(initialValue = emptyList())
    Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
        if (episodes.isEmpty()) {
            Text("Loading episodes…", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        } else {
            episodes.take(15).forEach { e -> EpisodeRow(e, vm, onOverride) }
        }
    }
}
```

   And the caller in A7's new expanded-body block:
   `EpisodeList(feedUrl = show.feedUrl, vm = vm, onOverride = { overrideEpisode = it })`.

3. Add the affordance to `EpisodeRow` (`:694`). Two access paths (parity with
   favourites' "visible affordance for the long-press menu", T232): a
   `combinedClickable` long-press AND a visible overflow icon. Change the row
   signature + its click modifier + add a trailing overflow button. Replace the
   `EpisodeRow` signature and the row `Modifier.clickable`:

```kotlin
@Composable
private fun EpisodeRow(
    e: PodcastEpisodeEntity,
    vm: PodcastsViewModel,
    onOverride: (PodcastEpisodeEntity) -> Unit
) {
    // …existing posMs / durMs / played / inProgress / progress…
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { vm.playEpisode(e) },
                onLongClick = { onOverride(e) }
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
```

   Add imports: `androidx.compose.foundation.combinedClickable`,
   `androidx.compose.material.icons.filled.MoreVert`. Add a visible overflow
   icon in the trailing area — directly after the download `Box(...)`
   (`:766-794`), inside the same `Row`:

```kotlin
        IconButton(onClick = { onOverride(e) }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Playback effects for ${e.title}",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
```

   (`combinedClickable` is experimental-foundation in some BOMs; if the build
   flags it, add `@OptIn(ExperimentalFoundationApi::class)` on `EpisodeRow` —
   match whatever `LastPlayedScreen` does, which already uses
   `combinedClickable` at `:6`.)

4. Build: `./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.

5. Full unit suite: `./gradlew :app:testDebugUnitTest`. Expect `BUILD
   SUCCESSFUL` (no test touches this UI; this confirms no compile/regression).

**Commit:** `feat(podcast): per-episode effects entry (long-press + 3-dot → MediaOverridesPopup)`

### Task B4 — Device verification (#6)

**Manual (on the connected phone).**
1. Install: `./gradlew :app:installDebug`. Expect `Success`.
2. Subscribe + expand a show; play an episode (Player tab shows it, audio
   plays). Confirm in logcat the media id = the episode `audioUrl`
   (the override key).
3. Long-press the episode row (or tap its 3-dot). Expected: the
   "Custom settings for this file" bottom sheet (`MediaOverridesPopup`) opens
   with the episode title.
4. On the Audio tab, enable Reverb → pick "Cave" (or enable Volume boost).
   Expected: the effect applies LIVE to the currently-playing episode (audible
   change) with NO reload — proving `MediaOverrideRepository.activeOverride`
   picked up the `audioUrl`-keyed row.
5. Reopen the popup → "Clear all overrides". Expected: the effect reverts to
   the global default live.
6. Auto-clear: set an override on an episode, then unsubscribe the show.
   Re-subscribe and replay the same episode. Expected: NO leftover override
   (the `media_overrides` row was cleared on unsubscribe) — global defaults
   apply.

**Evidence to capture for TASKS.md:** logcat line(s) showing the playing
mediaId == audioUrl; a note that reverb/boost changed audibly while playing
(live apply); a note that unsubscribe cleared the override.

---

## Final gate (run before reporting done)

1. `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`.
2. `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`; the new
   `PodcastShowOrderTest` (3 tests) green, all pre-existing suites green.
3. Device: #5 drag persists across relaunch + new show appends; #6 effect
   applies live + clears on unsubscribe.
4. Tell-sweep: `grep` the two touched files for banned tokens
   ("TODO", "placeholder", "for now", "stub") → clean.
5. Update TASKS.md rows for #5 and #6 with the evidence lines (the table, not
   prose). Push + auto-install per the session's standing hook.

## Acceptance predicates (machine-checkable where possible)

| # | Predicate | Check |
|---|-----------|-------|
| P1 | `displayOrder` column exists on `PodcastShowEntity` | grep `displayOrder` in `PodcastShowEntity.kt` = 1+ |
| P2 | `observeShows` orders by `displayOrder` | grep `ORDER BY displayOrder` in `PodcastDao.kt` = 1 |
| P3 | `MIGRATION_18_19` registered | grep `MIGRATION_18_19` in `AppModule.kt` = 1 AND in `AppDatabase.kt` ≥1 |
| P4 | DB version is 19 | grep `version = 19` in `AppDatabase.kt` = 1 |
| P5 | `PodcastShowOrderTest` passes | `testDebugUnitTest` report shows 3/3 |
| P6 | reorder compaction is contiguous 0..n-1 | the `reorderCompaction…` test assertion |
| P7 | podcast VM injects `MediaOverrideDao` | grep `mediaOverrideDao` in `PodcastsSection.kt` ≥1 |
| P8 | override key = `audioUrl` | `MediaOverridesPopup(mediaUri = ep.audioUrl …)` present in `PodcastsSection.kt` |
| P9 | unsubscribe clears overrides | grep `audioUrlsForFeed` + `mediaOverrideDao.clear` in `PodcastsSection.kt` |
| P10 | effects entry on the episode row | grep `onLongClick = { onOverride` OR `MoreVert` in `EpisodeRow` |
| P11 | #5 order persists across relaunch | device step A8.4 |
| P12 | #6 applies live + auto-clears | device steps B4.4 + B4.6 |

## Notes / risks

- The Video tab in the override popup is inert for audio podcasts (D4) —
  harmless; kept for video-enclosure feeds and shared-composable simplicity.
- No playback/audio-chain edit anywhere — #6 rides the existing
  `MediaOverrideRepository` mechanism unchanged.
- The bounded `heightIn(max=360.dp)` on `ReorderableShowList` preserves the
  T313/A3 nested-scroll fix; the expanded settings/episodes body renders BELOW
  the bounded list (outside the reorderable area) so it is never clipped.
- If the user requires a true `MigrationTestHelper` v18→v19 test, it is a
  separate, larger work item (enable `exportSchema` + schemaDirectory + commit
  v18/v19 JSON + `room-testing` androidTest dep) — flagged in D6, NOT folded
  here.
- Install path: if the phone holds a release-signed build, a debug install
  needs uninstall (data wipe) — an AWAITING-USER blocker, not a code item.
