# Bluetooth Car-Control Skip + Artwork Fit/Fill — Design Spec

- Date: 2026-05-05
- Branch target: `main`
- Touchpoints: 6 files (5 modify, 0 new)

## Problem

- Tester report (verbatim, see chat screenshot 2026-05-05):
  - "media controls in car don't work as planned"
  - Setting: forward 30s, back 10s
  - Symptom: "forward did nothing and back went back to the previous chapter. So if on chapter 2 it went back to chapter 1, not even the beginning of chapter 2"
  - Secondary: "dead space at the top of the screen, not sure if it's possible to fill that and make the controls less cramped"

## Root cause (verified, no guessing)

### Skip path divergence
- In-app skip buttons (working correctly per tester):
  - `PlaybackConnection.skipBack/skipForward` (`PlaybackConnection.kt:263-287`)
  - → `controller.sendCustomCommand(ACTION_SKIP_BACK_N / ACTION_SKIP_FORWARD_N)`
  - → `PlayerSessionCallback.onCustomCommand` (`PlaybackService.kt:602-623`)
  - → `cumulativeSkip(player, deltaMs)` (`PlaybackService.kt:705-745`)
  - `cumulativeSkip` clamps to `(0, duration)`, accumulates rapid taps via `pendingSeekTarget`, debounces via 180 ms `serviceScope.launch`. Naturally spills across chapter / track boundaries when the new target exceeds the current item's range — Media3 advances the playlist as part of the seek.
- Bluetooth car-control path (broken):
  - AVRCP `NEXT` / `PREVIOUS` button → `onPlayerCommandRequest(COMMAND_SEEK_TO_NEXT / COMMAND_SEEK_TO_PREVIOUS)` (`PlaybackService.kt:643-670`)
  - → `applyAction(player, mapping.nextAction / prevAction, secs, isPrev)` (`PlaybackService.kt:752-773`)
  - For `SKIP_FORWARD`: `player.seekTo(player.currentPosition + seconds * 1000L)` — bare seek, no clamp, no debounce, no spill logic.
  - For `SKIP_BACK`: `player.seekTo((player.currentPosition - seconds * 1000L).coerceAtLeast(0L))` — bare seek with floor at 0.
- Same logical operation, two independent implementations. The BT one lacks the cumulative + clamped + duration-aware behaviour, which is exactly the "robustness" the tester expects from the in-app buttons.

### AVRCP FF/RW gap
- Some Android Auto / car head-units bind their forward/back keys to AVRCP `FAST_FORWARD` / `REWIND`, which Media3 routes to `Player.seekForward()` / `seekBack()` via `Player.COMMAND_SEEK_FORWARD` / `COMMAND_SEEK_BACK`.
- `onPlayerCommandRequest` does **not** intercept those two commands. Result: those head-units bypass the user's `btMapping` entirely and use the unconfigured ExoPlayer defaults (15 s fwd, 5 s back).
- `ExoPlayer.Builder` in `PlaybackService.onCreate` (`PlaybackService.kt:318-330`) does **not** call `setSeekForwardIncrementMs` / `setSeekBackIncrementMs`. Confirmed by grep — no matches across `app/src/main`.

### Artwork dead space
- `CoverArtBackground.kt:86, 104` hard-codes `ContentScale.Fit` on both rendering paths (decoded `Bitmap` and Coil `AsyncImage`). On a portrait phone the square cover fits the width and leaves vertical margins; the bottom margin is hidden by the gradient + transport controls overlay; the top margin is the visible "dead space".
- `Fit` was chosen so book-cover text stays fully visible. Behaviour preserved by default; the new setting is opt-in.
- Two visible call-sites: `PlayerScreen.kt:295` (Compact, audio-only branch) and `PlayerScreen.kt:577` (Expanded). Compose's `ContentScale` is intrinsically dynamic across orientation / fold-state / window-size class — no per-form-factor branching needed.

## Design

### A. Bluetooth car controls

#### A1. Unify the skip code path
- In `PlaybackService.applyAction` (`PlaybackService.kt:752-773`), replace the bare `seekTo` calls in the `SKIP_BACK` and `SKIP_FORWARD` branches with calls to the existing `cumulativeSkip(player, deltaMs)`:
  - `BluetoothMediaActions.SKIP_BACK` → `cumulativeSkip(player, -seconds * 1000L)`
  - `BluetoothMediaActions.SKIP_FORWARD` → `cumulativeSkip(player, seconds * 1000L)`
- This is a substitution, not a rewrite — `cumulativeSkip` already exists, is already proven by the in-app buttons, and already runs on the binder thread safely (it dispatches into the application looper internally per the existing comment at `PlaybackService.kt:748-751`).
- All other branches (`PREV_TRACK`, `NEXT_TRACK`, `RESTART_TRACK`, `PREV_CHAPTER`, `NEXT_CHAPTER`, `else`) are unchanged.

#### A2. Intercept AVRCP FF/RW
- Extend the `when (playerCommand)` block in `onPlayerCommandRequest` (`PlaybackService.kt:657-668`) to add two cases:
  - `Player.COMMAND_SEEK_BACK` → `applyAction(player, BluetoothMediaActions.SKIP_BACK, mapping.skipBackSeconds, isPrev = true); SessionResult.RESULT_INFO_SKIPPED`
  - `Player.COMMAND_SEEK_FORWARD` → `applyAction(player, BluetoothMediaActions.SKIP_FORWARD, mapping.skipForwardSeconds, isPrev = false); SessionResult.RESULT_INFO_SKIPPED`
- These two commands are hard-mapped to `SKIP_BACK` / `SKIP_FORWARD` regardless of `mapping.prevAction` / `nextAction`. AVRCP semantically distinguishes FF/RW from NEXT/PREV — FF/RW is always "seek by N seconds", whereas NEXT/PREV is "navigate items". User remapping applies only to NEXT/PREV.
- Forwarded count uses `mapping.skipBackSeconds` / `mapping.skipForwardSeconds` so the user's chosen seconds drive both AVRCP NEXT/PREV (when remapped to SKIP_*) and AVRCP FF/RW. Single source of truth.

#### A3. Defensive: configure ExoPlayer seek increments
- In `PlaybackService.onCreate`, immediately after `player = ExoPlayer.Builder(...)…build()` (`PlaybackService.kt:318-330`):
  - Read `settingsDataStore.btMappingSnapshot()` synchronously (matches the existing `runBlocking { withTimeoutOrNull(200) { ... } }` pattern at `PlaybackService.kt:203-209` for `useSoftwareDecoding`).
  - Call `player.setSeekBackIncrementMs(snapshot.skipBackSeconds * 1000L)` and `player.setSeekForwardIncrementMs(snapshot.skipForwardSeconds * 1000L)` (Media3 setters, confirmed via Context7).
- Inside the existing `serviceScope.launch { ... combine(...).collect { btMapping = it } }` (`PlaybackService.kt:396-405`), additionally call `player?.setSeekBackIncrementMs(it.skipBackSeconds * 1000L)` and `player?.setSeekForwardIncrementMs(it.skipForwardSeconds * 1000L)` so live setting changes propagate.
- Belt-and-braces: even if A2 misses an internal Media3 routing path, the player's own seek increments match the user's setting.

### B. Artwork Fit / Fill

#### B1. Storage
- `data/preferences/SettingsDataStore.kt`:
  - Add to `Keys`: `val ARTWORK_SCALE_MODE = stringPreferencesKey("artwork_scale_mode")`.
  - Add accessor: `val artworkScaleMode: Flow<String> = context.dataStore.data.map { it[Keys.ARTWORK_SCALE_MODE] ?: "fit" }`.
  - Add setter: `suspend fun setArtworkScaleMode(mode: String) { context.dataStore.edit { it[Keys.ARTWORK_SCALE_MODE] = mode } }`.
- Default `"fit"` preserves existing behaviour. Allowed values: `"fit"`, `"fill"`. Unknown values fall back to `"fit"` at the UI layer.

#### B2. ViewModel
- `ui/settings/SettingsViewModel.kt`:
  - Add `artworkScaleMode: String = "fit"` to `SettingsUiState`.
  - Add the new flow to the existing `combine(...)` aggregator; project into the new field in the destructure.
  - Add `fun setArtworkScaleMode(mode: String) { viewModelScope.launch { settingsDataStore.setArtworkScaleMode(mode) } }`.

#### B3. Settings UI
- `ui/settings/SettingsScreen.kt`:
  - New section header `SettingsSectionHeader("Display")` placed near the top of the screen (immediately above or below the existing "Bluetooth Car Controls" block — exact placement chosen during implementation).
  - Single `ExposedDropdownMenuBox` reusing the same Compose pattern as `BluetoothActionPicker` (`SettingsScreen.kt:436-513`) with options:
    - `"fit"` — label "Fit (show whole cover)"
    - `"fill"` — label "Fill (no margins, may crop edges)"
  - `currentAction = uiState.artworkScaleMode`; `onActionChange = { viewModel.setArtworkScaleMode(it) }`.
  - No seconds-stepper variant needed — strict 2-option picker.

#### B4. Player surface
- `ui/player/components/CoverArtBackground.kt`:
  - Add new param to the composable signature: `contentScale: androidx.compose.ui.layout.ContentScale = ContentScale.Fit`.
  - Replace `contentScale = ContentScale.Fit` at lines 86 and 104 with `contentScale = contentScale`.
  - Default value preserves current behaviour for any other call-site that doesn't pass the param.
- `ui/player/PlayerScreen.kt`:
  - Read the preference once at `PlayerScreen` level via `viewModel.artworkScaleMode.collectAsStateWithLifecycle()` (a new `StateFlow<String>` exposed by `PlayerViewModel` reading the same DataStore flow).
  - Map to `ContentScale`: `if (mode == "fill") ContentScale.Crop else ContentScale.Fit`.
  - Pass through to `PlayerScreenCompact` and `PlayerScreenExpanded` as a parameter; the two `CoverArtBackground(...)` invocations at `:295` and `:577` add `contentScale = scale`.
  - Plumbing only — no logic changes to existing layout selection or to the `OverlayContent` composable.

## Behaviour notes

- Existing user data: untouched. No migrations needed. New key is absent for existing installs → `?: "fit"` returns the legacy default.
- Foldable / rotation / tablet / window-class: handled implicitly by Compose. `ContentScale.Crop` recomposes against the new constraints; `Modifier.fillMaxSize` already adapts. No per-config-qualifier resources or `BoxWithConstraints` branching introduced.
- BT mapping live updates: existing `combine().collect` already keeps `btMapping` fresh on every DataStore emission. The new `setSeekBack/ForwardIncrementMs` calls inside the same collect block reuse that flow.
- Cumulative-skip threading: `cumulativeSkip` is already invoked from the binder thread by `onCustomCommand` today. Re-using it from `applyAction` (also binder thread) introduces no new threading concern.

## Out of scope

- The `else` silent fallback in `applyAction` (`PlaybackService.kt:771`). Pre-existing footgun unrelated to this bug; left alone to avoid behavioural surprises in the `PREV_TRACK` / `NEXT_TRACK` defaults.
- Default `BT_PREV_ACTION` / `BT_NEXT_ACTION` values. Tester explicitly chose `SKIP_*`; no need to flip defaults.
- In-app skip buttons. They already work correctly per tester. Untouched.
- Mini-player and last-played-screen thumbnail rendering. Out of scope — the dead-space complaint is about the now-playing surface only.
- Chapter-aware skip clamping. Withdrawn — the in-app behaviour (cross-boundary spill) is the reference behaviour the BT path must match, not deviate from.

## Files modified

1. `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt`
   - `applyAction` SKIP_* branches → delegate to `cumulativeSkip`.
   - `onPlayerCommandRequest` → add `COMMAND_SEEK_FORWARD` / `COMMAND_SEEK_BACK` cases.
   - `onCreate` → set initial seek increments from `btMappingSnapshot()`; live-update inside the existing `combine().collect` block.
2. `app/src/main/java/com/powermediaplayer/data/preferences/SettingsDataStore.kt`
   - New `ARTWORK_SCALE_MODE` key + Flow + setter.
3. `app/src/main/java/com/powermediaplayer/ui/settings/SettingsViewModel.kt`
   - New `artworkScaleMode` field + combine wiring + setter.
4. `app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt`
   - New "Display" section with Fit/Fill picker.
5. `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt`
   - Read pref, thread `ContentScale` to two `CoverArtBackground(...)` call-sites.
6. `app/src/main/java/com/powermediaplayer/ui/player/components/CoverArtBackground.kt`
   - Accept `contentScale: ContentScale = ContentScale.Fit` param.

## Verification plan

- Unit-build the APK; confirm no compile / type errors.
- Manual: confirm in-app skip buttons still work unchanged (they call the same `cumulativeSkip` from `onCustomCommand` — should be functionally identical).
- Manual: with a connected BT/AVRCP source (or `adb shell media dispatch fast_forward` / `next`), exercise NEXT/PREV and FF/RW; verify both routes now use the user-configured seconds and spill across chapter / track boundaries the way the in-app buttons do.
- Manual: toggle the new "Display" Fit/Fill picker; confirm that the now-playing artwork redraws immediately (Compose recomposition) on phone-portrait, phone-landscape, and unfolded foldable form factors.
