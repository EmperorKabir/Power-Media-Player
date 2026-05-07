# Phase 3 — Library improvements — COMPLETE

**Completed:** 2026-05-07.
**Tasks:** 8 of 8. All committed + pushed.

## Tasks recap

| # | Subject | Outcome |
|---|---|---|
| 3.1 | refreshIfStale on Library tab open | LaunchedEffect calls refreshIfStale when stale (>30 s). Zero battery vs continuous watcher. |
| 3.2 | TrackContextSheet component | Reusable ModalBottomSheet with 9 menu items; null callbacks hide rows. |
| 3.3 | Library long-press wiring + Hide | Library row long-press → context sheet (Favourite / Unfavourite / Hide / Share). HIDDEN_URIS DataStore set; recomputeDisplayed filters out hidden. |
| 3.4 | LastPlayedScreen long-press | Recents long-press → Pin / Share / Delete. Pinned long-press → Unpin / Share. |
| 3.5 | CloudBrowserScreen long-press | Drive/Spotify track long-press → Favourite / Share. Folders skip menu. |
| 3.6 | HiddenFilesSheet + Settings entry | New HiddenFilesSheet bottom sheet. Settings → Deep Scan section gains "Hidden files (N)" row. |
| 3.7 | Multi-select mode in Library | 3-dot menu → Select multiple. Selection bar with Favourite-all + Hide-all + Cancel. Selection-tinted rows + checkboxes. |
| 3.8 | Phase 3 final regression sweep | 200 monkey events both devices, 0 FATAL. |

## Commits

```
38ac2d3 feat(library): multi-select mode (§C26)
6cfeb48 feat(library): Hidden files Settings sub-sheet
aaa85f6 feat(long-press): wire context menu into Last Played + Cloud screens
33deaf9 feat(library): long-press menu + hidden-files filter
d6c137f feat(track-context): TrackContextSheet
bff2118 feat(library): refresh-if-stale on tab open
```

## Regression results

- Build: clean.
- Z Fold 6: monkey 200 events seed=4, 0 FATAL.
- Emulator: monkey 200 events seed=4, 0 FATAL.
- Cold-start branch from Phase 1 still fires.

## Deferred from Phase 3

- Bulk Delete (needs ContentResolver delete; risky).
- Bulk Add-to-queue (no queue manager yet).
- Override speed / audio / video items in long-press menu (Phase 5 work).
- Edit tags item (Phase 5+).
- Audio-mode controls auto-hide (deferred from Phase 2; needs new state machinery).

## Next phase

**Phase 4 — TRUE 2-PLAYER CROSSFADE** (HIGH risk).

- 9 sub-toggles in DataStore + Settings + Player popup.
- Second ExoPlayer instance for genuine overlap.
- 4 fade curves (Equal-power default).
- Per-source / per-media-type compatibility matrix with grey-outs.
- Auto-revert on incompatible source.
- Mid-crossfade interaction handling (pause/scrub/next/prev).
- Audio-effect chain hot-swap with 50 ms cubic smoothing.
- Metadata handoff at crossfade START.

Pre-flight: Context7 query for Media3 dual-instance ExoPlayer + AudioAttributes.
