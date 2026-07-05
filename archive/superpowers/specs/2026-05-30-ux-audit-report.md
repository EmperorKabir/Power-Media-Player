# Power Media Player — UX/UI Review Report

Generated 2026-05-30 by a 6-dimension multi-agent audit (57 agents, ~5.2M tokens):
navigation, discoverability, consistency, accessibility, friction, settings-UX.
38 findings, each adversarially verified against the actual code (several refuted
or down-ranked in verification — noted inline).

## 1. Executive Summary

The app is a feature-dense, power-user media player whose core interaction patterns
are sound but whose feedback, discoverability, and accessibility polish lag behind
its functionality. The single most damaging issue is the **Last-Played resume
hang**: tapping a Drive-backed audiobook navigates to a frozen-looking Player for up
to 2–3 minutes with zero loading feedback, while repeated frustrated taps stack
concurrent coroutines — a cluster that maps directly to known user complaints.
Beyond this, the app under-uses the recent-play data it already holds (nothing waits
in the player on launch), ships several touch targets below the 48dp accessibility
minimum, has one genuine WCAG-AA contrast failure, and leaves its rich help content
behind a small corner icon and undiscoverable long-press gestures. Most consistency
findings are real but low-value cosmetics. Edge-to-edge inset handling for Android
15 remains unaddressed (matches the open Play warning).

## 2. Findings by Severity

### Severity 4 — High

**Last-Played resume shows no loading indicator (2–3 min silent hang)**
- Screens: `PlayerScreen.kt`, `LastPlayedScreen.kt`, `PlayerViewModel.kt`
- Problem: `PlayerUiState.isLoading` is set during resume but never rendered in
  `PlayerScreen`. Tapping a multi-GB M4B navigates immediately to Player, then
  blocks 2–3 min on `M4bChapterParser` IO with no spinner/banner/progress — looks
  frozen/broken.
- Fix: Render a `CircularProgressIndicator` + "Loading media…" banner when
  `uiState.isLoading`, mirroring the existing `cloudFetchInProgress` banner
  (`PlayerScreen.kt:160-189`). Add tap debouncing (paired finding). Effort M.

**Disabled controls fail WCAG AA contrast on OLED black**
- Screens: `PlaybackControls.kt`, `ProgressSliders.kt`, `SecondaryControls.kt`
- Problem: `DisabledGrey (0xFF555555)` on `OledBlack (0xFF000000)` = **2.82:1** —
  fails the 3:1 AA minimum (original "~7.5:1" figure was wrong, corrected in
  verification). Disabled controls near-invisible. Used as the Material3 `outline`
  token → propagates widely.
- Fix: Lighten `DisabledGrey` to ≥`0xFF6E6E6E`. Effort M (mostly one-line).

### Severity 3 — Medium

- **Frame-step ± buttons are 40dp** (`PlayerScreen.kt` 593/616/847/870; bookmark
  621/875) — below 48dp. Fix `Modifier.size(48.dp)`. Effort S.
- **Bookmark-delete X is 18dp** (`PlayerScreen.kt` 684-690/914-920) — 62% below
  min. Expand to ≥32dp or move to long-press menu. Effort S.
- **Info-icon target ignores font-scale** (`InfoIcon.kt:42`, systemic) —
  `LocalPmpScale` is consumed by ZERO composables; the Settings "scale touch
  targets" claim is inert. Consume `LocalPmpScale.current` or drop the claim.
  Effort S/component, M complete.
- **'Clear all' recents has no confirmation** (`LastPlayedScreen.kt:412`) — one tap
  wipes all history + cascade-deletes session bookmarks, no undo. Gate behind
  AlertDialog. Effort S.
- **Long-press context menu undiscoverable** (Library + Last Played rows) — 15+
  actions behind an unhinted long-press. Add a three-dot per-row menu or first-run
  hint. Effort M.
- **Hue settings information overload** (`SettingsScreen.kt ~1502-2009`) — 500+
  lines of dense help text. Progressive disclosure: collapsible Quick Setup /
  Advanced / Tuning. (Original "sev-5" overstated.) Effort M.

### Severity 2 — Low-Medium

- **Repeated Last-Played taps stack resume coroutines** (`LastPlayedViewModel`) —
  `playLocalAt()` un-debounced. Already instrumented via `resumeActive`/
  `resumeAttempts`; gate it. Effort S.
- **Nothing waits in the player on launch/after pause** (`MainActivity`,
  `PlayerScreen`, `PlayerViewModel`) — matches the user request. `MiniPlayerBar`
  hides when `title=="No media loaded"`; cold-start resume only restores present
  local files. Pre-load most-recent into a paused player. Effort L.
- **Empty player state gives no guidance** (`PlayerScreen.kt`) — raw "No media
  loaded" text, no icon/actions. Add icon + "Open Library"/"Pick a file". Effort M.
- **Sliders fixed 28dp; don't scale** (`ProgressSliders.kt:135`,
  `SecondaryControls.kt:62,107`) — same `LocalPmpScale`-unused root cause. Effort M.
- **Speed dropdown 44dp** (`SecondaryControls.kt:197,293`) — 4dp under min. Effort S.
- **No Android-15 inset handling** (`MainActivity.kt`) — `enableEdgeToEdge()` with
  no `systemBarsPadding()`/`imePadding()`; bottom controls may collide with the
  back-gesture zone. Matches the open Play warning. Effort M.
- **Swipe/inline delete has no undo** (`LastPlayedScreen.kt` 221-222/737-744) — add
  a 5s "Deleted — Undo?" snackbar. Effort M.
- **CloudBrowser icons missing contentDescription** (`CloudBrowserScreen.kt`) — ~4-5
  state/action icons leave TalkBack users without context. Effort M (S if scoped).
- **Navigation to Player precedes media readiness** — RESOLVED by the sev-4 loading
  indicator; no separate work.
- **Settings audio-sync controls scattered** — low value; existing cross-refs
  mitigate. At most add one cross-link. Skip full reorg.
- **Settings bottom spacer 80dp may crowd Reset** (`SettingsScreen.kt:639`) — raise
  to 96dp or derive from nav-bar insets; folds into the inset fix. Effort S.
- Lower-value sev-2s: widget deep-links only target Player; no deep-link to
  non-Player tabs (dup); tab scroll position not `rememberSaveable`; MiniPlayerBar
  full-surface tap nav; Cloud provider nav not in back stack (intentional);
  Library multi-select back leaves Info sheet open.

### Severity 1 — Trivial / Polish

Audio-effects "on" state subtle (60% opacity, intentional); info icon small/corner;
crossfade button unlabelled (standard for compact); multi-select buried in
three-dot; dialog Cancel-button colour inconsistent (TealAccent vs grey —
standardise on grey); dialog title typography inconsistent (standardise
`titleLarge`); Settings row padding 8 vs 12dp; subtitle-delay slider lacks range
hint; reverb presets RadioButton vs chips (cosmetic).

### Reclassified to ~0 — do NOT action

- **"Primary-button inconsistency"** — recommendation was BACKWARDS. The single
  `Button()` in `MediaOverridesPopup.kt:137` is the outlier; Material3 correctly
  uses `TextButton` for AlertDialog actions everywhere else. Fix that one to
  `TextButton`; do NOT convert dialogs to FilledButton.
- Cloud-tab back-exit feedback / modal-sheet BackHandler docs / no breadcrumbs —
  correct Material3 behaviour already. Skip or comment only.

## 3. Top-5 Do-First (impact vs effort)

1. Render `isLoading` in PlayerScreen (sev-4, M) — kills the "frozen app"
   perception; also resolves "nav precedes readiness" for free.
2. Debounce Last-Played taps (sev-2, S) — ship with #1.
3. Lighten `DisabledGrey` to clear 3:1 (sev-4, ~1 line).
4. Enlarge sub-48dp touch targets (sev-3 ×2 + sev-2, S each) — batch as one a11y pass.
5. Confirm 'Clear all' recents (sev-3, S).

Honourable mention (L): pre-load last-played into a paused player on launch —
fulfils the user's stated request; sequence after the quick wins.

## 4. Cross-Reference to Known User Feedback

- **2–3 min resume hang:** cluster of 3 findings — no loading indicator (sev-4,
  core fix), taps stack (sev-2, debounce), nav precedes readiness (resolved by
  rendering `isLoading`). The `isLoading` plumbing already exists end-to-end
  (`PlayerViewModel:1785`) → additive render, evidence-locked, not speculative.
- **"Last thing waiting in the player":** nothing-waits (sev-2, L) + empty-player
  (sev-2, M). Cold-start resume only restores present local files, so cloud/
  audiobook users get nothing. Pre-load most-recent paused item with art/title/pos.
- **Android-15 edge-to-edge warning:** no-inset-handling (sev-2, M) + bottom-spacer
  (sev-2, S). `enableEdgeToEdge()` with no inset padding is the concrete code behind
  the Play warning; address-before-Production.
