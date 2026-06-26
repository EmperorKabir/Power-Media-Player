# Multi-Issue Fix Plan (2026-05-02)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline). Steps use checkbox tracking.

**Goal:** Address six independent user-reported issues without regressing prior fixes.

**Architecture:** Each issue is isolated to a small surface; sequential execution with adb verification between every change.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 ExoPlayer, Hilt, Room, AppAuth, Spotify Web API, Drive REST.

**Bar:** Zero stutter; no functional regression of Drive metadata; backwards-compatible with all earlier fixes (gesture detector for skip taps, SurfaceView for video, @Immutable PlayerUiState, scheduleUpdate coalescer, cumulativeSkip debounce).

---

## Issue Index

1. Library — sort by Longest / Shortest duration
2. Player — restore visible scrim behind controls
3. Stutter — investigate backward-skip / backward-scrub residual jitter
4. Bluetooth — inline on/off toggle in popup, retain settings link, retain all options
5. Spotify — long freeze on connect, retry cycle, track tap does nothing
6. Drive — full-file slider doesn't scrub on single-file media (chapter slider works)

---

## Task 1: Library — Add Duration Sort Modes

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/library/LibraryViewModel.kt:50-58, 156-174`
- Modify: `app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt:409-415`

- [ ] **Step 1: Extend `SortMode` enum**

```kotlin
enum class SortMode {
    NAME_ASC,
    NAME_DESC,
    SIZE_ASC,
    SIZE_DESC,
    TYPE,
    DATE_DESC,
    FAVORITES_FIRST,
    DURATION_DESC,   // longest first
    DURATION_ASC     // shortest first
}
```

- [ ] **Step 2: Wire into `applySort` `when`**

Add cases to the comparator switch:
```kotlin
SortMode.DURATION_DESC -> compareByDescending { it.duration }
SortMode.DURATION_ASC -> compareBy { it.duration }
```

- [ ] **Step 3: Add labels in `LibraryScreen.sortModeLabel`**

```kotlin
SortMode.DURATION_DESC -> "Duration (longest first)"
SortMode.DURATION_ASC -> "Duration (shortest first)"
```

- [ ] **Step 4: Build + install**

```
./gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: Verify on device**

Open library → tap sort icon → confirm "Duration (longest first)" and "Duration (shortest first)" appear and re-order list.
Rollback: revert this commit if list breaks.

- [ ] **Step 6: Commit**

```
git add LibraryViewModel.kt LibraryScreen.kt
git commit -m "feat(library): add longest/shortest duration sort options"
```

---

## Task 2: Player — Restore Visible Scrim

**Files:** `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt:259-280`

**Why:** Current peak alpha 0.03 is invisible against any non-black video frame. User asked for "3% transparency" — the most charitable read is *the scrim is mostly opaque*, which is what they originally complained about. To square the conflicting feedback, restore a clearly-visible bottom-anchored gradient (transparent → ~50% black at the very bottom) only behind the bottom strip where controls sit; the upper 60% of the screen stays untouched.

- [ ] **Step 1: Replace the alpha values**

```kotlin
val scrim: Brush = remember(uiState.isVideoContent) {
    val cols = if (uiState.isVideoContent) {
        listOf(
            Color.Transparent,
            Color.Transparent,
            Color.Transparent,
            OledBlack.copy(alpha = 0.25f),
            OledBlack.copy(alpha = 0.55f)
        )
    } else {
        listOf(
            Color.Transparent,
            OledBlack.copy(alpha = 0.3f),
            OledBlack.copy(alpha = 0.75f),
            OledBlack.copy(alpha = 0.97f),
            OledBlack
        )
    }
    Brush.verticalGradient(colors = cols)
}
```

- [ ] **Step 2: Build + install**

- [ ] **Step 3: adb screenshot to verify visible darkening behind controls only**

`adb shell screencap -p /sdcard/dbg.png && adb pull /sdcard/dbg.png` → visually confirm.
Rollback: revert if user complains it's too dark.

- [ ] **Step 4: Commit**

---

## Task 3: Stutter — Investigate Backward-Only Stutter

**Hypothesis:** PREVIOUS_SYNC backward seeks may still need a brief decoder flush; forward seeks within the buffer don't. ExoPlayer's video frame release after a backward seek may also drop a frame.

- [ ] **Step 1: Set ExoPlayer v2 logger to verbose**

```kotlin
// In PlaybackService.onCreate, before player build:
androidx.media3.exoplayer.util.EventLogger // already part of media3-exoplayer
player!!.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
```

- [ ] **Step 2: Build + install + drive test**

Use existing PMP_DIAG markers + EventLogger output captured via:
```
adb logcat -s PMP_DIAG:I EventLogger:V MediaSessionService:D
```

- [ ] **Step 3: Drive 5 backward and 5 forward small skips and capture timing**

For each seek measure: `seekTo` log → first `onPositionDiscontinuity` → first `videoFrameReleased`. Compare backward vs forward.

- [ ] **Step 4: Analyse**

If backward median > forward median by >50ms → real codec issue, consider HW decoder pre-roll. If similar → app-side cause.

- [ ] **Step 5: Apply targeted fix based on data**

Possible fixes (pick after data):
- Drop video frames near seek target (`MediaCodecVideoRenderer` flag — not exposed; would need custom renderer)
- Reduce `bufferForPlayback` so post-seek resume is faster
- Pre-warm decoder by seeking to nearest keyframe before user even taps (impossible without UX hint)
- Accept it as a hardware limitation and add visual "seeking" indicator

- [ ] **Step 6: Commit (or do not commit if no actionable fix found, just document the finding)**

---

## Task 4: Bluetooth — Add Inline On/Off Toggle

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/components/BluetoothButton.kt`
- Reference: `app/src/main/java/com/powermediaplayer/util/BluetoothHelper.kt` (no changes needed)
- Reference: `AndroidManifest.xml` (no changes — BLUETOOTH_CONNECT already present)

**Constraint:** Keep ALL existing options (the Bluetooth status row, the System settings link, the device list, the Pair-new-device link). Only ADD a Switch.

- [ ] **Step 1: Replace the static Row with a Row containing a Switch**

```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
) {
    Text(
        text = if (isEnabled) "Bluetooth on" else "Bluetooth off",
        style = MaterialTheme.typography.titleMedium,
        color = TextPrimary,
        modifier = Modifier.weight(1f)
    )
    Switch(
        checked = isEnabled,
        onCheckedChange = { wantOn ->
            if (wantOn) {
                onEnable()  // launches ACTION_REQUEST_ENABLE
            } else {
                // No public disable API since SDK 33 — direct user to system
                // settings where they can toggle off.
                onOpenSettings()
            }
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = TealAccent,
            checkedTrackColor = Teal800,
            uncheckedThumbColor = DisabledGrey,
            uncheckedTrackColor = SurfaceElevated
        )
    )
    Spacer(Modifier.width(8.dp))
    TextButton(onClick = onOpenSettings) {
        Text("Settings", color = TealAccent, style = MaterialTheme.typography.labelSmall)
    }
}
```

The previous `if (!isEnabled) FilledTonalButton(onEnable) else TextButton(onOpenSettings)` block is REPLACED by this Row. The "Pair a new device" link and the device list below remain UNTOUCHED.

- [ ] **Step 2: Build + install**

- [ ] **Step 3: Open BT popup with BT off, flip switch → Android consent dialog → confirm BT turns on**

`adb shell screencap` after each step. Verify devices list now populates.

- [ ] **Step 4: Flip switch off → opens Settings activity → return → switch reflects new state**

- [ ] **Step 5: Verify "Pair a new device" link still works**

- [ ] **Step 6: Commit**

---

## Task 5: Spotify — Diagnose & Fix Connect Freeze + Track Play

**Files:** `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt`, `app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt`, `app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt`

- [ ] **Step 1: Read current SpotifyProvider.kt fully to map the connect + play methods**

- [ ] **Step 2: Add PMP_DIAG logs at every Spotify boundary**

- `connect()` entry / OAuth launch / callback receive / token persisted
- `listPlaylists()` / `listTracks()` call + response code
- `play(track)` entry / preview-URL resolution / MediaItem build / setMediaItems

- [ ] **Step 3: Build + install**

- [ ] **Step 4: User-driven adb capture: ask user to tap Connect Spotify, then describe screen state at each freeze; meanwhile run `adb logcat -s PMP_DIAG:I System.err:W AndroidRuntime:E AppAuth:V`**

- [ ] **Step 5: From logs, classify freeze:**
  - If main-thread blocked during OAuth Custom Tabs return → move token persist + first-API-call to IO dispatcher
  - If freeze is the X-and-retry cycle → likely AppAuth response handler is being missed (intent-filter for `powermediaplayer://callback` not being matched)
  - If track tap does nothing → either `play` builds a MediaItem with no playable URL (Spotify Web API only returns 30-second `preview_url`s for free tier; for full tracks need Spotify SDK with Spotify Premium auth)

- [ ] **Step 6: Fix the freeze — most likely move the synchronous OkHttp call out of main**

Wrap any `OkHttpClient.newCall(req).execute()` in `withContext(Dispatchers.IO)`.

- [ ] **Step 7: Fix track tap**
  - If preview_url exists → build MediaItem with that URL → setMediaItems → confirm 30s playback
  - If no preview_url → log "Full-track playback requires Spotify Premium SDK integration; only previews available" and surface this to UI as a Toast
  - DO NOT silently fail

- [ ] **Step 8: Re-test, commit**

---

## Task 6: Drive — Full-File Slider Scrub for Single-File Media

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt:250` (controls.playlistSlider gate)
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackConnection.kt:410-432` (seekToAbsolutePlaylistPosition single-track path)

**CRITICAL CONSTRAINT:** The Drive metadata pipeline (artwork, title, chapters) is fragile per the conversation history. **Do NOT touch CloudViewModel.kt, GoogleDriveProvider.kt, or PlaybackConnection.parseAndApply / setLocalMetadata / setLocalChapters.** The fix below is in the player layer only and does not change any cloud cache, download, or metadata flow.

**Root cause:** `controls.playlistSlider` is `playerState.isPartOfPlaylist && playerState.totalPlaylistDuration > 0 && playerState.isSeekable`. `isPartOfPlaylist = mediaItemCount > 1`. A single Drive M4B has `mediaItemCount = 1` → playlistSlider disabled. But the user's mental model is *full slider = entire file*. For a single audiobook with chapters, the chapter slider scrubs *within chapter* and the full slider should scrub *across the entire file*.

- [ ] **Step 1: Loosen the `playlistSlider` gate**

In `PlayerViewModel.mapToUiState`:

```kotlin
playlistSlider = (
    (playerState.isPartOfPlaylist && playerState.totalPlaylistDuration > 0) ||
    (playerState.hasChapters && playerState.duration > 0)
) && playerState.isSeekable
```

- [ ] **Step 2: Make sure `seekToAbsolutePlaylistPosition` works for single-track**

Read current `PlaybackConnection.seekToAbsolutePlaylistPosition`. For `mediaItemCount == 1`, it should fall through to `c.seekTo(absolutePositionMs)` directly. Already does because `cursor` stays at 0 and condition `absolutePositionMs < cursor + winDur` matches the single window. **No change needed** unless step 3 finds a bug.

- [ ] **Step 3: Verify `playlistDuration` correctly equals file duration for single track**

In PlayerScreen the slider passes `fraction * uiState.totalPlaylistDuration`. `totalPlaylistDuration = sum of all media-item durations`. For 1 item this equals the file duration. Should work.

- [ ] **Step 4: Verify `playlistProgress` = `totalPlaylistPosition / totalPlaylistDuration`**

For a single Drive file, `totalPlaylistPosition = currentPosition` (because `currentMediaItemIndex = 0`). Should work.

- [ ] **Step 5: Build + install**

- [ ] **Step 6: User test: play a Drive M4B, attempt full-slider scrub**

Capture `adb logcat -s PMP_DIAG:I` to confirm the seekTo target is the absolute file position.

- [ ] **Step 7: Verify Drive metadata still loads correctly**

`adb logcat | grep "setLocalMetadata\|setLocalChapters\|cloudFetch"` — same lines should appear as before. Title/artist/chapters should populate as they currently do.

- [ ] **Step 8: Commit**

---

## Task 7: Final Verification

- [ ] **Step 1: Smoke-test all six fixes in one session**

Single launch of app:
1. Library: change sort to "Longest first" — list reorders
2. Open a video — scrim is visibly darker behind controls without obscuring picture
3. Skip-back-30 once and scrub the slider backward — note any stutter
4. Open Bluetooth popup — flip switch to ON — system consent dialog
5. Open Cloud → Spotify Connect — observe full flow
6. Open Cloud → Drive → play an M4B — confirm full slider works AND metadata loads

- [ ] **Step 2: Run final `dumpsys gfxinfo` after a 2-minute video session for jank baseline**

- [ ] **Step 3: git push origin main**

---

## Self-Review Checklist

- [x] **Spec coverage:** All 6 issues have at least one task each (Tasks 1–6).
- [x] **No placeholders:** No "TBD" or "implement later" in Tasks 1, 2, 4, 6. Tasks 3 and 5 explicitly require live diagnostics first — the data drives the fix code.
- [x] **Type consistency:** `SortMode` enum cases are spelled identically in `LibraryViewModel.kt` and `LibraryScreen.kt`. `playlistSlider` boolean stays `Boolean`. No method-signature changes propagate.
- [x] **Constraint adherence:** Task 6 explicitly forbids changes to Drive cloud metadata files.
- [x] **Diagnostic logs preserved:** No step removes existing PMP_DIAG logs.
- [x] **Verification step per task:** Every task has at least one adb-driven verification step.
