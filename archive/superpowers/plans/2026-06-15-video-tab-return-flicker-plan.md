# Plan — video "refresh/flicker" on returning to the Player tab

Date: 2026-06-15. Status: PLAN ONLY (no code changes yet). Methodology:
superpowers `systematic-debugging` (Iron Law: confirm root cause with evidence
before any fix) + Context7 (authoritative API behaviour).

User constraints (binding):
- The **dual-surface** design (full `PlayerScreen` surface + `FloatingVideoMiniPlayer`
  surface) MUST stay — the video effects (flip/rotate/colour via `graphicsLayer`
  + TextureView ColorFilter) depend on per-screen `VideoSurface`.
- **No big/drastic refactor** (single hoisted surface is OFF the table — risk of
  subtle breakage to effects/PiP/tabletop).
- Fix must be **targeted, low-risk, evidence-based**.

## 1. Symptom + reproduction
- Play a video → switch to another tab → return to the Player tab.
- Whole screen "refreshes/flickers" on RETURN only (never on leaving).
- Two prior fixes FAILED (proof the cause is neither): (a) root `systemBarsPadding`
  relayout move (commit 8488398); (b) instant NavHost transitions (commit 1335524).

## 2. Evidence gathered so far
- **logcat (this session):** tab switches produce NO `MainActivity.onCreate`/lifecycle
  → the Activity is NOT recreated; the flicker is a **composition** event, not an
  Activity/process restart. `surface released while current — re-bound survivor`
  was seen only on cold start + PiP exit (no clean tab-switch capture yet → GAP).
- **Context7 / Jetpack Compose (authoritative):** `AndroidView` builds its `View` in
  `factory`; when the composable leaves composition the View is disposed, and on
  re-entry the factory runs again → a **new** View. Holding a View ref outside
  `AndroidView` to reuse it is explicitly discouraged ("Prefer to construct a View
  in the factory lambda"). → reusing the TextureView is fragile = violates the
  low-risk constraint.
- **Compose Navigation:** `NavHost` composes ONLY the current destination; leaving
  Player disposes `PlayerScreen` (and its `VideoSurface`), returning rebuilds it.
  `saveState/restoreState` (the app's tab pattern) preserves the ViewModel +
  SavedState but NOT the composition → the TextureView is rebuilt.
- **Context7 / Media3 (authoritative):** `setVideoTextureView(view)` attaches the
  codec output to that view; a freshly-attached surface is blank until the codec
  draws. `Player.Listener.onRenderedFirstFrame()` fires when the first frame is
  rendered to the (new) surface → the precise signal that the picture is live again.
- **Code:** `VideoSurface.kt` factory creates a `TextureView` + `VideoSurfaceBinding.bind`
  → `setVideoTextureView`. `PlayerScreenCompact` line ~462:
  `var controlsVisible by remember(uiState.isVideoContent){ mutableStateOf(true) }`
  → on rebuild, controls RESET to visible, then auto-hide.

## 3. Root-cause hypotheses (to CONFIRM in Phase 1 before any fix)
- **H1 (primary): full-screen TextureView recreation.** Returning rebuilds
  `PlayerScreen` → new full-screen `TextureView` → `setVideoTextureView(new)` →
  blank surface for N frames until `onRenderedFirstFrame` → full-screen black blink.
  (Leaving builds only the 192×108 mini surface → unnoticeable. Explains return-only.)
- **H2 (secondary): chrome reset flash.** `controlsVisible` resets to `true` on
  rebuild → controls + tab rail re-appear then auto-hide → a chrome flash that can
  read as "refresh". Independent of H1; may co-occur.
- **H3 (control): recomposition/empty-state flash.** If the ViewModel is NOT
  actually restored, uiState briefly defaults (empty) then repopulates → flash.
  (Expected FALSE because saveState preserves the VM, but must be ruled out.)

## 4. Phase 1 — CONFIRM root cause (REQUIRED first executable step; instrumentation only)
Add **debug-only** diagnostic logs (no behaviour change), build, install, capture a
CLEAN `Player(video) → Library → Player` cycle on the Z Fold6, then read logs +
a screen recording. This is the evidence the two failed fixes lacked.

Instrumentation to add (all `Diag.i`, debug-stripped in release):
1. `VideoSurface` factory: `"VS factory CREATE textureview hash=… isVideo=…"`.
2. `VideoSurface` `onRelease`: `"VS RELEASE hash=…"`.
3. `VideoSurfaceBinding.bind/release`: already logs survivor; add `"bind hash=… stackSize=…"`.
4. `PlaybackConnection` player listener: add `onRenderedFirstFrame` →
   `"RENDERED_FIRST_FRAME t=…"` (also confirms the Media3 callback fires on swap).
5. `PlayerScreenCompact` entry (`LaunchedEffect(Unit)`): `"PlayerScreen COMPOSE controlsVisible=true"`.
6. (H3) Log `uiState.title`/`hasMedia` at first composition on return.

Capture + evidence to extract (timestamps correlated):
- Around the RETURN tap: do we see `VS factory CREATE` (new full surface) +
  `setVideoTextureView` + a gap to `RENDERED_FIRST_FRAME`? → measures the blink
  duration (how many ms the surface is blank). CONFIRMS/with duration → H1.
- `adb shell screenrecord` of the return; inspect (on a machine with a frame
  extractor, or play back) whether the blank is the VIDEO RECT (H1) or the WHOLE
  window incl. chrome (H2), and whether controls flash on→off (H2).
- Check `uiState.title` non-empty at first composition (H3 false) vs empty (H3 true).

Decision gate (which fix to build):
- If `RENDERED_FIRST_FRAME` lags the new-surface attach by >1 frame AND the recording
  shows a video-rect blank → **H1 confirmed → Fix A**.
- If controls visibly flash on→off independent of the video → **H2 also true → Fix B**
  (in addition to A).
- If uiState is empty on first frame → **H3 → Fix C** (VM scoping). (Not expected.)

## 5. Phase 2 — Targeted fix (build ONLY what Phase 1 confirms; keeps dual-surface)

### Fix A — freeze-frame mask (for H1) — PRIMARY, localized to `VideoSurface.kt`
Rationale: the 1-frame blank on a newly-attached codec surface is INHERENT (the
codec must draw one frame); with surface recreation unavoidable (refactor ruled
out, view-reuse fragile), masking the unavoidable blank with the last frame is the
**standard** video-surface-handoff technique, not a hack.
- Add `object VideoFreezeFrame { @Volatile var bmp: Bitmap? }`.
- In `VideoSurfaceBinding.bind(newView)`: BEFORE switching, capture the OUTGOING
  current view's frame: `runCatching { outgoing?.bitmap }` → `VideoFreezeFrame.bmp`.
  (At return, the mini surface is still alive → `getBitmap()` yields the live frame.)
- In `VideoSurface`: after the `AndroidView`, render `Image(VideoFreezeFrame.bmp)`
  with the SAME `aspectMod.then(transformMod)` (so flip/rotate match), ON TOP of the
  TextureView, while a `freezeVisible` state is true.
- Hide trigger = **`onRenderedFirstFrame`** (precise), exposed from
  `PlaybackConnection` as a one-shot `SharedFlow`/event the `VideoSurface` collects;
  plus a **fallback timeout (~300 ms)** so a missed event can't strand the overlay.
  Read the holder in a `LaunchedEffect` (post-composition) to avoid write-during-
  composition; clear `VideoFreezeFrame.bmp` after use (recycle).
- Net: on return the user sees the last frame (held ~1 frame) → live video; no black
  blink. Applies to BOTH full + mini `VideoSurface` (mini freeze is harmless/benefit).
- Risk: LOW — additive overlay in one file + one listener; no change to binding/codec
  flow, effects, PiP, tabletop, or the dual-surface model.

### Fix B — preserve `controlsVisible` across navigation (ONLY if H2 confirmed)
- Hoist `controlsVisible` to survive recomposition (e.g., `rememberSaveable`, or a
  retained holder keyed to the session) so returning restores the prior chrome state
  instead of forcing visible→auto-hide. Eliminates the chrome flash.
- Risk: LOW — single state hoist; must preserve the existing auto-hide timer logic.

### Fix C — ViewModel scoping (ONLY if H3 confirmed; not expected)
- Ensure `PlayerViewModel` is restored (activity-scoped or via correct
  `LocalViewModelStoreOwner`) so uiState never flashes empty. Defer unless evidence.

### Rejected alternatives (record, per protocol — no silent scope drop)
- Single hoisted persistent surface: USER-REJECTED (effects depend on dual-surface).
- Reuse TextureView across nav (hold ref): Context7-discouraged + fragile (View
  parenting / SurfaceTexture lifecycle) → violates low-risk constraint.
- Switch to `SurfaceView`: breaks `graphicsLayer`/ColorFilter effects path (the
  VideoSurface doc comment already records why TextureView is required); handoff
  artefacts historically worse.
- Keep NavHost destination composed when off-screen: not supported by standard
  `NavHost`; would itself be a "drastic change".

## 6. Phase 3 — Verification (device)
- Re-run the Phase-1 capture WITH the fix; confirm logs still show surface recreate
  + `RENDERED_FIRST_FRAME`, but the screen recording shows NO black blink (freeze
  frame bridges it). 
- Acceptance predicates (machine/observation):
  1. P1: `onRenderedFirstFrame` log present on every return (mechanism intact).
  2. P2: screen recording of 3 returns shows zero full-screen black frames.
  3. P3 (if Fix B): controls do not flash on→off on return (recording).
  4. P4: effects (flip/rotate/colour), system PiP enter/exit, tabletop split all
     still render correctly (regression check — dual-surface untouched).
  5. P5: `assembleDebug` + unit suites green.
- Strip the Phase-1 diagnostic logs (or gate behind the existing DiagLog) before the
  final release build.

## 6b. Phase 1 RESULTS (captured on device 2026-06-15, logcat + frame burst)
Evidence files: `deeplogs/logcat-t294*.txt`, `deeplogs/montage.png`.
- **H1 CONFIRMED.** Clean `Player→Library→Player`: leaving = mini TextureView CREATE
  + bind, full RELEASE; returning = mini RELEASE → **new full TextureView CREATE**
  (hash changes each return: 113507244 → 116545419 → 202579223) + `setVideoTextureView`.
- **Frame burst across the return: the video rect is BLACK for the whole ~500ms+
  window** (frames 1–11). The clip was at its end (1:14/-0:00) → ended/paused → the
  codec has no frame to draw to the new surface → stays black. (Playing would fill in
  1–2 frames.) → the "refresh" = a black video on the re-attached surface.
- **`onRenderedFirstFrame` does NOT fire on the swap** (it fired on cold start, 220 ms
  after bind, but never on a return). → the planned hide-trigger is INVALID.
- **H2 CONFIRMED but minor.** `PlayerScreen COMPOSE … controlsVisible=true` on every
  return (controls reset to shown). `AnimatedVisibility` starts already-visible so
  there is NO 500 ms fade — controls just snap on (~1 frame). Secondary to the black.
- **H3 FALSE.** `hasMedia=true`, title populated at first composition → ViewModel
  restored, no empty-state flash. No fix needed.

### Corrected fix (replaces §5 Fix A trigger)
- **Fix A — freeze-frame over the re-attached surface, kept until a real frame
  renders.** Capture the outgoing surface's last frame in `VideoSurfaceBinding.bind`
  (`getBitmap`), show it ON TOP of the new TextureView. Hide it on the first
  **rendered-frame tick** AFTER mount — sourced from a `VideoFrameMetadataListener`
  on the ExoPlayer (fires per rendered frame; reliable on a live swap, unlike
  `onRenderedFirstFrame`). Paused/ended → no tick → freeze stays = the last frame
  shows (correct: a paused video should show its frame, not black). `isOpaque` stays
  true (no per-frame alpha-blend perf change).
- **Fix B — preserve `controlsVisible` across navigation** (`rememberSaveable`), so
  returning keeps the prior chrome state instead of snapping controls on. Low-risk,
  removes the secondary artefact.
- Both keep the dual-surface; both localized (`VideoSurface.kt`, `PlaybackService`/
  `PlaybackConnection` for the frame tick, `PlayerScreen` controlsVisible).

## 6c. PROPER FIX — audited design (2026-06-15, 4-agent parallel audit + Context7)

Root cause RE-CONFIRMED: NavHost disposes the off-screen PlayerScreen destination →
on every return the video `TextureView` is recreated → codec re-attaches to a blank
surface → 1-frame flash (video-only; audio has no surface → no flash, confirmed by
user). Masks (freeze-frame/transition/padding) cannot fix a recreated surface.

Audit key facts (evidence in agent reports):
- Video output target is set via `PlaybackService.getExoPlayer().setVideoTextureView`
  through the STATIC `VideoSurfaceBinding` — independent of any PlayerViewModel
  instance. Effects (flip/rotate/colour) read singleton-backed flows → identical
  across VM instances. So hoisting the surface's composable does NOT change rendering
  or effects.
- 4 `VideoSurface` call sites: PlayerScreen full (PlayerScreen.kt:579), tabletop
  top-leaf (:713), FloatingVideoMiniPlayer (:106), MainActivity PiP (:289).
- `fullBleedVideo`/`videoControlsVisible` are written by PlayerScreenCompact effects
  (PlayerScreen.kt:516-547) and read by AppNavigation (nav type None, immersive
  overlay) — MUST stay in PlayerScreen (control/immersive logic, not surface logic).
- PiP branch swaps out AppNavigation entirely (MainActivity.kt:285) → its own
  VideoSurface; the binding stack + freeze-frame still needed for PiP enter/exit.

CHOSEN DESIGN (single always-composed host, repositioned by route — NOT movableContent):
1. One video host composed in AppNavigation's root Box whenever `isVideoContent &&
   hasMedia`. It stays at the SAME tree position; only its Modifier (size/zIndex) +
   wrapper chrome change by route → AndroidView never recreated on tab switch.
   - isPlayerRoute && !tabletop → `fillMaxSize`, `zIndex 0` (behind the content).
   - isPlayerRoute && tabletop  → top-leaf Box (height = hinge.top from adaptive),
     `zIndex 0`.
   - !isPlayerRoute → floating 192×108 BottomEnd, `zIndex 2` (on top), with the
     drag/clamp/✕-dismiss/tap-expand chrome moved from FloatingVideoMiniPlayer.
2. PlayerScreen video branch: replace `VideoSurface(...)` with a transparent
   placeholder Box (keeps the tap-to-toggle layer + scrim + OverlayContent + flags
   exactly as now). The picture shows through from the host behind.
3. NavigationSuiteScaffold `containerColor` = transparent WHEN `fullBleedVideo`
   (else OledBlack) so the behind-host shows on the Player video route; MainActivity's
   OledBlack Surface remains the backdrop. Non-video routes stay opaque (host is
   zIndex-2 mini on top, so no transparency needed there).
4. KEEP unchanged: MainActivity PiP branch + its VideoSurface; `VideoSurfaceBinding`;
   `VideoFreezeFrame` + `videoFrameTick` (now only relevant for PiP-exit/tabletop
   handoff — dormant for tab switches); PlayerScreen's flag writes + immersive +
   controlsVisible + tap layer + rotate button + DisposableEffect cleanup.
5. Remove FloatingVideoMiniPlayer's own `VideoSurface` (absorbed by the host's mini
   mode) — keep its chrome.
6. `hiltViewModel()` in the host resolves Activity-scoped (outside NavHost) — benign
   (singleton-backed). Optionally pass effect params; not required.

Risk mitigations (from audit): keep binding/freeze-frame (PiP); keep flags in
PlayerScreen; do NOT alter `navigateToPlayer` (drill-in back-stack, no popUpTo);
enumerate tabletop as a 3rd slot; verify NO `VS factory CREATE` on tab return via
deeplog after each step.

VERIFY each step on device with the diag logs: a Player↔Library↔Player round-trip must
log ZERO new `VS factory CREATE` (proves the TextureView is reused) and the user
confirms no flicker. Audio, PiP enter/exit, tabletop fold, cast, drill-in back, widget
deep-link, mini drag/dismiss/expand all re-tested.

## 7. Anti-skip
- Do NOT implement any fix until Phase 1 evidence is captured and the decision gate
  picks the fix(es). (This is the discipline the two prior failed fixes skipped.)
- Build only the confirmed fix(es); each verified against P1–P5 before "done".
