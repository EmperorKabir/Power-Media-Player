# vc31 UX implementation — checklist + verification gate

Source of truth for the "proceed with everything (no phone yet)" batch. Each line
has a machine-checkable predicate. Status is set ONLY by the gate (build + grep +
unit test), never self-attested. `[VISUAL]` rows need on-device screenshot
(deferred — phone last, per user).

## DONE — gate PASS

Build: `assembleDebug` + `compileDebugKotlin` green. Unit tests: `SettingsSearchTest`
6/6 + full `testDebugUnitTest` green.

| # | Item | Predicate | Gate |
|---|------|-----------|------|
| 210 | PlayerScreen renders `isLoading` spinner | AnimatedVisibility gated on `isLoading && !cloudFetchInProgress` | PASS |
| 211 | Last-Played resume in-flight debounce | "tap IGNORED — resume already in flight" guard | PASS |
| 212 | DisabledGrey clears 3:1 | `Color(0xFF6E6E6E)` (was 2.82:1) | PASS |
| 213 | Sub-48dp touch targets enlarged | 0 remaining `size(40/18.dp)` / `height(44.dp)` | PASS |
| 214 | 'Clear all' recents behind confirm | `showClearAllConfirm` + AlertDialog | PASS |
| 215 | Empty-player guidance + waiting-in-player | `hasMedia` state + empty-state w/ Open-Library button; cold-start restore already present (PlayerViewModel ~755-798) | PASS · `[VISUAL]` empty-state + restore-on-launch |
| 216a | Edge-to-edge inset padding | `systemBarsPadding()` in MainActivity | PASS · `[VISUAL]` video full-bleed |
| 216b | Font-scale honoured | Theme overrides `LocalDensity.fontScale` (line 84) → all sp text scales; touch targets via M3 48dp min | PASS (verified; audit premise refuted) |
| 216c | CloudBrowser action-icon descriptions | every actionable IconButton already has contentDescription; null ones are decorative | PASS (verified) |
| 217 | Settings 8-group catalog | group titles == user order 1·3·4·5·2·8·7·6; `SettingsItem`==20==`SETTINGS_ITEM_IDS` | PASS · `[VISUAL]` A4 scroll order |
| 218 | Settings live search + synonyms | `settingsItemMatches` + no-match message; `SettingsSearchTest` B1-B4 | PASS · `[VISUAL]` B5 keyboard live-feel |
| 219b | Uniform dialog titleLarge | all 10 AlertDialog titles carry `titleLarge` | PASS |
| 219c | Recents swipe-delete Undo | snackbar Undo → `restoreRow` (row + bookmarks, ids preserved) | PASS · `[VISUAL]` swipe→undo restores |
| E1-E4 | Overrides TextButton · spacer 96dp · Cancel grey · subtitle hint | grep | PASS |

## NOT DONE — optional audit backlog (NOT in default scope)

Per design spec Part C: the audit is "a prioritised backlog, not auto-scoped work —
the user picks what to action." These two are visual-heavy (need on-device sign-off)
and were never part of the requested #215-219 / reorg+search scope:

| Item | Why held | Effort |
|------|----------|--------|
| Long-press discoverability (visible ⋮ per row) | New visible affordance across Library/LastPlayed rows; design choice + needs visual verification. Long-press context sheet already exists; this is discoverability polish. | M |
| Hue progressive disclosure (collapsible sub-sections) | Hue is now its own searchable "Lighting" group (217/218), which already mitigates overload. Further collapsing needs HueSection refactor + visual check. | M |

→ Awaiting user election before actioning.

## Phone-dependent (deferred to last, per user)

- All `[VISUAL]` predicates above (screenshots).
- Resume-hang on-device diagnosis: `DiagLog.dec branch=cold-start` evidence to see
  which guard skips the friend's restore; resume timing via the M4bChapterParser +
  LastPlayedViewModel instrumentation + DeepLogger.
- Hue disconnect→reconnect regression: PlaybackService collector snapshot +
  DISCONNECT log evidence.

## Notes

- Everything above is committed LOCALLY only — NOT pushed; phone install + testing
  held to last per user instruction.
