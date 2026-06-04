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
| T227 | End of turn: push + install instrumented debug build | — | DONE(pushed 30279d8..a3e1878; user consented to data-wipe → uninstall + fresh debug install Success, lastUpdateTime 2026-06-04 18:53. NOTE: app data wiped — settings/recents/Hue pairing need re-setup; Diagnostic toggle defaults OFF, user re-enabling) |

## B — Outstanding (carried, phone now available)

| ID | Task | Phase | Status |
|----|------|-------|--------|
| T228 | [VISUAL] on-device verification batch: settings 8-group order (A4), search live-feel (B5), empty-player state, swipe→Undo restore, edge-to-edge insets (video still full-bleed), isLoading spinner during slow resume | V | BLOCKED(needs T227 install → then user exercises app) |
| T229 | Resume-hang deep diagnosis with NEW instrumentation (DiagLog RESUME/PERF + DeepLogger frames/touch): which cold-start guard skips, which parse phase dominates; ALL source permutations (local / Drive / Spotify) per test-all-permutations rule | I | BLOCKED(needs T227 install + replication run) |
| T230 | Hue disconnect→reconnect regression: on-device evidence via collector decision-snapshot + DISCONNECT logs (re-pick room/zone after Disconnect → lights unresponsive) | I | BLOCKED(needs T227 install + Hue replication) |
| T231 | Edge-to-edge Play-warning closure: confirm Android-15 inset handling on device (ties to T228) | V | BLOCKED(needs T227) |

## C — User-approved UX (2026-06-04: "I approve of idea 1… make 3-5 sub sections… all main sections expandable… search still checks all")

| ID | Task | Status |
|----|------|--------|
| T232 | Visible ⋮ menu per row (Library + LastPlayed recents/pinned) | DONE(grep 1+2 "More options for"; build green; commit in 0ba355f range) `[VISUAL]` pending |
| T233 | Hue → 4 expandable sub-sections | DONE(5 ExpandableSubsection refs; no-drop inventory 16==16) `[VISUAL]` pending |
| T237 | ALL 8 settings groups expandable; search sees collapsed; clear restores | DONE(SettingsGroupHeader + "expanded || searching" + keyed rememberSaveable; SettingsSearchTest 6/6) `[VISUAL]` pending |

## D — Plan + implementation

| ID | Task | Phase | Status |
|----|------|-------|--------|
| T234 | PLAN (12 tasks, E1-E10 evidence index) | P | DONE(`docs/superpowers/plans/2026-06-04-vc32-fixes-and-ux.md`) |
| T235 | IMPLEMENT inline | M | Tasks 1-8 DONE(GATE A: 9/9 predicates PASS, assembleDebug + all unit tests green, pushed 0ba355f, installed Success). Tasks 9-12 AWAITING-USER(device script run) |
| T238 | T1 back fix shipped: drill-in pushes (3 legit popUpTo remain: widget, navigateToLibrary, tab onClick) | M | DONE(gate T1 PASS) |
| T239 | T2 Spotify banner 45s handoff grace + 3 armed call sites | M | DONE(SpotifyBannerGraceTest 3/3) |
| T240 | T3 banner over pre-player parse — 6 wrapped paths + CloudViewModel verified-covered; resumeActive leak hardened | M | DONE(14 flag sites, was 2) |
| T248 | PLAN ADDENDUM Tasks 13-20 for T241-T247 fixes (ResumeGate, async parse+cache, overlay gating, position retention, optimistic toggle, folder UX, GATE B) | P | DONE(`docs/superpowers/plans/2026-06-04-vc32-addendum-tasks-13-20.md`, E11-E18 index, self-review §end; parent Task 10 superseded by addendum Task 14) |
| T249 | IMPLEMENT addendum Tasks 13-20 | M | AWAITING-USER(go) |

## F — New bugs reported 2026-06-04 evening device run (phase I — investigate)

| ID | Task | Phase | Status |
|----|------|-------|--------|
| T241 | Drive folder add: no visible refresh/feedback | I | DONE(located: toggleDriveFavourite*→DataStore→strip reactive but silent; fix=confirm+auto-browse, plan) |
| T242 | Favourites strip left icon misleading for folders | I | DONE(located: CloudBrowserScreen fav rows ~1438-1546; fix=Folder icon for folders, plan) |
| T243 | Spotify stale metadata on switch | I | DONE(Spotify /me/player eventual-consistency returns OLD track 1-2s after PUT /play; we display first non-null snap. Fix=hold until snap.trackUri==requested, plan) |
| T244 | Spotify position retention | I | DONE(answer: NO — 5s tick reads local player only; log shows targetPos=0ms. Fix=tick writes spotifyState.positionMs while mirror active, plan) |
| T245 | CRITICAL ghost audiobook under Spotify | I | DONE(stale 76s resume coroutine completed setMediaItems+play at 19:53:53, 25s after Spotify switch; ALSO debounce hole: per-VM counter saw 0 mid-flight. Fix=global resume generation+cancel, plan) |
| T246 | Drive resume delay phase breakdown | I | DONE(parse=75,918ms of 75,918ms: textTrack 38.0s + neroChpl re-stream 37.9s; local=19ms. Task-10 cache GATE: GO) |
| T247 | Play/pause slow to respond | I | DONE(local touch→command = 11ms — instant; slowness = Spotify-mirror icon waits on 1 Hz poll + the T245 ghost pausing the wrong stream. Fix = optimistic mirror flip, plan) |

## E — Completed (evidence archived)

- T236 (added+done 2026-06-04, protocol rule 5): empty-player guidance drew OVER
  the player chrome (user screenshot: "No media loaded" bleeding through).
  Fixed — EmptyPlayerState now a layout branch replacing the chrome, not an
  overlay. Evidence: assembleDebug green, installed (Success), commit 72de6d1.
  `[VISUAL]` re-check on device with the rest of T228.

- vc30 shipped to Play Closed testing — sent for review. ✔
- vc31 UX batch (#210-#220 + reorg/search/empty-player/undo/typography):
  ALL gate-PASS — evidence tables in
  `docs/superpowers/specs/2026-05-30-vc31-ux-implementation-checklist.md`.
  8 local commits pushed status: see T227.
