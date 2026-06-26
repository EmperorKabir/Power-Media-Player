# Settings reorganisation + search + whole-app UX audit — design

Date: 2026-05-30
Status: design agreed (Settings reorg + search via visual companion; UX audit delivered). Awaiting user spec review → writing-plans.

## 0. Anti-skip contract (governs implementation)

Every item below carries a **machine-checkable acceptance predicate** — not prose.
Completion is declared by an **independent final verification gate** that runs all
predicates and emits a pass/fail table, NOT by self-attestation. Purely-visual
predicates that can't be grepped are flagged `[VISUAL]` and require a screenshot
or explicit eyeball check. See memory `feedback-anti-skip-verification-gate`.

---

## Part A — Settings reorganisation

**Goal:** replace today's ~1900-line flat `Column` (19 arbitrarily-ordered
`SettingsSectionHeader`s) with 8 logical groups in an agreed order, still a single
vertical scroll (structure option A, chosen via visual companion).

### Agreed group order + membership

| # | Group | Folds in today's sections |
|---|-------|---------------------------|
| 1 | Playback | Playback, Crossfade |
| 2 | Video & subtitles | Video, Subtitles (account + format), Auto-hide controls |
| 3 | Library & cloud | Library, Cloud |
| 4 | Connectivity | Bluetooth car controls, BT video/audio offset |
| 5 | Audio | Audio effects |
| 6 | Appearance & system | Display (artwork scale), Font/hitbox size, Theme, Diagnostic logging, About/reset |
| 7 | Automation | Wake-up alarms, Webhooks, External app control |
| 8 | Lighting | Philips Hue |

### Design

- Introduce a **data-driven section model** so the same list powers both the
  render and the search filter. A `SettingsCatalog` = ordered `List<SettingsGroup>`,
  each `SettingsGroup(titleRes, items: List<SettingsItem>)`. A `SettingsItem`
  carries: stable `id`, display `title`, `keywords: List<String>` (synonyms, see
  Part B), and a `content: @Composable () -> Unit` rendering the existing control.
- Re-parent the existing composables (HueSection, WebhooksSection, the inline
  rows) under their group's `items` — minimal logic change, just relocation +
  wrapping. No behaviour change to any individual control.
- Render = iterate catalog → group header + each visible item's `content()`.

### Acceptance predicates (A)

- **A1** `SettingsScreen.kt` (or a new `SettingsCatalog.kt`) declares exactly 8
  groups whose titles, in order, are: Playback, Video & subtitles, Library & cloud,
  Connectivity, Audio, Appearance & system, Automation, Lighting.
  *Predicate:* `grep` the ordered group-title list == the 8 above.
- **A2** Every control present in today's SettingsScreen is still reachable.
  *Predicate:* a unit/inventory check enumerating item ids before/after == same set
  (no setting dropped). Build green.
- **A3** Hue + Webhooks no longer render before "Playback".
  *Predicate:* `grep` confirms Hue/Webhooks composables are invoked inside groups
  7/8, not the top inline block.
- **A4** `[VISUAL]` Settings scrolls top-to-bottom in the agreed order.
  *Predicate:* screenshot of each group header in sequence.

---

## Part B — Settings search field

**Goal:** a keyboard text field at the top of Settings that live-filters the list.
(Behaviour option A — live filter — chosen via visual companion.)

### Design

- A persistent search `TextField` pinned at the top of the Settings scroll (under
  the TopAppBar). Placeholder "Search settings…".
- **Live filter:** as the query changes, each `SettingsItem` is shown iff its
  `title` OR any of its `keywords` contains the query (case-insensitive, trimmed).
  A `SettingsGroup` header shows iff ≥1 of its items match. Empty query → full list.
- **Synonym/keyword matching:** every `SettingsItem` gets a curated `keywords`
  list so labels and common alternate terms both resolve. Examples:
  Audio effects → `eq, equaliser, equalizer, reverb, echo, bass, stereo, mono`;
  Hue → `lights, lighting, philips, bulb, colour`; Subtitles → `captions, srt`;
  Diagnostic logging → `debug, logs, battery`; Crossfade → `fade, gapless`.
- **Restore on clear/close:** clearing the field (or the clear "✕" affordance)
  immediately restores the complete, grouped list. No residual filter state.
- No-match state: show "No settings match '<query>'" rather than a blank screen.

### Acceptance predicates (B)

- **B1** Typing a query filters items by title+keywords; non-matching items and
  their now-empty group headers are removed from composition.
  *Predicate:* a Compose/unit test: filter("rev") returns only items whose
  title/keywords contain "rev" (Audio effects), and group "Audio" header present,
  others absent.
- **B2** Synonym hit: filter("equalizer") yields the Audio-effects item even though
  its visible title is "Audio effects".
  *Predicate:* unit test asserts the keyword path matches.
- **B3** Clearing the query restores the full 8-group list with identical content
  to the no-query state.
  *Predicate:* unit test: render(query="") item-id set == full catalog set.
- **B4** Empty-result query shows the no-match message, not a blank list.
  *Predicate:* unit test: filter("zzzzz") → no items + no-match node present.
- **B5** `[VISUAL]` Field is reachable, keyboard opens, filtering feels live.
  *Predicate:* screenshot/recording of typing "rev" then clearing.

---

## Part C — Whole-app UX/UI evaluation (DELIVERED)

The evaluation requested is **complete**: a 6-dimension multi-agent audit
(navigation, discoverability, consistency, accessibility, friction, settings-UX),
38 adversarially-verified findings. Full report archived at
`docs/superpowers/specs/2026-05-30-ux-audit-report.md` (sibling file).

It is a **prioritised backlog**, not auto-scoped work — the user picks what to
action. Highlights that intersect known feedback + already-queued work:

### Top-5 do-first (from the audit)

1. **Render `isLoading` in PlayerScreen** (sev-4, M) — the `isLoading` flag is set
   on resume but never rendered; this is the "2–3 min frozen app" perception. Pure
   additive UI render; plumbing already exists (`PlayerUiState.isLoading`).
   *Predicate:* `grep` PlayerScreen renders a progress node gated on
   `uiState.isLoading`; `[VISUAL]` spinner shows during a slow resume.
2. **Debounce Last-Played taps** (sev-2, S) — ships with #1; stops stacked resume
   coroutines. Directly closes the friend's "keeps loading over and over".
   *Predicate:* `grep` an in-flight guard in `playLocalAt`; on-device RESUME log
   shows `activeNow` never exceeds 1 (ties to the DiagLog instrumentation already
   added).
3. **Lighten `DisabledGrey`** to clear 3:1 contrast (sev-4, ~1-line).
   *Predicate:* computed contrast(DisabledGrey, OledBlack) ≥ 3.0.
4. **Enlarge sub-48dp touch targets** (frame-step 40→48, bookmark-delete 18→≥32,
   speed field 44→48). *Predicate:* `grep` the cited `.size/.height` == ≥ target.
5. **Confirm 'Clear all' recents** with an AlertDialog (sev-3, S).
   *Predicate:* `grep` `clearAllRecents` is now behind an AlertDialog.

### Intersections with already-queued work

- **Resume hang** (friend feedback + DiagLog instrumentation in `351f329`/`9981c2e`)
  — audit confirms root cause + that the loading-indicator render is the missing
  UX half. Pairs with the on-device evidence still to be gathered.
- **"Last thing waiting in the player"** (friend request) — audit finding
  *"Nothing waits in the player on launch"* (sev-2, L) + *"Empty player state"*
  (sev-2, M). Pre-load most-recent item into a paused player with art/title/pos;
  extend cold-start resume to cloud items.
- **Android-15 edge-to-edge** (memory `edge-to-edge-warning`) — audit finding
  *"No explicit inset handling"* (sev-2, M): `enableEdgeToEdge()` called without
  `systemBarsPadding()`/`imePadding()`. Concrete code behind the Play warning.
- **Settings UX** — audit flags Hue-section information overload (progressive
  disclosure) and notes the reorg+search (Parts A/B) as the structural fix.

---

## Sequencing

1. **Phone-test investigation** (resume hang + Hue reconnect) — already instrumented;
   awaiting on-device evidence + fixes. The audit corroborates and adds the
   loading-indicator + debounce as the UX half of the resume fix.
2. **Settings reorg + search** (Parts A/B) — phone-independent; build after (or
   alongside) the phone fixes.
3. **UX backlog** (Part C) — user picks items; each becomes a predicate-bearing
   plan entry. Top-5 are quick wins; the "waiting in player" + edge-to-edge are
   larger, sequence later.

## Risks / notes

- Part A re-parents many composables — risk of accidentally dropping a control.
  Predicate **A2** (before/after item-id inventory) is the guard.
- `LocalPmpScale` is currently consumed by zero composables (the font-scale
  Settings toggle is partly inert) — a systemic accessibility item surfaced by the
  audit; in scope only if the user elects it.
