# docs/archive — historical docs index

Archived 2026-06-26 from `docs/superpowers/` to keep fresh-context load lean. Nothing deleted; read a file on demand. Live tasks: `/TASKS.md`. Closed task history: `docs/archive/TASKS-history.md`.

## saga writeups (1)
- `archive/2026-07-drive-picker-oauth-saga.md` — **FULL Drive picker + OAuth investigation (2026-07-22→24).** Read on ANY Drive/OAuth question. Covers: two access paths (SAF vs embedded drive.file); the correct model (drive.file DOES grant a picked folder's files; grants are per OAuth-client); the OAuth client table (do NOT delete client #3, it's live); every wrong theory + why; cold-start scaling fix; the one-time in-WebView second sign-in; why the `.test` build can't test Drive.

## audits (2)
- `docs/archive/superpowers/audits/2026-05-06-cast-controls-matrix.md` — Cast-controls matrix — what each control does WHILE casting is active
- `docs/archive/superpowers/audits/2026-05-06-pre-play-store-audit.md` — Pre-Play-Store Audit — 2026-05-06

## investigation (8)
- `docs/archive/superpowers/investigation/2026-05-05-spotify-cold-start-bounce/CHANGELOG.md` — Stop-the-world rule
- `docs/archive/superpowers/investigation/2026-05-05-spotify-cold-start-bounce/phase6-root-cause.md` — Root Cause — Spotify cold-start bounce-back failure on Samsung One UI
- `docs/archive/superpowers/investigation/2026-05-05-spotify-cold-start-bounce/phase7-fix-c-confirmed.md` — Fix-shape (c) — Confirmed Working on Samsung One UI 6
- `docs/archive/superpowers/investigation/2026-05-05-video-controls-jump/CHANGELOG.md` — Stop-the-world rule
- `docs/archive/superpowers/investigation/2026-05-05-video-controls-jump/phase2-summary.md` — Phase 2 Summary — primary probe, run 1
- `docs/archive/superpowers/investigation/2026-05-05-video-controls-jump/phase6-root-cause.md` — Root Cause — Video transport-controls "group jump" on backward scrub / skip
- `docs/archive/superpowers/investigation/2026-05-06-cast-failures-and-s25-launch/phase7-root-cause.md` — Root Cause — F1 + F2 + F3
- `docs/archive/superpowers/investigation/2026-06-24-19-item-investigation.md` — Investigation — 19-item batch (2026-06-24)

## plans (29)
- `docs/archive/superpowers/plans/2026-05-02-multi-issue-fix.md` — Multi-Issue Fix Plan (2026-05-02)
- `docs/archive/superpowers/plans/2026-05-03-last-played-and-fixes.md` — Last-Played Tab + Multi-Issue Followup (2026-05-03)
- `docs/archive/superpowers/plans/2026-05-03-multi-issue-followup.md` — Multi-Issue Follow-up Plan (2026-05-03)
- `docs/archive/superpowers/plans/2026-05-05-bt-skip-and-artwork-fit-fill.md` — BT Car-Control Skip + Artwork Fit/Fill Implementation Plan
- `docs/archive/superpowers/plans/2026-05-05-spotify-cold-start-bounce-investigation.md` — Spotify Cold-Start Bounce-Back — Investigation Plan
- `docs/archive/superpowers/plans/2026-05-05-video-controls-jump-investigation.md` — Video Controls "Jump" — Deep Investigation Plan
- `docs/archive/superpowers/plans/2026-05-06-cast-failures-and-s25-launch-crash-investigation.md` — Cast Failures + S25 Ultra Launch Crash — Investigation Plan
- `docs/archive/superpowers/plans/2026-05-06-master-plan-pre-play-store-v2.md` — Master Plan v2 — Pre-Play-Store consolidation (deeper-dive)
- `docs/archive/superpowers/plans/2026-05-06-master-plan-pre-play-store.md` — Master Plan — Pre-Play-Store consolidation
- `docs/archive/superpowers/plans/2026-05-07-info-icons-crossfade-power-features.md` — Info Icons + Crossfade DJ + Power-User Features Implementation Plan
- `docs/archive/superpowers/plans/2026-05-08-deep-audit-and-closeout.md` — 2026-05-08 — Deep audit + close-out plan
- `docs/archive/superpowers/plans/2026-05-08-pending-work-and-tests.md` — 2026-05-08 — Pending Work & Test Plan (Deep-dive after compaction)
- `docs/archive/superpowers/plans/2026-06-04-vc32-addendum-tasks-13-20.md` — vc32 plan ADDENDUM — Tasks 13-20 (round-2/3 deep-investigation fixes)
- `docs/archive/superpowers/plans/2026-06-04-vc32-fixes-and-ux.md` — vc32 — Evidence-locked fixes + approved UX (back-stack, loading coverage, ⋮ menus, expandable settings)
- `docs/archive/superpowers/plans/2026-06-11-vc33-master-plan.md` — Full audit remediation + adaptive redesign — master implementation plan
- `docs/archive/superpowers/plans/2026-06-15-cast-and-bluetooth-fixes-plan.md` — Plan — fix the cast crash + Bluetooth issues + play/pause (evidence-based)
- `docs/archive/superpowers/plans/2026-06-15-cast-local-video-and-av-offsets-plan.md` — Plan — local video while casting audio + Cast/Bluetooth A/V offset sliders (T295)
- `docs/archive/superpowers/plans/2026-06-15-player-tab-refresh-layered-fix-plan.md` — Plan — kill the "full refresh of all app tabs" on entering the video Player
- `docs/archive/superpowers/plans/2026-06-15-video-tab-return-flicker-plan.md` — Plan — video "refresh/flicker" on returning to the Player tab
- `docs/archive/superpowers/plans/2026-06-16-podcast-fixes-and-ui-overhaul-plan.md` — Podcast Bug-Fixes + UI Overhaul — Implementation Plan
- `docs/archive/superpowers/plans/2026-06-19-audiofocus-downloads-storage-spotifysearch-plan.md` — Audio-Focus + Downloads/Offline Management + Per-Source Storage + Spotify Search — Plan
- `docs/archive/superpowers/plans/2026-06-24-MASTER-INDEX-19-items.md` — Master Index — 19-item plan set (2026-06-24)
- `docs/archive/superpowers/plans/2026-06-24-eq-quality-and-live-state.md` — EQ audio-quality (#7) + live-state clobber on settings-expand (#9)
- `docs/archive/superpowers/plans/2026-06-24-folded-hitboxes-and-timer-popup.md` — Folded hitboxes (#14, #1, #15) + timer-tap numeric jump (#4)
- `docs/archive/superpowers/plans/2026-06-24-layman-settings-info-text.md` — Plan — Layman-friendly Settings + Info text (items #10 + #11)
- `docs/archive/superpowers/plans/2026-06-24-media-type-icons-and-classification.md` — Plan — media-type icons & classification (items #13, #12, #8)
- `docs/archive/superpowers/plans/2026-06-24-podcast-reorder-and-effects.md` — Plan — Podcast reorder (#5) + per-episode effects (#6)
- `docs/archive/superpowers/plans/2026-06-24-resume-star-storage-background.md` — Plan — items #19 / #18 / #3 / #17 (starred-position UX · no-resume-after-star · storage hygiene · background activity)
- `docs/archive/superpowers/plans/2026-06-24-spotify-perf-and-drive-search.md` — Plan — Spotify metadata perf (#2) + Drive metadata search (#16)

## specs (13)
- `docs/archive/superpowers/specs/2026-05-05-bt-skip-and-artwork-fit-fill-design.md` — Bluetooth Car-Control Skip + Artwork Fit/Fill — Design Spec
- `docs/archive/superpowers/specs/2026-05-30-settings-reorg-search-ux-audit-design.md` — Settings reorganisation + search + whole-app UX audit — design
- `docs/archive/superpowers/specs/2026-05-30-ux-audit-report.md` — Power Media Player — UX/UI Review Report
- `docs/archive/superpowers/specs/2026-05-30-vc31-ux-implementation-checklist.md` — vc31 UX implementation — checklist + verification gate
- `docs/archive/superpowers/specs/2026-06-04-investigation-findings.md` — Investigation findings — 2026-06-04 (Phase: INVESTIGATE only, no fixes)
- `docs/archive/superpowers/specs/2026-06-11-audit-verification-matrix.md` — Audit verification matrix — T281 (deep check, no code changes)
- `docs/archive/superpowers/specs/2026-06-11-perf-formfactor-audit.md` — Speed/efficiency + form-factor audit — 2026-06-11 (T278)
- `docs/archive/superpowers/specs/2026-06-24-background-activity.md` — 17 — background activity when the app is "closed"
- `docs/archive/superpowers/specs/2026-06-24-star-resume-ui-repro.md` — 18 — "no auto-resume after star" repro + verdict
- `docs/archive/superpowers/specs/2026-06-25-19-item-device-evidence.md` — 19-item master plan — on-device evidence pass (2026-06-25)
- `docs/archive/superpowers/specs/2026-06-25-metadata-holistic-assessment.md` — Metadata subsystem — holistic assessment (2026-06-25)
- `docs/archive/superpowers/specs/2026-06-26-audio-features-implementation-plan.md` — Audio/Podcast Features — Implementation Plan (feeds /android-build-and-device-test)
- `docs/archive/superpowers/specs/2026-06-26-resume-autoplay-design.md` — Resume & Auto-play — finer-grained controls + override-on-resume guarantee
