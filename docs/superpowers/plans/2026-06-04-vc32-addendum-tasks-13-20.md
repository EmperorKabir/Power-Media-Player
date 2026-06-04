# vc32 plan ADDENDUM — Tasks 13-20 (round-2/3 deep-investigation fixes)

> **EXECUTION RECORD:** implemented inline 2026-06-04; all code tasks verified via GATE A (9/9) / GATE B (11/11) predicate tables + unit suites — see TASKS.md (the binding ledger) and the checklist docs. Checkbox syntax below is the original plan text; the gates, not the boxes, are the verification record. Outstanding: on-device verification only (ledger T257).


> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. Parent plan: `2026-06-04-vc32-fixes-and-ux.md` (Tasks 1-12; its Task 10 is SUPERSEDED by Task 14 here; its Task 11 — Hue — unchanged, awaiting the user's Hue session).

**Goal:** Fix the seven device-run defects with the round-3 root causes: stale-resume ghost playback, 76 s remote parse, 11 s stale Spotify overlay, missing Spotify position retention, laggy mirror toggle, silent folder add, misleading folder icon.

## Evidence index (findings doc rounds 2-3)

| Ref | Fact |
|-----|------|
| E11 | Drive resume = 75,918 ms ≈ 100% chapter parse: textTrack full-streams ~1.2 GB https (38.0 s) + neroChpl re-streams it (37.9 s); book has ZERO chapters; ExoPlayer reached READY in 2.5 s via range requests |
| E12 | `PlaybackConnection.setMediaItems` (393-403) unconditionally `prepare()+play()`; the stale 76 s coroutine fired it 25 s after the user switched to Spotify |
| E13 | Debounce hole doc-confirmed: destination-scoped ViewModels cleared on back-stack pop; both taps logged `attempt=1` — instance-field guards reset |
| E14 | Spotify stale window measured 11 s; the stale snap is written into `_spotifyState` (the OVERLAY), not just the banner; requested uri known at play time |
| E15 | 5 s tick reads local player only; Spotify rows carry matchable `mediaUri=spotify:track:…`; `spotifyState` exposes trackUri/positionMs/isPlaying |
| E16 | `rememberPickedDriveFolder` (CloudViewModel:622-634) refreshes only when already inside Drive, silently; logged 45 s discovery gap. `browseDrive(folderId,label)` + `errorMessage` snackbar exist |
| E17 | play/pause local latency 11 ms; mirror routes via `spotifyProvider.togglePlayPause()` (PlayerViewModel:989-993); icon waits on the 1 Hz poll |
| E18 | `PlaybackConnection.setLocalChapters` (line 463) exists, documented for background parser coroutines — ready-made async chapter injection. Bundle→ChapterInfo mapping exists at CloudViewModel:1195-1200 |

---

### Task 13: Resume integrity — ResumeGate (process-wide) + setMediaItems(playWhenReady) (E12, E13)

**Files:**
- Create: `app/src/main/java/com/powermediaplayer/playback/ResumeGate.kt`
- Create: `app/src/test/java/com/powermediaplayer/playback/ResumeGateTest.kt`
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackConnection.kt:393-403`
- Modify: `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt` (replace instance-field guards)
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` (cold-start token)
- Modify (bump-only): `LibraryViewModel.kt`, `CloudViewModel.kt`

- [ ] **Step 13.1: Failing test**

```kotlin
package com.powermediaplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E12/E13: guards must survive ViewModel recreation; a newer play
 *  intent must invalidate any in-flight resume. */
class ResumeGateTest {
    @Test
    fun newIntentInvalidatesOlderToken() {
        val t1 = ResumeGate.begin()
        val t2 = ResumeGate.begin()
        assertFalse(ResumeGate.isCurrent(t1))
        assertTrue(ResumeGate.isCurrent(t2))
    }

    @Test
    fun activeCountTracksInFlight() {
        val before = ResumeGate.activeCount()
        val t = ResumeGate.begin()
        assertEquals(before + 1, ResumeGate.activeCount())
        ResumeGate.end(t)
        assertEquals(before, ResumeGate.activeCount())
    }

    @Test
    fun endIsIdempotent() {
        val t = ResumeGate.begin()
        ResumeGate.end(t)
        ResumeGate.end(t)
        assertTrue(ResumeGate.activeCount() >= 0)
    }
}
```

- [ ] **Step 13.2: Run → FAIL (ResumeGate unresolved)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.powermediaplayer.playback.ResumeGateTest" -q`

- [ ] **Step 13.3: Implement ResumeGate**

```kotlin
package com.powermediaplayer.playback

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * vc32 (E12/E13): process-wide resume coordination. Instance-field
 * guards died with their ViewModel (destination-scoped VMs are cleared
 * on back-stack pop — both ghost-bug taps logged attempt=1), so the
 * debounce counter AND the staleness check live here, JVM-wide.
 *
 * Slow, parse-bearing paths:
 *   val token = ResumeGate.begin()
 *   try {
 *     …parse…
 *     if (!ResumeGate.isCurrent(token)) return   // superseded — abort
 *     playbackConnection.setMediaItems(…)
 *   } finally { ResumeGate.end(token) }
 *
 * Fast play paths call ResumeGate.end(ResumeGate.begin()) — not to check
 * it, but so THEIR intent invalidates any older in-flight slow resume.
 */
object ResumeGate {
    private val generation = AtomicLong(0L)
    private val active = AtomicInteger(0)
    private val ended = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Start a new play intent; invalidates all earlier tokens. */
    fun begin(): Long {
        active.incrementAndGet()
        return generation.incrementAndGet()
    }

    /** True iff no newer play intent has begun since [token]. */
    fun isCurrent(token: Long): Boolean = generation.get() == token

    /** End an intent. Idempotent per token (finally-safe). */
    fun end(token: Long) {
        if (ended.add(token)) {
            active.updateAndGet { (it - 1).coerceAtLeast(0) }
            if (ended.size > 64) ended.clear() // tokens are monotonic; bound memory
        }
    }

    /** In-flight intents (debounce input). */
    fun activeCount(): Int = active.get()
}
```

- [ ] **Step 13.4: Run tests → 3/3 PASS**

- [ ] **Step 13.5: `setMediaItems` gains `playWhenReady`**

`PlaybackConnection.kt:393` becomes:

```kotlin
    fun setMediaItems(
        items: List<MediaItem>,
        startIndex: Int = 0,
        playWhenReady: Boolean = true
    ) {
        folderChapters = null
        localChapters = null
        localMetadata = null
        videoModeHint = false
        controller?.let { c ->
            c.setMediaItems(items, startIndex, 0L)
            c.prepare()
            if (playWhenReady) c.play() else c.pause()
        }
    }
```

In PlayerViewModel's cold-start branch: call
`playbackConnection.setMediaItems(listOf(item), 0, playWhenReady = false)`
and DELETE the post-hoc `player.playWhenReady = false` line (race closed).

- [ ] **Step 13.6: Wire LastPlayedViewModel**

Delete instance fields `resumeAttempts`/`resumeActive`. Debounce becomes:

```kotlin
        if (ResumeGate.activeCount() > 0) {
            com.powermediaplayer.diag.DiagLog.resume(
                "tap IGNORED — resume already in flight (active=${ResumeGate.activeCount()})"
            )
            return
        }
```

Local/Drive coroutine shape (existing body preserved inside):

```kotlin
        viewModelScope.launch {
            val token = ResumeGate.begin()
            val tStart = com.powermediaplayer.diag.DiagLog.now()
            com.powermediaplayer.diag.DiagLog.resume(
                "coroutine START token=$token activeNow=${ResumeGate.activeCount()}"
            )
            playbackConnection.setCloudFetchInProgress(true)
            try {
                val mediaItem = withContext(Dispatchers.IO) { /* existing build, UNCHANGED */ }
                // E12: a stale resume must never touch the player.
                if (!ResumeGate.isCurrent(token)) {
                    com.powermediaplayer.diag.DiagLog.resume(
                        "coroutine ABORT token=$token — superseded"
                    )
                    return@launch
                }
                playbackConnection.setMediaItems(listOf(mediaItem), 0)
                playbackConnection.seekTo(targetPos)
            } finally {
                playbackConnection.setCloudFetchInProgress(false)
                ResumeGate.end(token)
                com.powermediaplayer.diag.DiagLog.resume(
                    "coroutine END token=$token totalElapsed=${com.powermediaplayer.diag.DiagLog.now() - tStart}ms"
                )
            }
        }
```

Mirror token/begin/isCurrent/end in the Spotify branch and `playAlbumTrack`.
Keep all existing PERF lines.

- [ ] **Step 13.7: Bump-only call sites**

One line at the TOP of `LibraryViewModel.playSingle` / `playFiles` /
`playFolder` and both `CloudViewModel` Spotify plays + its Drive play path:

```kotlin
        com.powermediaplayer.playback.ResumeGate.end(com.powermediaplayer.playback.ResumeGate.begin())
```

(moves the generation so any older slow resume aborts; holds nothing.)

- [ ] **Step 13.8: Cold-start token**

In the PlayerViewModel cold-start branch: `val token = ResumeGate.begin()`
BEFORE the 800 ms delay; check `if (!ResumeGate.isCurrent(token)) return@launch`
immediately before `setMediaItems`; `ResumeGate.end(token)` right after the
existing `setCloudFetchInProgress(false)` post-clear.

- [ ] **Step 13.9: Build + tests + predicates + commit**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest -q
grep -c "ResumeGate" app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt   # ≥6
grep -c "resumeActive" app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt # 0
grep -c "playWhenReady: Boolean = true" app/src/main/java/com/powermediaplayer/service/PlaybackConnection.kt # 1
git commit -am "fix(resume): vc32 ResumeGate — process-wide debounce + generation abort + setMediaItems(playWhenReady) (E12/E13)"
```

---

### Task 14: Kill the 76 s — remote parse async + ChapterCache (E11, E18) [supersedes parent Task 10]

**Files:**
- Create: `app/src/main/java/com/powermediaplayer/util/ChapterCache.kt`
- Create: `app/src/test/java/com/powermediaplayer/util/ChapterCacheTest.kt`
- Modify: `app/src/main/java/com/powermediaplayer/util/M4bChapterParser.kt`
- Modify: `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt`

Design: http/https URIs are NEVER parsed inline — playback starts in ~2.5 s
(E11 proved the URL is range-capable); the parse runs in the background and
injects via the existing `setLocalChapters` (E18). ChapterCache stores
results INCLUDING empty ones (this book has none — without caching the
empty result every resume re-pays the full cost).

- [ ] **Step 14.1: Failing tests**

```kotlin
package com.powermediaplayer.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(org.robolectric.RobolectricTestRunner::class)
class ChapterCacheTest {
    @Test
    fun missThenHit() {
        val cache = ChapterCache(maxEntries = 4)
        assertNull(cache.get("uri1", "57:1000"))
        cache.put("uri1", "57:1000", android.os.Bundle())
        assertNotNull(cache.get("uri1", "57:1000"))
    }

    @Test
    fun staleTokenMisses() {
        val cache = ChapterCache(maxEntries = 4)
        cache.put("uri1", "57:1000", android.os.Bundle())
        assertNull(cache.get("uri1", "58:2000"))
    }

    @Test
    fun emptyResultIsCachedToo() {
        val cache = ChapterCache(maxEntries = 4)
        cache.put("uri1", "?", android.os.Bundle()) // 0 chapters
        assertNotNull(cache.get("uri1", "?"))
    }
}
```

- [ ] **Step 14.2: Run → FAIL, then implement ChapterCache**

```kotlin
package com.powermediaplayer.util

import android.os.Bundle

/**
 * vc32 (E11): LRU cache of parsed chapter bundles keyed by mediaUri + a
 * validity token ("?" when size/mtime are unknowable, e.g. https). The
 * M4B parse over Drive https cost 75.9 s — a same-session re-resume must
 * never re-stream, INCLUDING when the answer was "no chapters".
 * In-memory only: process death re-parses once (async, Task 14.4), which
 * is acceptable; persistence is YAGNI until evidence says otherwise.
 */
class ChapterCache(private val maxEntries: Int = 16) {
    private data class Entry(val token: String, val bundle: Bundle)
    private val map = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) =
            size > maxEntries
    }

    @Synchronized
    fun get(uri: String, validityToken: String): Bundle? =
        map[uri]?.takeIf { it.token == validityToken }?.bundle

    @Synchronized
    fun put(uri: String, validityToken: String, bundle: Bundle) {
        map[uri] = Entry(validityToken, bundle)
    }

    companion object { val shared = ChapterCache() }
}
```

Run tests → 3/3 PASS.

- [ ] **Step 14.3: Cache + remote helpers in the parser**

In `M4bChapterParser` (keep every existing DiagLog line):
wrap `extractChaptersAsBundle`'s body with a shared-cache get/put on key
`uri.toString()` / token `"?"`, logging
`DiagLog.perf("chapterParse.cacheHit", 0, …)` on hit; add:

```kotlin
    /** E11: remote schemes must never be parsed inline — both strategies
     *  full-stream the file (2×38 s measured on a 1.2 GB m4b). */
    fun isRemote(uri: Uri): Boolean =
        uri.scheme == "http" || uri.scheme == "https"

    /** Cache-only lookup — never parses. */
    fun cachedOnly(uri: Uri): Bundle? = ChapterCache.shared.get(uri.toString(), "?")
```

- [ ] **Step 14.4: Async remote parse in playLocalAt**

In the (Task-13-shaped) local/Drive coroutine, replace the unconditional
inline parse with:

```kotlin
                val isRemote = com.powermediaplayer.util.M4bChapterParser.isRemote(uri)
                val chapterExtras = if (isRemote) {
                    com.powermediaplayer.util.M4bChapterParser.cachedOnly(uri)
                        ?: android.os.Bundle()
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            com.powermediaplayer.util.M4bChapterParser
                                .extractChaptersAsBundle(context, uri)
                        }.getOrDefault(android.os.Bundle())
                    }
                }
```

and AFTER `setMediaItems+seekTo`, fire the background fill-in:

```kotlin
                if (isRemote && chapterExtras.getInt("chapter_count", 0) == 0) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val t0 = com.powermediaplayer.diag.DiagLog.now()
                        val late = runCatching {
                            com.powermediaplayer.util.M4bChapterParser
                                .extractChaptersAsBundle(context, uri)
                        }.getOrDefault(android.os.Bundle())
                        com.powermediaplayer.diag.DiagLog.perf(
                            "resume.asyncChapterFill",
                            com.powermediaplayer.diag.DiagLog.now() - t0,
                            "count=${late.getInt("chapter_count", 0)}"
                        )
                        val count = late.getInt("chapter_count", 0)
                        if (count > 0 && ResumeGate.isCurrent(token)) {
                            val chapters = (0 until count).mapNotNull { i ->
                                val title = late.getString("chapter_title_$i") ?: "Chapter ${i + 1}"
                                val start = late.getLong("chapter_start_$i", -1)
                                val end = late.getLong("chapter_end_$i", -1)
                                if (start >= 0) ChapterInfo(title, start, end, i) else null
                            }
                            playbackConnection.setLocalChapters(chapters)
                        }
                    }
                }
```

Use the SAME `ChapterInfo` type + import that `CloudViewModel.kt:1195-1200`
uses (verify constructor field order there before writing). Note the
`ResumeGate.isCurrent(token)` guard: chapters are only injected if the user
is STILL on this item; `setMediaItems` also wipes `localChapters` (line 395)
— double protection against cross-item injection.

- [ ] **Step 14.5: Build + tests + predicates + commit**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest -q
grep -c "cacheHit" app/src/main/java/com/powermediaplayer/util/M4bChapterParser.kt        # 1
grep -c "isRemote" app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt # ≥2
grep -c "asyncChapterFill" app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt # 1
git commit -am "perf(resume): vc32 remote parse async + ChapterCache — E11 (76s → ~2.5s)"
```

`[VISUAL]` Task 20: Drive resume starts in seconds; chapters appear later if present.

---

### Task 15: Spotify overlay gated on the REQUESTED track (E14)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt`
- Modify: `app/src/test/java/com/powermediaplayer/cloud/SpotifyBannerGraceTest.kt`
- Modify: `LastPlayedViewModel.kt`, `CloudViewModel.kt` (pass uri)

- [ ] **Step 15.1: Extend the test (failing)**

```kotlin
    @Test
    fun staleSnapSuppressedUntilRequestedTrackArrives() {
        assertFalse(shouldEmitSnap("spotify:track:OLD", "spotify:track:NEW", 1_000L, 45_000L))
        assertTrue(shouldEmitSnap("spotify:track:NEW", "spotify:track:NEW", 2_000L, 45_000L))
        assertTrue(shouldEmitSnap("spotify:track:OLD", "spotify:track:NEW", 50_000L, 45_000L)) // grace expired failsafe
        assertTrue(shouldEmitSnap("spotify:track:OLD", null, 0L, 0L)) // no expectation
    }
```

- [ ] **Step 15.2: Implement**

Top-level in SpotifyProvider.kt:

```kotlin
/** E14: suppress stale snaps (Spotify's /me/player lagged PUT /play by a
 *  measured 11 s) until the REQUESTED track is reported, while the
 *  handoff grace is active. */
internal fun shouldEmitSnap(
    snapUri: String,
    expectedUri: String?,
    nowMs: Long,
    graceUntilMs: Long
): Boolean {
    if (expectedUri == null) return true
    if (nowMs >= graceUntilMs) return true
    return snapUri == expectedUri
}
```

Field beside `bannerGraceUntilMs`:
`@Volatile private var expectedTrackUri: String? = null`

`startPlaybackPolling(expectPlayback: Boolean = false, expectedTrack: String? = null)`;
inside `if (expectPlayback)` add `expectedTrackUri = expectedTrack`.

In the non-null snap branch, wrap the ENTIRE existing snap-handling
(track-change detection, lyrics fetch, `_spotifyState` write, grace-zero,
fetching=false) in:

```kotlin
                        val emit = shouldEmitSnap(
                            snap.trackUri, expectedTrackUri,
                            android.os.SystemClock.uptimeMillis(), bannerGraceUntilMs
                        )
                        if (emit) {
                            expectedTrackUri = null
                            // existing handling UNCHANGED
                        } else {
                            com.powermediaplayer.util.Diag.i(
                                "PMP_DIAG",
                                "Spotify stale snap suppressed (${snap.trackUri})"
                            )
                        }
```

Clear `expectedTrackUri = null` in `stopPlaybackPolling`.

- [ ] **Step 15.3: Call sites**
`LastPlayedViewModel` Spotify branch → `startPlaybackPolling(expectPlayback = true, expectedTrack = item.mediaUri)`;
`CloudViewModel:439` → `expectedTrack = uri`; `CloudViewModel:951` → `expectedTrack = spotifyUri`.

- [ ] **Step 15.4: Tests + build + commit**
`git commit -am "fix(spotify): vc32 overlay+banner held until requested track (E14, 11s stale window)"`

---

### Task 16: Spotify position retention (E15)

**Files:** Modify `PlayerViewModel.kt` 5 s tick (~570-600)

- [ ] **Step 16.1:** Inside the tick loop, after the `delay(5_000)` and
before the local-player `currentMediaItem` read:

```kotlin
                // vc32 (E15): during a Spotify mirror the LOCAL player is
                // paused/stale — persist the MIRROR's position instead.
                val spot = spotifyProvider.spotifyState.value
                if (spot != null) {
                    if (spot.isPlaying && spot.trackUri.isNotBlank()) {
                        runCatching {
                            lastPlayedRepo.updatePositionByUri(spot.trackUri, spot.positionMs)
                        }
                    }
                    continue
                }
```

(The loop is `while(true) { delay(5_000); … }` per the Step 3.1 read —
`continue` re-enters at the delay. Verify and adjust placement if the
file's loop differs.)

- [ ] **Step 16.2:** Build + predicate
`grep -c "spot.positionMs" app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` → 1; commit
`git commit -am "fix(spotify): vc32 position retention — 5s tick persists mirror position (E15)"`

`[VISUAL]` Task 20: play Spotify ~30 s, reopen later — Last Played resumes near that position.

---

### Task 17: Optimistic mirror play/pause (E17)

**Files:** Modify `SpotifyProvider.togglePlayPause()`

- [ ] **Step 17.1:** Locate `fun togglePlayPause` (grep). After each
SUCCESSFUL play/pause Web API call, flip the mirror immediately so the icon
responds at tap speed; the next 1 Hz poll reconciles:

```kotlin
        _spotifyState.value = _spotifyState.value?.copy(isPlaying = !wasPlaying)
```

(adapt to the function's actual local naming for prior state; flip ONLY on
2xx.)

- [ ] **Step 17.2:** Build + predicate
`grep -c "copy(isPlaying" app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt` ≥1; commit
`git commit -am "fix(spotify): vc32 optimistic play/pause flip (E17 — 11ms local vs 1Hz poll)"`

---

### Task 18: Drive folder add — confirm + auto-open (E16)

**Files:** Modify `CloudViewModel.kt:622-634`

- [ ] **Step 18.1:**

```kotlin
    fun rememberPickedDriveFolder(folderId: String, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            driveOAuthProvider.rememberPickedFolder(folderId, folderName)
            lastCloudRefreshMs = System.currentTimeMillis()
            // vc32 (E16): the old refresh ran only when the user was
            // ALREADY inside Drive, and silently — a logged 45 s discovery
            // gap. Now: confirm via the errorMessage snackbar channel and
            // browse straight INTO the new folder regardless of where the
            // user was.
            _uiState.update {
                it.copy(
                    activeProvider = CloudProviderType.GOOGLE_DRIVE,
                    errorMessage = "Added \"$folderName\" — opening it"
                )
            }
            browseDrive(folderId, folderName)
        }
    }
```

(`browseDrive` manages `folderStack` itself — same call the old code made.)

- [ ] **Step 18.2:** Build + predicate
`grep -c "opening it" app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt` → 1; commit
`git commit -am "fix(cloud): vc32 folder add confirms + auto-opens (E16 — 45s discovery gap)"`

`[VISUAL]` Task 20: add a Drive folder → snackbar + lands inside it.

---

### Task 19: Folder icon on favourites strip (T242)

**Files:** Modify `CloudBrowserScreen.kt` favourite-row composables (~1438-1546; three variants)

- [ ] **Step 19.1:** For each favourite-row variant whose item can be a
folder, change the LEADING icon expression to:

```kotlin
                imageVector = if (item.isFolder) Icons.Filled.Folder else kindIcon,
contentDescription = if (item.isFolder) "Folder" else null,
```

Add `import androidx.compose.material.icons.filled.Folder` if absent. The
trailing favourite star is untouched. (`CloudMediaItem.isFolder` exists —
the `Cloud.openItem folder=` log line uses it.)

- [ ] **Step 19.2:** Build + predicate
`grep -c "Icons.Filled.Folder" app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt` ≥1; commit
`git commit -am "fix(cloud): vc32 folder favourites show a folder icon (T242)"`

---

### Task 20: GATE B — verification + deploy + device round

- [ ] **Step 20.1:** `./gradlew :app:assembleDebug :app:testDebugUnitTest -q` green
(suites: SettingsSearchTest 6, SpotifyBannerGraceTest 4, ResumeGateTest 3, ChapterCacheTest 3).
- [ ] **Step 20.2:** One script re-runs ALL Task 13-19 predicates → single pass/fail table; any FAIL stops the gate.
- [ ] **Step 20.3:** `git push`; `dumpsys media_session` shows no PLAYING; `adb install -r` → Success; update `TASKS.md` + checklist doc.
- [ ] **Step 20.4 (user):** re-run all 7 complaints: Drive resume (seconds + banner + late chapters), Spotify switch (no stale-track flash; banner holds until Drive-Thru), pause Spotify (NO ghost audiobook), play/pause feel, folder add (snackbar + auto-open + folder icon), Spotify position retention, back behaviour. Hue (T230) whenever ready.
- [ ] **Step 20.5:** Pull logs; verify in DiagLog: `chapterParse.cacheHit` / `resume.asyncChapterFill` present, `coroutine ABORT … superseded` on rapid switches, `stale snap suppressed` during Spotify handoff, and NO `mediaItemTransition` from a superseded resume. Close ledger rows with evidence.

## Addendum self-review

1. **Coverage:** E11→14; E12/E13→13; E14→15; E15→16; E16→18; E17→17; T242→19; gate→20. Parent Task 10 SUPERSEDED by 14; parent Task 11 (Hue) unchanged.
2. **Placeholders:** none — every code step concrete; the bounded verify-notes (ChapterInfo constructor, tick-loop shape, togglePlayPause naming) state exactly what to look up and where.
3. **Type consistency:** `ResumeGate.begin/end/isCurrent/activeCount` identical across 13.1/13.3/13.6/13.8/14.4; `shouldEmitSnap(snapUri, expectedUri, nowMs, graceUntilMs)` matches test+impl; `startPlaybackPolling(expectPlayback, expectedTrack)` matches all 3 call sites; ChapterCache API matches its tests.
4. **Logic cross-checks:** (a) `end()` idempotent → safe in finally even after ABORT-return; (b) async chapter fill guarded by `ResumeGate.isCurrent(token)` AND `setMediaItems` wiping `localChapters` — no cross-item injection; (c) optimistic flip self-heals via the poll; (d) suppressed stale snaps cannot blank the overlay — state holds its previous value while the banner stays up, with the 45 s grace expiry as failsafe; (e) ResumeGate.begin() in fast paths is end()-ed immediately so `activeCount` never blocks fast plays, while the generation bump still kills stale slow resumes.
