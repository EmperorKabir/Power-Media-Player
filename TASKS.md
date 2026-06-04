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
| T222 | Connect phone, identify installed build, pull ALL on-device logs (DiagLog `diag/log-current.txt` + any `deeplog/` sessions) to disk BEFORE anything touches the app | I | TODO |
| T223 | Back-button: analyse user's back presses from the pulled logs (what happened each press, app state at the time) + assess whether different behaviour would be better | I | TODO |
| T224 | Back-button: SEPARATE app-wide code survey (BackHandler / OnBackPressedDispatcher / predictive-back / nav pop behaviour per screen) + context7 current-Android guidance; produce behaviour map + recommendation | I | TODO |
| T225 | Resume delay: locate the user's replication of the tester's 2-3 min Last-Played resume in the logs; extract timeline evidence (which phase ate the time, how many stacked loads) | I | TODO |
| T226 | Metadata-loading warning: why did NO warning appear playing "Stealing Society" from Spotify (logs + code trace of `cloudFetchInProgress`); EXPAND delay investigation beyond Drive to local + Spotify paths | I | TODO |
| T227 | End of turn: git push origin + adb install -r fresh debug build (arms the new RESUME/PERF + DeepLogger instrumentation for the next evidence round) — AFTER T222 pull so existing logs are not disturbed | — | TODO |

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
