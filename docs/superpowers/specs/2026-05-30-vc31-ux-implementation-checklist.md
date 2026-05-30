# vc31 UX implementation — checklist + verification gate

Source of truth for the "proceed with everything" batch. Each line has a
machine-checkable predicate. Status set ONLY by the gate (build + grep), never
self-attested. `[VISUAL]` rows need on-device screenshot (deferred — phone last).

## DONE — gate PASS (build: assembleDebug SUCCESSFUL)

| # | Item | Predicate | Gate |
|---|------|-----------|------|
| 210 | PlayerScreen renders `isLoading` spinner | `grep` AnimatedVisibility gated on `uiState.isLoading && !cloudFetchInProgress` | PASS |
| 211 | Last-Played resume in-flight debounce | `grep` "tap IGNORED — resume already in flight" guard in `playLocalAt` | PASS |
| 212 | DisabledGrey clears 3:1 | `grep` `Color(0xFF6E6E6E)` (was 0xFF555555 = 2.82:1) | PASS |
| 213 | Sub-48dp touch targets enlarged | `grep` 0 remaining `size(40/18.dp)` in PlayerScreen + 0 `height(44.dp)` in SecondaryControls | PASS |
| 214 | 'Clear all' recents behind confirm | `grep` `showClearAllConfirm = true` + AlertDialog | PASS |
| 216a | Edge-to-edge inset padding | `grep` `systemBarsPadding()` wrapping AppNavigation in MainActivity | PASS `[VISUAL]` on-device check video still fills |
| E1 | MediaOverridesPopup filled Button → TextButton | `grep` 0 bare `Button(onClick=onDismiss)` | PASS |
| E2 | Settings bottom spacer 80→96dp | `grep` `height(96.dp)` | PASS |
| E3 | Dialog Cancel buttons standardised grey | `grep` `Text("Cancel", color = TextSecondary)` in Settings + Library | PASS |
| E4 | Subtitle-delay range hint | `grep` "Typical nudge is ±100–500 ms" | PASS |

## NOT DONE — remaining (honestly tracked, not skipped)

| # | Item | Why remaining | Effort |
|---|------|---------------|--------|
| 217 | **Settings reorg into 8-group catalog** (PRIMARY ASK) | 2000-line data-driven refactor; needs careful per-control inventory (predicate A2) + `[VISUAL]` order check | L |
| 218 | **Settings search field** (PRIMARY ASK) | Depends on 217's catalog model for item-level live filter + synonyms | M |
| 215 | Waiting-in-player + empty-player state | Pre-load most-recent into paused player incl. cloud; new VM startup path | L |
| 216b | LocalPmpScale consumed (font-scale touch targets) | Systemic — touch every fixed-size composable (InfoIcon, sliders, dropdown) | M |
| 216c | CloudBrowser contentDescriptions (4-5 state icons) | Small but unstarted | S |
| 219b | Dialog title typography → titleLarge (EditTags, Equalizer) | Small but unstarted | S |
| 219c | Swipe/inline delete undo snackbar | Needs VM re-insert path for undo | M |
| — | Long-press discoverability (three-dot per-row menu) | New affordance; design decision | M |
| — | Hue settings progressive disclosure | Collapsible Quick/Advanced/Tuning subsections | M |

## Notes

- All DONE items are local commits only (NOT pushed; phone/testing deferred to last
  per user instruction).
- The two PRIMARY asks (217/218 settings reorg + search) are the largest remaining
  pieces. They are designed + approved (see the sibling design spec) but not yet
  implemented — they need the catalog refactor + the `[VISUAL]` predicates (A4/B5)
  verified on-device, which the user deferred to last.
