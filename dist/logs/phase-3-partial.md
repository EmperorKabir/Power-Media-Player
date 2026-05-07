# Phase 3 — Library improvements — PARTIAL

**Updated:** 2026-05-07.
**Tasks completed:** 3 of ~14 planned.
**Status:** functional library long-press menu shipped on Library tab; Last Played + Cloud wirings + multi-select + hidden-files Settings sub-screen pending.

## Tasks recap

| # | Subject | Outcome |
|---|---|---|
| 3.1 | refreshIfStale on Library tab open | `LaunchedEffect(Unit)` calls `viewModel.refreshIfStale()` if last refresh was >30 s ago. Zero battery vs continuous watcher. |
| 3.2 | TrackContextSheet component | Reusable `ModalBottomSheet` with up to 9 menu items. Null callbacks hide the corresponding row. Used from any list screen. |
| 3.3 | Library long-press wiring + hide functionality | Long-press a Library row → TrackContextSheet with Favourite / Unfavourite / Hide / Share. Hide writes URI to `DataStore.HIDDEN_URIS`; `recomputeDisplayed` filters them out. Survives reinstall via Auto Backup. |

## Commits

```
33deaf9 feat(library): long-press menu (Favourite / Hide / Share) + hidden-files filter
d6c137f feat(track-context): TrackContextSheet bottom-sheet menu component
bff2118 feat(library): refresh-if-stale on tab open (§C16)
```

## Regression results

- Build: clean.
- Z Fold 6: monkey 150 events seed=3, 0 FATAL.
- Emulator (Pixel_6_API_34): monkey 150 events seed=3, 0 FATAL.

## What's NOT shipped yet (deferred to subsequent work)

- **Long-press menu wiring** for Last Played + Cloud screens. Pattern is identical to Library — copy + adapt callbacks per source.
- **Multi-select** (§C26). UI stubbed in TrackContextSheet but no SelectionTopAppBar / multi-select mode yet.
- **Hidden files Settings sub-screen** (§C27). User can hide via long-press today, but the only way to unhide is via shell `adb shell pm clear com.powermediaplayer` (DataStore wipe) or a future Settings UI.
- **Override speed / audio / video** items in TrackContextSheet — Phase 5 work.
- **Edit tags / Add to queue next / Delete** items — gated behind future features.
- **Audio-mode controls auto-hide** (§D2 audio-folded / audio-unfolded) — needs new state machinery.

## Next implementation block

When resuming, recommended order:

1. Phase 3 finish: wire long-press menu into LastPlayedScreen + CloudBrowserScreen using the same pattern as Library.
2. Phase 3 finish: build SelectionTopAppBar + multi-select mode for Library.
3. Phase 3 finish: HiddenFilesScreen as a Settings sub-page.
4. Phase 4 start: TRUE 2-PLAYER CROSSFADE — high-risk, requires Context7 query for Media3 dual-instance audio focus + AudioAttributes shared.
