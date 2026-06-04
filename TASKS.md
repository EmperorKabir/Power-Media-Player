# TASKS.md — binding task ledger (compaction-proof)

> ## PROTOCOL — applies to every session, every turn, forever
> 1. **Read this file at the start of EVERY turn** and again after any context
>    compaction. `CLAUDE.md` carries a pointer here, so the obligation survives
>    compaction even when conversation history does not.
> 2. A task may move to **DONE only with an Evidence line** (command output,
>    grep, log slice, test result, screenshot). Self-attestation is invalid.
> 3. Legal statuses: `TODO` · `ACTIVE` · `BLOCKED(<reason> → <exact unblock>)`
>    · `AWAITING-USER(<question>)` · `DONE(<evidence>)`. Nothing else.
>    "deferred", "later", "follow-up", "polish pass", code-comment TODOs as a
>    completion substitute — all forbidden.
> 4. Before any end-of-turn report: update every touched row here, run the
>    relevant gate (build / test / grep / log query), and report THE TABLE,
>    not prose claims.
> 5. In-scope work discovered mid-turn must be ADDED here in the same turn.
>    Silent scope-shrink is forbidden; scope changes need explicit user say-so.
> 6. Phase lock: **INVESTIGATE → PLAN → IMPLEMENT**, per task. No planning or
>    implementation while the task's phase column says INVESTIGATE, unless the
>    user advances the phase.
> 7. Genuine external blockers (user input, device action, credentials) are
>    recorded as BLOCKED/AWAITING-USER with the exact unblock — never used as
>    cover to skip automatable work around them.

Legend: phase I=investigate, P=plan, M=implement, V=verify-on-device.

## A — This prompt (2026-06-04): investigation phase

| ID | Task | Phase | Status |
|----|------|-------|--------|
| T222 | Connect phone, identify installed build, pull ALL on-device logs BEFORE anything touches the app | I | DONE(installed=vc30 22-May, NOT debug-signed; `diag/` EMPTY → diagnostic toggle was OFF; no deeplog on vc30; fallback: full logcat 330k lines → `deeplogs/logcat-2026-06-04-full.txt` + `app-slice.txt`) |
| T223 | Back-button: analyse user's back presses from logs + assess | I | DONE(6 presses found 18:05–18:32; every non-IME press → `moveTaskToBack` = whole app to launcher; all fired from Player tab because drill-in nav wipes the stack; full analysis in `docs/superpowers/specs/2026-06-04-investigation-findings.md`) |
| T224 | Back-button: app-wide code survey + guidance + recommendation | I | DONE(only 2 BackHandlers app-wide — CloudBrowser provider-exit, Library multi-select; tab taps use canonical popUpTo(start){saveState}; DEVIATION: `navigateToPlayer` drill-in also popUpTo-wipes → back cannot return to Last Played; context7 confirms drill-ins should push, not wipe) |
| T225 | Resume delay: locate user's replication + timeline | I | DONE(Drive+Cast: tap 18:05:10 FGS → playing 18:06:43.9 = **93 s**, user backed out 3× during gap; Spotify: tap 18:32:08 → Spotify audio 18:32:40 = **32 s**, backed out 2×; phase breakdown needs instrumented build → T229) |
| T226 | Metadata-warning gap: Spotify + expand to local/Drive | I | DONE(3 gaps: (1) Spotify banner killed ~1 s in — `SpotifyProvider.kt:1007` sets fetching=false on null snap during device-wake handoff; (2) Drive/local Last-Played resume NEVER sets cloudFetchInProgress — only CloudViewModel browse path does; (3) vc31 isLoading spinner starts only at ExoPlayer load — pre-player parse/SAF phase still uncovered) |
| T227 | End of turn: push + install instrumented debug build | — | push DONE(30279d8..a3e1878); install BLOCKED(INSTALL_FAILED_UPDATE_INCOMPATIBLE — installed vc30 is release-signed; replacing needs uninstall = APP-DATA WIPE → user consent. Both sessions confirmed PAUSED before attempt) |

## B — Outstanding (carried, phone now available)

| ID | Task | Phase | Status |
|----|------|-------|--------|
| T228 | [VISUAL] on-device verification batch: settings 8-group order (A4), search live-feel (B5), empty-player state, swipe→Undo restore, edge-to-edge insets (video still full-bleed), isLoading spinner during slow resume | V | BLOCKED(needs T227 install → then user exercises app) |
| T229 | Resume-hang deep diagnosis with NEW instrumentation (DiagLog RESUME/PERF + DeepLogger frames/touch): which cold-start guard skips, which parse phase dominates; ALL source permutations (local / Drive / Spotify) per test-all-permutations rule | I | BLOCKED(needs T227 install + replication run) |
| T230 | Hue disconnect→reconnect regression: on-device evidence via collector decision-snapshot + DISCONNECT logs (re-pick room/zone after Disconnect → lights unresponsive) | I | BLOCKED(needs T227 install + Hue replication) |
| T231 | Edge-to-edge Play-warning closure: confirm Android-15 inset handling on device (ties to T228) | V | BLOCKED(needs T227) |

## C — Awaiting user election (audit backlog, NOT auto-scoped)

| ID | Task | Status |
|----|------|--------|
| T232 | Long-press discoverability: visible ⋮ menu per row (Library/LastPlayed) | AWAITING-USER(want it?) |
| T233 | Hue settings progressive disclosure (collapsible Quick/Advanced/Tuning) | AWAITING-USER(want it?) |

## D — Next phases (locked until investigation reported + user advances)

| ID | Task | Phase | Status |
|----|------|-------|--------|
| T234 | PLAN: fixes for whatever T223-T226/T229-T230 evidence convicts (back behaviour, resume delay, metadata warning coverage, Hue reconnect) | P | BLOCKED(phase lock — investigation first) |
| T235 | IMPLEMENT per approved plan + on-device verify | M | BLOCKED(needs T234) |

## E — Completed (evidence archived)

- vc30 shipped to Play Closed testing — sent for review. ✔
- vc31 UX batch (#210-#220 + reorg/search/empty-player/undo/typography):
  ALL gate-PASS — evidence tables in
  `docs/superpowers/specs/2026-05-30-vc31-ux-implementation-checklist.md`.
  8 local commits pushed status: see T227.
