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
| T228 | [VISUAL] vc31 verification batch | V | folded into T257 (consolidated device pass) |
| T229 | Resume-hang deep diagnosis (phase breakdown, all permutations) | I | DONE(superseded — delivered across findings rounds 2-5: Drive=75.9s parse (2 strategies), local=19ms, Spotify=handoff; cold-start guard decisions logged via DEC lines; fixes shipped T246/T252/T253/T254) |
| T230 | Hue disconnect→reconnect + room-switch regression | I→M | DONE(T253 shipped: evidence run4 22:06-22:07 — disconnect zeroed intensity (priorIntensity=40→0) + area was never a collector source + isStreaming short-circuit ignored area changes. Fixed: intensity preserved on disconnect; area now a combine source; blank area=off; mid-stream switch restarts engine. 5/5 predicates (refined: clearHueSelectedArea clean, unpairHue zeroing intentional); pushed e70c8a7; installed Success) `[DEVICE]` re-test pending |
| T231 | Edge-to-edge Play-warning device check | V | folded into T257 |

| T257 | CONSOLIDATED on-device verification (the only outstanding work) — single user pass covering: T228 visuals (settings order+expand/collapse, search live-feel, empty-player, swipe→Undo, edge-to-edge/'transparent borders', loading banner), T232/233/237 visuals (⋮ menus, Hue sub-sections, expandable groups), T252/254 re-tests (Spotify switch clean, launch instant, taps never blocked), T253 re-test (Hue disconnect→re-pick→room switch) | V | AWAITING-USER(one device pass) |

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
| T249 | IMPLEMENT addendum Tasks 13-20 | M | Tasks 13-19 DONE(GATE B: 11/11 predicates PASS; suites ResumeGateTest 3/3 + ChapterCacheTest 3/3 + SpotifyBannerGraceTest 4/4 + SettingsSearchTest 6/6; assembleDebug green; pushed 8132182; installed Success). Task 20.4-20.5 AWAITING-USER(device re-test of the 7 complaints + Hue) |

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

## G — Round-3 device run reports (2026-06-04 late — phase I)

| ID | Task | Phase | Status |
|----|------|-------|--------|
| T250 | Spotify switch conflict | I | DONE(convicted: 6 USER toggles 21:47:31-38 ALL on local mediaId=2514d2e4 while Spotify played — playPause routes by isSpotifyActive which stays false through the handoff gap; round-2 suppression lengthened it) |
| T251 | Spotify pending-state metadata/control question | I | DONE(same gap; answer: show the REQUESTED track immediately via provisional mirror state — design in findings round 4) |

| T252 | FIX: provisional mirror state on Spotify play | M | DONE(arm+clear methods; armed at all 3 tap sites; cleared on all 3 failure paths; null-snap grace hold; 5/5 predicates PASS; all suites green 3+3+4+6; pushed bb8e195; installed Success) `[DEVICE]` re-test pending |

| T254 | Stuck 'Loading metadata' + tap lockout on launch (cold-start inline https parse; debounce swallowed taps; cache died with process) | M | DONE(4/4 predicates after grep-artefact verification; cold-start remote=cache-or-none; IGNORE→supersession; disk-backed ChapterCache; fill dedup; pushed 6708a96; installed Success) `[DEVICE]` re-test pending |
| T255 | Resume-option answer: launch restore DOES fire (recent id=23 DRIVE restored) — it was working but blocking; now instant | I | DONE(run5 DEC cold-start evaluating + restore lines) |

| T256 | Code audit: comments → plain engineering rationale (no tracker IDs / session timestamps / personal references); optimisation pass | M | DONE(tell-sweep grep returns clean; agent audit 6 findings — F1 settings-catalog remember(uiState) APPLIED, 5 micro-items assessed + declined with reasons in commit c1203cb; build+tests green; pushed; installed) |

## H — Device-run reports 2026-06-04 night (phase I)

| ID | Task | Phase | Status |
|----|------|-------|--------|
| T258 | Reverb does nothing | I | DONE(convicted: EnvironmentalReverb(0, 0) targets the GLOBAL output mix — AudioFlinger denies it on modern Android ('no permission for AUDIO_SESSION_OUTPUT_MIX', initCheck -3, 5 retries then gives up). Fix: attach to the player's audioSessionId exactly like LoudnessEnhancer in the same file, which attaches fine) |
| T259 | Volume boost does nothing | I | DONE(convicted: the ReplayGain collector resets boost to 0 whenever RG is disabled — i.e. always, by default — on every emission, amplified across multiple PlayerViewModel instances (10 identical resets in one ms logged). The LoudnessEnhancer itself attaches fine. Fix: RG-off must reset only RG attenuation, never the user's boost) |
| T260 | Local metadata delay? | I | DONE(answer: no — logs show local tap→loaded 34-140 ms, cacheHit 0 ms, READY within ~250 ms; the loading banner on local is a sub-second blip. No multi-second local event exists in the logs) |
| T261 | Resume-on-restart toggle? | I | DONE(answer: NO toggle exists — the launch restore is always-on built-in behaviour for local/Drive; 'Cold-start resume backoff' only sets the rewind amount; 'Resume on Bluetooth connect' is a different feature. Optional new toggle proposed — awaiting user) |

| T262 | In-app PiP mini-player | M | DONE(FloatingVideoMiniPlayer on non-Player tabs; reuses VideoSurface → effects + surface handoff identical to system PiP; GATE C 8/8 PASS; pushed 7809ae8; installed) `[DEVICE]` |
| T263 | Reverb fix: session-attached insert (OUTPUT_MIX denial closed) | M | DONE(GATE C; aux calls removed; rebuild-on-session-change) `[DEVICE]` audibility check |
| T264 | Boost fix: RG/user gain separation + shared effect holders across VM instances | M | DONE(GATE C) `[DEVICE]` |
| T265 | 'Restore last played on launch' toggle (default on); backoff subordinate; BT/headphone toggles independent by design | M | DONE(GATE C, 8 wiring refs) `[DEVICE]` |

| T266 | PiP-maximise black video | M | CLOSED(user: 'pip seems to be working' — ownership-stack healing) |
| T268 | Loading banner flickering during video playback/PiP | M | DONE(ExoPlayer isLoading oscillates per ~100ms chunk — banner now needs 450ms sustained loading; installed) `[DEVICE]` |
| T267 | Reverb | M | DONE v4(boost confirmed as crackle source; presets re-curved size-progressive — sqrt(1-fb), Cave ≈2.3x Room — + soft knee; diag click-free) `[DEVICE]` per-preset check |
| T269 | Boost crackle at high gain (user should never accept it) | M | DONE(LoudnessEnhancer hard-clip replaced by in-chain GainAudioProcessor with per-sample glide + 4:1 soft knee; enhancer deleted) `[DEVICE]` |
| T270 | Restore toggle 'not working' | M | DONE(evidence: 00:17:15 restore DID load the item paused but confirm-log missing → failures now logged; OFF now also clears a paused leftover surviving in the service, never touching playing audio) `[DEVICE]` test both directions |

| T271 | Player tab 'nothing playing' vs mini-bar offering resume | M | DONE(gate omitted isSpotifyActive — local player empty while the Spotify mirror was live; empty-state now respects the overlay; installed) `[DEVICE]` |
| T272 | Reverse audio | M | DONE v2(Drive enabled with 50 MB size guard — 5s-class load on decent Wi-Fi, size not duration is the honest cap; HEAD-checked pre-download; oversize → forward + snackbar; layman audio-only settings copy; ReverseAudioTest 2/2; installed) `[DEVICE]` |

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
