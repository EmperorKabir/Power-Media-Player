# BT Car-Control Skip + Artwork Fit/Fill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route Bluetooth car-control NEXT/PREV and FAST_FORWARD/REWIND through the existing in-app `cumulativeSkip` path so external presses inherit cross-boundary spill, and add a user-facing Fit/Fill picker for the now-playing cover-art surface.

**Architecture:** Two independent surgical changes in one branch. (A) `PlaybackService.applyAction` SKIP_* branches delegate to the existing `cumulativeSkip` function instead of bare `seekTo`; the AVRCP FF/RW commands are also intercepted; ExoPlayer's seek increments are kept in sync with the user's BT settings as defensive belt-and-braces. (B) New string preference `ARTWORK_SCALE_MODE` (default `"fit"`) drives a `ContentScale` parameter threaded into `CoverArtBackground` from both compact and expanded player layouts.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Media3 (ExoPlayer + MediaSession), DataStore Preferences, Hilt, Coil 3.

**Spec:** `docs/superpowers/specs/2026-05-05-bt-skip-and-artwork-fit-fill-design.md`

**Test convention:** This codebase has no `src/test` or `src/androidTest` directories — no existing test infrastructure for these surfaces. Verification is via `./gradlew assembleDebug` for compile correctness, plus the manual smoke checklist in Task 9. Setting up unit-test infrastructure is out of scope for this bug fix.

---

## File Structure

- `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` — modify `applyAction` SKIP_* cases, extend `onPlayerCommandRequest`, set + live-update seek increments.
- `app/src/main/java/com/powermediaplayer/data/preferences/SettingsDataStore.kt` — add `ARTWORK_SCALE_MODE` key, flow, setter.
- `app/src/main/java/com/powermediaplayer/ui/settings/SettingsViewModel.kt` — add `artworkScaleMode` field to UI state, combine wiring, setter.
- `app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt` — add "Display" section with Fit/Fill picker.
- `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` — expose `artworkScaleMode` flow.
- `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt` — read pref, thread `ContentScale` into both layout branches.
- `app/src/main/java/com/powermediaplayer/ui/player/components/CoverArtBackground.kt` — accept `contentScale: ContentScale = ContentScale.Fit` param.

No new files; no deletes.

---

### Task 1: Unify BT skip with the in-app `cumulativeSkip` path

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt:752-773`

- [ ] **Step 1: Replace bare `seekTo` calls with `cumulativeSkip` for SKIP_BACK and SKIP_FORWARD**

In `applyAction`, replace:

```kotlin
            BluetoothMediaActions.SKIP_BACK -> {
                val target = (player.currentPosition - seconds * 1000L).coerceAtLeast(0L)
                player.seekTo(target)
            }
            BluetoothMediaActions.SKIP_FORWARD -> {
                player.seekTo(player.currentPosition + seconds * 1000L)
            }
```

With:

```kotlin
            BluetoothMediaActions.SKIP_BACK -> {
                // Route through the same cumulativeSkip path the in-app
                // skip buttons use — inherits cross-boundary spill,
                // duration clamping, debounce, and rapid-tap accumulation.
                cumulativeSkip(player, -seconds * 1000L)
            }
            BluetoothMediaActions.SKIP_FORWARD -> {
                cumulativeSkip(player, seconds * 1000L)
            }
```

All other `applyAction` branches (`PREV_TRACK`, `NEXT_TRACK`, `RESTART_TRACK`, `PREV_CHAPTER`, `NEXT_CHAPTER`, `else`) remain untouched.

- [ ] **Step 2: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/powermediaplayer/service/PlaybackService.kt
git commit -m "fix(bt): route AVRCP skip through cumulativeSkip path

BT NEXT/PREV presses now inherit the same cross-boundary spill, duration
clamping, and rapid-tap debouncing the in-app skip buttons already use,
instead of running a divergent bare-seekTo implementation that no-op'd
when the target exceeded the current item's range."
```

---

### Task 2: Intercept AVRCP FAST_FORWARD / REWIND commands

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt:657-668` (the `when (playerCommand)` block inside `onPlayerCommandRequest`)

- [ ] **Step 1: Add two new cases to the command-routing `when`**

Replace:

```kotlin
            return when (playerCommand) {
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    applyAction(player, mapping.prevAction, mapping.skipBackSeconds, isPrev = true)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    applyAction(player, mapping.nextAction, mapping.skipForwardSeconds, isPrev = false)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                else -> @Suppress("DEPRECATION") super.onPlayerCommandRequest(session, controller, playerCommand)
            }
```

With:

```kotlin
            return when (playerCommand) {
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    applyAction(player, mapping.prevAction, mapping.skipBackSeconds, isPrev = true)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    applyAction(player, mapping.nextAction, mapping.skipForwardSeconds, isPrev = false)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                // AVRCP FAST_FORWARD / REWIND are semantically "seek by N
                // seconds" (distinct from NEXT/PREV which navigate items),
                // so they always go through SKIP_FORWARD / SKIP_BACK
                // regardless of the user's prevAction / nextAction
                // remapping. Some car head-units bind their forward / back
                // keys to these instead of NEXT / PREV.
                Player.COMMAND_SEEK_FORWARD -> {
                    applyAction(player, BluetoothMediaActions.SKIP_FORWARD, mapping.skipForwardSeconds, isPrev = false)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_BACK -> {
                    applyAction(player, BluetoothMediaActions.SKIP_BACK, mapping.skipBackSeconds, isPrev = true)
                    SessionResult.RESULT_INFO_SKIPPED
                }
                else -> @Suppress("DEPRECATION") super.onPlayerCommandRequest(session, controller, playerCommand)
            }
```

- [ ] **Step 2: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/powermediaplayer/service/PlaybackService.kt
git commit -m "fix(bt): intercept AVRCP FAST_FORWARD/REWIND in callback

Some Android Auto / car head-units bind their forward/back keys to AVRCP
FAST_FORWARD / REWIND rather than NEXT / PREVIOUS. The MediaSession
callback now intercepts those two commands and routes them through the
same cumulativeSkip path, using the user's configured BT skip seconds."
```

---

### Task 3: Configure ExoPlayer seek increments from BT settings

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt:318-330` (after `ExoPlayer.Builder…build()`)
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt:394-405` (existing `combine().collect` block)

- [ ] **Step 1: Snapshot the BT mapping at startup and apply seek increments**

Immediately after the `player = ExoPlayer.Builder(this, renderersFactory)…build()` line (the close of the existing `.build()` chain at `:330`) and before `player!!.setSeekParameters(SeekParameters.PREVIOUS_SYNC)` at `:343`, add:

```kotlin
        // Defensive: ensure Player.seekForward() / seekBack() — used by any
        // AVRCP path that bypasses our onPlayerCommandRequest interception
        // — match the user's configured BT skip seconds. Snapshot
        // synchronously with a short timeout so a sluggish DataStore can
        // never ANR service start; defaults (30s/30s in BtMappingSnapshot)
        // apply on timeout.
        val initialBtMapping = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(200) {
                    settingsDataStore.btMappingSnapshot()
                }
            }
        }.getOrNull() ?: BtMappingSnapshot(
            prevAction = BluetoothMediaActions.PREV_TRACK,
            nextAction = BluetoothMediaActions.NEXT_TRACK,
            skipBackSeconds = 30,
            skipForwardSeconds = 30
        )
        player!!.setSeekBackIncrementMs(initialBtMapping.skipBackSeconds * 1000L)
        player!!.setSeekForwardIncrementMs(initialBtMapping.skipForwardSeconds * 1000L)
```

- [ ] **Step 2: Live-update the seek increments inside the existing collect block**

Replace the existing `combine().collect` body at `:394-405`:

```kotlin
        // Keep the Bluetooth mapping snapshot fresh. Combine into a
        // single Flow so we set the @Volatile field atomically.
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsDataStore.btPrevAction,
                settingsDataStore.btNextAction,
                settingsDataStore.btSkipBackSeconds,
                settingsDataStore.btSkipForwardSeconds
            ) { prev, next, back, fwd ->
                BtMappingSnapshot(prev, next, back, fwd)
            }.collect { btMapping = it }
        }
```

With:

```kotlin
        // Keep the Bluetooth mapping snapshot fresh. Combine into a
        // single Flow so we set the @Volatile field atomically. Also
        // mirror the skip seconds into the Player's seek increments so
        // any internal Media3 path that calls seekForward() / seekBack()
        // honours the user's configuration without a player rebuild.
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsDataStore.btPrevAction,
                settingsDataStore.btNextAction,
                settingsDataStore.btSkipBackSeconds,
                settingsDataStore.btSkipForwardSeconds
            ) { prev, next, back, fwd ->
                BtMappingSnapshot(prev, next, back, fwd)
            }.collect {
                btMapping = it
                player?.setSeekBackIncrementMs(it.skipBackSeconds * 1000L)
                player?.setSeekForwardIncrementMs(it.skipForwardSeconds * 1000L)
            }
        }
```

- [ ] **Step 3: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

If the build complains that `setSeekBackIncrementMs` / `setSeekForwardIncrementMs` are not members of `ExoPlayer`, the project's Media3 version is older than the one that exposed them as live setters. Stop and report the error with the Media3 version from `app/build.gradle.kts`. Do not work around — the spec depends on these setters being callable on the constructed player.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/powermediaplayer/service/PlaybackService.kt
git commit -m "fix(bt): mirror BT skip seconds into ExoPlayer seek increments

Player.seekForward() / seekBack() previously used the Media3 defaults
(15s / 5s) regardless of the user's BT settings. Snapshot the user's
configuration at service start and live-update on every settings change
so any path that bypasses onPlayerCommandRequest still honours the
user's chosen seconds."
```

---

### Task 4: Add `ARTWORK_SCALE_MODE` preference

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/data/preferences/SettingsDataStore.kt`

- [ ] **Step 1: Add the key inside the existing `Keys` object**

Inside the `private object Keys { ... }` block (`SettingsDataStore.kt:24-97`), add the new key. A good location is immediately after the existing power-user toggles section (after `LIBRARY_SORT_MODE` at `:82` is fine; pick the line that keeps semantic grouping). Add:

```kotlin
        // Cover-art scaling mode for the now-playing surface — "fit"
        // (default; show whole cover with margins) or "fill" (no
        // margins, may crop edges).
        val ARTWORK_SCALE_MODE = stringPreferencesKey("artwork_scale_mode")
```

- [ ] **Step 2: Add the Flow accessor and suspend setter**

Place these after the existing `prefetchNextCloud` accessor / `setPrefetchNextCloud` setter near `:403, :420` (keeps adjacent power-user prefs grouped). Add:

```kotlin
    val artworkScaleMode: Flow<String> = context.dataStore.data.map {
        // Coerce unknown values back to "fit" so older / corrupt entries
        // never propagate an invalid ContentScale into the UI.
        when (val v = it[Keys.ARTWORK_SCALE_MODE]) {
            "fit", "fill" -> v
            else -> "fit"
        }
    }

    suspend fun setArtworkScaleMode(mode: String) {
        val coerced = if (mode == "fill") "fill" else "fit"
        context.dataStore.edit { prefs ->
            prefs[Keys.ARTWORK_SCALE_MODE] = coerced
        }
    }
```

- [ ] **Step 3: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/powermediaplayer/data/preferences/SettingsDataStore.kt
git commit -m "feat(prefs): add ARTWORK_SCALE_MODE for cover-art Fit/Fill toggle

Default 'fit' preserves existing letterbox-when-needed rendering;
'fill' will drive ContentScale.Crop in the player surface. Unknown
stored values are coerced back to 'fit' on read so corrupt entries
can never propagate."
```

---

### Task 5: Wire `artworkScaleMode` through `SettingsViewModel`

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Read the current `SettingsUiState` data class to know its full field list and combine arity**

Run: read `app/src/main/java/com/powermediaplayer/ui/settings/SettingsViewModel.kt` lines 15–160 (the `SettingsUiState`, the `combine` block, and the existing setters) so the destructure indices stay correct after the new field is added.

- [ ] **Step 2: Add `artworkScaleMode` field to `SettingsUiState`**

In the `data class SettingsUiState(...)` declaration (around `:15-43`), add at the end of the constructor parameter list, before the closing `)`:

```kotlin
    val artworkScaleMode: String = "fit",
```

- [ ] **Step 3: Add the new flow into the `combine` aggregator**

Inside the existing `combine(...)` block (around `:63-127`), append `settingsDataStore.artworkScaleMode` as the next argument and add the corresponding `as String` projection in the destructured assignment that builds the `SettingsUiState`. Concretely:

- Add `settingsDataStore.artworkScaleMode` as a new line in the argument list, in the same position the new field is added at the end.
- In the lambda that constructs the `SettingsUiState`, add `artworkScaleMode = v[<next-index>] as String` to the field assignments. Use the index that immediately follows the last existing one.

If `combine`'s vararg projection is already at the Kotlin overload limit (Kotlin's stdlib `combine` overloads stop at 5 args; beyond that the project uses the vararg `Array<*>` form, which is what the existing block at `:63-127` uses based on `v[3] as String` indexing). Add the new flow to the end of the vararg list and project as `v[v.size - 1] as String` if you prefer index-stable code, or use the explicit numeric index matching its position.

- [ ] **Step 4: Add the setter method**

Place after `setBtSkipForwardSeconds` near `:153` (end of the BT setters group):

```kotlin
    fun setArtworkScaleMode(mode: String) {
        viewModelScope.launch { settingsDataStore.setArtworkScaleMode(mode) }
    }
```

- [ ] **Step 5: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

If the compiler complains about index out of bounds in the destructure, double-check that the index used in `artworkScaleMode = v[N] as String` matches the new flow's position (zero-based) in the `combine` argument list.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/powermediaplayer/ui/settings/SettingsViewModel.kt
git commit -m "feat(settings-vm): expose artworkScaleMode in UI state

Adds the new field to SettingsUiState, wires the DataStore flow into
the existing combine aggregator, and adds the setArtworkScaleMode
passthrough."
```

---

### Task 6: Add the Fit / Fill picker to the Settings screen

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add a small dedicated picker composable at the bottom of the file**

Add this new private composable below `BluetoothActionPicker` / `SecondsStepper` (after `:540` or wherever the file's helper composables live). The picker reuses the same `ExposedDropdownMenuBox` pattern as `BluetoothActionPicker` but without the seconds-stepper variant:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtworkScalePicker(
    currentMode: String,
    onModeChange: (String) -> Unit
) {
    val options = listOf(
        "fit" to "Fit (show whole cover)",
        "fill" to "Fill (no margins, may crop edges)"
    )
    val selected = options.firstOrNull { it.first == currentMode } ?: options.first()
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Cover-art sizing",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(Modifier.height(6.dp))

        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selected.second,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = DisabledContent,
                    focusedTextColor = TealAccent,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                )
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(SurfaceElevated)
            ) {
                options.forEach { (token, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                color = if (token == currentMode) TealAccent else TextPrimary
                            )
                        },
                        onClick = {
                            onModeChange(token)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Insert a "Display" section into the settings scroll**

Locate the existing `SettingsSectionHeader("Bluetooth Car Controls")` block (`SettingsScreen.kt:146`). Immediately above it, insert:

```kotlin
        // ══════════════════════════════════════════════════════════
        // DISPLAY — cover-art scaling mode for the now-playing screen
        // ══════════════════════════════════════════════════════════
        SettingsSectionHeader("Display")
        Text(
            text = "Choose whether the now-playing cover art shows the " +
                "whole image with margins (Fit) or fills the screen, " +
                "cropping edges if needed (Fill).",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        ArtworkScalePicker(
            currentMode = uiState.artworkScaleMode,
            onModeChange = { viewModel.setArtworkScaleMode(it) }
        )
        SettingsDivider()

```

- [ ] **Step 3: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt
git commit -m "feat(settings-ui): add Display section with Fit/Fill cover-art picker

Reuses the ExposedDropdownMenuBox pattern from the Bluetooth Action
Picker for visual consistency."
```

---

### Task 7: Make `CoverArtBackground` accept a `ContentScale` parameter

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/components/CoverArtBackground.kt`

- [ ] **Step 1: Add the new param and use it on both rendering paths**

Replace the function signature at `:36-42`:

```kotlin
fun CoverArtBackground(
    artworkUri: Any?,
    hasCoverArt: Boolean,
    onColorsExtracted: (CoverArtColors?) -> Unit = {},
    artworkBytes: ByteArray? = null,
    modifier: Modifier = Modifier
) {
```

With:

```kotlin
fun CoverArtBackground(
    artworkUri: Any?,
    hasCoverArt: Boolean,
    onColorsExtracted: (CoverArtColors?) -> Unit = {},
    artworkBytes: ByteArray? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
```

Default `ContentScale.Fit` preserves the legacy behaviour for any unmigrated call-site.

Replace `contentScale = ContentScale.Fit,` at `:86` with `contentScale = contentScale,`.

Replace `contentScale = ContentScale.Fit,` at `:104` with `contentScale = contentScale,`.

The existing `import androidx.compose.ui.layout.ContentScale` at `:12` already covers the new param's type.

- [ ] **Step 2: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/powermediaplayer/ui/player/components/CoverArtBackground.kt
git commit -m "refactor(player): CoverArtBackground accepts contentScale param

Default ContentScale.Fit keeps legacy callers unchanged. Both the
decoded-bitmap and Coil URI rendering paths honour the new param."
```

---

### Task 8: Expose the artwork-scale flow on `PlayerViewModel`

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt`

- [ ] **Step 1: Read the existing PlayerViewModel header and constructor**

Run: read `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` lines 1–80 to confirm the `@HiltViewModel` constructor injects `settingsDataStore: SettingsDataStore` (or its equivalent name) and to identify the appropriate location to add the new exposed flow.

If `settingsDataStore` is not already injected, also inject it as a constructor parameter following the pattern already used in this file for other DataStore-backed flows (search the file for existing `settingsDataStore.<flow>` references).

- [ ] **Step 2: Expose the artwork-scale flow as a `StateFlow<String>`**

Add this property near the other exposed `StateFlow` properties in the class body (e.g., near `bookmarks`, `abLoopStart`, etc.):

```kotlin
    /**
     * Current cover-art scaling mode pulled from Settings — drives
     * ContentScale on the now-playing surface so the user can flip
     * between Fit (show whole cover) and Fill (no margins, may crop).
     */
    val artworkScaleMode: kotlinx.coroutines.flow.StateFlow<String> =
        settingsDataStore.artworkScaleMode.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = "fit"
        )
```

The `stateIn` arguments mirror the `WhileSubscribed(5_000)` pattern already used for other lifecycle-aware flows in the project. If the file uses a different convention (e.g., `Eagerly`), match the local style instead — search for `stateIn(` in the same file.

- [ ] **Step 3: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt
git commit -m "feat(player-vm): expose artworkScaleMode StateFlow

Surfaces the user's Fit/Fill preference to the player UI so the
PlayerScreen can map it to ContentScale and pass it into
CoverArtBackground."
```

---

### Task 9: Thread `ContentScale` into `PlayerScreen` call-sites

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt`

- [ ] **Step 1: Read the current PlayerScreen call-site signatures**

Run: read `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt` lines 50–110 (top-level `PlayerScreen` and the `PlayerScreenCompact` / `PlayerScreenExpanded` dispatch) and lines 280–600 (both `CoverArtBackground(...)` invocations and the surrounding parameter lists). The exact parameter ordering and any default values must be preserved.

- [ ] **Step 2: Read the artwork-scale preference at the top of `PlayerScreen`**

Inside the top-level `PlayerScreen` composable (around `:50-101`), add immediately after `val sleepTimerExpired by viewModel.sleepTimerExpired.collectAsStateWithLifecycle()` (around `:57`):

```kotlin
    val artworkScaleMode by viewModel.artworkScaleMode.collectAsStateWithLifecycle()
    val artworkContentScale: androidx.compose.ui.layout.ContentScale =
        if (artworkScaleMode == "fill") {
            androidx.compose.ui.layout.ContentScale.Crop
        } else {
            androidx.compose.ui.layout.ContentScale.Fit
        }
```

- [ ] **Step 3: Add `artworkContentScale` to the four `PlayerScreenCompact` / `PlayerScreenExpanded` invocations and to their parameter lists**

For each of the four call-sites that invoke `PlayerScreenCompact(...)` and `PlayerScreenExpanded(...)` inside the top-level `PlayerScreen` (the `when` cases at roughly `:67`, `:77`, `:86`, `:96`), add the new keyword argument:

```kotlin
                artworkContentScale = artworkContentScale,
```

Place it immediately after `artworkBytes = artworkBytes,` so the call-site grouping stays intuitive.

In the `PlayerScreenCompact` composable signature (find with grep `private fun PlayerScreenCompact(`), add the matching parameter:

```kotlin
    artworkContentScale: androidx.compose.ui.layout.ContentScale,
```

Place it immediately after `artworkBytes: ByteArray?,`.

In the `PlayerScreenExpanded` composable signature (find with grep `private fun PlayerScreenExpanded(`), add the same parameter in the same relative position.

- [ ] **Step 4: Pass `contentScale` into both `CoverArtBackground(...)` calls**

At `PlayerScreen.kt:295` (the audio branch inside `PlayerScreenCompact`'s Box), update the existing call:

```kotlin
            CoverArtBackground(
                artworkUri = uiState.artworkUri,
                artworkBytes = artworkBytes,
                hasCoverArt = uiState.hasCoverArt,
                onColorsExtracted = onColorsExtracted
            )
```

To:

```kotlin
            CoverArtBackground(
                artworkUri = uiState.artworkUri,
                artworkBytes = artworkBytes,
                hasCoverArt = uiState.hasCoverArt,
                onColorsExtracted = onColorsExtracted,
                contentScale = artworkContentScale
            )
```

At `PlayerScreen.kt:577` (the expanded layout's left panel), apply the same edit — append `contentScale = artworkContentScale` inside that `CoverArtBackground(...)` block.

- [ ] **Step 5: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt
git commit -m "feat(player-ui): apply user-chosen ContentScale to cover art

PlayerScreen reads artworkScaleMode and maps it to ContentScale.Fit or
ContentScale.Crop, threaded into both compact (audio) and expanded
CoverArtBackground call-sites. No layout-variant or per-form-factor
branching — Compose's ContentScale handles fold/rotation/window-class
dynamism natively."
```

---

### Task 10: Manual smoke verification

**Files:** none (manual verification only)

- [ ] **Step 1: Install the debug APK on a connected device or emulator**

Run: `./gradlew :app:installDebug`
Expected: `Installed on 1 device.`

- [ ] **Step 2: Verify in-app skip buttons still work (regression check)**

- Play any audiobook track.
- Tap the in-app skip-back-N and skip-forward-N buttons. Position should jump by N seconds, spilling across track boundaries on long skips. (Behaviour should be unchanged from before this branch.)

- [ ] **Step 3: Verify BT skip routing using `adb` media key dispatch**

With the app playing audio, run from a separate terminal:

```bash
adb shell media dispatch fast_forward
adb shell media dispatch rewind
adb shell media dispatch next
adb shell media dispatch previous
```

For each, watch the player UI:
- `fast_forward` → position advances by `skipForwardSeconds` (default 30 s).
- `rewind` → position retreats by `skipBackSeconds` (default 30 s).
- `next` → if `BT_NEXT_ACTION` is `SKIP_FORWARD`, advances by configured seconds. If still default `NEXT_TRACK`, advances to next media item.
- `previous` → mirrors `next` for the back direction.

Confirm in `logcat -s PMP_DIAG` that `skip delta=...ms` and `debounced seekTo target=...ms` log lines appear (these are the `cumulativeSkip` markers — proves the BT path is now using the same code as the in-app buttons).

- [ ] **Step 4: Verify cross-boundary spill from BT**

- Seek to within `skipForwardSeconds` seconds of the end of a chapter / track.
- `adb shell media dispatch fast_forward` (or use a paired BT remote).
- The player should advance into the next chapter / track at the appropriate offset, not no-op.

- [ ] **Step 5: Verify the Fit/Fill picker**

- Open Settings → Display section.
- Confirm "Cover-art sizing" picker is present and currently shows "Fit (show whole cover)".
- Switch to "Fill (no margins, may crop edges)". Open the now-playing screen.
- The album art should now fill the screen without top/bottom margins (square art on portrait phone → cropped left/right; square art on landscape → cropped top/bottom).
- Switch back to "Fit". Cover art should redraw with the original letterbox margins.

- [ ] **Step 6: Verify Fit/Fill is dynamic across configurations**

- With "Fill" selected, rotate the device or fold/unfold a foldable while the player is open.
- The cover art should re-fill the new container size on every recomposition without requiring app restart.

- [ ] **Step 7: Final commit cleanup (none expected)**

If any uncommitted changes remain after manual verification, stop and investigate. The implementation should be complete after Task 9's commit.

---

## Self-Review Pass

- All 6 spec sections (A1, A2, A3, B1, B2, B3, B4) are covered by Tasks 1–9.
- No "TBD" / "TODO" / "implement appropriate X" placeholders.
- Every code-emitting step shows complete code, not summaries.
- Type names are consistent: `ContentScale`, `ContentScale.Fit`, `ContentScale.Crop`, `BtMappingSnapshot`, `BluetoothMediaActions.SKIP_BACK`, `BluetoothMediaActions.SKIP_FORWARD`, `cumulativeSkip`, `setSeekBackIncrementMs`, `setSeekForwardIncrementMs`, `artworkScaleMode`, `setArtworkScaleMode`, `ARTWORK_SCALE_MODE`. Cross-task references match.
- Method signatures match: `applyAction(player, action, seconds, isPrev)`, `cumulativeSkip(player, deltaMs)`, `CoverArtBackground(...artworkBytes, modifier, contentScale)`.
- File paths are absolute Windows paths, line numbers cite the pre-edit positions in the current main branch.
