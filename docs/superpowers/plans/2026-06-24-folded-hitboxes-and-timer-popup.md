# Folded hitboxes (#14, #1, #15) + timer-tap numeric jump (#4)

> PLANNING ONLY — no code changes in this document. Investigation phase
> already DONE for items #1/#4/#14/#15 (see
> `docs/superpowers/investigation/2026-06-24-19-item-investigation.md`,
> findings 1/4/14/15). User advanced these four to PLAN.

## Goal

- **#14** Info-box "i" icon is drawn but NOT tappable on the **folded cover
  screen**. Cause: z-order/declaration-order inversion + folded scroll capture
  in `OverlayContent` — the controls `Column`
  (`PlayerScreen.kt:1145`, `fillMaxSize().verticalScroll`) is declared LAST and
  is therefore the top-most hit-test sibling, so it captures pointer-down across
  the entire screen including the top-right where the lower-declared `InfoIcon`
  Box (`PlayerScreen.kt:1101`) sits. Make the "i" tappable folded WITHOUT
  regressing the unfolded layout and WITHOUT touching the EQ hitboxes
  (`EqualizerScreen.kt:83-92` shares the pattern — FLAG ONLY).
- **#1** Brightness-permission banner ("Tap to grant brightness permission",
  `SecondaryControls.kt:149-158`) has a dead-zone (modifier `.padding(28dp)`
  applied BEFORE `.clickable{}` → the 28dp band is non-tappable, target is bare
  glyph bounds < 48dp) and is clipped/overlapped folded. Re-order to
  `.clickable{}` THEN `.padding()`, give it a ≥48dp touch target, and resolve
  the folded clipping.
- **#15** Black box above the floating mini-player on the folded cover screen.
  Cause: `imePadding()` on the mini-bar Box (`AppNavigation.kt:353`) injects an
  OledBlack inset when the Fold6 cover screen mis-reports a non-zero/residual
  IME inset under edge-to-edge. Apply an inset fix that keeps the keep-above-IME
  behaviour without the spurious black strip.
- **#4** (FEATURE) Make the elapsed + remaining time `Text`s
  (`ProgressSliders.kt:229-245`) tappable → open a numeric-input dialog →
  `seekTo`. Elapsed tap seeks to the typed time; remaining tap seeks to
  `durationMs − typed`; on the **Track** bar both add `chapterStartMs` (mirrors
  the existing `onTrackSeek` math at `PlayerScreen.kt:2136`). ≥48dp touch
  targets; ONE composable serves BOTH player layouts (single call site
  `PlayerScreen.kt:2129` → `PositionSection` → `ProgressSliders`).

## Architecture

- Jetpack Compose UI, single-Activity. Player has TWO layouts in
  `PlayerScreen.kt`: **compact/folded** (`OverlayContent`, lines ~1035-1320, a
  scrolling `Column`) and **expanded/unfolded** (`PlayerScreenExpanded`, the
  two-pane path ending ~1604). Both render the SAME shared sub-composables
  (`PositionSection` → `ProgressSliders`, `SecondaryControls`, `InfoIcon`).
- **Hit-test rule (Context7-verified, `/androidx/androidx`):** within a `Box`,
  pointer hit-testing walks siblings in reverse declaration order — the LAST
  declared sibling is the top-most and wins the hit chain
  (`HitTestResult` / `PointerInputModifierNode.sharePointerInputWithSiblings`).
  `Modifier.zIndex(...)` changes **draw order ONLY, not hit-test order**
  (Context7: `ZIndexModifierKt` doc — "drawing order") → re-ordering draw via
  zIndex would NOT fix #14. The load-bearing fix is **declaration order** (and/or
  detaching the InfoIcon from the scroll sibling's footprint).
- **Touch target (Context7-verified):** `minimumInteractiveComponentSize()` in
  `androidx.compose.material` (re-exported by the Material3 artifact in the
  project's Compose BOM 2025.04.00) expands a component to the 48dp Material
  minimum without changing visual size. Modifier ordering matters:
  `.clickable{}` must precede `.padding()` so the padded area is inside the
  clickable bounds (clickable wraps everything declared AFTER it on the chain;
  `.padding().clickable{}` makes the padding a dead margin OUTSIDE the click
  region — exactly the #1 defect).
- **Insets:** edge-to-edge app. `imePadding()` reads the live `WindowInsets.ime`;
  on the Fold6 cover screen this is mis-reported/residual. Mitigation =
  consume the IME inset only by its real, clamped intersection with the bar, or
  gate it so a non-focused / no-IME state contributes zero.

## Tech Stack

- Kotlin / Jetpack Compose (Material3), Compose BOM **2025.04.00**.
- Modifiers: `clickable`, `padding`, `minimumInteractiveComponentSize`
  (`androidx.compose.material`), `windowInsetsPadding`, `imePadding`,
  `verticalScroll`, `align`.
- ViewModel: `PlayerViewModel.seekTo(positionMs: Long)`
  (`PlayerViewModel.kt:798`) — already routes Spotify vs local. Reused as-is.
- `PositionUi` (`PlayerUiState.kt:135-148`) exposes `positionMs`, `durationMs`,
  `chapterStartMs`, `totalPlaylistDurationMs` — all the dialog needs.

---

## File Structure

### Modified
- `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt`
  - `OverlayContent` (~1035-1320): re-declare `InfoIcon` so it is the LAST
    hit-test sibling on the folded screen (fix #14). Wire the new numeric-jump
    dialog into `PositionSection` (fix #4 call site).
- `app/src/main/java/com/powermediaplayer/ui/player/components/SecondaryControls.kt`
  - `VolumeAndBrightnessControls` (149-158): reorder modifier to
    `clickable → minimumInteractiveComponentSize → padding`; fix folded clipping
    of the banner (fix #1).
- `app/src/main/java/com/powermediaplayer/ui/player/components/ProgressSliders.kt`
  - `PositionSlider` time-`Text` row (222-247): make elapsed + remaining `Text`s
    clickable with ≥48dp targets; add `onSeekToElapsedMs` / `onSeekToRemainingMs`
    callbacks; thread them through `ProgressSliders` params (fix #4).
- `app/src/main/java/com/powermediaplayer/ui/navigation/AppNavigation.kt`
  - `NonPlayerRoute` (340-357): replace bare `imePadding()` on the mini-bar Box
    with a clamped/gated IME-inset modifier (fix #15).

### Created
- `app/src/main/java/com/powermediaplayer/ui/player/components/SeekTimeDialog.kt`
  - New composable `SeekTimeDialog` (numeric time-entry AlertDialog) + a pure
    `parseTimeToMs(String): Long?` helper (fix #4). Kept in its own file so it
    is testable and reused by both player layouts via the single call site.

### Flagged, NOT modified (out of scope by user instruction)
- `app/src/main/java/com/powermediaplayer/ui/equalizer/EqualizerScreen.kt:83-92`
  — same `fillMaxSize().verticalScroll` + nested `InfoIcon` (125) pattern as
  #14. FLAG ONLY. Recorded in a code comment in Task 1; no behavioural change.

---

## Design decisions (confirm before execution)

1. **#4 dialog style.** Plan uses a Material3 `AlertDialog` matching the
   existing `SleepTimerDialog` (`PlayerScreen.kt:1911-2103`: `containerColor =
   SurfaceElevated`, teal `titleLarge` title, `TextSecondary` body) with ONE
   `OutlinedTextField` (`KeyboardType.Number`, no separators) + a hint showing
   the current value and the valid range, plus "Jump"/"Cancel" buttons. Input
   accepts `m:ss`, `h:mm:ss`, or bare seconds; invalid/out-of-range disables
   "Jump". **Confirm:** AlertDialog (recommended, consistent) vs a compact
   inline popup. Default = AlertDialog.
2. **#4 remaining-tap semantics.** Tapping the remaining time asks "jump so that
   THIS much remains", i.e. `seekTo(durationMs − enteredMs)` (Track adds
   `chapterStartMs`). **Confirm** this is the intended meaning (vs "rewind by
   x"). Default = absolute "time remaining".
3. **#4 which bars get it.** Plan makes BOTH the Track bar AND the Full/playlist
   bar tappable. Track → `seekTo(chapterStartMs + ms)` / `seekTo(chapterStartMs
   + durationMs − ms)`. Full → `seekToPlaylistPosition(ms)` /
   `seekToPlaylistPosition(totalPlaylistDurationMs − ms)`. Disabled bars (greyed)
   are NOT tappable. **Confirm** Full bar inclusion (it is the cheap, symmetric
   choice). Default = both.
4. **Min-touch-target helper.** Plan uses the existing library modifier
   `Modifier.minimumInteractiveComponentSize()` per-call; it does NOT add a new
   global helper. **Confirm** no app-wide helper wanted. Default = library
   modifier only.
5. **#14 fix shape.** Two viable shapes: (A) move the `InfoIcon` Box declaration
   to AFTER the controls `Column` inside `OverlayContent` (last = top-most hit
   sibling); (B) keep the InfoIcon where it is but stop the controls `Column`
   from capturing the empty top-right by NOT making the whole `Column`
   `fillMaxSize` (constrain its hit area). Plan adopts **(A)** — minimal, matches
   how `PlayerScreenExpanded` already declares InfoIcon last (line 1595) and is
   the structural mirror that makes both layouts consistent. **Confirm A vs B.**
   Default = A.
6. **#15 fix shape.** Plan replaces `Modifier.imePadding()` with
   `Modifier.windowInsetsPadding(WindowInsets.ime)` is identical, so instead it
   clamps: consume the IME inset only as the portion that actually overlaps the
   bar, OR gate the padding behind "an IME is genuinely visible" using
   `WindowInsets.isImeVisible`. Plan adopts the **`isImeVisible` gate** (apply
   `imePadding()` only when the IME is actually visible; otherwise `Modifier`),
   which removes the residual/animating mis-report on the cover screen while
   preserving keep-above-keyboard. **Confirm** gate vs a fixed
   `navigationBarsPadding` fallback. Default = `isImeVisible` gate.

---

## App-wide hitbox + display-robustness sweep (#14b / #15b)

The user asked for a hitbox review "across the app" (#14b) and a "general
robustness review for display types" (#15b) — not only the three specific fixes.
The investigation (Agent A, `docs/superpowers/investigation/2026-06-24-19-item-investigation.md`)
already swept every `ui/**` `pointerInput`/`clickable`/`size`/`offset`/width-derived
file. The sweep result, carried here:
- **No gesture region is sized from screen width** (only `ProgressSliders.kt:178-218`
  Canvas *draw* + `PlaybackControls.kt:88` icon *scaling* — neither is a hitbox), so
  there is no width-derived folded breakage beyond the three fixed in this plan.
- The **`fillMaxSize().verticalScroll` top-sibling-captures-taps** pattern (the #14
  root) recurs ONLY in `EqualizerScreen.kt:83-92` → **flagged, deliberately NOT
  touched** per the user's EQ instruction; the fixes in this plan do not affect it.
- The **edge-to-edge inset mis-apply** pattern (the #15 root) is confined to the
  mini-player Box; `FloatingVideoMiniPlayer` was already hardened (audit 6.11) and
  does not consume the InfoIcon's touches.
- Non-player InfoIcons (Last Played / Cloud / Library) sit in normal
  Scaffold/TopAppBar flow with no full-size sibling above them → should NOT exhibit
  the folded breakage.

**Conclusion:** #14b/#15b are accounted for — the three fixes (#1/#14/#15) are the
complete actionable set the sweep surfaced, EQ is flagged-untouched, and **Task 7
(final gate) below adds a cross-screen folded device-pass** that explicitly checks
the non-player InfoIcons AND the mini-player after the fixes, to CONFIRM no residual
folded hitbox/display breakage elsewhere. No additional code tasks are warranted.

---

## Tasks

> Build/install commands used in every verification (adb at
> `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, confirmed present):
> - Build: `.\gradlew.bat :app:assembleDebug -q`
> - Install: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk`
> - The phone holds a RELEASE-signed build → a debug install may fail
>   `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and require an uninstall (data wipe).
>   If so, that is an AWAITING-USER blocker for the device leg, not a code
>   blocker — record it, do not skip the code.
> - "Folded cover screen" = the Z Fold6 OUTER display (Compact width, < 600dp).
>   "Unfolded" = the inner display (Expanded, two-pane).

---

### Task 1 — #14: make the InfoIcon the top-most hit sibling on the folded layout

**Files**
- `PlayerScreen.kt:1096-1129` (the InfoIcon Box, currently declared at 1101,
  BEFORE the controls `Column` at 1145)
- `PlayerScreen.kt:1145-1308` (the controls `Column`, currently LAST sibling)
- `EqualizerScreen.kt:83-92` (FLAG comment only — no behaviour change)

**Steps**
1. In `OverlayContent`, locate the three `fillMaxSize()` z-siblings inside the
   outer `Box`: the scrim `Box` (1048-1052), the InfoIcon `Box` (1101-1129),
   and the controls `Column` (1145-1308). The render/hit order today is
   scrim → InfoIcon → controls (controls last = top-most, captures the
   top-right empty area on the folded scrolling Column).
2. Cut the entire InfoIcon `Box` block (the
   `Box(modifier = Modifier.fillMaxSize().then(cutoutPad).then(topBarInset)) { InfoIcon(...) ; if (uiState.isVideoContent && compactWidth) { IconButton(rotate) } }`,
   lines 1101-1129) and re-paste it IMMEDIATELY AFTER the controls `Column`
   closing brace (after line 1308, before the `bookmarks` block at 1310 — or
   after the bookmarks Row so it remains visually top-right and above everything;
   place it as the last child of the outer `Box`). The block's own
   `.align(Alignment.TopEnd)` keeps it visually top-right; being declared last
   makes it the top-most hit sibling so its 48dp `clickable` wins the hit chain
   over the controls Column.
3. Verify nothing inside the moved block depends on a variable declared between
   its old and new position. `cutoutPad`/`topBarInset` are defined at 1056/1074
   (before the controls Column) and `compactWidth` is computed inside the block
   itself (1113) — all remain in scope after the move. No new imports.
4. Add a one-line code comment above the EQ root in
   `EqualizerScreen.kt:83` (NO behaviour change), e.g.
   `// NOTE: shares the fillMaxSize().verticalScroll + nested InfoIcon pattern of`
   `// PlayerScreen OverlayContent (#14). Folded hit-capture not addressed here`
   `// per scope; revisit if EQ "i" proves untappable folded.`
   This documents the flag without touching the hitboxes.

**Verification (DEVICE — folded AND unfolded)**
- Build: `.\gradlew.bat :app:assembleDebug -q` → BUILD SUCCESSFUL. Install.
- FOLDED (outer display): `adb.exe shell input keyevent KEYCODE_WAKEUP`; open
  the app, play any audio item, go to the Player tab.
  `adb.exe shell wm size` to confirm the compact width. Tap the blue "i" box in
  the top-right corner (it sits at top=8dp,end=8dp; on a ~1080px-wide outer
  screen tap near x≈1020 y≈140). **PASS** = the Info sheet opens. Pre-fix it did
  nothing.
- UNFOLDED (inner display): unfold, repeat — tap the top-right "i" on the
  two-pane player. **PASS** = the Info sheet still opens (no regression; this
  path was already declared-last at 1595).
- REGRESSION: with controls visible, drag/scroll the controls Column up/down on
  the folded screen → **PASS** = it still scrolls (the InfoIcon, a 48dp box in
  the corner, must not block the scroll gesture elsewhere).

**Commit:** `fix(#14): folded info icon tappable — declare InfoIcon last in OverlayContent so it wins the hit chain over the scrolling controls Column`

---

### Task 2 — #1: brightness-banner touch target + folded clipping

**Files**
- `SecondaryControls.kt:149-158` (the `if (!canWrite) { Text(... .padding(start=28dp,top=2dp).clickable{...}) }` banner)
- `SecondaryControls.kt:57-60` (the wrapping `Column` padding — relevant to the
  folded clipping)

**Steps**
1. Replace the banner block (149-158). Move `.clickable{}` to the FRONT of the
   chain (so the whole region is the click target), add
   `minimumInteractiveComponentSize()` for the 48dp minimum, and move the
   visual `.padding()` AFTER clickable so it pads inside the clickable bounds:
   ```kotlin
   if (!canWrite) {
       Text(
           text = "Tap to grant brightness permission",
           style = MaterialTheme.typography.bodyMedium, // up from bodySmall for legibility
           color = TealAccent,
           modifier = Modifier
               .clickable { BrightnessHelper.requestWriteSettingsPermission(context) }
               .minimumInteractiveComponentSize()
               .padding(start = 28.dp, top = 6.dp, bottom = 6.dp)
       )
   }
   ```
   (Order: `clickable` first → padding is inside the ripple/hit region;
   `minimumInteractiveComponentSize` forces ≥48dp height even though the text is
   one line.)
2. Add the import `import androidx.compose.material.minimumInteractiveComponentSize`
   (the modifier lives in `androidx.compose.material`; the project already pulls
   it via the Material3 BOM — verify it resolves at compile, else use
   `androidx.compose.material3.minimumInteractiveComponentSize` if exposed in
   2025.04.00; pick whichever the compiler accepts).
3. Folded clipping: the banner is the LAST child of the
   `VolumeAndBrightnessControls` `Column`, which itself lives inside the player
   controls `Column` that is `fillMaxSize().verticalScroll` + `bottomBarInset`
   (80dp) on the folded screen. Because the parent scrolls, the banner is
   reachable by scrolling; the residual issue is the bottom nav overlay
   overlapping it. The Task 1 move (InfoIcon last) does NOT affect this. Ensure
   the banner is not the very last pixel by confirming the parent `Column`'s
   `bottomBarInset` already reserves the 80dp overlay band
   (`PlayerScreen.kt:1083-1095`) — it does. No extra padding needed here beyond
   the vertical padding added in step 1, which lifts the glyph off the clip edge.
   Add a comment noting the banner relies on the parent's `bottomBarInset` for
   folded clearance.

**Verification (DEVICE — folded AND unfolded)**
- Precondition: revoke WRITE_SETTINGS so the banner shows —
  `adb.exe shell appops set com.powermediaplayer WRITE_SETTINGS deny` (then
  re-launch the player; if the package id differs, read it from
  `adb.exe shell pm list packages | findstr power`).
- Open the Player tab on a video or audio item; scroll the controls down to the
  Volume/Brightness block; the "Tap to grant brightness permission" banner shows.
- FOLDED (outer): tap anywhere on the banner text INCLUDING the left 28dp margin
  region and just below the glyph (within the new 48dp band). **PASS** = the
  system WRITE_SETTINGS permission screen opens. Pre-fix, taps on the margin / a
  few px off the glyph did nothing.
- UNFOLDED (inner): repeat in the expanded two-pane player. **PASS** = same.
- CLIPPING: on the folded screen, confirm the full banner text is visible (not
  cut by the bottom nav overlay) once scrolled to the bottom. **PASS** = whole
  string legible.
- Restore: `adb.exe shell appops set com.powermediaplayer WRITE_SETTINGS allow`.

**Commit:** `fix(#1): brightness-permission banner — clickable-before-padding + 48dp touch target + folded clearance comment`

---

### Task 3 — #4a: numeric seek dialog composable + parser (new file)

**Files**
- NEW `app/src/main/java/com/powermediaplayer/ui/player/components/SeekTimeDialog.kt`

**Steps**
1. Create the file with a pure parser usable from a unit test:
   ```kotlin
   package com.powermediaplayer.ui.player.components

   /**
    * Parses "h:mm:ss", "m:ss", or bare seconds (digits only) into milliseconds.
    * Returns null on malformed input or any negative/overflowing value. Pure —
    * unit-testable with no Android deps.
    */
   fun parseTimeToMs(raw: String): Long? {
       val s = raw.trim()
       if (s.isEmpty()) return null
       val parts = s.split(":")
       if (parts.size > 3) return null
       val nums = parts.map { it.toLongOrNull() ?: return null }
       if (nums.any { it < 0 }) return null
       val (h, m, sec) = when (nums.size) {
           1 -> Triple(0L, 0L, nums[0])
           2 -> Triple(0L, nums[0], nums[1])
           else -> Triple(nums[0], nums[1], nums[2])
       }
       if (nums.size >= 2 && sec >= 60) return null
       if (nums.size == 3 && m >= 60) return null
       return ((h * 3600 + m * 60 + sec) * 1000L)
   }
   ```
2. Add a formatter mirror used for the dialog hint (or reuse the existing
   `*Formatted` strings already in `PositionUi` — pass them in to avoid a second
   formatter). The dialog takes the formatted current value as a label.
3. Add the dialog composable (style matches `SleepTimerDialog`):
   ```kotlin
   @Composable
   fun SeekTimeDialog(
       title: String,
       currentLabel: String,
       maxMs: Long,
       onConfirmMs: (Long) -> Unit,
       onDismiss: () -> Unit
   ) {
       var text by remember { mutableStateOf("") }
       val parsed = parseTimeToMs(text)
       val valid = parsed != null && parsed in 0..maxMs
       AlertDialog(
           onDismissRequest = onDismiss,
           containerColor = SurfaceElevated,
           title = { Text(title, style = MaterialTheme.typography.titleLarge, color = TealAccent) },
           text = {
               Column {
                   Text(
                       "Current: $currentLabel. Enter a time (m:ss, h:mm:ss, or seconds).",
                       style = MaterialTheme.typography.bodyMedium,
                       color = TextSecondary,
                       modifier = Modifier.padding(bottom = 8.dp)
                   )
                   OutlinedTextField(
                       value = text,
                       onValueChange = { text = it },
                       singleLine = true,
                       isError = text.isNotEmpty() && !valid,
                       keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                       placeholder = { Text("e.g. 12:30") }
                   )
               }
           },
           confirmButton = {
               TextButton(
                   onClick = { parsed?.let { onConfirmMs(it); onDismiss() } },
                   enabled = valid
               ) { Text("Jump", color = if (valid) TealAccent else DisabledGrey) }
           },
           dismissButton = {
               TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
           }
       )
   }
   ```
   Imports: `androidx.compose.foundation.text.KeyboardOptions`,
   `androidx.compose.ui.text.input.KeyboardType`, Material3 dialog/text-field
   APIs, `com.powermediaplayer.ui.theme.*`.

**Verification**
- Build only this task: `.\gradlew.bat :app:assembleDebug -q` → BUILD SUCCESSFUL
  (the dialog/parser compile; not yet wired, so nothing visible on device).
- (Optional, recommended) add `SeekTimeParserTest` (pure JVM unit test) asserting
  `parseTimeToMs("90")==90_000`, `parseTimeToMs("1:30")==90_000`,
  `parseTimeToMs("1:00:00")==3_600_000`, `parseTimeToMs("1:60")==null`,
  `parseTimeToMs("x")==null`, `parseTimeToMs("")==null`. Run
  `.\gradlew.bat :app:testDebugUnitTest --tests "*SeekTimeParser*" -q`.

**Commit:** `feat(#4): SeekTimeDialog + parseTimeToMs (numeric time-entry dialog, pure parser + unit test)`

---

### Task 4 — #4b: make elapsed + remaining time Texts tappable in ProgressSliders

**Files**
- `ProgressSliders.kt:222-247` (the time-`Text` `Row` inside `PositionSlider`)
- `ProgressSliders.kt:28-49` (the `ProgressSliders` parameter list)
- `ProgressSliders.kt:62-72` and `98-106` (the two `PositionSlider` call sites)
- `ProgressSliders.kt:116-127` (the `PositionSlider` signature)

**Steps**
1. Add two nullable callbacks to `PositionSlider`'s signature (after
   `onSeek`): `onSeekToElapsedMs: (() -> Unit)? = null,
   onSeekToRemainingMs: (() -> Unit)? = null`. (The composable itself only knows
   fractions, so the dialog-open is delegated upward; the callback fires when the
   user taps the time. Naming reflects the resulting action.)
2. In the time-`Text` `Row` (222-247), wrap the elapsed (`positionFormatted`,
   line 229) and remaining (`remainingFormatted`, line 235) `Text`s so they open
   the dialog when enabled:
   ```kotlin
   Text(
       text = positionFormatted,
       style = MaterialTheme.typography.bodySmall,
       color = if (enabled) TextSecondary else DisabledGrey,
       modifier = if (enabled && onSeekToElapsedMs != null) {
           Modifier
               .clickable { onSeekToElapsedMs() }
               .minimumInteractiveComponentSize()
               .padding(horizontal = 4.dp)
       } else Modifier
   )
   ```
   Apply the symmetric wrap to the remaining `Text` with `onSeekToRemainingMs`.
   The `durationFormatted` `Text` (241) stays plain (it is the immutable total).
   `minimumInteractiveComponentSize()` lifts each tappable label to the 48dp
   minimum; the slider sits 28dp tall directly above, so the expanded target may
   slightly overlap the slider's bottom — acceptable because the slider's drag
   gesture is on its own thumb/track and the labels are below it; verify no
   accidental seek on device (Task 6).
3. Add imports to `ProgressSliders.kt`:
   `androidx.compose.foundation.clickable`,
   `androidx.compose.material.minimumInteractiveComponentSize`.
4. Add the same two callbacks to the public `ProgressSliders` signature
   (28-49), one pair for the Track bar and one pair for the Full bar:
   `onTrackSeekToElapsedMs`, `onTrackSeekToRemainingMs`,
   `onPlaylistSeekToElapsedMs`, `onPlaylistSeekToRemainingMs` (all
   `(() -> Unit)? = null`).
5. Thread them into the two `PositionSlider` calls: the Track call (62-72) gets
   `onSeekToElapsedMs = onTrackSeekToElapsedMs, onSeekToRemainingMs = onTrackSeekToRemainingMs`;
   the Full call (98-106) gets the playlist pair.

**Verification**
- Build: `.\gradlew.bat :app:assembleDebug -q` → BUILD SUCCESSFUL. (Callbacks
  default null at the existing call site in `PositionSection` until Task 5 wires
  them, so the project still compiles after this task in isolation.)

**Commit:** `feat(#4): ProgressSliders elapsed/remaining Texts clickable with 48dp targets + seek callbacks (Track + Full)`

---

### Task 5 — #4c: wire the dialog into PositionSection (single call site, both layouts)

**Files**
- `PlayerScreen.kt:2112-2150` (`PositionSection` — the SINGLE call site of
  `ProgressSliders`, used by BOTH layouts)

**Steps**
1. Inside `PositionSection`, add dialog state and the four handlers. The dialog
   choice is held in local state (which bar + which end), the dialog is shown
   once:
   ```kotlin
   var seekDialog by remember { mutableStateOf<SeekDialogReq?>(null) }
   ```
   where `SeekDialogReq` is a tiny local sealed/`data class` carrying
   `title: String`, `currentLabel: String`, `maxMs: Long`, and
   `onConfirmMs: (Long) -> Unit` (define it as a private top-level data class in
   the same file).
2. Pass the four callbacks to `ProgressSliders`:
   ```kotlin
   onTrackSeekToElapsedMs = {
       seekDialog = SeekDialogReq(
           title = "Jump to time",
           currentLabel = pos.positionFormatted,
           maxMs = pos.durationMs
       ) { ms -> viewModel.seekTo(pos.chapterStartMs + ms) }
   },
   onTrackSeekToRemainingMs = {
       seekDialog = SeekDialogReq(
           title = "Jump to time remaining",
           currentLabel = pos.remainingFormatted,
           maxMs = pos.durationMs
       ) { ms -> viewModel.seekTo(pos.chapterStartMs + (pos.durationMs - ms)) }
   },
   onPlaylistSeekToElapsedMs = {
       seekDialog = SeekDialogReq(
           title = "Jump in album/book",
           currentLabel = pos.playlistPositionFormatted,
           maxMs = pos.totalPlaylistDurationMs
       ) { ms -> viewModel.seekToPlaylistPosition(ms) }
   },
   onPlaylistSeekToRemainingMs = {
       seekDialog = SeekDialogReq(
           title = "Jump — album/book remaining",
           currentLabel = pos.playlistRemainingFormatted,
           maxMs = pos.totalPlaylistDurationMs
       ) { ms -> viewModel.seekToPlaylistPosition(pos.totalPlaylistDurationMs - ms) }
   },
   ```
   (The Track math `chapterStartMs + ms` / `chapterStartMs + (durationMs − ms)`
   exactly mirrors the existing `onTrackSeek` at 2136. Full uses
   `seekToPlaylistPosition` mirroring 2146.)
3. Render the dialog when set:
   ```kotlin
   seekDialog?.let { req ->
       SeekTimeDialog(
           title = req.title,
           currentLabel = req.currentLabel,
           maxMs = req.maxMs,
           onConfirmMs = req.onConfirmMs,
           onDismiss = { seekDialog = null }
       )
   }
   ```
4. Import `com.powermediaplayer.ui.player.components.SeekTimeDialog`.

**Verification (DEVICE — folded AND unfolded)**
- Build: `.\gradlew.bat :app:assembleDebug -q` → BUILD SUCCESSFUL. Install.
- Play a local audiobook/album with a known duration on the Player tab.
- FOLDED (outer display):
  - Tap the ELAPSED time (left label under the Track bar). **PASS** = the
    "Jump to time" dialog opens; type `1:00`, tap Jump → playback position jumps
    to 1:00 of the current track/chapter (verify the slider thumb + elapsed
    label update). Confirm via `adb.exe shell dumpsys media_session | findstr -i position` or visually.
  - Tap the REMAINING time (right label). **PASS** = "Jump to time remaining"
    dialog; type `0:30`, Jump → position lands at `duration − 30s`.
  - On a multi-track album, tap the FULL bar's elapsed/remaining labels →
    **PASS** = album-level jump.
  - Enter invalid input (`1:75` or `abc`) → **PASS** = "Jump" is disabled, field
    shows error state, no seek.
- UNFOLDED (inner display): repeat all four taps in the expanded two-pane player
  → **PASS** = identical behaviour (single composable, single call site).
- TARGET SIZE: tap slightly above/below each label (within ~48dp) → **PASS** =
  dialog still opens. Tap the slider thumb itself → **PASS** = it drags/seeks,
  it does NOT open the dialog (no target overlap regression).

**Commit:** `feat(#4): wire SeekTimeDialog into PositionSection — tap elapsed/remaining → numeric jump (Track adds chapterStartMs; both layouts)`

---

### Task 6 — #15: fix the OledBlack strip above the folded mini-player

**Files**
- `AppNavigation.kt:340-357` (`NonPlayerRoute`, the `Box(Modifier.imePadding())`
  wrapping `MiniPlayerBar` at 353)

**Steps**
1. Replace the bare `imePadding()` on the mini-bar Box with an
   IME-visibility-gated inset so the cover screen's residual/animating IME
   mis-report contributes zero when no keyboard is up:
   ```kotlin
   @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
   // ...
   val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
   Box(
       modifier = if (imeVisible) Modifier.imePadding() else Modifier
   ) {
       com.powermediaplayer.ui.components.MiniPlayerBar(onClick = onMiniClick)
   }
   ```
   `WindowInsets.isImeVisible` (`androidx.compose.foundation.layout`,
   `ExperimentalLayoutApi`) is true ONLY while the IME is genuinely shown, so
   the OledBlack inset can no longer be injected by a stale/animating inset on
   the folded cover screen. When a keyboard IS up (search fields etc.) the bar
   still lifts above it.
2. Keep the explanatory comment (348-352) but update it to note the visibility
   gate added for the folded edge-to-edge mis-report (#15).
3. Confirm the `NonPlayerRoute` `Column` itself is unaffected — `contentInset`
   (346) and the `weight(1f)` content Box are untouched; only the mini-bar Box
   modifier changes.

**Verification (DEVICE — folded AND unfolded)**
- Build: `.\gradlew.bat :app:assembleDebug -q` → BUILD SUCCESSFUL. Install.
- Have something playing so the mini-player bar is visible. Go to a NON-Player
  tab (Last Played / Library / Cloud) where the mini-bar shows at the bottom.
- FOLDED (outer display): observe the bar with NO keyboard open. **PASS** = the
  grey (`0xFF1A1A1A`) mini-bar sits directly above the system nav with NO
  black (`0xFF000000`) strip above it. Capture
  `adb.exe shell screencap -p /sdcard/mini.png` →
  `adb.exe pull /sdcard/mini.png deeplogs/` and eyeball the region just above
  the bar — it must be content, not a black band.
- FOLDED + keyboard: open a search box on the Cloud tab (raise the IME).
  **PASS** = the mini-bar rises above the keyboard (keep-above-IME preserved),
  and when the keyboard closes the bar returns flush above the nav with no
  residual black strip.
- UNFOLDED (inner display): repeat — **PASS** = no regression (the side rail
  layout never had the strip; bar still correct above the nav).

**Commit:** `fix(#15): gate mini-player imePadding behind isImeVisible — removes the OledBlack strip from the folded cover screen`

---

### Task 7 — Final gate: build + install + consolidated device pass

**Files** — none (verification only).

**Steps**
1. Clean build: `.\gradlew.bat :app:assembleDebug -q` → BUILD SUCCESSFUL.
2. Run any unit tests touched: `.\gradlew.bat :app:testDebugUnitTest -q`
   (includes `SeekTimeParserTest` from Task 3) → all green.
3. Install:
   `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk`.
   If `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → record AWAITING-USER (debug install
   needs an uninstall = data wipe; do not wipe without consent). Code work is
   complete regardless.
4. Re-run the four acceptance checks back-to-back on BOTH form factors:
   - #14: folded "i" opens Info sheet (Task 1 PASS criterion).
   - #1: folded brightness banner tappable on margin + below glyph (Task 2).
   - #4: folded + unfolded elapsed/remaining tap → numeric jump (Task 5).
   - #15: folded mini-bar with no black strip, rises over keyboard (Task 6).
5. Per repo policy (auto-push + auto-install): `git push` after the turn's
   commits; confirm the install above.
6. Update `TASKS.md`: add rows for #14/#1/#15/#4 under a new section with
   evidence lines reflecting the device PASS/PASS-pending statuses; report THE
   TABLE.

**Verification**
- All four PASS criteria met on the folded cover screen AND unfolded inner
  display, with the listed regression checks (scroll still works, slider still
  drags, keyboard-lift preserved) green. Build + unit tests green. Install
  Success (or AWAITING-USER if the signature blocks it).

**Commit:** (no code) — covered by the per-task commits; this task is the gate.

---

## Notes / risks

- **#14 declaration-order move (Task 1)** is the one structural change; verify by
  reading the moved block compiles in-scope (all referenced vals are declared
  earlier in `OverlayContent`). It mirrors the unfolded layout (InfoIcon already
  last at 1595), so it converges the two layouts rather than diverging them.
- **Touch-target overlap (#4, Task 4/5):** expanding the time labels to 48dp may
  nudge into the 28dp slider band above. The slider's drag is on its own
  pointer-input region; the device check in Task 5 explicitly verifies a slider
  drag does NOT trigger the dialog and a label tap does NOT seek the slider.
- **`minimumInteractiveComponentSize` import:** lives in
  `androidx.compose.material`. If the project's BOM exposes a Material3 alias,
  prefer it; otherwise the `material` import is the canonical one and is already
  on the classpath (Material is a transitive dep of Material3). Confirm at
  compile.
- **EQ (`EqualizerScreen.kt:83-92`)** is FLAGGED ONLY (Task 1 step 4 comment).
  No hitbox change there per user instruction.
- All four are Compose UI / folded-device behaviours — NOT JVM-unit-testable
  except the pure `parseTimeToMs` parser (Task 3 test). Every other acceptance
  is a device step with an explicit adb command + tap + observation.
