# vc32 — Evidence-locked fixes + approved UX (back-stack, loading coverage, ⋮ menus, expandable settings)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the three log-convicted defects (back-stack wipe, Spotify banner death, uncovered Drive/local resume wait) and ship the three user-approved UX changes (⋮ row menus, Hue sub-sections, expandable settings groups with search-across-collapsed), each gated by build + test + grep predicates and a final on-device round.

**Architecture:** All changes are additive/surgical: one navigation-options change, one grace-window in SpotifyProvider, banner-flag wraps around the three resume paths, a trailing IconButton in two row composables, and two expand/collapse wrappers in the settings catalog (the catalog itself — data + search — is untouched, which is what keeps search seeing collapsed content). A conditional performance task (chapter cache) is gated on instrumented numbers, per systematic-debugging.

**Tech Stack:** Kotlin, Jetpack Compose (M3), navigation-compose, Media3, JUnit4 (app/src/test), DiagLog/DeepLogger instrumentation already installed on device (vc31 debug, 2026-06-04 18:53).

---

## Evidence index (cross-references — every task cites these)

| Ref | Source | Key fact |
|-----|--------|----------|
| E1 | `docs/superpowers/specs/2026-06-04-investigation-findings.md` §1 | 6 back presses; every non-IME press exited the app; cause = `navigateToPlayer` popUpTo-wipe |
| E2 | findings §3 | Drive resume 93 s / Spotify 32 s with zero indication |
| E3 | findings §3 gap 1 | `SpotifyProvider.kt:1007` kills banner on null snap ~1 s into handoff |
| E4 | findings §3 gap 2 | `setCloudFetchInProgress(true)` only in `CloudViewModel.kt:1101/1129`; Last-Played resume never sets it |
| E5 | findings §3 gap 3 | vc31 spinner keys off ExoPlayer load — pre-player parse/SAF phase uncovered |
| E6 | `docs/superpowers/specs/2026-05-30-vc31-ux-implementation-checklist.md` | settings catalog predicates A1-A3/B1-B4 all PASS; `SETTINGS_ITEM_IDS` = 20-item A2 guard |
| E7 | User approvals 2026-06-04: Idea 1 yes; Idea 2 = 3-5 expandable Hue sub-sections; ALL 8 groups expandable; search must check all sections whether expanded or not |
| E8 | `TASKS.md` | binding ledger; every task below ends by updating its row |
| E9 | context7 /websites/developer_android_develop_ui_compose | drill-ins push (don't wipe); expandable = `rememberSaveable` + `AnimatedVisibility(expandVertically/shrinkVertically)`; root back not intercepted |
| E10 | logcat `deeplogs/app-slice.txt` | replication timestamps 18:05:10→18:06:43.9, 18:32:08→18:32:40 |

**Process rules (binding):** update `TASKS.md` rows as tasks complete (E8 protocol); auto-push + adb install -r after APK-affecting commits (standing consent incl. signature-conflict uninstall, but check `dumpsys media_session` shows no PLAYING first); investigation-gated tasks (10, 11) MUST NOT be implemented before their evidence step.

## File map

| File | Change |
|------|--------|
| `app/src/main/java/com/powermediaplayer/ui/navigation/AppNavigation.kt` | Task 1: `navigateToPlayer` becomes push |
| `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt` | Task 2: handoff grace window |
| `app/src/test/java/com/powermediaplayer/cloud/SpotifyBannerGraceTest.kt` | Task 2: new unit test |
| `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt` | Task 2 (call site) + Task 3 (banner wrap) |
| `app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt` | Task 2: call-site audit |
| `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` | Task 3: cold-start banner wrap |
| `app/src/main/java/com/powermediaplayer/ui/library/LibraryViewModel.kt` | Task 3: playSingle/playFiles banner wrap |
| `app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt` | Task 4: ⋮ in `MediaFileItem` |
| `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedScreen.kt` | Task 5: ⋮ in both `trailing` slots |
| `app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt` | Task 6: expandable groups; Task 7: Hue sub-sections |
| `TASKS.md`, checklist doc | every task: ledger/evidence updates |

---

### Task 1: Back — drill-in to Player pushes instead of wiping (E1, E9)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/navigation/AppNavigation.kt:82-88` (`navigateToPlayer`)

- [ ] **Step 1.1: Confirm current wipe behaviour (evidence re-check)**

Run: `grep -n "navigateToPlayer\|popUpTo" "app/src/main/java/com/powermediaplayer/ui/navigation/AppNavigation.kt" | head`
Expected: `navigateToPlayer` body contains `popUpTo(navController.graph.findStartDestination().id)`.

- [ ] **Step 1.2: Change drill-in to push**

Replace the `navigateToPlayer` body:

```kotlin
    // vc32 (E1): drill-ins PUSH the Player so back returns to the list the
    // user came from (Last Played / Library / Cloud / mini-player). The
    // previous popUpTo-wipe made every back press exit the whole app —
    // logcat 2026-06-04 18:05-18:32 shows 5 moveTaskToBack exits during
    // resume waits. Bottom-bar TAB taps keep their canonical
    // popUpTo(start){saveState} pattern (unchanged below) so tab presses
    // still reset the stack and growth stays bounded.
    val navigateToPlayer = {
        navController.navigate(Screen.Player.route) {
            launchSingleTop = true
        }
    }
```

Do NOT touch the `NavigationBarItem` `onClick` block or the widget deep-link `LaunchedEffect` — both keep `popUpTo`.

- [ ] **Step 1.3: Build**

Run: `./gradlew :app:compileDebugKotlin -q` → expect silent success.

- [ ] **Step 1.4: Predicate**

Run: `grep -c "popUpTo" app/src/main/java/com/powermediaplayer/ui/navigation/AppNavigation.kt`
Expected: exactly **2** (tab onClick + widget deep-link). `navigateToPlayer` contains none.

- [ ] **Step 1.5: Commit + ledger**

```bash
git add app/src/main/java/com/powermediaplayer/ui/navigation/AppNavigation.kt
git commit -m "fix(nav): vc32 drill-in to Player pushes — back returns to source list (E1)"
```
Update `TASKS.md` T234-1 row → DONE(predicate output).
`[VISUAL]` Task 12: tap recent → Player → back ⇒ Last Played list, app does NOT exit.

---

### Task 2: Spotify banner survives the device-wake handoff (E2, E3)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt` (fields ~line 120, `startPlaybackPolling` 962-1008)
- Modify: `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt:284`
- Inspect: `app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt:439,951`
- Create: `app/src/test/java/com/powermediaplayer/cloud/SpotifyBannerGraceTest.kt`

- [ ] **Step 2.1: Write the failing test**

```kotlin
package com.powermediaplayer.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E3: banner must NOT clear on a null /v1/me/player snap while a
 *  user-initiated handoff is still inside its grace window. */
class SpotifyBannerGraceTest {
    @Test
    fun nullSnap_insideGrace_keepsBanner() {
        assertFalse(shouldClearBannerOnNullSnap(nowMs = 10_000L, graceUntilMs = 45_000L))
    }
    @Test
    fun nullSnap_afterGrace_clearsBanner() {
        assertTrue(shouldClearBannerOnNullSnap(nowMs = 46_000L, graceUntilMs = 45_000L))
    }
    @Test
    fun nullSnap_noHandoff_clearsImmediately() {
        assertTrue(shouldClearBannerOnNullSnap(nowMs = 1L, graceUntilMs = 0L))
    }
}
```

- [ ] **Step 2.2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.powermediaplayer.cloud.SpotifyBannerGraceTest" -q`
Expected: FAIL — `shouldClearBannerOnNullSnap` unresolved.

- [ ] **Step 2.3: Implement grace window in SpotifyProvider**

(a) Top-level (same file, outside the class):

```kotlin
/** Pure, testable handoff-grace predicate (E3 fix). */
internal fun shouldClearBannerOnNullSnap(nowMs: Long, graceUntilMs: Long): Boolean =
    nowMs >= graceUntilMs
```

(b) Field next to `_spotifyMetadataFetching` (~line 121):

```kotlin
    /** vc32 (E3): while a user-initiated play/transfer is in flight, null
     *  /v1/me/player snaps (Spotify still waking) must NOT clear the
     *  loading banner. Set by startPlaybackPolling(expectPlayback=true). */
    @Volatile private var bannerGraceUntilMs: Long = 0L
    private companion object { const val HANDOFF_GRACE_MS = 45_000L }
```

(c) Signature + entry of `startPlaybackPolling` (962):

```kotlin
    fun startPlaybackPolling(expectPlayback: Boolean = false) {
        if (expectPlayback) {
            bannerGraceUntilMs = android.os.SystemClock.uptimeMillis() + HANDOFF_GRACE_MS
            _spotifyMetadataFetching.value = true
        }
        if (pollJob?.isActive == true) return
```

(d) The null-snap branch (currently lines 1001-1008) becomes:

```kotlin
                    } else {
                        _spotifyState.value = null
                        lastTrackUri = ""
                        lastLyrics = null
                        lastSynced = emptyList()
                        // vc32 (E3): during a handoff Spotify legitimately
                        // reports no active device for many seconds — only
                        // clear once outside the grace window.
                        val clear = shouldClearBannerOnNullSnap(
                            android.os.SystemClock.uptimeMillis(), bannerGraceUntilMs
                        )
                        com.powermediaplayer.util.Diag.i(
                            "PMP_DIAG",
                            "Spotify poll null snap — bannerClear=$clear graceRemainMs=" +
                                (bannerGraceUntilMs - android.os.SystemClock.uptimeMillis())
                        )
                        if (clear) _spotifyMetadataFetching.value = false
                    }
```

(e) In the non-null snap path, immediately before the existing
`_spotifyMetadataFetching.value = false` (line ~1000), add:

```kotlin
                        bannerGraceUntilMs = 0L   // handoff resolved
```

(f) In `stopPlaybackPolling` (the function containing line 1089's
`_spotifyMetadataFetching.value = false`), add `bannerGraceUntilMs = 0L`
beside it (stop = nothing to wait for).

- [ ] **Step 2.4: Call-site audit (all permutations, E8 rule)**

- `LastPlayedViewModel.kt:284` → `startPlaybackPolling(expectPlayback = true)` (user tapped a Spotify recent — this IS the reported path).
- `CloudViewModel.kt:439` and `:951` → read 10 lines around each. Rule: pass `true` iff the call directly follows a user-initiated play/transfer command; leave default `false` iff it is passive mirror discovery. Record verdicts in the commit message.

- [ ] **Step 2.5: Tests pass + full build**

Run: `./gradlew :app:testDebugUnitTest --tests "com.powermediaplayer.cloud.SpotifyBannerGraceTest" :app:compileDebugKotlin -q`
Expected: 3/3 PASS, compile green.

- [ ] **Step 2.6: Commit + ledger**

```bash
git add -A
git commit -m "fix(spotify): vc32 banner survives device-wake handoff — 45s grace on null snaps (E3)"
```
Update `TASKS.md`. `[VISUAL]` Task 12: Spotify resume shows the banner until track metadata lands.

---

### Task 3: Drive/local resume shows the banner during the pre-player phase (E2, E4, E5)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt` (local/Drive branch of `playLocalAt`, ~257-334)
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` (cold-start restore branch, ~755-798)
- Modify: `app/src/main/java/com/powermediaplayer/ui/library/LibraryViewModel.kt` (`playSingle` + `playFiles` if they call `extractChaptersAsBundle`)

The existing top-centre banner ("Loading metadata… please wait…", `PlayerScreen.kt:160-189`) renders whenever `PlayerState.cloudFetchInProgress` is true — we reuse it; no UI change needed.

- [ ] **Step 3.1: Locate every pre-player parse site (evidence sweep)**

Run: `grep -rn "extractChaptersAsBundle" app/src/main/java --include=*.kt`
Expected ≥3 hits: LastPlayedViewModel, PlayerViewModel (cold-start), LibraryViewModel. EVERY hit gets the wrap below (test-all-permutations rule; E5 says local is affected too, not only Drive).

- [ ] **Step 3.2: Wrap the LastPlayed local/Drive resume branch**

Inside the local/Drive coroutine of `playLocalAt`, immediately after `resumeActive.incrementAndGet()` (line ~266), and with the clear in a `finally` so failures can't strand the banner:

```kotlin
            playbackConnection.setCloudFetchInProgress(true)
            try {
                // existing body: SAF resolve → extractChaptersAsBundle →
                // setMediaItems → seekTo → play  (UNCHANGED)
            } finally {
                playbackConnection.setCloudFetchInProgress(false)
            }
```

Keep all existing DiagLog RESUME/PERF lines exactly where they are — Task 9 needs them.

- [ ] **Step 3.3: Wrap the cold-start restore (PlayerViewModel ~755-798)**

Same pattern: `setCloudFetchInProgress(true)` as the first statement inside the `runCatching {` of the `currentMediaUri == null && (LOCAL||DRIVE)` branch; `false` in a `finally { }` appended to the runCatching (`.also`-style is fine: convert `runCatching { … }` to `try { … } catch (e: Exception) { existing-failure-log } finally { playbackConnection.setCloudFetchInProgress(false) }`).

- [ ] **Step 3.4: Wrap LibraryViewModel parse sites the same way**

For each `extractChaptersAsBundle` call found in Step 3.1 inside LibraryViewModel: wrap from just before the parse to just after the corresponding `setMediaItems` in the same `try/finally` pattern.

- [ ] **Step 3.5: Build + predicate**

Run: `./gradlew :app:compileDebugKotlin -q` → green.
Run: `grep -rn "setCloudFetchInProgress" app/src/main/java --include=*.kt | grep -v "PlaybackConnection.kt" | wc -l`
Expected: ≥ 8 (was 2 — CloudViewModel only, E4). Every `(true)` has a paired `finally`-scoped `(false)` — verify by eye per file.

- [ ] **Step 3.6: Commit + ledger**

```bash
git add -A
git commit -m "fix(resume): vc32 loading banner covers pre-player parse on ALL resume paths (E4/E5)"
```
`[VISUAL]` Task 12: Drive resume shows banner within ~1 s of tap.

---

### Task 4: ⋮ menu on Library rows (E7 idea 1)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt` — `MediaFileItem` (~699-790)

- [ ] **Step 4.1: Add the ⋮ trailing button**

In `MediaFileItem`, immediately AFTER the favourite-star `IconButton` (the last child of the Row, ~line 778-787), add:

```kotlin
        // vc32 (E7 idea 1): visible affordance for the long-press context
        // menu — same sheet, discoverable via a normal tap.
        IconButton(onClick = onLongClick) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options for ${file.title}",
                tint = TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
```

`Icons.Filled.MoreVert` is already imported/used at line ~369. `onLongClick` already opens `contextItem = file` → `TrackContextSheet` (line 87) — zero new logic. Long-press continues to work.

- [ ] **Step 4.2: Build + predicate**

Run: `./gradlew :app:compileDebugKotlin -q` → green.
Run: `grep -c "More options for" app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt` → `1`.

- [ ] **Step 4.3: Commit + ledger** — `git commit -m "feat(library): vc32 visible ⋮ menu per row (E7-1)"`

---

### Task 5: ⋮ menu on Last Played rows (E7 idea 1)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedScreen.kt` — the two `trailing = {` slots (recents ~286, pinned ~550)

- [ ] **Step 5.1: Recents rows (trailing slot at ~286)**

The recents `HistoryRowWithBookmarks` call already sets `onLongClick = { contextItem = item; contextFromRecents = true }` (lines ~282-283). Inside its `trailing = {` lambda, AFTER the existing pin-star `IconButton`, add:

```kotlin
                                IconButton(onClick = {
                                    contextItem = item
                                    contextFromRecents = true
                                }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "More options for ${item.title}",
                                        tint = TextTertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
```

- [ ] **Step 5.2: Pinned rows (trailing slot at ~550)**

Same insertion, but the body matches the pinned long-press (lines ~224-225): `contextItem = item; contextFromRecents = false`.

- [ ] **Step 5.3: Import check**

`Icons.Filled.MoreVert` is NOT yet imported in LastPlayedScreen (imports list lines 12-23). Add: `import androidx.compose.material.icons.filled.MoreVert` and `import com.powermediaplayer.ui.theme.TextTertiary` if absent.

- [ ] **Step 5.4: Build + predicate**

Run: `./gradlew :app:compileDebugKotlin -q` → green.
Run: `grep -c "More options for" app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedScreen.kt` → `2`.

- [ ] **Step 5.5: Commit + ledger** — `git commit -m "feat(recents): vc32 visible ⋮ menu on recents + pinned rows (E7-1)"`

---

### Task 6: All 8 settings groups expandable; search sees collapsed content (E6, E7, E9)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt` — render loop (the `visibleGroups.forEach` block) + new `SettingsGroupHeader` composable + imports

**Why search keeps working (cross-check):** filtering operates on the `groups` DATA (`g.items.filter { it.matches(q) }`) before any render/expand decision — collapse state never feeds the filter, so a collapsed group's items still match (E7 requirement satisfied structurally). During a search, matching groups are force-shown regardless of their collapse state; clearing the query restores each group's own remembered state. `SettingsSearchTest` (E6) keeps guarding the filter itself.

- [ ] **Step 6.1: Add imports**

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
```

- [ ] **Step 6.2: Add the group header composable** (below `SettingsSectionHeader`)

```kotlin
/**
 * vc32 (E7): clickable expand/collapse group header. The chevron hides
 * while searching because search force-shows matches regardless of the
 * remembered collapse state.
 */
@Composable
private fun SettingsGroupHeader(
    title: String,
    expanded: Boolean,
    searching: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !searching) { onToggle() }
            .padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TealAccent,
            modifier = Modifier.weight(1f)
        )
        if (!searching) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = TealAccent
            )
        }
    }
}
```

- [ ] **Step 6.3: Replace the render loop**

Current loop (post-vc31):

```kotlin
            visibleGroups.forEach { (group, items) ->
                SettingsSectionHeader(group.title)
                items.forEachIndexed { idx, item ->
                    item.content()
                    if (idx < items.lastIndex) SettingsDivider()
                }
                SettingsDivider()
            }
```

becomes:

```kotlin
            visibleGroups.forEach { (group, items) ->
                // Keyed on the group title so collapse states survive the
                // search filter removing/reinserting groups (positional
                // rememberSaveable would mix states up). Default collapsed —
                // the 8 headers form a compact index; ASSUMPTION flagged for
                // [VISUAL] sign-off in Task 12.
                var expanded by rememberSaveable(key = "grp_${group.title}") {
                    mutableStateOf(false)
                }
                val searching = q.isNotEmpty()
                SettingsGroupHeader(
                    title = group.title,
                    expanded = expanded,
                    searching = searching,
                    onToggle = { expanded = !expanded }
                )
                AnimatedVisibility(
                    visible = expanded || searching,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        items.forEachIndexed { idx, item ->
                            item.content()
                            if (idx < items.lastIndex) SettingsDivider()
                        }
                    }
                }
                SettingsDivider()
            }
```

`SettingsSectionHeader` stays (still used by the subtitles item's inner headings).

- [ ] **Step 6.4: Build + unit tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest -q`
Expected: green; `SettingsSearchTest` 6/6 still PASS (filter untouched — that is the search-sees-collapsed proof at the data layer).

- [ ] **Step 6.5: Predicates**

```bash
grep -c "SettingsGroupHeader(" app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt   # ≥2 (def + call)
grep -c "expanded || searching" app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt  # 1
grep -c "rememberSaveable(key = \"grp_" app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt # 1
```

- [ ] **Step 6.6: Commit + ledger** — `git commit -m "feat(settings): vc32 expandable groups; search force-shows matches across collapsed groups (E7)"`
`[VISUAL]` Task 12: collapsed-by-default acceptable? search "equalizer" reveals Audio across collapsed groups; clearing restores.

---

### Task 7: Hue content → 4 expandable sub-sections (E7 idea 2)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt` — `HueSection` composable (definition ~line 1660+) + new `ExpandableSubsection` helper

Sub-section mapping (4 — within the approved 3-5), derived from `HueSection`'s existing parameters:

| # | Title | Contains (existing controls, moved verbatim) | Default |
|---|-------|----------------------------------------------|---------|
| 1 | Bridge connection | discover, manual IP, pair, unpair, pair status | expanded iff `appKey.isBlank()` (unpaired users must see pairing) |
| 2 | Rooms & zones | area list/refresh, select, disconnect | expanded |
| 3 | Power & scenes | all on / all off, scene presets | expanded |
| 4 | Audio-reactive tuning | reactive intensity, sync offset, spread bands, drive dimmable, dimmable lag offset | collapsed |

- [ ] **Step 7.1: Add the helper** (below `SettingsGroupHeader`)

```kotlin
/** vc32 (E7 idea 2): collapsible sub-section inside a settings item. */
@Composable
private fun ExpandableSubsection(
    title: String,
    stateKey: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(key = "sub_$stateKey") {
        mutableStateOf(initiallyExpanded)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column { content() }
    }
}
```

- [ ] **Step 7.2: Read `HueSection`'s body and mark the four block boundaries**

Run: `grep -n "fun HueSection" app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt` then read the full body. Identify the contiguous regions rendering: (a) discover/pair/unpair controls, (b) area refresh/list/clear, (c) all-on/all-off + scenes, (d) intensity/sync/spread/dimmable/lag sliders+toggles. The body's controls move VERBATIM — wrapping only (same A2-style no-drop rule as the vc31 catalog, E6).

- [ ] **Step 7.3: Wrap each region**

```kotlin
    ExpandableSubsection(
        title = "Bridge connection",
        stateKey = "hue_bridge",
        initiallyExpanded = appKey.isBlank()
    ) {
        // existing discover/pair/unpair block, verbatim
    }
    ExpandableSubsection("Rooms & zones", "hue_rooms", initiallyExpanded = true) {
        // existing area block, verbatim
    }
    ExpandableSubsection("Power & scenes", "hue_scenes", initiallyExpanded = true) {
        // existing on/off + scenes block, verbatim
    }
    ExpandableSubsection("Audio-reactive tuning", "hue_tuning", initiallyExpanded = false) {
        // existing intensity/sync/spread/dimmable/lag block, verbatim
    }
```

Anything in the body that is a section-wide status line (e.g. pair status text) stays ABOVE sub-section 1, unwrapped.

- [ ] **Step 7.4: No-drop inventory (A2-style)**

Before: `grep -cE "SliderRow|SettingsToggleItem|Button|IconButton|FilterChip|AssistChip|OutlinedTextField" <HueSection body range>` — record N.
After wrapping: same grep — must equal N. Build: `./gradlew :app:compileDebugKotlin -q` green.

- [ ] **Step 7.5: Predicate**

`grep -c "ExpandableSubsection(" app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt` → **5** (1 def + 4 calls).

- [ ] **Step 7.6: Commit + ledger** — `git commit -m "feat(hue): vc32 four expandable sub-sections — daily controls up front, tuning tucked away (E7-2)"`
`[VISUAL]` Task 12: unpaired shows Bridge connection open; tuning collapsed.

---

### Task 8: GATE A — full verification + deploy

- [ ] **Step 8.1:** `./gradlew :app:assembleDebug :app:testDebugUnitTest -q` → both green (includes `SettingsSearchTest` 6/6 + `SpotifyBannerGraceTest` 3/3).
- [ ] **Step 8.2:** Re-run EVERY predicate from Tasks 1-7 in one script; emit a pass/fail table. Any FAIL → stop, fix, re-run (no partial reporting).
- [ ] **Step 8.3:** `git push origin main`; check `dumpsys media_session` shows no PLAYING; `adb install -r app/build/outputs/apk/debug/app-debug.apk` → `Success`.
- [ ] **Step 8.4:** Update `TASKS.md` rows T232/T233/T237 + checklist doc with the gate table.

---

### Task 9: On-device evidence round (gates Tasks 10-11) — needs user

User script (Diagnostic logging is already ON):
1. Last Played → tap a **Drive** audiobook resume; wait for playback; note feel.
2. Kill app; relaunch (cold-start restore path).
3. Last Played → tap a **local** item.
4. Last Played → tap the **Spotify** recent; watch for the banner.
5. A few back presses: from Player after a drill-in (expect: returns to list), from Player tab root (expect: app backgrounds).
6. Hue: pair/connect → Disconnect → re-pick a room/zone → observe lights (T230 replication).

- [ ] **Step 9.1:** `bash tools/deeplog/pull_logs.sh` + adb-pull `diag/log-current.txt` into `deeplogs/`.
- [ ] **Step 9.2:** `python tools/deeplog/parse_logs.py deeplogs/<session> --summary` then `--category` slices; grep DiagLog for `RESUME`/`PERF`/`chapterParse`/`Spotify.startPlaybackPolling`/`bannerClear`/`collector fire`/`DISCONNECT`. NEVER read raw files into context.
- [ ] **Step 9.3:** Produce a phase table for each resume permutation (local/Drive/Spotify): tap→parse→setMediaItems→playing, in ms. Update findings doc + `TASKS.md` T229/T230 with evidence.

---

### Task 10: CONDITIONAL — chapter cache (ONLY if Task 9 convicts the parse)

**Gate:** implement ONLY if Task 9's PERF table shows `chapterParse`/`findChplBox` ≥ 50% of the tap→playing gap on Drive or local. Otherwise record DECLINED(evidence) in `TASKS.md` and skip — that is a gate outcome, not a deferral.

**Files:**
- Create: `app/src/main/java/com/powermediaplayer/util/ChapterCache.kt`
- Modify: the three `extractChaptersAsBundle` call sites (Step 3.1 list)
- Create: `app/src/test/java/com/powermediaplayer/util/ChapterCacheTest.kt`

- [ ] **Step 10.1: Failing test**

```kotlin
package com.powermediaplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterCacheTest {
    @Test
    fun missThenHit() {
        val cache = ChapterCache(maxEntries = 4)
        assertNull(cache.get("uri1", validityToken = "57:1000"))
        cache.put("uri1", "57:1000", android.os.Bundle())
        assertEquals(true, cache.get("uri1", "57:1000") != null)
    }
    @Test
    fun staleTokenMisses() {
        val cache = ChapterCache(maxEntries = 4)
        cache.put("uri1", "57:1000", android.os.Bundle())
        assertNull(cache.get("uri1", "58:2000")) // size/mtime changed → re-parse
    }
}
```

(Bundle in a JVM test requires Robolectric — already a test dep; annotate the class with `@RunWith(org.robolectric.RobolectricTestRunner::class)`.)

- [ ] **Step 10.2: Run → FAIL (ChapterCache unresolved).**

- [ ] **Step 10.3: Implement**

```kotlin
package com.powermediaplayer.util

import android.os.Bundle

/**
 * vc32 (Task-9 evidence): LRU cache of parsed chapter bundles keyed by
 * mediaUri + a validity token (file size + mtime, or "?" when unknown).
 * The M4B box-walk over Drive content cost the bulk of the 93 s resume
 * (E2/E10) — a same-session re-resume should never re-walk.
 * In-memory only: process death re-parses once, which Task 9 timed as
 * acceptable for first-play; persistence is NOT planned (YAGNI until
 * evidence says otherwise).
 */
class ChapterCache(private val maxEntries: Int = 16) {
    private data class Entry(val token: String, val bundle: Bundle)
    private val map = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) =
            size > maxEntries
    }
    @Synchronized fun get(uri: String, validityToken: String): Bundle? =
        map[uri]?.takeIf { it.token == validityToken }?.bundle
    @Synchronized fun put(uri: String, validityToken: String, bundle: Bundle) {
        map[uri] = Entry(validityToken, bundle)
    }
    companion object { val shared = ChapterCache() }
}
```

- [ ] **Step 10.4:** At each call site: build `validityToken` from `DocumentFile`/`ContentResolver` size+lastModified when cheaply available else `"?"`; `ChapterCache.shared.get(...) ?: parse().also { ChapterCache.shared.put(...) }`. Add a `PERF chapterCache hit/miss` DiagLog line at each site.
- [ ] **Step 10.5:** Tests + build green → commit `perf(resume): vc32 chapter cache — evidence: <numbers from Task 9>` + push/install + ledger.

---

### Task 11: Hue disconnect→reconnect — evidence verdict (T230)

- [ ] **Step 11.1:** From Task 9's pull: extract every `collector fire intensity=… isStreaming=… selectedArea=…` and `DISCONNECT` line around the replication timestamps.
- [ ] **Step 11.2:** Answer with evidence: after re-pick, does the collector fire with the NEW area? is `isStreaming` stuck false? is intensity 0? Write verdict + the single convicted component into the findings doc.
- [ ] **Step 11.3:** STOP — present verdict to user; the fix becomes its own planned task (phase lock, E8 rule 6). Update `TASKS.md` T230.

---

### Task 12: [VISUAL] verification batch + final gate (closes T228/T231/T236)

- [ ] **Step 12.1:** User confirms on device (screenshots where listed): settings 8-group order + collapsed-default + expand/collapse; search "equalizer" reveals Audio from collapsed state and clearing restores; Hue sub-sections (unpaired ⇒ Bridge open); ⋮ menus open the context sheets on Library + both Last Played lists; empty-player state no longer overlaps (T236); drill-in back returns to source list; root back backgrounds app; Drive + Spotify resume banners visible; edge-to-edge: no nav-bar collision + video still full-bleed (T231).
- [ ] **Step 12.2:** Final gate: one script re-running ALL plan predicates + build + tests → single pass/fail table into the checklist doc.
- [ ] **Step 12.3:** `TASKS.md`: close T228/T231/T236-related rows with evidence; list anything FAILED as ACTIVE (never silently dropped). Push.

---

## ADDENDUM (2026-06-04 evening)

Tasks 13-20 live in the sibling file
`2026-06-04-vc32-addendum-tasks-13-20.md` — fixes for the seven device-run
defects (T241-T247) with the round-2/3 evidence index E11-E18. **Task 10
below is SUPERSEDED by addendum Task 14** (same cache, stronger design:
remote parse fully async via the existing `setLocalChapters` path).
Task 11 (Hue) unchanged — awaiting the user's Hue session.

## Self-review (run before execution)

1. **Coverage:** E1→Task 1; E3→Task 2; E4/E5→Task 3; E7-1→Tasks 4-5; E7-groups→Task 6; E7-2→Task 7; perf→Task 10 (gated); Hue regression→Task 11; visual backlog→Task 12. No approved item without a task.
2. **Placeholders:** none — every code step shows code; Tasks 10/11 are evidence-GATED, not stubbed.
3. **Consistency:** `shouldClearBannerOnNullSnap(nowMs, graceUntilMs)` matches test+impl; `ExpandableSubsection(title, stateKey, initiallyExpanded, content)` matches all 4 calls; `SettingsGroupHeader(title, expanded, searching, onToggle)` matches its call; `ChapterCache.get(uri, validityToken)` matches tests.
4. **Logic cross-checks:** (a) push-not-wipe bounded because tab taps still popUpTo-reset; (b) search-sees-collapsed holds because filtering precedes rendering and never reads `expanded`; (c) banner `finally` placement guarantees no stranded banner on parse failure; (d) grace window cannot stick the banner forever — non-null snap and stopPolling both zero it, and 45 s expiry caps the worst case.
