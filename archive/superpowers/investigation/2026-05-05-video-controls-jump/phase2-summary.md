# Phase 2 Summary — primary probe, run 1

**File under test:** `20260323_130942.mp4` (4K, 168 s, 2.82 GiB)
**Recording:** `phase1-step03-primary-backward-run1.mp4` (45 s, 17 MiB)
**Capture wall-clock window:** approximately 22:55:42 → 22:56:27 (recording start +45 s)
**HEAD at run:** `cbf1628` (pre-instrumentation main; commit `96cffd7` had landed but the on-device APK is the build that was installed at `30751b0` with the artworkBytes flow fix; per CHANGELOG this is acceptable since none of the post-`30751b0` changes touch the player video path).

## What the user did during the recording

Per the gesture log derivable from `PMP_DIAG`:
- 22:55:42 — file opens, playback steady.
- 22:55:44.967 — manual scrub forward to 20.7 s (slider drag forward).
- 22:55:45.770 — manual scrub forward to 45.7 s.
- 22:55:47.283 — tapped Skip-Forward 10s button → target 56.085 s.
- 22:55:47.843 — tapped Skip-Forward 15s button → target 71.085 s.
- 22:55:48.677 — tapped Skip-Back 10s button → target 61.085 s.
- 22:55:49.385 — tapped Skip-Back 10s button → target 51.085 s.
- 22:55:50.374 — manual scrub backward, slider release at 36.296 s.
- 22:55:51.216 — manual scrub backward, release at 22.744 s.
- 22:55:52.922 — manual scrub backward, release at 17.400 s.

This run exercises BOTH directions (forward and backward) of BOTH input modes (skip-button and slider scrub) on the same recording. That's better than the plan's strict "Script A only" because it lets us compare fwd vs bwd in a single capture window with everything else held constant.

## Direction-asymmetry observation (PMP_DIAG, single-substrate)

Filtered to `seekTo target` / `SkipBtn click` / `debounced seekTo` / `loadingChanged`:

```
22:55:47.283  SkipBtn click fwd=true sec=10
22:55:47.463  debounced seekTo target=56085ms          [no loadingChanged]
22:55:47.843  SkipBtn click fwd=true sec=15
22:55:48.023  debounced seekTo target=71085ms          [no loadingChanged]

22:55:48.677  SkipBtn click fwd=false sec=10
22:55:48.857  debounced seekTo target=61085ms
22:55:48.895  evt loadingChanged=true                   *** 38 ms after seekTo
22:55:48.957  evt loadingChanged=false                  *** 62 ms duration

22:55:49.385  SkipBtn click fwd=false sec=10
22:55:49.566  debounced seekTo target=51085ms
22:55:49.614  evt loadingChanged=true                   *** 48 ms after seekTo
22:55:49.685  evt loadingChanged=false                  *** 71 ms duration

22:55:50.374  seekTo target=36296ms      (bwd scrub)
22:55:50.405  evt loadingChanged=true                   *** 31 ms
22:55:50.477  evt loadingChanged=false                  *** 72 ms duration

22:55:51.216  seekTo target=22744ms      (bwd scrub)
22:55:51.250  evt loadingChanged=true                   *** 34 ms
22:55:51.327  evt loadingChanged=false                  *** 77 ms duration

22:55:52.922  seekTo target=17400ms      (bwd scrub)
22:55:52.953  evt loadingChanged=true                   *** 31 ms
22:55:53.034  evt loadingChanged=false                  *** 81 ms duration
```

**Pattern:** every backward operation (skip-button OR slider scrub) is followed within ~30-50 ms by `loadingChanged=true`, then `loadingChanged=false` ~60-80 ms later. **Forward** operations produce no `loadingChanged` event at all.

`loadingChanged` corresponds to ExoPlayer's `Player.Listener.onIsLoadingChanged`, which is mapped through `PlaybackConnection` into `PlayerState.isLoading` and from there into `PlayerUiState.isLoading`. In `PlayerScreen.kt` `OverlayContent`, an `if (uiState.isLoading)` branch conditionally inserts a `Spacer(8.dp)` + `CircularProgressIndicator(24.dp)` block at the bottom of the controls Column. The Column is bottom-anchored (`verticalArrangement = Arrangement.Bottom`) with a `Spacer(weight(1f))` at top. Adding/removing the 32 dp loading block at the bottom would push every other transport-control row up by 32 dp during the loading state and back down on completion — visible as a "group jump" of the entire transport-controls cluster.

**This is single-substrate evidence (PMP_DIAG only).** The plan's two-substrate corroboration rule (Phase 4.5) requires a second independent substrate before promotion to the Phase 5 shortlist.

## Aggregate gfxinfo (whole recording window)

```
Total frames rendered: 912
Janky frames: 123 (13.49%)
Number Missed Vsync: 45
Number High input latency: 248       *** unusually high
Number Slow UI thread: 103
Number Slow issue draw commands: 10
Number Frame deadline missed: 123
50th percentile: 5 ms
90th percentile: 13 ms
95th percentile: 20 ms
99th percentile: 46 ms
```

The aggregate confirms that 123 janky frames occurred during the recording, consistent with the loadingChanged-driven recompose hypothesis above, but **does not by itself prove direction asymmetry** because gfxinfo's per-frame `framestats` buffer holds only the most recent ~120 frames and those are post-gesture idle (parsed: 120 frames, 0 deadlines missed in the buffer). The gesture-window frames have rolled out.

## Outstanding evidence gaps

1. **Per-direction framestats.** Need targeted captures: dump framestats *immediately* after a backward action and again after a forward action so the buffer holds the corresponding frames. Compare missed-deadline rates.
2. **SurfaceFlinger latency.** First attempt with layer name `com.powermediaplayer/com.powermediaplayer.MainActivity#3069` produced 20 bytes (empty). The layer name on this device's One UI build differs; need a second probe with the correct layer.
3. **Compose recompose evidence.** The PMP_DIAG `loadingChanged` event proves `isLoading` flipped; it does not prove `OverlayContent` recomposed *because* of that flip. Phase 5 instrumentation needed to log composition entry / exit on `OverlayContent` and verify the recompose corresponds to the loadingChanged event.

## Provisional Phase 4 verdicts (not yet locked)

| # | Substrate | Verdict | Note |
|---|---|---|---|
| C7 | BUFFERING/READY cycle on backward seek triggers `isLoading` flip | **PASS (single substrate)** | Direct evidence in `phase2-step04-pmp-diag-primary-backward-run1-utf8.txt` lines for events at 48.677, 49.385, 50.374, 51.216, 52.922. |
| C25 | Bottom-anchored Arrangement + sibling growth | **PASS (architectural)** | Code at `PlayerScreen.kt:404-411` confirms `verticalArrangement = Arrangement.Bottom` + `Spacer(weight(1f))` + `if (uiState.isLoading) { Spacer(8.dp) + CircularProgressIndicator(24.dp) }` at lines 545-548. The ~32 dp insert/remove at the bottom is the mechanism by which an `isLoading` flip would cause a "group jump" in the controls cluster. **Note:** this is structural / code-derived corroboration, not Phase-2-runtime — useful but does not satisfy the two-substrate rule on its own. |
| All others (C1-C6, C8-C24, C26) | INCONCLUSIVE | No Phase-2 evidence one way or the other yet. |

The pair (C7 + C25) jointly explain the symptom: the existence of a backward-only loading event (C7) plus the layout structure that converts an isLoading flip into a 32 dp group shift (C25). However promoting this to the Phase 5 shortlist requires the second runtime substrate (gfxinfo per-direction or a recompose-instrumented build).

## Next runs

1. **Per-direction framestats run.** Execute Script C (skip-back-30 then skip-forward-30 on primary probe), dumping framestats immediately after each. Two artefacts: `phase2-step01b-gfxinfo-primary-skipfwd-run.txt` and `phase2-step01b-gfxinfo-primary-skipbwd-run.txt`. Compare frame-deadline-missed counts in the corresponding ~30-frame windows.
2. **Repeat asymmetry capture on secondary probe and control probe.** Confirms the asymmetry is content-dependent or content-independent (Phase 4.3 file-class check).
3. **Phase 5 instrumentation** — gated. Only after two substrates corroborate.
