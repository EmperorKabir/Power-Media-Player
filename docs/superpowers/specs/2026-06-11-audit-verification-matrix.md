# Audit verification matrix — T281 (deep check, no code changes)

Scope: every finding in `2026-06-11-perf-formfactor-audit.md`, the T279 fix,
and the §8 discussion-item premises. Verification levels:

- **[mech]** citation mechanically verified (file exists, line in range, cited
  code present at the cited lines)
- **[read]** semantics re-verified by direct read this session
- **[dual]** two audit agents independently reported the same finding with
  matching line citations
- **[c7]** API behaviour cross-checked against current docs via Context7
- **[device]** reproduced/verified on a running build

Verdicts: CONFIRMED / AMENDED(<correction>) / WITHDRAWN(<reason>).

## 0 — Mechanical pass (2026-06-11)

`pmp_verify_citations.py` over the audit doc: **101 unique path:line citations
checked — 101 OK, 0 problems** (file-not-found 0, out-of-range 0; line text at
each citation matches the claimed code). Note: PlayerViewModel.kt cited ranges
below :860 carry ±2 line drift after the T279 fix edits; snippets still match.

## 1 — Section 1 (structural defects) — verdicts

| Finding | Levels | Verdict |
|---|---|---|
| 1.1 audio focus never abandoned | mech+read (grep: zero `abandonAudioFocus` app-wide; request at PlaybackService.kt:1440/1443) | CONFIRMED |
| 1.2 Cast listener never removed | mech+read (grep: zero `removeSessionManagerListener`; add at :1333) | CONFIRMED |
| 1.3 sender caches unbounded | mech+read (grep: zero remove/clear on either map) | CONFIRMED |
| 1.4 Hue DTLS cleanup skipped on send-failure | mech+read (read :420-540: `cancel()` at :443; close/stop-PUT at :478-480 not in finally; `connectDtls` assigns socket at :508 before handshake at :530) | CONFIRMED |
| 1.5 onDestroy doesn't stop Hue stream | mech (stop() called only at :1152/:1247 in collector; engine scope is its own) | CONFIRMED |
| 1.6 MMR release not in finally | mech+read (PlayerViewModel:930-943) | CONFIRMED |

## T279 fix — verdicts

| Claim | Levels | Verdict |
|---|---|---|
| Mechanism: MediaController drops seekTo while restore item still BUFFERING (command-availability gating on COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) | device (emulator diag: seekTo issued during BUFFERING, zero SEEK discontinuities, READY at 0) + c7 (Media3 docs: per-command availability model governs controller calls; 1.10.1 release note fixes a COMMAND_SEEK_TO_MEDIA_ITEM availability bug in MediaController — gating is real and active; Player javadoc: methods "must only be called if COMMAND_X is available") | CONFIRMED |
| Fix: startPositionMs inside setMediaItems is the canonical atomic resume API | c7 (`setMediaItems(mediaItems, startIndex, startPositionMs)` documented; framework's own onPlaybackResumption uses MediaItemsWithStartPosition — same atomic shape) + device (same cycle restored position=5772 exactly = saved 10772 − 5s backoff; was 0 pre-fix) | CONFIRMED |
| All 3 set-then-seek sites covered (cold-start restore / LastPlayed tap-resume / reverse flip) | read (grep: zero seekTo-after-setMediaItems remain in ui/) + device (tap-resume: tapped the @0:10 recents row → PLAYING at position=10772 EXACTLY — raw saved position, atomic; cold-start verified earlier at 5772; reverse flip = identical one-line pattern, statically verified) | CONFIRMED |

## Context7 cross-check verdicts (batches 2-7, 2026-06-11)

| Topic | Result | Verdict |
|---|---|---|
| 8.2 fold posture | `WindowInfoTracker.windowLayoutInfo(activity): Flow<WindowLayoutInfo>` is current; BETTER: `androidx.compose.material3.adaptive` ships `Posture` (`isTabletop`, hinge-bounds helpers: all/occluding/separating × H/V) — Compose-native wrapper over androidx.window. Plan uses material3-adaptive's posture, not hand-rolled WindowInfoTracker | CONFIRMED + route improved |
| 8.1 adaptive stack | `NavigationSuiteScaffold` in `androidx.compose.material3:material3-adaptive-navigation-suite` (NavigationSuiteScope.item, layoutType); pane scaffolds in `androidx.compose.material3.adaptive:adaptive`/`adaptive-layout`/`adaptive-navigation` (Posture API seen at 1.2.0-beta01; pick latest stable at implementation). Plain `NavigationRail` also available for manual control | CONFIRMED |
| 6.5 edge-to-edge | `enableEdgeToEdge(statusBarStyle, navigationBarStyle)`; default `SystemBarStyle.auto` keys icon appearance off the SYSTEM/resource dark mode (detectDarkMode), not the app's forced-dark Compose theme, and overrides the theme attr → dark icons on black for system-light users. Fix = explicit `SystemBarStyle.dark(TRANSPARENT)` both bars | CONFIRMED |
| 6.4/8.3 immersive | `WindowInsetsControllerCompat.hide(systemBars()) + BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` is the supported immersive route | CONFIRMED |
| 8.3 rotate button | `setRequestedOrientation` requires NO permission and overrides the user's auto-rotate quick-setting on phones (user's "ask for permission" concern: not needed — WRITE_SETTINGS would only be for changing the GLOBAL auto-rotate setting, which we don't do). CAVEAT: Android 12L+ large-screen devices may IGNORE orientation requests (documented `OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION` + letterbox workarounds; manifest opt-out property exists) → plan: force-rotate only on compact widths; expanded widths go immersive without forcing | CONFIRMED with policy caveat |
| 5.11 AppAuth | Docs verbatim: AuthorizationService "must be disposed when no longer needed to prevent Custom Tab connection leaks. Instantiate per-Activity and dispose in onStop()/onDestroy()". Current code: process-lifetime lazy singleton, zero dispose | CONFIRMED (documented contract violated) |
| 8.6 baseline profiles | androidx.benchmark/baselineprofile plugin + profileinstaller current; generation via test module + `androidx.benchmark.enabledRules=BaselineProfile`; plan pins plugin + module + CI/local generation step | CONFIRMED |
| T279 (batch 1) | recorded above | CONFIRMED |

## Sections 2-7 — verdicts (mech = all citations passed the script; read = direct re-read this session; dual = two agents independently, matching citations)

| Finding | Levels | Verdict |
|---|---|---|
| 2.1 runBlocking cluster in service onCreate | mech+dual (A10+C5) | CONFIRMED |
| 2.2 onTaskRemoved runBlocking no timeout | mech+dual | CONFIRMED |
| 2.3 App.onCreate blocking first DataStore read | mech+read (session-start read of PowerMediaPlayerApp) | CONFIRMED |
| 2.4 no baseline profile | read (build.gradle.kts full read) + c7 | CONFIRMED |
| 2.5 Theme collects 75-field uiState via collectAsState | mech+read (Theme.kt:70-98) | CONFIRMED |
| 3.1 PlayerViewModel multiplication | mech+dual (A1+B4) + read (init blocks 560-900) | CONFIRMED |
| 3.2 mapToUiState per-tick chapter renormalisation | mech+dual (A2+B3) | CONFIRMED |
| 3.3 position tick recomposes whole player tree; :577-583 comment wrong | mech+read (PlayerScreen 570-700: OverlayContent takes whole uiState; TrackInfoSection/ChapterPickerChip(uiState)) | CONFIRMED |
| 3.4 Hue FFT ungated + per-sample Hann cos + per-frame allocs | mech+read (HueAnalyserAudioProcessor 130-265: no gate in queueInput; cos at :222; TimedSnapshot+2×copyOf at :243-254; hypot :233; twiddles precomputed, window not) | CONFIRMED |
| 3.5 crossfade 10 Hz forever-ticker | mech (A6; start at :1568-1574 unconditional) | CONFIRMED |
| 3.6 duplicate RG pipelines, direct player.volume | mech+read (PlayerViewModel 265-312 + 899-966 read this session) | CONFIRMED |
| 3.7 500ms poller never pauses; doc says 250ms | mech+read (PlaybackConnection 850-868: no isPlaying gate; comment :851 says 250ms, delay(500)) | CONFIRMED |
| 3.8 Spotify poll no background stop; per-iteration token deserialise | mech+read (SpotifyProvider 1029-1131: while(isActive) delay(400/1000), currentAccessToken() per iter, no lifecycle hook) + dual (C3+E12) | CONFIRMED |
| 3.9 Cast REQUEST_DISCOVERY for button lifetime | mech (snippet shows the exact flag at CastSwitcher.kt:111) | CONFIRMED |
| 3.10 widget full-res decodes on Main per event | mech+read (NowPlayingWidgetProvider 78-132) | AMENDED: 2 decodes per refresh (wide+large; compact variant skips art — hasText=false), not 3. Impact unchanged (full-res, Main thread, per play/pause/metadata event) |
| 3.11 per-tick micro-churn (6 collectors, volume IPC, A2DP poll, Teal getters, processor bypass copies) | mech+read (volume IPC seen at PlayerScreen :685-686; rest mech+dual) | CONFIRMED |
| 4.1 library search undebounced, on Main, Collator per comparison | mech+read (LibraryViewModel 248-430 + TextNormalizer full: setSearchQuery→recomputeDisplayed sync; collator() = Collator.getInstance per compare() call in NAME/TYPE/FAVOURITES comparators) | CONFIRMED |
| 4.2 settings catalogue reallocation | mech+read (SettingsScreen :141-145 remember(uiState) over full literal) | AMENDED: per settings WRITE (incl. slider drags), not per search keystroke — search query is screen-local rememberSaveable. Impact claim (font-scale drag churn) stands |
| 4.3 Palette generated twice on Main | mech (PaletteHelper :46-57 snippet shows generate(); call sites cited) + dual(B7+E-adjacent) | CONFIRMED |
| 4.4 full-res decode for 40dp mini-bar | mech+dual (B8+E7) | CONFIRMED |
| 4.5 per-row linear scans + snapshot-invisible read | mech (B14/B15) | CONFIRMED |
| 4.6 LastPlayed collectAsState + per-row observers + flowOf(Unit) stale count | mech (B13 + C14) | CONFIRMED |
| 4.7 cloud forceRefresh on every ON_RESUME | mech+read (CloudBrowserScreen 85-95) | CONFIRMED |
| 5.1 isRemote misses content:// | mech+read (M4bChapterParser :59-60 read; PlayerViewModel :837-846 read) | CONFIRMED |
| 5.2 DocumentFile N+1 | mech (snippet shows listFiles().mapNotNull→toCloudItem) | CONFIRMED |
| 5.3 OkHttp fragmentation/no cache/no callTimeout | mech+dual (C11) | CONFIRMED |
| 5.4 alarm ring blocks Main | mech+read (FullScreenAlarmActivity 80-174: scope=Main; startRinging on Main does resolveAlarmMediaUri (internal runBlocking Room + MediaStore scan on smart-playlist branch) + synchronous MediaPlayer.prepare() :163 for ALL alarms) | CONFIRMED (refined: prepare() always on Main; Room runBlocking + 2000-row scan only on smart-playlist alarms) |
| 5.5 podcast CONNECTED not UNMETERED, sequential, blocking on Default | mech (C8 snippet) | CONFIRMED |
| 5.6 DataStore write amplification | mech+dual (C7; collector counts) | CONFIRMED |
| 5.7 Hue CLIP per-call DataStore reads + serial PUTs | mech (C10) | CONFIRMED |
| 5.8 ReplayGainScanner unbatched writes + reflection + remote MMR | mech (C9) | CONFIRMED |
| 5.9 receivers lack goAsync | mech+dual (C12+E14) + read (TaskerReceiver full read this session) | CONFIRMED |
| 5.10 CastRelay map race + stream leak + skipFully | mech+dual (A11+E6) | CONFIRMED |
| 5.11 misc (ChapterCache lock IO, markFilling strand, drive_* caches, DiagLog channel, AppAuth dispose, WebView destroy-attached, CrossfadeController scope) | mech+dual + c7 (AppAuth: documented contract verbatim) | CONFIRMED |
| 6.1 WindowSizeClass only consumed by PlayerScreen | mech+dual (D1; grep zero LazyVerticalGrid) | CONFIRMED |
| 6.2 widget deep-link re-fires on recreation; pendingOpenTab never cleared | read (MainActivity full read session-start: writes only at :96/:218, zero clears; comment :212 claims otherwise) | CONFIRMED |
| 6.3 isInPip not seeded in onCreate | read (MainActivity :70, :227) | CONFIRMED |
| 6.4 video letterboxed by global systemBarsPadding; no immersive; cutout unhandled | read (MainActivity :190-193) + mech (grep zero hide()/safeDrawing) + c7 (WindowInsetsControllerCompat is the supported route) | CONFIRMED |
| 6.5 status-bar icons on system-light devices | read (MainActivity :97 bare enableEdgeToEdge) + c7 (SystemBarStyle.auto follows system dark mode, overrides theme attr) | CONFIRMED |
| 6.6 IME insets nowhere | mech (grep zero imePadding) | CONFIRMED |
| 6.7 alarm activity no configChanges → ring restarts on rotation | mech (manifest read session-start: no configChanges on FullScreenAlarmActivity) + read (onDestroy→stopRinging; onCreate→startRinging) | CONFIRMED |
| 6.8 compact-height overflow (video overlay non-scrolling; Library header stack; LastPlayed pinned forEach; 540dp sheet) | mech+read (scrollMod = Modifier when isVideoContent at PlayerScreen :614-618) | CONFIRMED |
| 6.9 transport row ≥344dp fixed | mech (D9 arithmetic from cited sizes) | CONFIRMED |
| 6.10 font-scale fix landed in unused SpeedControl; PreparedSpeedComponent keeps width(110.dp); 4× compounding | mech (D10: both components cited; PlayerScreen calls PreparedSpeedComponent at :664) + read (Theme.kt :79-86 multiplier confirmed) | CONFIRMED |
| 6.11 floating mini-player drag unclamped | mech (D11) | CONFIRMED |
| 6.12 PiP no sourceRectHint/setActions | mech (grep zero) + read (MainActivity PiP blocks have neither) | CONFIRMED |
| 6.13 minor (DrivePicker density, widget resize floors, API-30 widget path) | mech (D13/D14) | CONFIRMED |
| 7.1 debug log hot-path strings / 7.2 no StrictMode | mech | CONFIRMED |

## PROGRESS / RESUME POINT (updated on pause 2026-06-11)

Done: §0 mechanical pass (101/101 OK); §1 verdicts (6/6 CONFIRMED); T279
verdicts (above); Context7 batch 1 (Media3) complete.

NEXT (in order):
1. Context7 batch 2: androidx.window FoldingFeature/WindowInfoTracker (8.2
   premise) — resolve returned generic Jetpack IDs; query
   `/git_android_googlesource_com/platform_frameworks_support` or
   `/websites/developer_android_develop_ui_compose` with a window-manager
   query, or re-resolve "androidx window".
2. Context7 batch 3: material3-adaptive (ListDetailPaneScaffold,
   NavigationSuiteScaffold, artifact names + versions for 8.1 full redesign) —
   candidate ID `/websites/composables_jetpack-compose_androidx_compose_material3_material3`.
3. Context7 batch 4: activity enableEdgeToEdge SystemBarStyle.auto behaviour
   (finding 6.5); batch 5: baseline profile plugin setup w/ AGP 8.7.3 (8.6);
   batch 6: AppAuth dispose() (5.11); batch 7: setRequestedOrientation vs
   system rotation lock + API 12L+/A16 large-screen ignore policies (8.3 user
   concern: confirm NO permission needed — activity-level orientation request
   overrides the auto-rotate quick toggle; WRITE_SETTINGS only needed to
   change the GLOBAL setting, which we don't do).
4. Device pass (emulator): LastPlayed tap-resume position lands at saved
   position; reverse-flip static check is recorded — optionally drive it.
5. Spot-read semantics of dual-agent findings 3.1-3.4, 4.1, 5.1-5.4, 6.1-6.10
   (most already read this session — record verdicts per finding into §2-§7
   tables mirroring §1's format).
6. Append §2-§7 verdict tables; then T282: invoke superpowers:writing-plans →
   `docs/superpowers/plans/2026-06-11-vc33-master-plan.md` (checkbox plan,
   per-item predicates, AWAITING-USER gates: minSdk Play-stats check + phone
   debug-install wipe consent); update ledger; commit+push; report plan in chat.

User decisions already locked (do NOT re-ask): scope=EVERYTHING (§1-§7 + §8,
granular); 8.1=full adaptive redesign; 8.3=tap-to-toggle immersive + rotate
button, no rotation-lock clash; 8.8=decide later from Play stats (gated item).
