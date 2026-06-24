# Master Index — 19-item plan set (2026-06-24)

Spec: `docs/superpowers/investigation/2026-06-24-19-item-investigation.md`. Seven subsystem plans, written per the writing-plans methodology. Execution will be **INLINE** (superpowers:executing-plans, sequential, this session) to minimise error + conflict risk — NOT subagent worktrees.

## The 7 plans
| # | Plan file | Items | Lines |
|---|---|---|---|
| P1 | `2026-06-24-folded-hitboxes-and-timer-popup.md` | #1, #14, #15, #4 | ~600 |
| P2 | `2026-06-24-eq-quality-and-live-state.md` | #7, #9 | 547 |
| P3 | `2026-06-24-spotify-perf-and-drive-search.md` | #2, #16 | 615 |
| P4 | `2026-06-24-media-type-icons-and-classification.md` | #13, #12, #8 | ~810 |
| P5 | `2026-06-24-podcast-reorder-and-effects.md` | #5, #6 | 796 |
| P6 | `2026-06-24-resume-star-storage-background.md` | #19, #18, #3, #17 | 738 |
| P7 | `2026-06-24-layman-settings-info-text.md` | #10, #11 | 788 |

---

## Coverage matrix — every item + every multi-part sub-question

Legend: **PLAN** = a plan task implements it · **ANS** = answered in the investigation (informational ask) · **VERIFY** = device-confirmation task.

| # | Sub-question | Where | Type |
|---|---|---|---|
| 1 | Brightness permission hitbox folded | P1 Task 2 | PLAN |
| 2 | Spotify metadata slow (Slipknot album) | P3 Tasks A1–A5 | PLAN |
| 3a | How is metadata stored? | Inv #3 (full store table) | ANS |
| 3b | App cache? | Inv #3 (ArtworkCache/ChapterCache = cacheDir) | ANS |
| 3c | Offline (durable)? | Inv #3 (DB + filesDir/offline) | ANS |
| 3d | Storage figures (e.g. a Spotify album) | Inv #3 (code-grounded; release build blocks `run-as` so DB bytes not device-measured — flagged) | ANS (caveat) |
| 3e | Add → remove → re-add, each stage | Inv #3 (lifecycle) + **P6** cache-orphan hygiene (the actionable gap: remove orphans ArtworkCache/ChapterCache) | ANS + PLAN |
| 4 | Timer-tap → numeric jump (elapsed vs remaining) + hitbox folded/unfolded | P1 Tasks 3–5 | PLAN |
| 5 | Reorder saved podcasts like favourites + similar visuals | P5 Tasks A1–A8 | PLAN |
| 6 | Effects on podcasts like favourites + similar visuals | P5 Tasks B1–B4 | PLAN |
| 7 | EQ robotic/low-bitrate | P2 Tasks A1–A4 | PLAN |
| 8a | BT mappings split music/audiobook + chapters-loading logic | Inv #8 (BT global; chapters=hasChapters) + P4 | ANS + PLAN |
| 8b | Failure probability podcast/audiobook/song distinction | Inv #8 + P4 `MediaClassifier` | ANS + PLAN |
| 8c | What risks? | Inv #8 (BT risk≈0; sub-kind ambiguity) | ANS |
| 9 | EQ shifted when expanding Audio settings | P2 Tasks B1–B5 | PLAN |
| 10 | Webhooks not layman-friendly | P7 Task 1 (+ stale placeholder) | PLAN |
| 11 | Full layman review of info + settings | P7 (per-section tasks + coverage table) | PLAN |
| 12a | Video snapshot icon feasible? | P4 Tasks 4.1–4.5 (Library-only) | PLAN |
| 12b | All video types? | P4 §Design D1 + Task 4.3 (MediaStore.Video + coil-video) | PLAN |
| 12c | False audio file trying to show a still? | P4 Task 4.3 (gate on `isVideo`) + §Design D4 | PLAN |
| 13a | Camera icon: file-type or metadata? | Inv #13 (file-type / Drive container mime) | ANS |
| 13b | Explore a fix | P4 Tasks 1.1–2.2 (extension-authoritative `MediaClassifier`) | PLAN |
| 13c | High risk of not telling? | P4 §Design D4 (added) | ANS |
| 13d | Would an imperceptible probe help? | **P4 §Design D4 (added this verification)** — no: extension authoritative for m4b, remote `.mp4` probe = 38–76s (not imperceptible), local = MediaStore already knows | ANS |
| 14a | Info box hitbox folded | P1 Task 1 | PLAN |
| 14b | Hitbox review across the app | **P1 §App-wide sweep (added)** + Inv sweep (no width-derived gestures; only the 3 fixes actionable) + P1 Task 7 cross-screen verify | ANS + VERIFY |
| 14c | Don't touch EQ hitboxes; flag | P1 (EQ flag-only, EqualizerScreen.kt:83-92) | HONOURED |
| 15a | Black box above mini-player folded | P1 Task 6 | PLAN |
| 15b | General display-robustness review | **P1 §App-wide sweep (added)** | ANS |
| 16a | Drive search searches what? | Inv #16 (filename-only) | ANS |
| 16b | Expand to all metadata? | P3 Tasks B1–B8 (DB enriched-metadata search) | PLAN |
| 16c | Would that be slow? | P3 §Design D4/D5 (indexed LIKE = fast; whole-Drive blocked by drive.file scope) | ANS |
| 16d | Background enrich on favourite? | P3 Part C C1–C5 (**CORE, always-on, unconditional** — per user directive; dedup + offline reuse + non-blocking) | PLAN |
| 16e | Would the background process feel like a freeze? | Inv #16 (IO scope, no UI freeze, but heavy download) + P3 C1 caveat | ANS |
| 17 | Background activity when closed | Inv #17 + **P6 §17** (instrument all 5 vectors + prove termination on device trace, hard-stop safeguard, UNCONDITIONAL transparency note) | PLAN |
| 18 | No auto-resume after star+close+reopen | Inv #18 + **P6 §18** (warm-reopen surfacing fix ships UNCONDITIONALLY; 18.3b audio-resume if trace shows stopped; device repro confirms) | PLAN + VERIFY |
| 19a | Dynamic-update-or-not as an option | P6 (`followLive` flag) | PLAN |
| 19b | Some fixed, some progress | P6 (`followLive` flag) | PLAN |
| 19c | User-friendly strong UX for both | P6 (star dialog) | PLAN |
| 19d | Popup when starring | P6 (dialog on star tap) | PLAN |

**Result: all 19 items and every multi-part sub-question are accounted for** — implemented by a plan task, or answered in the investigation (the purely informational asks: #3 figures, #8 risk, #13a/c/d, #14b/15b sweep, #16a/c/e). Per the no-downgrade directive, **#16d, #17, and #18 were upgraded from optional/doc/verify-only to full shipping fixes** (P3 Part C always-on; P6 §17 instrument-prove-harden-note; P6 §18 unconditional surfacing fix). #18 + #14b + #17 additionally carry a device-verify leg. No gaps; nothing parked as "good enough".

---

## Cross-plan conflict analysis (mandatory for inline execution)

### ⚠️ CONFLICT 1 — three plans each add a `MIGRATION_18_19` (the biggest risk)
- **P3** Task B4: `v18→v19` covering-index for the search LIKE.
- **P5** Task A4: `v18→v19` adds `PodcastShowEntity.displayOrder`.
- **P6**: `v18→v19` adds `HistoryFavouriteEntity.followLive`.

All three are written as `v18→v19` in isolation. They CANNOT all be v19. **Resolution for sequential inline execution:** the migration-bearing plans claim consecutive versions in execution order — the first claims `MIGRATION_18_19`, the second `MIGRATION_19_20`, the third `MIGRATION_20_21`; bump `@Database(version=…)` once per plan. The executor renumbers the 2nd/3rd plan's migration constant + version at execution time (the migration body is unchanged — additive `ALTER … ADD COLUMN` / `CREATE INDEX`). Per the order below: **P3=18→19, P5=19→20, P6=20→21** (final DB version 21). (Alternative: fold all three schema changes into ONE `v18→v19` migration done first — rejected: decouples each migration from its plan + complicates the schema-free Robolectric tests each plan writes.)

### CONFLICT 2 — `CloudViewModel.kt`
- P3 (search): inject `playbackHistoryDao`/`enrichmentCacheDao`, add merge into `setSearchQuery`.
- P4 (icon #13): may extract the `audioExts` set (CloudViewModel.kt:1458-66) into the shared `MediaClassifier`.
- **Resolution:** run **P3 before P4**; P4's classifier extraction then refactors the (already-search-wired) file. Different regions; sequential = no merge conflict.

### CONFLICT 3 — `PlayerViewModel.kt`
- P2 (EQ #9): EQ override region (~230–242, 456–481).
- P4 (#8): `inferMediaKind` (1631–1637).
- **Resolution:** disjoint regions; sequential order (P2 before P4) avoids any clash.

### CONFLICT 4 — `HeadphoneEqSection.kt`
- P2 (EQ #9): add `.drop(1)` / route restore through the guard.
- P7 (layman): rewrite the section's strings.
- **Resolution:** run **P2 before P7**; P7's string rewrite is the last touch. Disjoint (logic vs strings).

No other shared-file overlaps (P1 owns the player-UI/nav files; P5 owns PodcastsSection; P6 owns LastPlayed* + caches; P7 owns InfoContent + settings strings).

---

## Recommended INLINE execution order (minimises risk + conflict)

Sequential, build + unit-tests + device-verify + commit per task (per each plan's own gate). Order chosen so migrations are claimed in sequence and shared files are touched logic-first, strings-last:

1. **P2 — EQ (#7, #9).** Isolated audio DSP + controller; high-value audible bug; JVM-unit-tested. No migration.
2. **P1 — folded hitboxes + timer (#1, #14, #15, #4).** Isolated player-UI/nav files; high-value broken hitboxes. No migration.
3. **P3 — Spotify perf + Drive search (#2, #16).** → claims **`MIGRATION_18_19`** (search index). Touches CloudViewModel first.
4. **P4 — media-type icons + classifier (#13, #12, #8).** After P3 (CloudViewModel) + P2 (PlayerViewModel). No migration. (New dep: `coil-video:3.1.0`.)
5. **P5 — podcast reorder + effects (#5, #6).** → renumber its migration to **`MIGRATION_19_20`** (`displayOrder`), version 19→20.
6. **P6 — resume/star + storage + background (#19, #18, #3, #17).** → renumber its migration to **`MIGRATION_20_21`** (`followLive`), version 20→21. Final DB version = 21.
7. **P7 — layman settings/info text (#10, #11).** LAST — pure strings, lowest risk, touches the most files but only text; runs after P2's HeadphoneEqSection logic change.

Each plan's `## Design decisions (confirm before execution)` section MUST be confirmed by the user before that plan runs (≈35 decisions total across the 7; the consequential ones: EQ #7 approach, #4 dialog semantics, #16 metadata-search always-on, #12 Library-only thumbnails, #8 ship-classifier-now, #19 default=Fixed, ArtworkCache 32MB cap, the 3-way migration renumbering above).

---

## Self-review (writing-plans step, done inline)
- **Spec coverage:** every spec item + multi-part sub-question maps to a task or an answered finding (matrix above). Two gaps found + closed inline (#13d probe → P4 §D4; #14b/15b sweep → P1 §App-wide-sweep).
- **Placeholder scan:** the plans use complete code in code-steps + concrete device-verify steps; the agents reported no `TODO`/`TBD`/"add error handling" placeholders. (Spot-confirm during inline execution.)
- **Type/version consistency:** package corrected to `com.powermediaplayer` in all plans; shared symbol names consistent (`MediaClassifier.classifyMedia`/`isVideoByName`, `EqualizerEffectController` guard, `DriveMetadataSearch.mergeDriveResults`, `StarPositionResolver`, `ReorderableShowList`). Cross-plan: the migration version numbers are the ONLY inconsistency, resolved above.
- **Test-infra constraint (carried across plans):** repo has `exportSchema=false` + no `room-testing`/`androidTest` → Room verified via schema-free Robolectric in-memory tests, not `MigrationTestHelper`. JVM-DSP via the existing `ReverbAudioProcessorTest` harness. UI/folded via device steps.
- **Device caveat:** the phone holds a RELEASE-signed 1.3.4/38 build → a debug install may need an uninstall (data wipe) for the device-verify legs; an AWAITING-USER blocker for verification only, not for the code.

This is planning only. No application code changed; the only edits are the plan docs, this index, and the TASKS.md ledger rows (T347–T353).
