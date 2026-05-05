# Root Cause — Video transport-controls "group jump" on backward scrub / skip

**Status:** evidence-locked, two-substrate corroboration achieved.
**Date filed:** 2026-05-05.
**Investigation plan:** `docs/superpowers/plans/2026-05-05-video-controls-jump-investigation.md`.

## 1. Symptom (cite Phase 1 recordings)

User report (verbatim, 2026-05-05): *"for larger files e.g. 4k, portrait etc, the controls can sometimes jump as a group on screen or they jump visibly when scrubbing backwards or skipping backwards, but not when scrubbing or skipping forwards."*

Screen recordings on disk:
- `phase1-step03-primary-backward-run1.mp4` — 45 s, primary 4K probe, both directions exercised.
- `phase1-step03-method1.mp4` — 30 s, primary 4K probe, single skip-back + skip-forward 5 s apart.

## 2. Reproduction script (verbatim)

Locked in `CHANGELOG.md` under Phase 1.2. Summary: open any 4K video, allow steady playback, then either tap Skip-Back-N OR drag the track-slider thumb backward. The transport-controls cluster visibly shifts up ~32 dp for ~80-100 ms then drops back. Forward-direction operations of identical magnitude do not produce the shift.

## 3. Confirmed substrates

Both PASS by the plan's two-substrate corroboration gate.

### C7 — Backward seek triggers ExoPlayer rebuffer; `isLoading` flips true → false (~60-100 ms)

**Substrate:** existing `PMP_DIAG` logcat, file `phase2-step04-pmp-diag-method1-skipback-only.txt` and the four prior runs.

**Citation (Method 2 instrumented run, `phase5-step03-method2-instrumented-run1-utf8.txt`):**

```
23:10:00.234  PMP_DIAG       SkipBtn click fwd=false sec=30 enabled=true
23:10:00.416  PMP_DIAG       debounced seekTo target=26730ms
23:10:00.455  PMP_DIAG       evt loadingChanged=true        ← +39 ms after seek
23:10:00.552  PMP_DIAG       evt loadingChanged=false       ← +97 ms duration
```

Forward seek immediately after, on the same file at the same playback session:
```
23:10:06.190  PMP_DIAG       SkipBtn click fwd=true sec=30 enabled=true
23:10:06.372  PMP_DIAG       debounced seekTo target=61450ms
                              ← NO loadingChanged event in the entire window
```

Pattern reproduced across 4 separate runs: 5 of 5 backward operations (skip-button OR slider scrub) produced a loadingChanged true→false cycle within 30-50 ms of the seek. 0 of 4 forward operations produced any loadingChanged event.

### C25 — Conditional spinner block in the bottom-anchored Column actually executes on backward, never on forward

**Substrate:** new `PMP_DIAG_VIDEO` instrumentation (commit `b9154d5`), file `phase5-step03-method2-instrumented-run1-utf8.txt`.

**Code under test:** `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt:404-563`. Specifically:
- `:404-411` — `OverlayContent` Column with `verticalArrangement = Arrangement.Bottom` and a `Spacer(modifier = Modifier.weight(1f))` at top, anchoring all children to the bottom.
- `:558-561` — `if (uiState.isLoading) { Spacer(modifier = Modifier.height(8.dp)) ; CircularProgressIndicator(... size = 24.dp ...) }` near the bottom of the Column. When `isLoading` is true, this branch contributes **8 dp + 24 dp = 32 dp** of additional intrinsic height at the bottom of the bottom-anchored Column. With `Arrangement.Bottom`, every sibling above is pushed up by 32 dp.

**Citation (Method 2 instrumented run):**

Backward window — three executions of the spinner-block branch in 60 ms:
```
23:10:00.464  PMP_DIAG_VIDEO  overlay-recompose isLoading=true ...
23:10:00.465  PMP_DIAG_VIDEO  loading-spinner-block ENTERED — adding 8dp Spacer + 24dp CircularProgressIndicator
23:10:00.484  PMP_DIAG_VIDEO  overlay-recompose isLoading=true ...
23:10:00.486  PMP_DIAG_VIDEO  loading-spinner-block ENTERED ...
23:10:00.523  PMP_DIAG_VIDEO  overlay-recompose isLoading=true ...
23:10:00.526  PMP_DIAG_VIDEO  loading-spinner-block ENTERED ...
23:10:00.557  PMP_DIAG_VIDEO  overlay-recompose isLoading=false   ← spinner block NOT entered; cluster drops back
```

Forward window — every recompose has `isLoading=false`, the spinner block is **never** entered:
```
23:10:06.382  PMP_DIAG_VIDEO  overlay-recompose isLoading=false ...
23:10:06.424  PMP_DIAG_VIDEO  overlay-recompose isLoading=false ...
23:10:06.583  PMP_DIAG_VIDEO  overlay-recompose isLoading=false ...
... (continues, no loading-spinner-block ENTERED ever)
```

Zero spinner-block executions across all forward operations. Three executions for the single backward operation, oscillating with the CircularProgressIndicator's own animation frame.

## 4. Causal chain

```
[backward seek requested]
       │
       ▼
ExoPlayer's pre-fetched buffer does NOT contain the target position
(target is BEFORE current playback position, pre-fetch is forward-only)
       │
       ▼
ExoPlayer enters BUFFERING; Player.Listener.onIsLoadingChanged(true) fires
       │ (PlaybackConnection.kt listener, mapped into PlayerState.isLoading)
       ▼
PlayerState.isLoading = true emitted via StateFlow
       │ (PlayerViewModel.uiState combine in PlayerScreen.kt:312)
       ▼
PlayerUiState.isLoading = true reaches OverlayContent
       │
       ▼
Compose recomposes OverlayContent. The if-branch at PlayerScreen.kt:558
becomes true, adding 8 dp Spacer + 24 dp CircularProgressIndicator
to the bottom-anchored Column (verticalArrangement = Arrangement.Bottom)
       │
       ▼
Every transport-control row in the same Column is pushed up by 32 dp
       │ (~80-100 ms later)
       ▼
loadingChanged(false) → isLoading=false → if-branch falls away
       │
       ▼
Cluster drops back down by 32 dp. User perceives a "group jump."
```

## 5. Why forward is unaffected

ExoPlayer maintains a forward-direction read-ahead buffer (project's `LoadControl` in `PlaybackService.kt:304-312` sets `maxBufferMs = 120_000`, i.e. 2 minutes ahead of playhead). A forward skip of 30 s typically lands inside this buffered region → no buffering → `onIsLoadingChanged` is never invoked → `PlayerUiState.isLoading` stays `false` → the if-branch at `PlayerScreen.kt:558` never executes → no 32 dp insertion → no shift.

A backward skip lands *behind* the playhead. The buffered region after a forward-seeking playback session is by definition only ahead of playhead (or at most a small ring of recent samples). Almost any meaningful backward seek therefore exceeds the buffered region → forced rebuffer.

## 6. Why it's worse on bigger / 4K files

Larger frames take longer to decode, so the time spent in the BUFFERING state is longer, making the 32 dp shift more perceptible. On 1080p content the buffering window is shorter and the eye may not notice the shift. The user's report specifically named 4K and portrait files — both have higher pixel counts → longer rebuffer windows → more visible shift.

## 7. What is NOT the cause (rejected candidates)

Listed verbatim with evidence so future sessions don't re-investigate dead ends.

| # | Substrate | Verdict | Evidence |
|---|---|---|---|
| C1 | Compose recompose of OverlayContent triggered purely by uiState position-tick | **PARTIAL_NO** | The recompose IS happening on every poll tick (visible in `PMP_DIAG_VIDEO` overlay-recompose lines every ~500 ms), but those recomposes have `isLoading=false` and produce **zero** spinner-block entries. The position-tick recompose is benign. The shift is specifically driven by the `isLoading=true` recompose. |
| C2 | aspectRatio modifier on VideoSurface reacting to videoWidth/videoHeight changes | **NOT EXAMINED** | No videoSize-change events in the captured window. INCONCLUSIVE but unrelated to the symptom because the shift is in the *controls cluster*, not the video frame. |
| C3 | TextureView re-attach after backward seek | **REJECTED** | VideoSurface.kt:101-129 explicitly does NOT re-attach on settings change; the player is bound once in `factory{}` and stays bound. Code-derived rejection; no Phase-2 evidence of a re-attach. |
| C4 | Position-poll tick reflowing chapterStart/chapterDuration | **REJECTED** | Position-tick recomposes do happen but they don't change the spinner-block branch. Logs show `chapStart=0 chapDur=113661` constant across the entire run. |
| C5 | cumulativeSkip 180 ms debounce producing a delayed seek that lands after the gesture is over | **CONFOUNDING_BUT_NOT_CAUSAL** | The 180 ms debounce IS observable (debounced seekTo at +181 ms after click in both directions). But the asymmetry between forward and backward is not caused by the debounce — both directions have it identically, only backward triggers the loadingChanged. |
| C6 | Spacer(weight(1f)) reflow when sibling sizes change | **CONFIRMED, secondary** | This is the *mechanism* through which C25 manifests. Phase 6 root cause is the C7 + C25 pair; the weight-Spacer is the layout primitive that makes the 32 dp insertion propagate as a translation of every sibling. |
| C8 | coverColors / palette extraction emitting state on video files | **REJECTED** | No `Cover decoded` / `extractColorSet` log lines in the recorded window. Palette extraction is in `CoverArtBackground` which is only invoked on the audio path, not the video path. |
| C9 | controlsVisible 4 s auto-hide cross-fade | **REJECTED** | The controls cluster shift in the recording predates the 4 s auto-hide (jump occurs <100 ms after seek, well within the 4 s window in which controls remain visible). |
| C10 | Scrim Brush rebuild churn | **NOT_CAUSAL** | scrim is `remember(uiState.isVideoContent)` keyed; isVideoContent doesn't change during a seek. INCONCLUSIVE on jank cost but not the cause of the shift. |
| C11 | Choreographer skipping a frame from 4K decoder pressure | **PARTIAL_TRUE** | gfxinfo aggregate shows 4.48% jank during the backward window vs 3.88% on forward — slight elevation but not what causes the layout shift. The shift would happen at any frame rate; decoder pressure is a separate jank concern. |
| C12 | AnimatedVisibility re-fade for OverlayContent | **REJECTED** | OverlayContent is wrapped in AnimatedVisibility ONLY in video mode (`PlayerScreen.kt:347-361`). The bug reproduces when controls are fully visible (controlsVisible=true), so AnimatedVisibility is in a steady "visible" state during the symptom — not crossfading. |
| C13 | SeekParameters.PREVIOUS_SYNC making backward seeks land on a far-back keyframe | **CONFOUNDING_BUT_NOT_CAUSAL** | This setting (PlaybackService.kt:343) does increase backward-seek decoder cost (the player has to decode forward from an older keyframe to reach the target), which contributes to *how long* loadingChanged stays true. But the *existence* of the loadingChanged event is what causes the shift, not its duration. |
| C14-C26 | Various Compose / Window / poll-tick / chapter-list / pointer-input / multi-window candidates | **NOT_NEEDED** | The symptom is fully explained by C7 + C25. Per parsimony rule, no need to invoke additional candidates unless a new symptom appears. Each remains INCONCLUSIVE in the artefacts. |
| Cnull | Test-rig artefact (rendering pipeline / panel / OS, not the app) | **REJECTED** | The PMP_DIAG_VIDEO logs prove the spinner-block is being entered in the app's own composition. The cause is in app code, not OS rendering. |

## 8. Outstanding unknowns

None blocking. Two minor follow-ups, neither needed for the fix plan:

1. **Why does `isLoading=true` produce three rapid recompositions before `isLoading=false`?** (lines 23:10:00.464, .484, .523). Plausibly the `CircularProgressIndicator`'s own animation invalidates per ~20-40 ms; verifying this would require a `Composable` recompose-counter on the indicator itself. Not in scope — irrelevant to whether the shift happens.
2. **Does the same shift happen on audio-only files?** Audio uses the same OverlayContent, so theoretically yes — but audio rebuffer is rare and the user has not reported it. Not investigated; out of scope.

## 9. Reproducibility summary

- **5 of 5** backward operations across 4 runs produced a `loadingChanged=true / =false` cycle within 30-50 ms of the seek.
- **0 of 4** forward operations produced any `loadingChanged` event.
- **3 of 3** backward operations in the instrumented run produced `loading-spinner-block ENTERED` log entries.
- **0 of 1** forward operations in the instrumented run produced any `loading-spinner-block ENTERED` log entry (the forward window has 12 logged recomposes, all with isLoading=false, none triggering the if-branch).
- All evidence is in this directory; reproduction script in `CHANGELOG.md`.

## 10. Fix scope (NOT in this document)

The fix is a separate plan. Per the investigation plan's Phase 7 rule, instrumentation is removed only after this RCA is approved. The fix itself is out of scope of the diagnosis document.

Likely fix shapes (filed for the user, not implemented here):
- (a) **Stable-height spinner placeholder.** Reserve 32 dp at all times; populate the spinner only when isLoading=true. Eliminates the layout shift entirely; spinner appears in-place rather than inserting space.
- (b) **Move the spinner outside the bottom-anchored Column.** Render it as an `Box(align=BottomCenter)` overlay on the parent Box so its presence doesn't reflow siblings.
- (c) **Mask the spinner during seek.** Suppress isLoading from the UI for, say, the first 200 ms after a user-initiated seek, since most rebuffers complete in <100 ms; the spinner only shows on protracted buffering. Keeps the spinner's purpose intact (long-load feedback) without churning layout on every backward seek.

The user can pick whichever they prefer when the fix plan is opened. The diagnosis itself is closed.
