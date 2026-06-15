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
