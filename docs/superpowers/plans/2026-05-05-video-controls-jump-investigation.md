# Video Controls "Jump" — Deep Investigation Plan

> **For agentic workers:** This is a DIAGNOSIS plan. NO code fixes are written until the root cause is **evidence-locked** in Phase 5. Steps use checkbox (`- [ ]`) syntax. REQUIRED SUB-SKILL for execution: superpowers:executing-plans (one phase at a time, never skip ahead).

**Goal:** Identify, with logged on-device evidence, why the video player's transport-controls cluster visibly "jumps" — sometimes as a group, and reliably during backward scrub / backward skip on large files (4K, portrait, etc.) — but does NOT jump on forward scrub / forward skip. Output is a written root-cause document referencing concrete frame timings, recompose IDs, and code lines. **No fix is in scope** of this plan.

**Hard constraints (non-negotiable):**
- **No guessing.** A hypothesis is only "considered" once it is on the ranked falsification list (Phase 4) with a defined evidence threshold. A hypothesis is only "accepted" once Phase 5 produces matching evidence. No hypothesis is "fixed".
- **No code deletion** during the diagnosis phases (1-5). Any new logging is purely additive, gated behind a `PMP_DIAG_VIDEO` constant so it can be removed in one revert. Lessons from prior sessions: aggressive removal of "looks wrong" code has broken unrelated features.
- **Reproduce before instrumenting.** If we cannot reproduce the bug deterministically on the test rig (Phase 1), we do not move to instrumentation. Otherwise we are tuning blind.
- **Evidence types are explicit per step.** Every step states *what artefact must be produced* (logcat tag/range, frame-time CSV from gfxinfo, a screen-recording with timestamp overlay, a perfetto trace, a code-line reference). Steps that can't produce one of those are not steps.
- **One change at a time.** During instrumentation, only one logging block is added per build. Two simultaneous changes obscure causality.
- **adb / logcat / direct device control are pre-authorised** per `project_operational_state.md` §2.

**Test rig:**
- Device: `RFCY70BARDJ` (Samsung Z Fold 6 — adaptive 1-120 Hz panel; the same device that exhibited the prior frame-rate-strategy regression; `project_operational_state.md` notes the frame-rate strategy was *intentionally* left at default for it).
- adb: `C:\Users\Kabir\AppData\Local\Android\Sdk\platform-tools\adb.exe` (NOT on PATH; always invoke by full path).
- App build: latest `main` debug.
- **Test corpus is whatever is already in the device's local library** — no host-side pushes. Use the app's own Library tab sort to pick the candidate files. Concrete protocol:
  - Open the Library tab, sort by **Duration desc**: pick the top file as the "long-duration probe".
  - Sort by **Size desc** (or whatever the closest analogue available is): pick the top file as the "large-file probe".
  - If the same file tops both sorts, accept it as a single primary probe and pick the next-ranked distinct file from each sort as a secondary.
  - Pick a third "control" file from the bottom of the Duration sort (smallest / shortest video already on device). It is the contrast case used to falsify decoder-cost hypotheses.
  - Filenames + `MediaStore` paths + duration + bytes + WxH are recorded in the evidence log on capture. Files are NOT modified during the investigation.
  - We never push our own corpus — we use whatever the user has, because (a) per project rules we don't synthesise content, (b) the user's own files are the production-realistic test set, and (c) if the bug is content-dependent we want it to surface against the user's actual content distribution.
  - If sorted Library shows fewer than 3 distinct video files, stop and tell the user; ask them to add content before we proceed.

**Evidence directory:** `docs/superpowers/investigation/2026-05-05-video-controls-jump/` — created in Phase 0. All artefacts (logcat captures, screen recordings, perfetto traces, gfxinfo dumps, annotated screenshots) land here, named `phase<N>-step<M>-<short-tag>.<ext>`. The final RCA document lives at the root of that directory.

---

## Phase 0 — Set-up and ground rules

- [ ] **Step 0.1: Create the evidence directory and a CHANGELOG.md inside it.**

```powershell
$dir = "C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\docs\superpowers\investigation\2026-05-05-video-controls-jump"
New-Item -ItemType Directory -Path $dir -Force
New-Item -ItemType File -Path "$dir\CHANGELOG.md" -Force
```

The CHANGELOG records every artefact captured, the device build fingerprint at capture time, and any code instrumentation added. A single source of truth so the next session can pick up without re-deriving context.

- [ ] **Step 0.2: Capture device fingerprint and app version.**

```powershell
$adb = 'C:\Users\Kabir\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell getprop ro.build.fingerprint
& $adb shell dumpsys package com.powermediaplayer | Select-String -Pattern 'versionName|versionCode'
& $adb shell wm size
& $adb shell wm density
```

Save output to `phase0-step02-device-fingerprint.txt`. We need this on every artefact so a future session can rule out "different OS / different panel / different DPI".

- [ ] **Step 0.3: Confirm app is at the expected `main` HEAD.**

```powershell
git rev-parse HEAD
```

Record in CHANGELOG. If the user runs more code while we investigate, every artefact gets the new HEAD recorded too.

- [ ] **Step 0.4: Decide on a stop-the-world rule.**

Document at the top of CHANGELOG: *"This investigation MUST NOT be paused mid-phase to ship unrelated fixes. If the user requests an unrelated fix, finish the current step, capture its artefact, and only then context-switch."* Past failures came from interleaved sessions that "improved" code mid-investigation and broke evidence chains.

---

## Phase 1 — Reproduce the bug deterministically

We do not move on until we have at least one screen-recording for each of (4K landscape, 4K portrait, 1080p landscape) showing the bug clearly, plus at least one negative case (forward scrub) confirming that direction matters. If 1080p landscape does not exhibit the bug, that itself is a data point — record it.

- [ ] **Step 1.1: Pick the test corpus from the device's existing library via the in-app sort.**

a. Launch the app. Navigate to the **Library** tab.

b. Sort by **Duration desc**. Screencap, save as `phase1-step01a-library-by-duration.png`. Capture the file id, name, size, and duration of the top entry from the on-screen list. Cross-check against MediaStore directly so we have the canonical metadata, not screen-OCR:

```powershell
$adb = 'C:\Users\Kabir\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell "content query --uri content://media/external/video/media --projection _id,_data,_display_name,duration,_size,width,height --sort 'duration DESC LIMIT 5'" `
    > "$dir\phase1-step01a-mediastore-by-duration.txt"
```

c. Sort by **Size desc** (or the analogue available — name TBD by the in-app implementation; check `LibraryViewModel`). Screencap as `phase1-step01b-library-by-size.png`. Pull MediaStore by size:

```powershell
& $adb shell "content query --uri content://media/external/video/media --projection _id,_data,_display_name,duration,_size,width,height --sort '_size DESC LIMIT 5'" `
    > "$dir\phase1-step01b-mediastore-by-size.txt"
```

d. Compare the two top entries. If they differ, our **primary probe** = the top by Duration, **secondary probe** = the top by Size. If they're the same file, secondary = #2 by Size.

e. Take the bottom (shortest) by Duration as the **control probe**. Pull it the same way:

```powershell
& $adb shell "content query --uri content://media/external/video/media --projection _id,_data,_display_name,duration,_size,width,height --sort 'duration ASC LIMIT 1'" `
    > "$dir\phase1-step01c-mediastore-control.txt"
```

f. Record the three probes in CHANGELOG with: filename, full path, duration (ms), size (bytes), WxH, codec mime if available. Format `<role>: <filename> (<duration_ms> ms / <bytes> bytes / <WxH>)`. These three names are the ONLY content we touch for the rest of the investigation.

g. Hard stop if the Library has fewer than 3 distinct video entries. Tell user. Do not synthesise.

- [ ] **Step 1.2: Define the exact reproduction steps for "jump on backward scrub".**

Write this as a script in CHANGELOG so anyone can replay it identically:

> *Open the app. Library → tap `4k_landscape.mp4`. Wait until the first frame renders and playback is steady (3 s). Press the on-screen track-slider thumb at ~80% width and drag to ~30% width in one continuous gesture, taking ~600 ms. Release. Observe the transport-controls cluster.*

And the contrast script for "no jump on forward scrub":

> *Same setup. From the same start position (~30% width), drag the slider thumb to ~80% width over ~600 ms. Release. Observe the transport-controls cluster.*

Both scripts must use ONE finger and the on-screen slider — not BT, not adb input. The bug is described as visible to the user during in-app interaction; isolate that path first.

- [ ] **Step 1.3: Capture screen recordings of both reproduction scripts on each test file.**

```powershell
# Start recording on device. Time-bomb at 60 s so it always returns.
Start-Job { & 'C:\Users\Kabir\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell screenrecord --time-limit 60 --bit-rate 16000000 /sdcard/repro_<file>_backward.mp4 }
# … Perform the manual scrub gesture during the recording window …
# Wait for the job to finish, then pull
& $adb pull /sdcard/repro_<file>_backward.mp4 "$dir\phase1-step03-<file>-backward.mp4"
& $adb shell rm /sdcard/repro_<file>_backward.mp4
```

Repeat for forward scrub on every file. Six recordings minimum. Each lands in the evidence dir with a descriptive name.

- [ ] **Step 1.4: For each recording, scrub frame-by-frame in a video player and find the exact moment the controls jump.**

Document in CHANGELOG: filename, timestamp at which the jump starts, duration of the jump (frames between pre-jump and post-jump steady state), and a short description of what moves (e.g. *"the entire ProgressSliders + PlaybackControls Column shifts up ~12 px and back over ~3 frames"*). If different files behave differently, record per file.

- [ ] **Step 1.5: Decide whether reproduction is solid.**

A reproduction is "solid" when:
- The bug appears on at least one file.
- Direction matters: forward scrub of the same magnitude on the same file does not produce the jump (confirmed in the negative-case recording).
- The behaviour is consistent across at least 3 attempts per direction per file.

If reproduction is **not solid**, stop and re-spec the bug with the user. Do NOT proceed into instrumentation. The cost of guessing past unreliable repro is exactly the failure mode the user called out.

If reproduction is solid, write the precise repro script and the file/timing fingerprint into `phase1-summary.md` and proceed.

---

## Phase 2 — Capture rendering-pipeline ground truth

Before we touch any code, we capture what the system already knows. The four ground-truth tools:

1. **`adb shell dumpsys gfxinfo`** — frame-by-frame draw timings for the app's surface(s).
2. **Perfetto / systrace** — full timeline of choreographer, RenderThread, MediaCodec, AudioFlinger, Compose recompose.
3. **`adb shell dumpsys SurfaceFlinger`** — layer composition and any latched-buffer stalls.
4. **App's existing `PMP_DIAG` logcat tag** — already emits `seekTo target`, `skip delta`, `debounced seekTo target` (cumulativeSkip path).

These four together cover all plausible substrates: graphics frame pacing, decoder stalls, surface composition, and our app-level seek logic. If the bug is real and on-device, at least one of them will show it.

- [ ] **Step 2.1: Reset gfxinfo, perform the bug repro once on `4k_landscape.mp4`, dump gfxinfo.**

```powershell
& $adb shell dumpsys gfxinfo com.powermediaplayer reset
# … perform the manual backward-scrub repro from Phase 1 …
& $adb shell dumpsys gfxinfo com.powermediaplayer framestats > "$dir\phase2-step01-gfxinfo-4k-backward.txt"
```

`framestats` gives one row per frame with timestamps for INTENDED_VSYNC, HANDLE_INPUT, ANIMATION, LAYOUT, DRAW, SYNC_QUEUED, FRAME_COMPLETED. Look specifically for frames where `LAYOUT - ANIMATION` exceeds 8 ms (one half of 16.67 ms frame budget at 60 Hz, or one quarter of 8.33 ms at 120 Hz). Those are the "long layout" frames where the controls-cluster jump would be visible.

Repeat for forward scrub. The contrast between the two is the primary diagnostic.

- [ ] **Step 2.2: Capture a perfetto trace covering the bug repro.**

```powershell
& $adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/jump.pftrace --time 15s `
    --buffer 32mb `
    --app com.powermediaplayer `
    --atrace-categories view,gfx,sched,freq,input,wm,am,binder_driver,view,res,sm `
    --atrace-apps com.powermediaplayer
# Manual repro during the 15s window
& $adb pull /data/misc/perfetto-traces/jump.pftrace "$dir\phase2-step02-perfetto-4k-backward.pftrace"
```

Open in https://ui.perfetto.dev. Look for the following on the timeline aligned to the moment of jump:
- RenderThread spikes (long DequeueBuffer / drawFrame).
- MediaCodec.Codec2:input long-running blocks (seek-induced flush + re-init).
- Choreographer doFrame skipped frames.
- Compose recomposition slices in the `view` track.

Save the per-track timing observations into `phase2-step02-perfetto-notes.md` with screenshots. Do **not** speculate at this point — record what is *measurably* anomalous, with timestamps.

- [ ] **Step 2.3: Capture SurfaceFlinger timing.**

```powershell
& $adb shell dumpsys SurfaceFlinger --latency com.powermediaplayer/MainActivity > "$dir\phase2-step03-sf-latency-pre.txt"
# Manual repro
& $adb shell dumpsys SurfaceFlinger --latency com.powermediaplayer/MainActivity > "$dir\phase2-step03-sf-latency-post.txt"
```

Diff `pre` vs `post`. Look for pattern of dropped or repeated frames during the scrub window. SurfaceFlinger sees buffer-latch jitter that gfxinfo doesn't.

- [ ] **Step 2.4: Capture the existing `PMP_DIAG` logcat across the repro.**

```powershell
& $adb logcat -c
& $adb logcat -v threadtime PMP_DIAG:I '*:S' > "$dir\phase2-step04-pmp-diag-4k-backward.txt"
# Run the bug repro, then Ctrl-C the logcat (or pipe through Out-File with -Wait and Stop-Job)
```

The app already logs `seekTo target`, `skip delta`, `debounced seekTo target`. If the symptom corresponds to a multi-fire seek on backward but a single-fire on forward (or vice-versa), the existing log alone may answer the question.

- [ ] **Step 2.5: Cross-reference the four artefacts on a single timeline.**

Open the screen-recording from Phase 1. Pick the exact frame the jump starts (already documented in CHANGELOG). Convert that frame number to a clock timestamp (recording fps × frame number). Locate the same clock timestamp in: gfxinfo `framestats`, perfetto, SurfaceFlinger latency, `PMP_DIAG`. Annotate each artefact with the matched moment.

Output: `phase2-summary.md` containing a table — for each test file × direction, the wall-clock millisecond of the jump and the corresponding readings from the four data sources. **No interpretation in this file yet** — only synchronised raw evidence.

---

## Phase 3 — Catalogue the candidate substrates (no ranking yet)

We list every layer that could be implicated. Each candidate gets a one-line definition and a falsifiability test that can be run against the Phase 2 artefacts.

- [ ] **Step 3.1: Write `phase3-candidates.md` listing each substrate and its falsifiability test.**

The exhaustive substrate list, pre-committed before evidence is examined so we can't selectively narrow:

| # | Substrate | What it is | Falsifiable by … |
|---|---|---|---|
| C1 | Compose recomposition of `OverlayContent` triggered by `uiState` changes during seek | A flush of recompositions where the controls Column re-measures and re-lays out | gfxinfo: a layout-cost spike in the same frame the jump is observed; perfetto: long Compose `recompose` slice |
| C2 | `aspectRatio` modifier on `VideoSurface` reacting to `videoWidth`/`videoHeight` arriving asynchronously after a backward seek (re-init) | The video Box size flickers as the player re-reports dimensions after a flush | gfxinfo: layout-cost spike; perfetto: `Player.onVideoSizeChanged` slice; logcat: a `videoWidth`/`videoHeight` change near the jump moment |
| C3 | TextureView re-attach after backward seek due to decoder flush | The TextureView surface is detached and re-attached, leaving a frame where it has zero size | perfetto: TextureView surface lifecycle traces; SurfaceFlinger: a re-create event |
| C4 | Position-poll tick fires mid-seek and updates `trackProgress`/`chapterStartMs` causing the slider Column to re-lay out | The `ProgressSliders` Column briefly grows/shrinks because `chapterStartMs` jumps | logcat: position-poll values around the jump; perfetto: Compose recompose of `ProgressSliders` |
| C5 | `cumulativeSkip` debounce window (180 ms) producing a deferred seek that lands AFTER the user thinks the gesture is over, causing a delayed layout pass | A seek occurs ~180 ms after release, well into the supposedly-steady period | logcat `debounced seekTo target` timestamp vs jump moment from screen-recording |
| C6 | `Modifier.weight(1f)` `Spacer` above `OverlayContent` redistributing space when sibling sizes change | Vertical layout of the OverlayContent Column reflows because something else in the column changes intrinsic size | perfetto: layout slice listing the affected Column; visual: jump direction matches a single sibling's growth |
| C7 | Backward seek triggers `BUFFERING` → `READY` cycle that the existing comment on `PlayerScreen.kt` already mentions as a pre-existing seek freeze; `LoadControl.bufferForPlaybackAfterRebufferMs` was tuned to 1000 ms but may still surface as a layout-affecting state change | `isLoading` flips true/false during the scrub | logcat `playerState.isLoading` transitions; gfxinfo: input-handle latency spike |
| C8 | The `coverColors` palette extraction reruns when artwork changes — would not affect video, but would prove the bug is not artwork-related if absent on video files | n/a (control hypothesis; expected dormant on video) | logcat `Cover decoded` absence on video |
| C9 | `controlsVisible` 4 s auto-hide timer firing concurrently with the seek causing a fade-out / fade-in mid-jump | Controls appear to "jump" because they cross-fade while shifting | timing: jump moment vs auto-hide timer state; reproduction: disabling auto-hide should remove the jump |
| C10 | Scrim `remember(uiState.isVideoContent)` re-evaluation thrashing, forcing a Brush rebuild on each frame | A minor allocation-driven hitch during scrub | perfetto: GC slices, allocation counters |
| C11 | Choreographer skipping a frame because RenderThread missed deadline due to 4K decoder pressure during seek-flush | Frame deadline misses correspond exactly to jump | perfetto / gfxinfo: late-frame flag |
| C12 | Compose's `LookaheadScope` / animation-driven layout causing a one-frame intermediate size during AnimatedVisibility re-fade | AnimatedVisibility wrapping `OverlayContent` is the actual culprit on video paths | perfetto: AnimatedVisibility recompose; visual: jump always coincides with controls fade-out timer expiry |
| C13 | `ExoPlayer.setSeekParameters(SeekParameters.PREVIOUS_SYNC)` causing backward seeks to land on a far-back keyframe (longer than the requested skip), producing a much longer decoder flush than the symmetric forward seek | The asymmetry FW vs BW is *built into seek-parameters semantics* and would only show on backward | logcat: actual landed position vs requested; perfetto: MediaCodec flush slice duration BW vs FW |
| C14 | `LaunchedEffect(uiState.isVideoContent, controlsVisible)` re-keying mid-scrub because a state field flips, restarting the 4 s auto-hide and incidentally causing a recomposition of its parent | Auto-hide timer state changes during scrub | logcat: instrument LaunchedEffect entry/exit; perfetto: recompose around the scrub boundary |
| C15 | `parentTapModifier` swap: when `controlsVisible` flips during the scrub, the parent Box modifier chain swaps from `pointerInput { … }` to `Modifier`, causing the child subtree to re-attach | Modifier-chain identity changes mid-scrub | perfetto: layout-pass on parent Box at scrub boundary; instrumentation: log every `controlsVisible` flip |
| C16 | `onTracksChanged` firing on seek-flush, recomputing `audioFormatLabel`, propagating into `PlayerUiState`, recomposing the controls cluster | Tracks-change event coincides with the jump | logcat: `cachedAudioFormatLabel` change instrumentation; existing `PMP_DIAG`: track-change logs if any |
| C17 | `coverColors` (palette) extraction completing on a worker frame and emitting state — even on video, if the path is not properly gated | Palette emission on video files | logcat: `Cover decoded` lines during video; `PaletteHelper.extractColorSet` traces |
| C18 | `SettingsViewModel` collected inside `VideoSurface` (`val s by settingsVm.uiState.collectAsStateWithLifecycle()`): every Settings flow tick recomposes the AndroidView holding the TextureView | Any settings change during scrub triggers Composer-level work that touches the surface | log: instrument `SettingsViewModel.uiState` emissions; visual: bug correlates with any pref tick |
| C19 | Window insets / system-bar visibility flicker during the gesture (some Samsung gestures flash bars), forcing OverlayContent to re-pad | System-bar visibility transitions in window state during scrub | perfetto: WindowManager / InsetsController slices |
| C20 | PiP / multi-window listener firing on lifecycle events tangential to the scrub | Multi-window mode change during scrub | logcat: `onPictureInPictureModeChanged` / `onMultiWindowModeChanged` |
| C21 | Frame-rate strategy: 4K decoder asks the panel to switch refresh rate; the swap itself is a one-frame visual hitch (`project_operational_state.md` notes the strategy was left at default specifically for the Z Fold 6 panel) | Refresh-rate switch coincident with seek | perfetto: `DisplayManager.setFrameRate` slices; SurfaceFlinger refresh-rate change |
| C22 | `OverlayContent`'s `Box(fillMaxSize().background(scrim))` painting first and then `Column.fillMaxSize` second — if either child reports a different intrinsic height after the seek (e.g. ProgressSliders' formatted strings change width on a long position number "00:00" → "1:23:45"), the bottom-anchored Column may shift while the rest of the tree is steady | A two-line / two-token text length change at the seek boundary | log: instrument the formatted-position strings and the Column intrinsic height calls |
| C23 | `ProgressSliders` slider thumb position updates from a stale `trackProgress` after the seek but before the position-poll catches up: the slider shows a brief incorrect position, then corrects on the next tick — visually the slider "jumps" | Two-emission slider position reading | log: `trackProgress` immediately before/after seek vs first poll-tick after |
| C24 | The act of letting go of the slider drag causes a `pointerInput` release-handler to re-emit a state change to the slider's controller, which triggers a Layout pass independent of the seek | Slider release event correlates with the jump | perfetto: input-event slice at finger-up; instrumentation in slider handler |
| C25 | The bottom-up `Arrangement.Bottom` with `Spacer(weight(1f))` reflows whenever a sibling's intrinsic height changes; if any element above the controls cluster resizes (e.g. the gradient scrim, the VideoSurface aspectRatio) the controls visually shift | Vertical arrangement reflow at the boundary | layout instrumentation; visual: jump direction (always down? always up?) consistent with a sibling growing/shrinking by the observed pixel count |
| C26 | `chapters` field on `PlayerUiState` is rebuilt with `.map { it.copy(...) }` in `mapToUiState` on every emission, producing a fresh `List<ChapterInfo>` reference even when content is identical → Compose treats it as changed → controls cluster recomposition | Recompose-counter increase on every poll-tick | Compose recompose counter via `Modifier.composed` instrumentation OR perfetto recompose slice frequency |

This is exhaustive on purpose. **No substrate is ruled in or out at this step.** New candidates may be appended ONLY with explicit justification (a Phase-2 observation that doesn't fit any existing candidate). They MUST NOT be added to fit a guess.

---

## Phase 4 — Falsify candidates against Phase 2 evidence

Now and only now do we narrow.

- [ ] **Step 4.1: For each candidate C1-C26, look up the corresponding Phase 2 evidence artefact and write a one-line PASS / FAIL / INCONCLUSIVE verdict.**

Output: `phase4-falsification.md`. Format:

| # | Substrate | Verdict | Citation (artefact + line/timestamp) | Note |
|---|---|---|---|---|
| C1 | Compose recompose of OverlayContent | PASS / FAIL / INCONCL. | `phase2-step02-perfetto-4k-backward.pftrace @ 7.214 s` | one short sentence |

PASS = the evidence shows this substrate IS active during the jump moment.
FAIL = the evidence shows this substrate is NOT active during the jump moment.
INCONCLUSIVE = the evidence does not speak to it; needs targeted instrumentation in Phase 5.

Hard rules:
- Do not mark anything PASS without a citation to a specific timestamp + file.
- Without evidence, it is INCONCLUSIVE, not "likely". This is the rule prior sessions broke.
- A FAIL verdict requires a *positive* observation that the substrate did NOT fire (e.g. "no recompose slice in window"); absence of mention is not absence.

- [ ] **Step 4.2: Direction-asymmetry check.**

For every candidate marked PASS, verify the same artefact for the *forward* direction shows the substrate is **NOT** active (or active to a measurably lesser degree). A candidate that fires identically on forward and backward cannot explain a direction-asymmetric bug. Mark it `FAIL_DIR_ASYM` if it does, with citation to both directions.

- [ ] **Step 4.3: File-class-asymmetry check.**

For every PASS candidate, check whether the same substrate is active on the **control probe** (smallest video file). If it fires identically on the control file (where the bug should be absent or weaker per Phase 1.4 observations), the substrate cannot be the *sole* explanation — it must combine with another file-class-dependent factor. Mark the substrate `FAIL_FILE_ASYM` if so, or `COMBINES_WITH_<X>` if a coupling is plausible from the artefacts (no speculation; only when both substrates are PASS in the same window).

- [ ] **Step 4.4: Repeatability check.**

Re-run Phase 1.3's screen-recording capture and Phase 2 captures on the **primary probe** TWO MORE times (so 3 runs total, not counting the first). For each PASS candidate verify it fires in all three runs. If it only fires in 1/3 or 2/3, mark `INTERMITTENT` and require Phase 5 instrumentation; do not promote to shortlist on a single observation.

- [ ] **Step 4.5: Two-substrate corroboration rule.**

A candidate is only allowed onto the Phase 5 shortlist when it has supporting evidence from at least TWO of the four Phase 2 substrates (gfxinfo, perfetto, SurfaceFlinger, PMP_DIAG). Single-substrate signals are too easy to misread. Document each shortlisted candidate's two corroborating citations.

- [ ] **Step 4.6: Negative-control candidate.**

Add `Cnull` to the shortlist by default — meaning "the bug is an artefact of the test rig (rendering pipeline / panel / OS), not the app". Falsifiable by: same APK + same files on a second device, OR an OS-level repro outside the app. If we never get to verify on a second device, `Cnull` stays INCONCLUSIVE on the rejected list — never silently dropped.

- [ ] **Step 4.7: Build the shortlist.**

Identify the smallest set of substrates whose PASS verdicts can collectively explain the observed bug AND whose FAIL verdicts on the rejected ones are supported by Phase 2 evidence. Each shortlisted candidate gets a *predicted observation* — a specific reading we expect from a targeted instrumentation in Phase 5. If Phase 5 doesn't observe the prediction, we drop the candidate.

If the shortlist is empty (no candidate has direct Phase-2 evidence), we either:
- Re-capture Phase 2 with different categories enabled in perfetto (e.g. add `gfx`, `dalvik`, `memory` if missing), OR
- Add a NEW candidate to Phase 3 with explicit justification anchored in a Phase-2 observation, OR
- Stop and report that the existing tool kit cannot see the bug; ask user.

We **do not invent a fix** at this point regardless of how confident the shortlist looks.

---

## Phase 5 — Targeted, additive instrumentation to confirm or kill the shortlist

For each shortlisted candidate, we add ONE diagnostic log per build, gated by `private const val PMP_DIAG_VIDEO = true`. Logs must be:

- Tagged `PMP_DIAG_VIDEO` (new tag, distinct from `PMP_DIAG`) so they're greppable in isolation.
- Single-line, key=value format so they're parseable.
- Removable in one revert (a single commit per instrumentation block).

We do NOT change behaviour. We do NOT delete or rewrite existing code. We only add logs.

- [ ] **Step 5.1: Add the `PMP_DIAG_VIDEO` constant in a new file `app/src/main/java/com/powermediaplayer/util/DiagFlags.kt` and a helper `fun diagV(msg: String) = if (PMP_DIAG_VIDEO) Log.i("PMP_DIAG_VIDEO", msg) else Unit`.**

Single tiny file, one constant, one helper. Commit message: `chore(diag): add PMP_DIAG_VIDEO flag for video-jump investigation`. Easy to revert in one squash.

- [ ] **Step 5.2: For each shortlisted candidate, add one targeted log block.**

Examples (which apply depends on the shortlist):

- C1 (`OverlayContent` recompose): inside `OverlayContent`, log `diagV("overlay-recompose pos=${uiState.currentPosition} dur=${uiState.duration} chapStart=${uiState.chapterStartMs} chapDur=${uiState.chapterDurationMs}")` once per recomposition. The cadence and field deltas around the jump moment will tell us what's driving the recomposition.
- C2 (videoWidth/videoHeight settle): inside `VideoSurface`, log `diagV("video-size w=$videoWidth h=$videoHeight")` whenever the params change. We expect zero changes on a steady playback; any change during the scrub identifies a re-init.
- C4 (poll-tick reflow): inside the position-poll path in `PlaybackConnection.updatePlayerState` add `diagV("poll-state pos=${state.currentPosition} chap=${state.currentChapterIndex} hasChap=${state.hasChapters} chapStart=${chapterStart} chapDur=${chapterDuration}")` for the duration of the scrub. We can identify whether `chapterStart` / `chapterDuration` flip during the seek.
- C5 (debounced seek): the existing `debounced seekTo target` log already covers this; cross-reference timing.
- C7 (BUFFERING flicker): log `diagV("ploading=${state.isLoading} pos=${state.currentPosition}")` on every `isLoading` transition.

Each instrumentation block is its own commit. Build, install, capture, analyse, then move to the next candidate.

- [ ] **Step 5.3: Re-run the Phase 1 repro for each instrumented build and capture `PMP_DIAG_VIDEO` logcat.**

```powershell
& $adb logcat -c
& $adb logcat -v threadtime PMP_DIAG_VIDEO:I PMP_DIAG:I '*:S' > "$dir\phase5-step03-<candidate>-<file>-backward.txt"
# Run the standard repro from Phase 1.2
```

Diff the per-candidate logs against the predicted observations from `phase4-shortlist.md`. Update `phase4-shortlist.md` in place: each candidate gets CONFIRMED, REJECTED, or NEEDS_MORE.

- [ ] **Step 5.4: Iterate Phase 5 until exactly one candidate remains CONFIRMED with corroborating evidence from at least two of the four Phase-2 substrates.**

If two candidates are both CONFIRMED, that's allowed — bugs can have multiple necessary causes (e.g. a recompose AND a layout reflow). The output is whichever set, not "the one".

If after three iterations no candidate is CONFIRMED, stop. Report to the user: "Existing instrumentation cannot resolve the bug deterministically; the next step is X." That is a legitimate end-state, not a failure to be papered over.

---

## Phase 6 — Write the root-cause document

- [ ] **Step 6.1: Write `phase6-root-cause.md` containing exactly these sections:**

1. **Symptom (cite Phase-1 recordings):** observed visual behaviour, by file × direction.
2. **Reproduction script (verbatim from Phase 1.2):** so anyone can replay.
3. **Confirmed substrate(s) (from Phase 5.4):** with exact file path and line numbers from the codebase.
4. **Causal chain:** event A at line X triggers state Y in flow Z which causes layout pass W in composable V, which is why the controls cluster shifts. Each link cites either a code line or an evidence artefact. No links may be inferred without a citation.
5. **Why forward scrub is unaffected:** the same causal chain applied to forward, with the divergence point made explicit.
6. **What is NOT the cause (rejected candidates from Phase 4 and Phase 5):** brief, with citations. This protects the next session from re-investigating the same dead ends.
7. **Outstanding unknowns:** anything that's still INCONCLUSIVE at the end. No hand-waving — each entry has a defined "what would resolve this".

- [ ] **Step 6.2: Commit the entire evidence directory (CHANGELOG, all artefacts, RCA) and push.**

```powershell
git add docs/superpowers/investigation/2026-05-05-video-controls-jump
git commit -m "docs(investigation): root-cause analysis for video controls jump on backward scrub"
git push
```

Per the saved feedback memory, push happens automatically; per the same memory, we install the APK only if `app/src/main/` changed — for Phase 6 it has not, since the only code touched in Phase 5 is the additive `PMP_DIAG_VIDEO` instrumentation, which we keep on the device until the user signs off on the RCA.

---

## Phase 7 — Decommission instrumentation (only after user signs off)

- [ ] **Step 7.1: Stop. Wait for user explicit OK.**

Once the user has read `phase6-root-cause.md` and approved it, remove all `PMP_DIAG_VIDEO` log calls and the `DiagFlags.kt` file in a single commit (`chore(diag): remove PMP_DIAG_VIDEO instrumentation, RCA filed`). Push and reinstall.

If the user says "now write the fix plan", that becomes a NEW plan in `docs/superpowers/plans/` — not appended to this one.

---

## Anti-patterns this plan deliberately rules out

- "It's probably a recomposition issue" without a perfetto trace. We capture the trace first, then decide.
- "Let me delete this LaunchedEffect, it looks suspicious." Forbidden in Phases 1-5; even in a future fix, only after Phase 6 has located it.
- Fixing a found bug mid-investigation. Even if Phase 5.2 makes a fix obvious, we file the RCA first and a separate fix plan after, so the change is tracked, justified, and reviewable.
- Capturing only the failing direction. Forward scrub is the control; without it we cannot prove direction is causally relevant.
- Using a single test file. The 1080p control file falsifies "this is purely a 4K decoder cost" — without it we'd over-scope.
- Editing or moving captured artefacts after the fact. Once an artefact lands in the evidence dir it is immutable. New runs go in new files.

---

## What success looks like

A document that, six months from now, the next contributor (or the next Claude session) reads and immediately understands:
- where the bug lived,
- by what evidence it was located,
- which substrates were investigated and ruled out,
- what to leave alone in any future refactor.

The plan is finished when the RCA is committed, pushed, and the user has read it. Not before.
