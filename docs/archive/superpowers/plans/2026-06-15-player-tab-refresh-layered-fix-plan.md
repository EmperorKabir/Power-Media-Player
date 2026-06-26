# Plan — kill the "full refresh of all app tabs" on entering the video Player

Date 2026-06-15. Status: PLAN ONLY — NO CODE until approved. Built from two
independent investigations (Context7+docs agent `a073e6bd`, first-principles
reasoning agent `aab568f9`), cross-examined, then re-checked against this
session's 4-agent feature audit.

## 1. Confirmed root cause (both investigations agree; matches device logs)
The flicker is a WINDOW/CONTENT RELAYOUT triggered by LAYOUT-level chrome changes
when entering/using the full-screen video Player — NOT the video surface, NOT the
NavHost cross-fade (already None). Sources:
- **S1 system-bar hide/show** — `PlayerScreen.kt ~512/514` `WindowInsetsControllerCompat.hide/show(systemBars())`. OS re-dispatches insets → `WindowInsets changed` + `Relayout Window`. Fires per controls toggle.
- **S2 nav removed from layout** — `AppNavigation.kt ~138` `navLayoutType = None` when `fullBleedVideo`. Removes the bar/rail; content slot grows → relayout. Fires on Player entry.
- **S3 root padding toggle** — `MainActivity.kt ~309-312` `if(fullBleedVideo) Modifier else systemBarsPadding()`. Re-measures whole subtree. Fires on entry/exit.
- **S5 controls AnimatedVisibility** — `PlayerScreen.kt ~627` composes/disposes OverlayContent per toggle (layer rebuild + the popup-dispose workaround at ~476).
- **S6 overlay reads LIVE insets** — `PlayerScreen.kt ~800/808-825` `statusBarsPadding/navigationBarsPadding` collapse to 0 when bars hide → re-pad; AND the tab-clearance padding flips `0 ↔ height` on `videoControlsVisible` per toggle.
- **S7 tab overlay AnimatedVisibility** — `AppNavigation.kt ~302` composes/disposes the NavigationBar/Rail subtree per toggle.
- (S4 NavHost destination compose, S8 rotate-orientation — one-time/explicit, not the flash.)

## 2. The fix (the user's layered model — both agents confirm feasible)
Make full-screen a pure VISIBILITY change; keep LAYOUT constant.
- **Video layer:** unchanged — one `VideoSurface(fillMaxSize)`, full-bleed, never inset, never recomposed/relaid out. (Already so.)
- **Controls layer:** keep composed always; drive visibility with
  `Modifier.graphicsLayer { alpha = animatedAlpha }` (lambda form = draw-phase only,
  no layout, no recomposition) instead of `AnimatedVisibility`. Reserve insets with
  the CONSTANT `WindowInsets.systemBarsIgnoringVisibility` (does NOT collapse when
  bars hide) instead of live `systemBars*` padding. Reserve tab clearance
  UNCONDITIONALLY (no `0↔height` flip). Gate pointer-input + `clearAndSetSemantics`
  when alpha≈0 so invisible controls don't eat taps / mislead screen-readers.
- **App-tabs layer:** keep the `NavigationBar`/`NavigationRail` as an always-composed
  overlay (the existing `ImmersiveVideoTabOverlay`), visibility via `graphicsLayer{alpha}`,
  NOT `AnimatedVisibility`. Do NOT swap `navLayoutType` to `None` for the content
  (that's S2) — see scope note.
- **System bars:** KEEP `WindowInsetsControllerCompat.hide(systemBars())` +
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, under the existing `enableEdgeToEdge()`.
  Once no layer reads the live (visibility-dependent) insets, hiding them moves
  nothing — visually inert.

## 3. Two scopes (entry vs per-toggle) — the honest split
- **Scope A — per-toggle flicker (tapping controls on/off on the video):** convert
  S5/S6/S7 to `graphicsLayer{alpha}` + `systemBarsIgnoringVisibility` + constant
  tab-clearance + tap/semantics gating. LOW risk, localized to `PlayerScreen.kt` +
  the tab overlay in `AppNavigation.kt`. Eliminates per-toggle relayout entirely.
- **Scope B — entry flicker ("all app tabs full refresh on switching INTO the Player"):**
  additionally remove S2 + S3 — i.e. content is full-bleed for ALL tabs and the nav
  bar/rail is ALWAYS an alpha overlay (never a layout sibling, never `None`-swapped).
  This is the part the user is complaining about. HIGHER risk / more invasive: every
  non-video tab's content must self-inset (bottom for the bar, start for the rail) so
  list items aren't hidden behind the overlay, and the back-stack + tab-selection must
  be re-verified. Both agents flag this as the genuinely invasive piece (H2).

## 4. The ONE decision the user must make
Hiding the OS system bars ALWAYS produces a window-frame relayout in the logs
(`WindowInsets changed`). Options:
- (i) Keep hiding the bars; make content immune (IgnoringVisibility) → no VISIBLE
  flash, but the log line still fires. Recommended — matches "nothing rebuilds".
- (ii) Never hide the bars (leave them transparent over the full-bleed video) → zero
  relayout even in logs, but the status/nav bars stay visible in "full-screen".
Pick (i) unless the literal requirement is "no relayout in logs".

## 5. Re-check against this session's 4-agent audit — does anything break?
- Video output is via process singletons (`VideoSurfaceBinding`/`getExoPlayer`),
  independent of composables → alpha/inset changes do NOT affect rendering, cast, or
  the surface. SAFE.
- PiP branch (MainActivity), `VideoSurfaceBinding`, freeze-frame, `videoFrameTick`:
  UNTOUCHED by this plan. SAFE.
- `navigateToPlayer` drill-in back-stack (no popUpTo): UNTOUCHED. SAFE.
- Cast: this plan doesn't touch the surface or cast gating. SAFE. (Hue-during-cast is
  by-design, unrelated.)
- Audio path: controls always visible (alpha=1 constant) → unaffected. SAFE.
- Tabletop (`TabletopVideoLayout`): controls always-visible there → alpha=1 constant;
  the alpha conversion is a no-op for tabletop. Verify the IgnoringVisibility insets
  don't shift the bottom-leaf controls. LOW risk.
- **Scope B caveat (the real risk):** turning the nav into an always-overlay app-wide
  touches the navigation backbone the audit flagged as high-blast-radius. Every tab's
  content gains self-insets; the immersive overlay + MiniPlayerBar + rail/bar selection
  + widget deep-link + drill-in back must all be re-verified on device. This is why
  Scope B is separated and gated.

## 6. Verification (when implemented)
- Device + logcat: toggling controls on the Player must produce ZERO `Relayout Window`
  / content `WindowInsets changed`-driven re-measure (Scope A); switching INTO the
  Player must not visibly reflow the tabs (Scope B). Confirm via a screen recording
  (pulled off-device, ffmpeg) AND the absence of relayout log lines.
- Regression pass (from audit): cast-video shows; Hue reacts on local playback; PiP
  enter/exit; mini-player drag/dismiss/expand; drill-in back; widget open; rotate;
  every tab's nav + insets correct.

## 7. Anti-skip
- Implement Scope A first, verify on device (logs + recording) before Scope B.
- Do NOT touch surface/PiP/binding/cast/routes.
- No code until the user approves this plan + picks decision §4.

## 8. APPROVED 2026-06-15 — Scope B (incl A), decision (i). Deep audit done (agents ab5bc6ce layout + aa0b3992 interaction). Binding implementation spec:

### Step A — controls become alpha overlays (PlayerScreen.kt)
- A1. Replace the `AnimatedVisibility(visible=controlsVisible)` around `OverlayContent` (video branch, ~:627) with `OverlayContent(modifier = Modifier.graphicsLayer { alpha = a })` where `val a by animateFloatAsState(if (controlsVisible) 1f else 0f)`. Keep `controlsVisible` as source of truth; DERIVE alpha.
- A2. Pointer + semantics gate: when `a < 0.01f`, OverlayContent root also gets a pointer-consume modifier + `clearAndSetSemantics{}` so the (now-composed) sliders/rotate/InfoIcon/popup-buttons/chips can't be tapped or seen by TalkBack. The separate tap-to-toggle layer (~:578-584, sibling BELOW OverlayContent) stays live — do NOT gate it.
- A3. Insets: in OverlayContent swap `statusBarsPadding()`/`navigationBarsPadding()` (visibility-dependent, ~:800/811/819) for the constant `WindowInsets.systemBarsIgnoringVisibility` variants so a bar-hide doesn't re-pad.
- A4. Tab-clearance (~:808-825): keep the WIDTH branch (start-rail vs bottom-bar) but reserve UNCONDITIONALLY — drop the `if(videoControlsVisible) height else 0` flip. Use `ImmersiveVideoTabBarHeight + NavigationBarDefaults.windowInsets` / `ImmersiveVideoRailWidth + NavigationRailDefaults.windowInsets`.
- A5. Keep system-bar hide (decision i). Accept PositionSection 4 Hz recompose while hidden (measure post-change; do not re-introduce compose/dispose).

### Step B — nav becomes an always-present alpha overlay app-wide
- B1. AppNavigation.kt: remove the `navLayoutType = None` branch (~:137). Keep the scaffold as a plain content host with layoutType always `None` (no built-in nav), and render the Bar/Rail via the existing `ImmersiveVideoTabOverlay` pattern GENERALISED to all routes (always composed). Its alpha = `if (fullBleedVideo && !videoControlsVisible) 0f else 1f` (visible on normal tabs + video-with-controls; hidden only on video-controls-hidden). Pointer-gate the overlay when alpha 0.
- B2. Tab clearance per screen — content is full-bleed; each screen self-insets so items aren't hidden behind the overlay. Compact → bottom `ImmersiveVideoTabBarHeight + NavigationBarDefaults.windowInsets`; Expanded → start `ImmersiveVideoRailWidth + NavigationRailDefaults.windowInsets`. Plumb width-class + a clearance PaddingValues down (or expose via the holder). Screens: Library (LazyVerticalGrid contentPadding), LastPlayed (grid contentPadding + Column start), Cloud (every lazy body contentPadding + Column start), Equalizer (convert :301 Spacer to inset-aware), Settings (convert :838/:2481 Spacer + two-pane start). Player audio branch (~:826) gains the same reservation (today only the video branch reserves).
- B3. KEEP the top status-bar inset app-wide. Change `MainActivity.kt:309-312`: instead of `systemBarsPadding() | nothing`, always apply only `statusBarsPadding()` (top) app-wide; bottom/start handled per-screen (B2). Confirms ranked-risk #1 mitigation.
- B4. MiniPlayerBar: lift out of the content `Column` into the overlay tier; sit directly ABOVE the bottom-bar overlay (Compact) / RIGHT of the rail (Expanded). Each screen reserves its 56.dp when visible.
- B5. FloatingVideoMiniPlayer: clamp bounds now include the overlay region — keep its BottomEnd resting offset clear of the bar.

### Invariants to preserve (from interaction audit)
- PlayerScreen stays a NavHost destination that disposes on tab-leave (flag reset at :526-537 stays load-bearing).
- Clearance reservation stays WIDTH-conditional; only the visibility flip is removed.
- Tabletop: controls rendered directly (alpha no-op); overlay nav alpha 0 in tabletop (unchanged). Verify IgnoringVisibility insets don't shift the bottom-leaf.
- Routes/back-stack/widget/PiP/cast/surface untouched.

### Verify (device + logcat + ffmpeg recording)
- Per-toggle: no content `Relayout`/re-measure on controls tap.
- Entry: switching INTO Player does not reflow the tabs.
- Phantom-tap test: rotate/InfoIcon/sliders/popup buttons do NOTHING when controls hidden.
- Every tab: last list items not hidden behind overlay (compact bottom + expanded start); tab highlight correct; drill-in back; widget open; fold bar↔rail; PiP; cast-video; Hue on local.
