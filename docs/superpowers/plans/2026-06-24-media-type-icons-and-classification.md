# Plan — media-type icons & classification (items #13, #12, #8)

Source spec: `docs/superpowers/investigation/2026-06-24-19-item-investigation.md`
(items 8, 12, 13). Repo: `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player`.
Package: `com.powermediaplayer`. minSdk 30 / targetSdk 35 / compileSdk 35.
Build: `app/build.gradle.kts` (media3 1.6.0, Coil 3.1.0).

> **PHASE LOCK / NO-DEFER**: planning only — no code changes in this turn.
> Every task carries exact **Files**, 2–5 min **Steps** (each = a command +
> expected output), COMPLETE code (no placeholders), and a machine-checkable
> acceptance predicate. Commit after each task group. The three items are
> independent — they may be executed in any order, but the order below
> (#13 → #8-classifier → #12) front-loads the two cheap pure-logic wins.

---

## Scope summary

| Item | What | Risk | Verification |
|------|------|------|--------------|
| #13 | Audiobook (.m4b) shows a video/camera icon in the Cloud list | LOW (extension authoritative, already proven on the play path) | TDD — pure classifier + JUnit |
| #8  | Add a reliable media-sub-kind classifier (podcast/audiobook/song) for **display/labels only**; do **not** split BT controls | LOW (additive; net BT risk ≈ 0) | TDD — pure classifier + JUnit |
| #12 | Real video-frame thumbnail for **LIBRARY** local video rows; Cloud remote thumbnails **OUT** | MEDIUM (new decode path, gated) | unit test for the gating predicate + device/manual verification |

---

## Design decisions (confirm before execution)

These three choices change scope/effort. The plan below **assumes the
recommended option** in each; flag now if any should flip.

1. **#12 Library-only vs Cloud thumbnails.**
   - **Recommended: Library-only.** Library video rows have an authoritative
     `MediaFileInfo.isVideo` (set from the `MediaStore.Video.Media`
     collection, `LibraryViewModel.kt:866` — zero false-positives) and a local
     `content://` `uri` that decodes in tens of ms. Cloud remote decode is the
     38–76 s header-stream class (spec #12) → **OUT** (recorded as future, not
     deferred work — it is a separate, explicitly-out-of-scope feature).
   - Alternative (rejected): also thumbnail Cloud video — heavy remote decode,
     no acceptable UX, blocked by cost.

2. **#12 thumbnail source library: Coil `coil-video` `VideoFrameDecoder` vs
   `MediaMetadataRetriever`/`ThumbnailUtils` (+ custom bitmap cache).**
   - **Recommended: Coil `coil-video` `VideoFrameDecoder`.** The app already
     registers a singleton `coil3.ImageLoader`
     (`PowerMediaPlayerApp.kt:51-57`, `coil3.SingletonImageLoader.Factory`)
     and already uses `AsyncImage` in several rows. Adding
     `io.coil-kt.coil3:coil-video:3.1.0` + `VideoFrameDecoder.Factory()` gives
     off-main-thread decode **and** Coil's built-in memory+disk bitmap cache
     for free (no hand-rolled cache, no MMR lifecycle/`release()` plumbing).
     Context7 `/coil-kt/coil`: `coil-video` README — `add(VideoFrameDecoder.Factory())`,
     `videoFrameMillis(...)`/`videoFramePercent(...)`; Android-only feature.
     **Pin to 3.1.0** to match the existing `coil-compose`/`coil-network-okhttp`
     3.1.0 (the README example shows 3.5.0 — do NOT bump; version-align).
   - Alternative A (rejected as primary): `MediaMetadataRetriever.getFrameAtTime()`
     /`getScaledFrameAtTime()` behind a custom `coil3.Fetcher` (mirrors the
     existing `LocalTrackArtFetcher`, `PowerMediaPlayerApp.kt:54-55`) + reuse
     `ArtworkCache`-style disk cache. More code, manual MMR `release()`,
     re-implements caching Coil already does. Keep as the fallback if
     `coil-video` proves problematic on a device.
   - Alternative B (rejected): platform `ThumbnailUtils.createVideoThumbnail(File, Size, CancellationSignal)`
     / `ContentResolver.loadThumbnail(Uri, Size, CancellationSignal)` — both
     API 29 (safe at minSdk 30) but `createVideoThumbnail(File,…)` needs a real
     `File` (our rows are `content://` MediaStore URIs → would need
     `loadThumbnail`), and neither integrates with Coil's cache → still a hand
     cache. No advantage over Coil.
   - Alternative C (noted, rejected): Media3 `FrameExtractor`
     (`androidx.media3.inspector`, Context7 `/websites/developer_android_media`
     — `FrameExtractor.Builder(ctx, mediaItem).getThumbnail(): ListenableFuture`).
     Correct + first-party, but a NEW media3 artifact and async-future glue with
     no Coil-cache integration. Overkill for local MediaStore videos.

3. **#8: ship a classifier now, or document-only?**
   - **Recommended: ship the pure classifier now** (it is the lowest-risk,
     unit-tested helper) **and** wire it into the ONE display site that already
     has a dead `PODCAST` branch (`PlaybackControls.kt:60-68` maps
     `MediaKind.PODCAST → "Show"/"Episode"`, but `inferMediaKind`
     (`PlayerViewModel.kt:1631-1637`) never emits PODCAST → that branch is
     currently unreachable). Wiring is a **display-label** change only.
     **Do NOT** branch BT controls on kind — `applyAction`
     (`PlaybackService.kt:3367-3398`) has no kind switch and the user did not
     ask to split it; keep it global.
   - Alternative (acceptable if you want zero behaviour change this turn):
     **document-only** — land the classifier + tests as a future-use helper,
     do NOT change `inferMediaKind`. Choose this if activating the (correct but
     never-seen) PODCAST label is considered a UX change needing sign-off.

4. **#13 the "imperceptible probe" question (user asked this explicitly).**
   - **Answer: no probe.** For the reported case the file EXTENSION is
     authoritative, so a probe is unnecessary; for the genuinely-ambiguous case a
     probe is NOT imperceptible. Detail: `.m4b`/`.m4a`/`.mp3`/… resolve audio-vs-video
     at zero cost via `MediaClassifier.isVideoByName` (the play path already trusts
     it, `CloudViewModel.kt:1458-66`), so the Cloud camera-icon bug (#13) is fixed
     with NO file probe. The ONLY genuinely-ambiguous container is a bare `.mp4`
     that could be audio-only; there `MediaMetadataRetriever.extractMetadata(
     METADATA_KEY_HAS_VIDEO)` / a video-track check IS reliable, BUT on a REMOTE
     Drive item it costs the 38–76 s header-stream class (the opposite of
     "imperceptible"), and on a LOCAL Library item the `MediaStore.Video`
     collection membership already answers it for free. So a pre-display probe is
     **rejected**: unnecessary for the reported m4b case, too slow for remote
     `.mp4`, redundant for local. **Confirm** acceptance that a rare ambiguous
     remote audio-only `.mp4` keeps a video icon (harmless — it still plays).

---

## File structure (created / modified)

### NEW files
- `app/src/main/java/com/powermediaplayer/util/MediaClassifier.kt`
  — pure-logic classifier object: `classifyMedia(...)` (covers #13 icon
  decision + #8 sub-kind). No Android/Compose imports → plain JUnit testable.
- `app/src/test/java/com/powermediaplayer/util/MediaClassifierTest.kt`
  — plain JUnit (no Robolectric) unit tests for `.m4b`, `video.mp4`,
  `.mp3`-podcast, song, audiobook-by-chapters, etc.

### MODIFIED files
- `app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt`
  — `CloudItemRow` icon `when` (line 1602-1607) uses the classifier (#13).
- `app/build.gradle.kts` — add `coil-video:3.1.0` (#12).
- `app/src/main/java/com/powermediaplayer/PowerMediaPlayerApp.kt`
  — register `VideoFrameDecoder.Factory()` on the singleton ImageLoader (#12).
- `app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt`
  — `MediaFileItem` icon box (line 768-781): `AsyncImage` frame thumbnail for
  gated video rows, fall back to the existing `Icon` (#12).
- `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt`
  — `inferMediaKind` adopts the classifier so it can emit PODCAST/AUDIOBOOK
  (#8, display-labels-only — only if Design decision 3 = "ship now").

> No Room migration. No DAO change. No new permission. No audio-chain change.
> No BT-mapping change.

---

## Ground-truth code references (verified this turn)

- **#13 bug**: `CloudBrowserScreen.kt:1605`
  `item.mimeType.startsWith("video/") -> Icons.Filled.VideoFile` — MIME-only,
  no extension override → a `.m4b` (Drive container mime `video/mp4`) gets the
  video icon.
- **#13 existing fix on the play path**: `CloudViewModel.kt:1458-1466`
  `audioExts = setOf("m4b","m4a","m4p","m4r","mp3","flac","ogg","oga","opus",
  "wav","wave","aac","aiff","aif","ape","wma")`; `nameExt in audioExts → false`,
  else `mimeType.startsWith("video/")`. Documenting comment 1452-1457.
- **PLAYABLE_EXTENSIONS** (audio+video) `GoogleDriveProvider.kt:588-595`.
- **#12 Library row**: `MediaFileItem` `LibraryScreen.kt:742-840`; icon box
  `:768-781`; gate `file.isVideo`.
- **#12 model**: `MediaFileInfo` `LibraryViewModel.kt:36-71` — `id:Long`,
  `uri:Uri`, `mimeType:String`, `duration:Long`, `isVideo:Boolean`,
  `albumArtUri:Uri?`. `isVideo` hardcoded true in `scanVideoFiles()` at
  `LibraryViewModel.kt:866` (MediaStore.Video collection = authoritative).
- **#12 Coil**: `coil-compose:3.1.0` + `coil-network-okhttp:3.1.0`
  (`app/build.gradle.kts:257,262`); singleton ImageLoader with custom
  fetchers `PowerMediaPlayerApp.kt:51-57`. `coil-video` NOT present.
- **#8 MediaKind enum** (already exists, has PODCAST + AUDIOBOOK):
  `PlayerUiState.kt:154-162`.
- **#8 `inferMediaKind`** `PlayerViewModel.kt:1631-1637` — never emits PODCAST.
- **#8 audiobook signal**: `hasChapters = chapters.isNotEmpty()`
  `PlaybackConnection.kt:1111`; `.m4b` ext check `PlaybackService.kt:1900-1902`.
- **#8 podcast membership**: `PodcastDao.episodeByAudioUrl(url): PodcastEpisodeEntity?`
  `PodcastDao.kt:91-92` (suspend); display heuristic
  `item.source == Source.LOCAL && item.mediaUri.startsWith("http")`
  `LastPlayedScreen.kt:810-813`.
- **#8 BT mapping is global**: `applyAction` `PlaybackService.kt:3367-3398`,
  no `MediaKind` branch (confirmed). Net BT risk ≈ 0.
- **#8 existing label consumer**: `PlaybackControls.kt:60-68`
  `MediaKind.PODCAST -> "Show" to "Episode"`, `AUDIOBOOK -> "Book" to "Chapter"`.

---

# GROUP 1 — `MediaClassifier` pure-logic helper (#13 + #8 shared core) · TDD

> Build the classifier test-first. It is the authoritative decision used by
> both the Cloud icon (#13) and the player labels (#8). Pure Kotlin, no Android
> types in the function signature → plain JUnit (faster than the Robolectric
> suites; precedent for Robolectric is `M4bIsRemoteTest`/`ChapterCacheTest`,
> but those need `android.net.Uri`/`Bundle`; this one needs neither).

### Task 1.1 — write the failing test first

**Files:** `app/src/test/java/com/powermediaplayer/util/MediaClassifierTest.kt` (NEW)

**Steps:**
1. Create the test file with the full content below.

```kotlin
package com.powermediaplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic media classification used by:
 *  - the Cloud list-row icon (#13): a .m4b audiobook in a "video/mp4"
 *    Drive container must NOT show the video icon;
 *  - the player media-sub-kind labels (#8): podcast vs audiobook vs song.
 * No Android types in the API → plain JUnit, no Robolectric.
 */
class MediaClassifierTest {

    // ---- isVideoByName: extension is authoritative over container mime ----

    @Test fun `m4b in a video container is NOT video`() {
        // Drive reports video/mp4 for an .m4b audiobook container.
        assertFalse(MediaClassifier.isVideoByName("Book 7.m4b", "video/mp4"))
    }

    @Test fun `m4a in a video container is NOT video`() {
        assertFalse(MediaClassifier.isVideoByName("song.m4a", "video/mp4"))
    }

    @Test fun `real mp4 video is video`() {
        assertTrue(MediaClassifier.isVideoByName("clip.mp4", "video/mp4"))
    }

    @Test fun `mkv with a video mime is video`() {
        assertTrue(MediaClassifier.isVideoByName("clip.mkv", "video/x-matroska"))
    }

    @Test fun `mp3 audio is not video`() {
        assertFalse(MediaClassifier.isVideoByName("track.mp3", "audio/mpeg"))
    }

    @Test fun `unknown extension falls back to the mime`() {
        // No telling extension → trust the container mime.
        assertTrue(MediaClassifier.isVideoByName("movie", "video/mp4"))
        assertFalse(MediaClassifier.isVideoByName("noext", "audio/mpeg"))
    }

    @Test fun `extension match is case-insensitive`() {
        assertFalse(MediaClassifier.isVideoByName("BOOK.M4B", "VIDEO/MP4"))
        assertTrue(MediaClassifier.isVideoByName("CLIP.MP4", "VIDEO/MP4"))
    }

    // ---- classifyAudioSubKind: podcast vs audiobook vs song ----

    @Test fun `podcast membership wins`() {
        assertEquals(
            AudioSubKind.PODCAST,
            MediaClassifier.classifyAudioSubKind(
                name = "ep-42.mp3", hasChapters = false, isPodcast = true
            )
        )
    }

    @Test fun `podcast wins even with chapters`() {
        // Chaptered podcast episode is still a podcast.
        assertEquals(
            AudioSubKind.PODCAST,
            MediaClassifier.classifyAudioSubKind(
                name = "ep-42.m4a", hasChapters = true, isPodcast = true
            )
        )
    }

    @Test fun `m4b extension is an audiobook`() {
        assertEquals(
            AudioSubKind.AUDIOBOOK,
            MediaClassifier.classifyAudioSubKind(
                name = "Book.m4b", hasChapters = false, isPodcast = false
            )
        )
    }

    @Test fun `chapters make an audiobook`() {
        assertEquals(
            AudioSubKind.AUDIOBOOK,
            MediaClassifier.classifyAudioSubKind(
                name = "anything.mp3", hasChapters = true, isPodcast = false
            )
        )
    }

    @Test fun `plain mp3 with no chapters is a song`() {
        assertEquals(
            AudioSubKind.SONG,
            MediaClassifier.classifyAudioSubKind(
                name = "track.mp3", hasChapters = false, isPodcast = false
            )
        )
    }
}
```

2. Run the suite — it MUST fail to compile (class absent):

```
cd "C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player"
./gradlew :app:testDebugUnitTest --tests "com.powermediaplayer.util.MediaClassifierTest"
```

**Expected output:** compilation error
`unresolved reference: MediaClassifier` (and `AudioSubKind`). Red = correct
starting state for TDD.

**Acceptance predicate:** the command above fails with an unresolved-reference
error naming `MediaClassifier`.

---

### Task 1.2 — implement `MediaClassifier` to make the test green

**Files:** `app/src/main/java/com/powermediaplayer/util/MediaClassifier.kt` (NEW)

**Steps:**
1. Create the file with the full content below. The `AUDIO_EXTENSIONS` set is
   copied verbatim from the proven play-path list (`CloudViewModel.kt:1458-1461`)
   so the icon path and the play path agree byte-for-byte.

```kotlin
package com.powermediaplayer.util

/** Audio sub-kind for display/labels only. Not used to gate playback or BT. */
enum class AudioSubKind { PODCAST, AUDIOBOOK, SONG }

/**
 * Pure-logic media classification. Single source of truth for:
 *  - whether a cloud row is video (#13 — the .m4b-in-video/mp4 false-positive);
 *  - the audio sub-kind for player labels (#8 — podcast/audiobook/song).
 *
 * No Android imports → JVM-unit-testable. The extension is authoritative
 * over the container mime because Drive labels MP4-container files
 * (including .m4b audiobooks) "video/mp4": the container CAN hold video,
 * so the mime is ambiguous; the file extension is not. This mirrors the
 * play-path override in CloudViewModel.openItemInternal.
 */
object MediaClassifier {

    /** Audio container/codec extensions — verbatim from the play-path set. */
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "m4b", "m4a", "m4p", "m4r", "mp3", "flac", "ogg", "oga",
        "opus", "wav", "wave", "aac", "aiff", "aif", "ape", "wma"
    )

    /** Audiobook-by-extension set (extension-authoritative). */
    private val AUDIOBOOK_EXTENSIONS: Set<String> = setOf("m4b")

    /** Lower-cased file extension after the last dot, or "" when none. */
    private fun extOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    /**
     * True iff [name]/[mimeType] denote a VIDEO file. Extension wins:
     * an audio extension (incl. .m4b) forces false even when the mime
     * is "video/*"; otherwise fall back to the mime.
     */
    fun isVideoByName(name: String, mimeType: String): Boolean {
        val ext = extOf(name)
        return when {
            ext in AUDIO_EXTENSIONS -> false
            else -> mimeType.lowercase().startsWith("video/")
        }
    }

    /**
     * Podcast wins (a known episode is a podcast regardless of chapters);
     * else .m4b OR parsed chapters → audiobook; else a song.
     *
     * @param isPodcast caller-resolved membership (e.g.
     *   PodcastDao.episodeByAudioUrl(uri) != null). Kept a plain Boolean so
     *   this function stays pure / DB-free.
     */
    fun classifyAudioSubKind(
        name: String,
        hasChapters: Boolean,
        isPodcast: Boolean
    ): AudioSubKind = when {
        isPodcast -> AudioSubKind.PODCAST
        extOf(name) in AUDIOBOOK_EXTENSIONS || hasChapters -> AudioSubKind.AUDIOBOOK
        else -> AudioSubKind.SONG
    }
}
```

2. Re-run the suite:

```
./gradlew :app:testDebugUnitTest --tests "com.powermediaplayer.util.MediaClassifierTest"
```

**Expected output:** `BUILD SUCCESSFUL`, all 13 test methods pass
(`Tests: 13, Failures: 0`).

**Acceptance predicate:** the command exits 0 and the HTML report
`app/build/reports/tests/testDebugUnitTest/classes/com.powermediaplayer.util.MediaClassifierTest.html`
shows 13/13 passed.

---

### Task 1.3 — commit the pure helper + tests

**Steps:**
1. `git add app/src/main/java/com/powermediaplayer/util/MediaClassifier.kt app/src/test/java/com/powermediaplayer/util/MediaClassifierTest.kt`
2. Commit:

```
git commit -m "feat(media): pure MediaClassifier (video-by-extension + audio sub-kind) + JUnit"
```

**Acceptance predicate:** `git log --oneline -1` shows the commit; `git status`
clean for those two paths.

---

# GROUP 2 — #13 Cloud audiobook icon fix

> Use the classifier in the Cloud list-row icon `when` so a `.m4b` (Drive
> `video/mp4`) shows the audio icon, not the video icon. One-line behavioural
> change; the decision is already unit-tested in Group 1.

### Task 2.1 — replace the MIME-only icon test

**Files:** `app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt`

**Steps:**
1. In `CloudItemRow`, replace the icon `when` (current lines 1602-1607):

   **Before:**
   ```kotlin
   val (icon, label) = when {
       item.isFolder -> Icons.Filled.Folder to "Folder"
       item.sourceProvider == CloudProviderType.SPOTIFY -> Icons.Filled.MusicNote to "Spotify track"
       item.mimeType.startsWith("video/") -> Icons.Filled.VideoFile to "Video"
       else -> Icons.Filled.AudioFile to "Audio"
   }
   ```

   **After:**
   ```kotlin
   val (icon, label) = when {
       item.isFolder -> Icons.Filled.Folder to "Folder"
       item.sourceProvider == CloudProviderType.SPOTIFY -> Icons.Filled.MusicNote to "Spotify track"
       // #13 — extension-authoritative: a .m4b audiobook arrives from Drive
       // with mime "video/mp4" (the MP4 container can hold video). The file
       // extension is the real signal, matching the play-path override in
       // CloudViewModel.openItemInternal.
       com.powermediaplayer.util.MediaClassifier
           .isVideoByName(item.name, item.mimeType) -> Icons.Filled.VideoFile to "Video"
       else -> Icons.Filled.AudioFile to "Audio"
   }
   ```

2. Compile:

```
./gradlew :app:compileDebugKotlin
```

**Expected output:** `BUILD SUCCESSFUL`.

**Acceptance predicate (grep — both true):**
- `rg -n "MediaClassifier.*isVideoByName\(item.name, item.mimeType\)" app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt` → 1 hit.
- `rg -n 'item.mimeType.startsWith\("video/"\) -> Icons.Filled.VideoFile' app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt` → 0 hits (old MIME-only line gone).

---

### Task 2.2 — full unit-test gate + commit

**Steps:**
1. Run the whole unit suite (no regressions):

```
./gradlew :app:testDebugUnitTest
```

**Expected output:** `BUILD SUCCESSFUL`, 0 failures (existing suites + the new
`MediaClassifierTest`).

2. Commit:

```
git add app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt
git commit -m "fix(cloud): audiobook .m4b shows audio icon not video (extension-authoritative #13)"
```

**Acceptance predicate:** `testDebugUnitTest` exits 0; commit present.

**Device/manual confirmation (record in TASKS row as `[DEVICE]` AWAITING-USER):**
open the Cloud tab → a Drive `.m4b` audiobook row now shows the audio
(`AudioFile`) icon; a real `.mp4` video row still shows the video icon.

---

# GROUP 3 — #8 media-sub-kind classifier wiring (display labels only)

> Activate the existing-but-dead `MediaKind.PODCAST`/`AUDIOBOOK` label branches
> by feeding `inferMediaKind` the sub-kind from `MediaClassifier`. **No BT
> change.** Choose this group's scope per Design decision 3:
> - **Ship now** (recommended) → do Task 3.1 + 3.2.
> - **Document-only** → skip 3.1; do only Task 3.2's note + the classifier
>   already shipped in Group 1 stands as the future-use helper.

### Task 3.1 — emit PODCAST/AUDIOBOOK from `inferMediaKind`

**Files:** `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt`

> `inferMediaKind` is a pure `when` over a `PlayerState`. It already has
> `hasChapters` and `isVideoContent`; it lacks the podcast + name signals.
> Resolve those in the ViewModel and pass them in. The podcast membership is a
> suspend DB call (`PodcastDao.episodeByAudioUrl`), so it must be computed off
> the synchronous `inferMediaKind` path — resolve it where the current media id
> changes and cache it as a field the `when` can read.

**Steps:**

1. **Read first** to confirm the call site + available fields:

```
rg -n "inferMediaKind|isVideoContent|hasChapters|currentMediaId|PodcastDao|podcastDao" app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt
```

   Expected: locate the single `inferMediaKind(s)` call (the function is
   `PlayerViewModel.kt:1631-1637`) and confirm whether `PodcastDao` is already
   injected. (If `PodcastDao` is NOT injected, add it to the constructor — it is
   a `@Singleton`-graph Room DAO, same pattern as the existing injected DAOs;
   verify the Hilt module provides it: `rg -n "PodcastDao" app/src/main/java/com/powermediaplayer/di`.)

2. Add a podcast-membership cache that updates when the media id changes.
   Insert near the other `private val`/state of `PlayerViewModel` (exact
   surrounding lines determined in step 1; pattern below is complete):

```kotlin
// #8 — podcast membership for the current item (display sub-kind only).
// Resolved off the synchronous inferMediaKind path; a known episode by
// audioUrl is a podcast. Defaults false until resolved.
private val _currentIsPodcast = MutableStateFlow(false)

init {
    // Re-resolve whenever the playing media id changes.
    viewModelScope.launch {
        playbackConnection.playerState
            .map { it.currentMediaId }      // adapt to the real field name (step 1)
            .distinctUntilChanged()
            .collect { mediaId ->
                _currentIsPodcast.value = mediaId != null &&
                    mediaId.startsWith("http") &&
                    runCatching { podcastDao.episodeByAudioUrl(mediaId) != null }
                        .getOrDefault(false)
            }
    }
}
```

   > If `PlayerState` exposes the current media id under a different name (e.g.
   > `mediaId`, `currentMediaUri`), use that — step 1's grep gives the exact
   > field. The podcast mediaId IS the episode `audioUrl` (`PodcastsSection.kt:141`
   > `.setMediaId(episode.audioUrl)`), so `episodeByAudioUrl(mediaId)` is the
   > correct lookup.

3. Change `inferMediaKind` to consult the classifier for the audio sub-kind:

   **Before (`PlayerViewModel.kt:1631-1637`):**
   ```kotlin
   private fun inferMediaKind(s: PlayerState): MediaKind = when {
       s.isVideoContent -> MediaKind.VIDEO
       s.hasChapters -> MediaKind.AUDIOBOOK
       s.mediaItemCount > 1 -> MediaKind.ALBUM
       s.mediaItemCount == 1 -> MediaKind.MUSIC
       else -> MediaKind.UNKNOWN
   }
   ```

   **After:**
   ```kotlin
   private fun inferMediaKind(s: PlayerState): MediaKind {
       if (s.isVideoContent) return MediaKind.VIDEO
       // #8 — display sub-kind: podcast wins, then audiobook (.m4b/chapters),
       // else fall through to album/song by queue size. Labels only — BT
       // controls stay global (PlaybackService.applyAction is kind-agnostic).
       val sub = com.powermediaplayer.util.MediaClassifier.classifyAudioSubKind(
           name = s.title,
           hasChapters = s.hasChapters,
           isPodcast = _currentIsPodcast.value
       )
       return when (sub) {
           com.powermediaplayer.util.AudioSubKind.PODCAST -> MediaKind.PODCAST
           com.powermediaplayer.util.AudioSubKind.AUDIOBOOK -> MediaKind.AUDIOBOOK
           com.powermediaplayer.util.AudioSubKind.SONG -> when {
               s.mediaItemCount > 1 -> MediaKind.ALBUM
               s.mediaItemCount == 1 -> MediaKind.MUSIC
               else -> MediaKind.UNKNOWN
           }
       }
   }
   ```

   > Behaviour change vs today: AUDIOBOOK now also triggers on a `.m4b` whose
   > chapter parse is still pending/empty (previously `hasChapters`-only).
   > PODCAST becomes reachable for the first time → `PlaybackControls.kt:64`
   > "Show"/"Episode" labels light up. ALBUM/MUSIC unchanged for plain audio.
   > `s.title` is the human title (may be a filename pre-enrichment); the `.m4b`
   > suffix survives in filenames, so the extension check still fires.

   > **Recompute trigger:** ensure the flow that maps `PlayerState` → UI
   > `mediaKind` also reads `_currentIsPodcast` so a late podcast resolution
   > re-emits. If `inferMediaKind` is called inside a `combine(...)`/`map` on
   > `playerState` only, add `_currentIsPodcast` as a `combine` source (step 1
   > identifies the exact mapper). Predicate below checks this.

4. Compile:

```
./gradlew :app:compileDebugKotlin
```

**Expected output:** `BUILD SUCCESSFUL`.

**Acceptance predicate (grep — all true):**
- `rg -n "classifyAudioSubKind" app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` → ≥1 hit.
- `rg -n "MediaKind.PODCAST" app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` → ≥1 hit (PODCAST now emitted).
- `rg -n "_currentIsPodcast" app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` → ≥2 hits (declared + read), and the mapper that produces `mediaKind` references `_currentIsPodcast` (manual confirm against step 1).

---

### Task 3.2 — full gate + commit (#8)

**Steps:**
1. Run the full unit suite (the classifier is already covered by Group 1; this
   confirms no regression from the wiring):

```
./gradlew :app:testDebugUnitTest
```

**Expected output:** `BUILD SUCCESSFUL`, 0 failures.

2. Assemble (the wiring touches the player VM — confirm the app links):

```
./gradlew :app:assembleDebug
```

**Expected output:** `BUILD SUCCESSFUL`.

3. Commit:

```
git add app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt
git commit -m "feat(player): media-sub-kind labels via MediaClassifier (podcast/audiobook); BT stays global (#8)"
```

**Acceptance predicate:** both gradle commands exit 0; commit present.

**Findings note (record verbatim in the TASKS row for #8):**
> BT mapping is global by design — `applyAction` (`PlaybackService.kt:3367-3398`)
> has no `MediaKind` branch and the user did not request a split → **no BT
> change made**. Net BT functional risk ≈ 0. The classifier is display-only:
> it activates the previously-dead `PlaybackControls.kt:64` "Show"/"Episode"
> podcast labels and makes `.m4b` audiobooks label as AUDIOBOOK even before a
> chapter parse completes.

> **Document-only variant:** if Design decision 3 = document-only, skip Task 3.1
> entirely. Group 1 already shipped + tested `MediaClassifier` as the future-use
> helper; record the findings note above and stop. No behaviour change.

---

# GROUP 4 — #12 Library local video-frame thumbnails

> Add a real first-frame thumbnail for LIBRARY video rows via Coil's
> `coil-video` `VideoFrameDecoder`. Cloud thumbnails are OUT (remote decode
> cost). The gate predicate (which rows get a thumbnail) is unit-tested; the
> decode itself is device/manual-verified.

### Task 4.1 — add the `coil-video` dependency (version-aligned)

**Files:** `app/build.gradle.kts`

**Steps:**
1. After the existing Coil lines (`app/build.gradle.kts:257-262`), add:

```kotlin
// Video-frame thumbnails for LOCAL library video rows (#12). Pin to the
// same 3.1.0 as coil-compose/coil-network-okhttp above — do NOT bump.
implementation("io.coil-kt.coil3:coil-video:3.1.0")
```

2. Sync/resolve:

```
./gradlew :app:dependencies --configuration debugRuntimeClasspath > /dev/null && echo OK
```

   (or simply `./gradlew :app:compileDebugKotlin` which forces resolution).

**Expected output:** dependency resolves (no "Could not find
io.coil-kt.coil3:coil-video:3.1.0"). If 3.1.0 is unavailable in the resolved
repos, STOP and report — do not silently bump the whole Coil stack.

**Acceptance predicate:**
`rg -n "coil-video:3.1.0" app/build.gradle.kts` → 1 hit; gradle resolves it.

---

### Task 4.2 — register `VideoFrameDecoder.Factory()` on the singleton ImageLoader

**Files:** `app/src/main/java/com/powermediaplayer/PowerMediaPlayerApp.kt`

**Steps:**
1. Add the decoder to the existing `components { }` block
   (`PowerMediaPlayerApp.kt:51-57`):

   **Before:**
   ```kotlin
   override fun newImageLoader(context: android.content.Context): coil3.ImageLoader =
       coil3.ImageLoader.Builder(context)
           .components {
               add(com.powermediaplayer.util.LocalTrackArtFetcher.ArtKeyer())
               add(com.powermediaplayer.util.LocalTrackArtFetcher.Factory(context))
           }
           .build()
   ```

   **After:**
   ```kotlin
   override fun newImageLoader(context: android.content.Context): coil3.ImageLoader =
       coil3.ImageLoader.Builder(context)
           .components {
               add(com.powermediaplayer.util.LocalTrackArtFetcher.ArtKeyer())
               add(com.powermediaplayer.util.LocalTrackArtFetcher.Factory(context))
               // #12 — decode a video's first frame as a row thumbnail.
               // Context7 coil-video README: add VideoFrameDecoder.Factory().
               add(coil3.video.VideoFrameDecoder.Factory())
           }
           .build()
   ```

   > Decoder ordering is safe: `VideoFrameDecoder` only claims sources whose
   > sniffed content is a video container, so audio rows + the existing
   > `LocalTrackArtFetcher` path are unaffected. Verify the exact import path
   > `coil3.video.VideoFrameDecoder` against the resolved 3.1.0 artifact
   > (Context7 README shows `VideoFrameDecoder.Factory()`; confirm the package
   > with `rg -n "class VideoFrameDecoder"` over the unpacked AAR if the import
   > fails to resolve).

2. Compile:

```
./gradlew :app:compileDebugKotlin
```

**Expected output:** `BUILD SUCCESSFUL`.

**Acceptance predicate:**
`rg -n "VideoFrameDecoder.Factory\(\)" app/src/main/java/com/powermediaplayer/PowerMediaPlayerApp.kt` → 1 hit; compiles.

---

### Task 4.3 — gating predicate (unit-tested) for which rows get a thumbnail

> The thumbnail must render ONLY for genuine local video — never attempt to
> decode a frame from an `.m4b`/audio row. The Library `isVideo` flag is already
> authoritative (MediaStore.Video collection), but harden it with the
> extension check so a future code path that sets `isVideo` from mime can't
> sneak an `.m4b` in. Add a tiny pure predicate + a test.

**Files:**
- `app/src/main/java/com/powermediaplayer/util/MediaClassifier.kt` (extend)
- `app/src/test/java/com/powermediaplayer/util/MediaClassifierTest.kt` (extend)

**Steps:**
1. Add to `MediaClassifier` (after `isVideoByName`):

```kotlin
    /**
     * #12 gate — should this LIBRARY row attempt a video-frame thumbnail?
     * Requires the authoritative MediaStore video flag AND that the name is
     * not an audio extension (defence-in-depth so an .m4b can never be
     * frame-decoded even if a future path mis-sets [mediaStoreIsVideo]).
     */
    fun shouldThumbnailVideo(name: String, mediaStoreIsVideo: Boolean): Boolean =
        mediaStoreIsVideo && extOf(name) !in AUDIO_EXTENSIONS
```

2. Add to `MediaClassifierTest`:

```kotlin
    // ---- shouldThumbnailVideo: #12 gate ----

    @Test fun `mediastore video with a video name is thumbnailed`() {
        assertTrue(MediaClassifier.shouldThumbnailVideo("clip.mp4", true))
    }

    @Test fun `m4b is never thumbnailed even if flagged video`() {
        assertFalse(MediaClassifier.shouldThumbnailVideo("Book.m4b", true))
    }

    @Test fun `audio row is never thumbnailed`() {
        assertFalse(MediaClassifier.shouldThumbnailVideo("track.mp3", false))
    }
```

3. Run:

```
./gradlew :app:testDebugUnitTest --tests "com.powermediaplayer.util.MediaClassifierTest"
```

**Expected output:** `BUILD SUCCESSFUL`, 16 methods pass (13 + 3 new).

**Acceptance predicate:** suite exits 0; `MediaClassifierTest` report shows
16/16.

---

### Task 4.4 — render the frame thumbnail in `MediaFileItem`

**Files:** `app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt`

**Steps:**
1. Ensure the imports exist (top of file): `coil3.compose.AsyncImage`,
   `coil3.request.ImageRequest`, `coil3.video.videoFrameMillis`,
   `androidx.compose.ui.layout.ContentScale`. Add any missing.

2. Replace the icon `Box` (current `LibraryScreen.kt:768-781`):

   **Before:**
   ```kotlin
   // Icon
   Box(
       modifier = Modifier
           .size(48.dp)
           .clip(RoundedCornerShape(8.dp))
           .background(SurfaceElevated),
       contentAlignment = Alignment.Center
   ) {
       Icon(
           imageVector = if (file.isVideo) Icons.Filled.VideoFile else Icons.Filled.AudioFile,
           contentDescription = if (file.isVideo) "Video file" else "Audio file",
           tint = TealAccent,
           modifier = Modifier.size(28.dp)
       )
   }
   ```

   **After:**
   ```kotlin
   // Icon / video-frame thumbnail (#12 — local video rows show a real frame;
   // audio rows + .m4b keep the generic icon).
   Box(
       modifier = Modifier
           .size(48.dp)
           .clip(RoundedCornerShape(8.dp))
           .background(SurfaceElevated),
       contentAlignment = Alignment.Center
   ) {
       val context = androidx.compose.ui.platform.LocalContext.current
       if (com.powermediaplayer.util.MediaClassifier
               .shouldThumbnailVideo(file.title, file.isVideo)
       ) {
           AsyncImage(
               model = ImageRequest.Builder(context)
                   .data(file.uri)
                   .videoFrameMillis(1000L) // ~1s in — skip black lead frames
                   .crossfade(true)
                   .build(),
               contentDescription = "Video thumbnail",
               contentScale = ContentScale.Crop,
               modifier = Modifier.fillMaxSize(),
               error = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                   Icons.Filled.VideoFile
               )
           )
       } else {
           Icon(
               imageVector = if (file.isVideo) Icons.Filled.VideoFile else Icons.Filled.AudioFile,
               contentDescription = if (file.isVideo) "Video file" else "Audio file",
               tint = TealAccent,
               modifier = Modifier.size(28.dp)
           )
       }
   }
   ```

   > Notes:
   > - `file.title` is the gate name. `MediaFileInfo` has no raw filename field;
   >   `title` is `MediaStore.Video.Media.TITLE` (no extension), so the `.m4b`
   >   defence-in-depth check in `shouldThumbnailVideo` is effectively a no-op
   >   for the MediaStore-video set (which is the point — it cannot regress) but
   >   blocks any future audio item mistakenly flagged `isVideo`. If a stronger
   >   ext check is wanted, pass `file.mimeType`-derived data; not required.
   > - `error` painter falls back to the generic video icon if decode fails
   >   (DRM/corrupt/codec) — never a blank box.
   > - Coil caches the decoded bitmap (memory + disk) keyed on the request →
   >   no manual cache, no re-decode on scroll. This is why `coil-video` was
   >   chosen over MMR (Design decision 2).
   > - The 48 dp `Crop` box may letterbox-crop wide frames; acceptable for a
   >   list thumbnail. `videoFrameMillis(1000L)` avoids common black lead-in.

3. Compile + assemble:

```
./gradlew :app:assembleDebug
```

**Expected output:** `BUILD SUCCESSFUL`.

**Acceptance predicate (grep — all true):**
- `rg -n "shouldThumbnailVideo\(file.title, file.isVideo\)" app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt` → 1 hit.
- `rg -n "videoFrameMillis" app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt` → 1 hit.
- `assembleDebug` exits 0.

---

### Task 4.5 — full gate + commit (#12)

**Steps:**
1. Full unit suite:

```
./gradlew :app:testDebugUnitTest
```

**Expected output:** `BUILD SUCCESSFUL`, 0 failures (incl. the 16-method
`MediaClassifierTest`).

2. Commit:

```
git add app/build.gradle.kts \
        app/src/main/java/com/powermediaplayer/PowerMediaPlayerApp.kt \
        app/src/main/java/com/powermediaplayer/util/MediaClassifier.kt \
        app/src/test/java/com/powermediaplayer/util/MediaClassifierTest.kt \
        app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt
git commit -m "feat(library): real video-frame thumbnails for local video rows (coil-video, gated; .m4b excluded) (#12)"
```

**Acceptance predicate:** suite exits 0; commit present.

**Device/manual verification (the decode itself — record `[DEVICE]` AWAITING-USER):**
1. Install the debug APK; open the Library tab with at least one local `.mp4`
   and one `.m4b` (and one `.mp3`).
   - **`.mp4` row** → shows a real first-frame thumbnail (not the generic
     video icon) within ~1 s; thumbnail persists/instant on re-scroll
     (Coil cache).
   - **`.m4b` row** → shows the generic audio/video icon, NEVER a decoded frame
     (no `setDataSource` on the audiobook). Confirm no jank/log decode attempt.
   - **`.mp3` row** → unchanged audio icon.
2. (Optional log check) with diag logging on, scroll the Library list — there
   must be no `MediaMetadataRetriever`/decode error spam for the `.m4b` row.

---

# Final gate (run before reporting #13/#8/#12 done)

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

**Expected:** both `BUILD SUCCESSFUL`; `MediaClassifierTest` 16/16.

**Anti-skip checklist (every box must be ticked + evidenced):**

| # | Item | Predicate | Evidence |
|---|------|-----------|----------|
| 1.1 | classifier test written (red) | unresolved-ref failure naming `MediaClassifier` | paste gradle output |
| 1.2 | `MediaClassifier` impl (green) | 13/13 pass | test report |
| 1.3 | helper committed | `git log` shows commit | hash |
| 2.1 | Cloud icon uses classifier | grep: new line 1 hit / old line 0 hits | grep output |
| 2.2 | #13 gate + commit | `testDebugUnitTest` exit 0 | output + hash |
| 3.1 | `inferMediaKind` emits PODCAST | grep: `MediaKind.PODCAST` ≥1, `classifyAudioSubKind` ≥1, `_currentIsPodcast` ≥2 | grep output |
| 3.2 | #8 gate + commit + findings note | `testDebugUnitTest`+`assembleDebug` exit 0 | output + hash |
| 4.1 | coil-video dep added (3.1.0) | grep 1 hit + resolves | output |
| 4.2 | decoder registered | grep 1 hit + compiles | output |
| 4.3 | gate predicate unit-tested | 16/16 pass | test report |
| 4.4 | thumbnail rendered + gated | 2 greps + `assembleDebug` exit 0 | output |
| 4.5 | #12 gate + commit | `testDebugUnitTest` exit 0 | output + hash |
| DEVICE | #13 + #12 on-device | manual: .m4b=audio icon (Cloud), .mp4=frame (Library), .m4b≠frame | `[DEVICE]` AWAITING-USER |

Auto-push + auto-install (per MEMORY): after the last commit, `git push` and
adb-install the debug APK on the connected phone.

---

## Out of scope (explicitly, not deferred)

- **Cloud remote video thumbnails** — remote decode is the 38–76 s
  header-stream class (spec #12); a separate future feature, not part of #12.
- **Splitting BT mappings by media kind** — the user did not request it;
  `applyAction` stays global (spec #8: net BT risk ≈ 0). The classifier is
  display-only.
- **Persisting the sub-kind** to `playback_history.mediaKindOrdinal` — that
  column exists but is always 0 today; populating it is a separate change with
  no current consumer.

---

## Self-review

- **#13** is a one-line behavioural change backed by a pure unit test; the
  extension set is copied verbatim from the proven play-path override so the
  icon and play paths cannot diverge. Risk LOW.
- **#8** ships the requested "single classifier surfaced for display/labels"
  and explicitly does NOT touch BT controls (confirmed `applyAction` is
  kind-agnostic). The only visible change is activating the already-coded
  "Show/Episode" labels + labelling `.m4b` as AUDIOBOOK pre-parse. If even that
  is unwanted this turn, Design decision 3 offers a document-only variant that
  still lands the tested helper. Risk LOW.
- **#12** is Library-only (authoritative MediaStore signal), uses the existing
  Coil ImageLoader infrastructure (no new caching code), and is double-gated
  against ever frame-decoding an `.m4b`. The decode path is the only part not
  unit-coverable → it is the single device-verified item. Risk MEDIUM, confined
  to the new decode path with an icon fallback on failure.
- All three share the one pure `MediaClassifier` → one place to reason about
  audio-vs-video and sub-kind, fully unit-tested.
