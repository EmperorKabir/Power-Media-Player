# Plan — six on-device issues (2026-07-30)

> Investigation-complete, evidence-locked. Source: user on-device testing (two messages)
> + 7 parallel Context7/Superpowers investigators + device confirmation. **No code changed
> during investigation.** This is the PLAN phase per the TASKS.md protocol (phase lock
> INVESTIGATE -> PLAN -> IMPLEMENT). Nothing here is implemented yet.

## Binding execution rules
- Every task ships with an **acceptance predicate** (a device screenshot / logcat line / build
  exit / DB read). A box is ticked ONLY with that evidence pasted in the same turn.
- **Device-first**: full control of the Oppo `3B166N000CZ00000` (run-as, debug build). Screencap
  via `shell screencap -p /sdcard/x.png` + `pull` (exec-out truncates); PIL-thumbnail before Read.
- **Order (user directive):** do I1, I3, I3b, I4 (a-d), I5a, I5b, I6 first. Do **I2 (Spotify)
  LAST**, and
  **hold the app-data clear for I2 until the very end** — it wipes Drive/Spotify/downloads/history.
- **Delicate zones (do not break):** Spotify auth handoff + bounce machinery (I2); the Cast relay
  + `switchPlayer` path (I5b) — most regression-prone code in the app (cf T295/T296). Additive,
  gated, verified-against-repro only. No scope-narrowing, no WebView Picker, no guesswork.
- Use Context7 for AppAuth (I2) and Media3/Cast (I5b) before touching them.

---

## Checklist (tick only with evidence)

Status legend: `[ ]` not started · `[~]` code complete + compiles/tests green, device-verify pending · `[x]` device-verified.

| ID | Item | Phase | Box |
|----|------|-------|-----|
| I1 | Deep-scan prompt shows even when already enabled in Settings | M | [~] |
| I3 | Player track-list tap does nothing for local multi-track | M | [~] |
| I3b | Library row tap silently no-ops when the MediaController isn't bound (the "other bug") | I->M | [~] |
| I4a | Add "Autoplay next track" Settings toggle + info box (+ audit the existing autoplay toggles) | M | [~] |
| I4b | Add autoplay toggle button to player bottom bar (before Bluetooth) | M | [~] |
| I4c | Shuffle button shows a mini "Shuffle on/off" popup | M | [~] |
| I4d | Single-item plays (Cloud/downloaded/Last-Played) don't auto-advance — enqueue surrounding tracks | M | [~] |
| I5a | Cast metadata/artwork display on phone — INSTRUMENT (logging) + device test (poss. regressed) | I->M | [~] |
| I5b | Cast start cutoff: add a start-delay setting + prime the cast start (+ triple-check castVideoAudioOffsetMs) | I->M | [~] |
| I6 | Surface downloaded podcasts in the Library | M | [~] |
| I2 | Spotify sticky sign-in — log first, repro (data-clear LAST), then fix | I->M | [~] logging only; repro+fix pending |
| GATE | Anti-skip final gate + coverage cross-check | V | [ ] |

**Device-verification note (2026-07-30):** the physical Oppo Find X9 Ultra runs the
Play Closed-test build (release-signed `f45bab3e…`, carries a Play source stamp).
That signer ≠ the local release keystore (`3981a282…`), so a locally-built APK
cannot `install -r` over it — installing vc74 needs an uninstall, which wipes the
Spotify session held for the I2 data-clear. Therefore ALL physical-phone testing
(cast I5a/I5b + real Spotify repro I2 + data-dependent checks) is consolidated into
the FINAL authorised-wipe phase: uninstall vc73 → install debug vc74 → enable
DiagLog → run cast + Spotify repro together. Emulator covers the rest.

---

## I1 — Deep-scan prompt shows even when already enabled in Settings
- **Root (evidence):** the Library prompt is gated on a SEPARATE flag `first_run_seen`
  (`LibraryScreen.kt:295`, `LibraryViewModel.kt:380`), NOT on the deep-scan setting
  `metadata_deep_scan` (`SettingsDataStore.kt:25`). `SettingsViewModel.setDeepScan()`
  (`:391-393`) never writes `first_run_seen`, so enabling deep scan in Settings first leaves
  the Library prompting once. (Same class as the Drive settings-vs-cloud split; it prompts
  ONCE — the flag latches — not every visit.)
- **Fix:** (a) gate the dialog on `!firstRunSeen && !useDeepScan && hasPermission`
  (`LibraryScreen.kt:295`; expose `useDeepScan` on `LibraryViewModel`); (b) make
  `SettingsViewModel.setDeepScan(true)` also call `settingsDataStore.setFirstRunSeen()`.
  Preferred structural option: model the preference as a tri-state (`Unset/On/Off`) so no
  second flag can disagree — but the two-line fix is sufficient.
- **Files:** `LibraryScreen.kt`, `LibraryViewModel.kt`, `SettingsViewModel.kt`,
  (optional) `SettingsDataStore.kt`.
- **Predicate:** fresh data. (1) Settings -> enable Deep Scan -> open Library = **NO prompt**
  (screenshot). (2) fresh -> open Library = prompt appears; tap Skip -> re-open Library = no
  re-prompt. Build EXIT 0.

## I3 — Player track-list tap does nothing (local multi-track)
- **Root (device-confirmed):** the now-playing chip `ChapterPickerChip` (`PlayerScreen.kt:1930`)
  opens `ChapterPickerDialog`. Chapters mode works (`seekToChapter`); tracks mode is fed
  `playlist = emptyList()` hardcoded (`PlayerScreen.kt:637`, comment "surfaced ... in future
  pass") -> dialog shows **"No chapters or tracks available."** (device: 23-track queue, chip
  says "1 / 23 tracks", dialog empty). Even if populated, `onTrackSelected` calls
  `seekToPlaylistPosition(index.toLong())` (`PlayerScreen.kt:639`) which treats the arg as
  absolute **milliseconds** (`PlaybackConnection.kt:604`), not a track index -> no-op.
- **Fix:** expose the real queue (per-item title + index) from `PlayerViewModel` /
  `PlaybackConnection`; pass it as `ChapterPickerDialog.playlist`; change `onTrackSelected` to a
  per-index seek `controller.seekTo(index, 0L)` (new `PlaybackConnection.seekToQueueIndex(i)`,
  mirroring the folder-chapter path at `PlaybackConnection.kt:596`).
- **Files:** `PlayerScreen.kt` (637/639), `PlayerViewModel.kt`, `PlaybackConnection.kt`,
  `PlayerUiState.kt`.
- **Predicate:** play a 23-track Library queue -> tap chip -> dialog **lists 23 tracks** -> tap
  track 5 -> `media_session` active item id / title = track 5 (screenshot + dumpsys).
  Regression: audiobook chapters still seek (device).
- **Spotify-album device test (user asked explicitly, "which you will have to do"):** with
  Spotify signed in, play a Spotify album, open `SpotifyAlbumTracksButton` (QueueMusic), tap a
  track -> it plays in-context. Confirm on DEVICE (code says separate/working path, but the user
  wants it verified). Sequencing note: this needs a Spotify session — do it after I2 signs in, or
  ask the user to sign in for this one check; do NOT let it force the I2 data-clear earlier.

## I3b — Library row tap silently no-ops when the MediaController isn't bound (the "other bug")
- **Root (evidence, agent-found):** a Library row tap ALWAYS calls `onNavigateToPlayer()` (so it
  jumps to the Player regardless), but `playFiles -> setMediaItems` is guarded by `controller?.let{}`
  (`PlaybackConnection.kt:440`) — if the `MediaController` hasn't bound to the service yet it is a
  **silent no-op** (no playback, no error), and any exception in `createMediaItems` is swallowed
  with only a debug log (`LibraryViewModel.kt:571`). So a tap can land on an empty Player and look
  like "nothing happened". This is the SECOND, distinct bug (separate from I3's track-list); the
  user hinted at it ("unless you have uncovered some other bug").
- **Fix direction:** don't silently drop the play — queue/retry it once the controller binds (or
  surface a toast). Confirm the mechanism first with a device repro + logging (unbound-controller
  timing vs a swallowed exception) before choosing the fix (no-guesswork).
- **Files:** `PlaybackConnection.kt` (setMediaItems guard), `LibraryViewModel.kt` (playFiles/
  createMediaItems catch), possibly `PlaybackConnection` bind callback.
- **Predicate:** repro the silent no-op on device (cold launch, tap a track immediately) with a
  DiagLog line proving the controller was unbound; after the fix, an early tap plays (or shows a
  clear message) instead of a dead empty Player.

## I4a — "Autoplay next track" Settings toggle + info box
- **Finding:** no local-music auto-advance toggle exists (only `PODCAST_AUTOPLAY_NEXT`).
  Auto-advance currently always happens for Library multi-track queues (ExoPlayer default,
  no `pauseAtEndOfMediaItems`).
- **Fix:** add `MUSIC_AUTOPLAY_NEXT` (default ON) in `SettingsDataStore`; a
  `SettingsToggleItem` in the "Auto-play conditions" block (`SettingsScreen.kt:~312`, beside
  "Auto-play next podcast episode") with a layman `description` (the info box); apply it via
  `player.pauseAtEndOfMediaItems = !autoplayNext` (so OFF stops at end of track).
- **Files:** `SettingsDataStore.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`,
  `PlaybackConnection.kt`/`PlaybackService.kt` (apply-on-connect + on-change).
- **"and stuff" (audit the existing autoplay settings):** the app already has
  autoplay-on-launch, on-Bluetooth, on-cast, kind gates (spoken/music/video), ease-in, and
  podcast-autoplay-next. Confirm they all sit in the right Settings place with accurate info
  boxes; add the new "Autoplay next track" toggle beside them; keep the layman grouping coherent.
- **Predicate:** toggle OFF -> at end of a Library track, playback **stops** (does not advance);
  ON -> advances to next. Device (short tracks) + screenshot of the Settings row + info text.

## I4d — Single-item plays don't auto-advance (enqueue surrounding tracks)
- **Finding:** auto-advance only works where a real multi-item queue is built (Library album/
  folder). Single-item paths play ONE `MediaItem` so end-of-track = stop: Cloud single tracks
  (`CloudViewModel.kt:1842`), downloaded Drive books (`playDownloadedBook`), Last-Played resume
  (`LastPlayedViewModel:236/641`), videos, reverse-mode audio.
- **Fix direction (part of "autoplay ... and stuff"):** when "Autoplay next track" (I4a) is on,
  build a queue for the single-item paths too — enqueue the surrounding items (the folder /
  Recents context / the browsed list) so ExoPlayer can advance. Scope carefully: keep mediaId
  keys stable (resume/Recents unaffected); Spotify/cast paths follow their own advance.
- **Files:** `CloudViewModel.kt`, `LibraryViewModel.kt` (playDownloadedBook), `LastPlayedViewModel.kt`.
- **Predicate:** with the toggle on, play a Cloud/downloaded track -> at end it advances to the
  next in that context; toggle off -> it stops. Device. (Ask user first: include this, or ship
  I4a as toggle-over-existing-queues only — flagged as an open decision.)

## I4b — Autoplay toggle button in the player bottom bar (before Bluetooth)
- **Fix:** add an autoplay IconButton in BOTH control rows immediately before `BluetoothButton`
  (`PlayerScreen.kt:1305` compact, `:1602` expanded). Mirror the Shuffle button's toggle
  structure (`:1270`) with the Bluetooth tint convention: `tint = if (on) TealAccent else
  TextTertiary` (TealAccent is the live user accent, state-backed — no extra wiring). Icon e.g.
  `Icons.Filled.RepeatOn`/`PlaylistPlay`. Toggles the same `MUSIC_AUTOPLAY_NEXT` setting (I4a).
- **Files:** `PlayerScreen.kt` (both rows), `PlayerViewModel.kt` (expose flow + toggle).
- **Predicate:** button present before BT in BOTH layouts (compact screenshot + unfolded/expanded
  screenshot), teal when on / grey when off, and toggling it flips the Settings value (round-trip).

## I4c — Shuffle mini-popup "Shuffle on / Shuffle off"
- **Finding:** shuffle toggle currently gives only a tint flip; no popup/toast.
- **Fix:** on shuffle toggle, show a short Toast "Shuffle on"/"Shuffle off" (mirror the
  offline-status Toast-via-flow at `PlayerScreen.kt:187-194`, or a direct Toast in the shuffle
  `onClick`). Apply to both control rows' shuffle buttons.
- **Files:** `PlayerScreen.kt` (shuffle onClick) and/or `PlayerViewModel.kt` (status SharedFlow).
- **Predicate:** tap shuffle -> Toast "Shuffle on"; tap again -> "Shuffle off" (device screenshot
  of the toast).

## I5a — Cast metadata/artwork display on the phone (INSTRUMENT + device test; likely regressed)
- **User asked for LOGGING, not just a glance** ("5a obviously requires on device testing and
  logging"), and "the problem has gone" most likely means it CAME BACK. Title/artist path
  (`senderMetadataByMediaId`) looks intact at HEAD, BUT the specific historical fix was for
  COVER ART on the phone during cast — `castSafeArtModel` reads local-file art directly because
  the phone can't fetch its own cleartext http relay art URL (TASKS §R, "RESOLVED 2026-07-10").
  That ART-bind path is the likely regression surface and was NOT separately re-verified.
- **Step 1 (INSTRUMENT):** add DiagLog breadcrumbs on the cast art-bind path (`castSafeArtModel`
  / the artwork resolution in `PlaybackConnection.updatePlayerState` around `:1119-1180`) logging
  per item: mediaId, artworkUri scheme, artworkData present, resolved model, during-cast flag.
  Debug-only (R8-stripped) — safe, no behaviour change.
- **Step 2 (DEVICE):** cast a Drive book + a local track to **Living Area TV** / **Kabir Stereo**
  (never Bedroom; set cast volume low non-zero ~3 per the cast-verification memory) -> read the
  trace + screenshot -> confirm whether title/artist AND artwork populate on the phone. If
  artwork is blank, the trace pinpoints where (cleartext-http relay URL vs local bytes) -> fix
  that path.
- **Predicate:** cast active -> phone now-playing shows title/artist AND cover art (screenshot +
  DiagLog line). If blank, the logged trace names the failing bind; fix + re-verify.

## I5b — Cast start cutoff (~0.5s) + start-delay setting
- **Root:** there is NO cast delay option (the only cast offset, `castVideoAudioOffsetMs`, is an
  A/V-sync nudge for video-audio-to-audio-device, not a start delay). The cutoff is a
  **receiver-side warm-up** — Google's Default Media Receiver drops the first fraction of a
  second on every fresh LOAD and every seek-to-0; the app uses a plain `CastPlayer` with no
  priming (`PlaybackService.kt:1973`). Context7: Cast SDK exposes no pre-roll knob; standard
  mitigation is LOAD-paused -> wait ready -> seek 0 -> play, or a short silence lead-in.
- **Triple-check the existing offset (user: "if the toggle does exist, triple check the coding.
  not sure i had it on if so"):** re-read `castVideoAudioOffsetMs` end-to-end
  (`SettingsDataStore.kt:44/1163`, `CastSwitcher.kt:231`, `PlaybackService.kt:2018/2053/2064`) and
  confirm it is A/V-video-sync only and cannot itself clip audio start; report the read-through so
  it is explicitly ruled in or out (in case the user had it on).
- **Fix:** (1) add a "Cast start delay" Settings option + info box (`SliderRow` 0-1000 ms,
  mirroring "Cold-start resume backoff" `SettingsScreen.kt:265-273`); (2) prime the cast start on
  the cast-begin path (`switchPlayer`/`rebuildForCast`): load paused, wait for the receiver to
  report buffered/ready, seek 0, then play (apply the configured lead-in). DELICATE cast path —
  gate it, keep every non-cast path byte-identical, and run a full cast regression (video cast /
  audio-only-device / audiobook) after.
- **Files:** `SettingsDataStore.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`,
  `PlaybackService.kt` (cast start path).
- **Predicate:** cast a song -> opening ~0.5s no longer clipped (user ear + relay/cast logcat);
  the setting renders with its info box; restart-mid-cast no longer re-clips. Full cast
  regression green (no double-audio / aspect / A-B-loop regressions).

## I6 — Surface downloaded podcasts in the Library
- **Design (chosen):** a separate **"Downloaded podcasts (N)"** section in the Library Audio tab,
  directly under the existing "Downloaded" (books) section — its own row model (podcasts come
  from a different table with network cover art + show-title-date subtext + the podcast play
  path; folding into the delicate `DownloadedBook` list is not worth the regression risk, and it
  mirrors the Downloads-manager's two-section grouping).
- **Fix:** new `LibraryViewModel.downloadedEpisodes` flow = `podcastDao.observeDownloaded()`
  joined with the shows (for `title` + `artworkUrl`); new `DownloadedEpisode(guid, title,
  showTitle, artworkUrl, audioUrl, localPath, bytes, durationS)`; new section in `LibraryScreen`
  after the books divider, gated `selectedTab==0 && downloadedEpisodes.isNotEmpty()`, cover via
  the existing `PodcastArtwork` (podcast-glyph offline fallback); new
  `LibraryViewModel.playDownloadedEpisode(...)` reusing `buildEpisodeItem`/`resolvePlayableLocal`
  + `episodeQueueSlice` (keep `mediaId = audioUrl` so Recents/resume unify). Inject `PodcastDao`
  + `SettingsDataStore` into `LibraryViewModel`.
- **Files:** `LibraryViewModel.kt`, `LibraryScreen.kt` (reuse `PodcastsSection` helpers).
- **Predicate:** download a podcast episode -> it appears in Library "Downloaded podcasts" with
  show cover + title + date -> tap -> it plays from the local file (offline) and lands in
  Recents. Device screenshot + play.

## I2 — Spotify sticky sign-in (LAST; data-clear held to the end)
- **Root (evidence + Context7):** sign-in is plain AppAuth `startActivityForResult` with the
  result handler registered INSIDE the Compose Cloud screen (`CloudBrowserScreen.kt:267`). On
  aggressive ColorOS, process death during the Custom Tab drops the pending `ActivityResult`
  (the Compose launcher may not re-register, or its key changes) -> `handleSpotifyResult` never
  fires -> "nothing happens" -> force-close -> warm retry works. Known AppAuth failure class;
  documented remedy = PendingIntent completion. The `SpotifyBounceService`/bounce machinery is
  the **device-wake** path, NOT sign-in — do not disturb it; preserve the `oauthInFlight`
  contract.
- **Step 1 (FIRST — additive, safe):** add persistent `DiagLog` breadcrumbs at four auth
  checkpoints (button tap; `handleSpotifyResult` entry with `data!=null`; `handleAuthResponse`
  parse; token-exchange callback). Debug logs are logcat-only + R8-stripped and don't touch the
  redirect path — this is the ONLY way to trace the release/real-device path.
- **Step 2 (device repro — DO LAST):** clear PMP app data on the Oppo -> attempt Spotify sign-in
  -> pull the DiagLog trace -> confirm whether `handleSpotifyResult` fires (dropped result vs
  token hang). **Blocker:** the Spotify login page needs the user's Spotify password — I cannot
  enter it; the user performs the login, I capture the trace. Force process death mid-tab
  (`am kill` / "Don't keep activities") to reproduce H1.
- **Step 3 (fix — only after the repro confirms):** migrate sign-in dispatch to AppAuth
  PendingIntent completion into a dedicated `SpotifyAuthCompleteActivity` (survives process
  death); OR the smaller fix of an activity-level stable-key launcher. Additive,
  behaviour-preserving, verified against the repro. Preserve `oauthInFlight` + the bounce path.
  Also add an explicit timeout + surfaced failure around `performTokenRequest` so a stalled token
  POST can't present as a silent hang (agent B's H4).
- **Predicate:** with logging, reproduce the stuck state (trace shows the drop); after the fix,
  sign-in completes without a force-close (device). If the repro can't be induced, ship Step 1
  (logging) + the low-risk activity-level launcher and re-verify on the user's next stuck event.

---

## GATE — anti-skip final gate + coverage cross-check
Before reporting done: count unticked boxes above (must be 0 or explicitly BLOCKED with the exact
unblock). Then confirm every phrase from the user's two messages maps to a task:

**From the investigation-request message:**
- deep scan prompt when already enabled -> **I1**.
- Spotify sign-in stuck, check logs, common issue, delicate handoff -> **I2**.
- player suggests tracks, tapping does nothing (local); audiobook chapters work; Spotify albums
  unsure (test) -> **I3** (track-list) + **I3b** (the "other bug": silent play no-op) + the
  Spotify-album device test inside I3.
- autoplay next track toggle? works? -> **I4a** (works for Library queues only) + **I4d**
  (single-item paths can't advance).
- cast metadata display, fixed but "problem has gone", read project files -> **I5a** (instrument
  + device test; treats it as possibly regressed — the castSafeArtModel art path).
- cast delay option / start cutoff -> **I5b** (+ triple-check the existing offset code).
- podcasts in Library like Drive downloads -> **I6**.

**From the plan-request message (this turn):**
- point 1 = a fix -> **I1**.
- point 2 = clear app data + attempt sign-in; HOLD clearing until last -> **I2** (order enforced).
- point 3 corrected (track/chapter section; local tracks broken, chapters work) -> **I3**
  (device-confirmed empty "Tracks" dialog); "unless you have uncovered some other bug" -> **I3b**;
  "spotify albums ... testing first on device which you will have to do" -> I3 Spotify device test.
- point 4: autoplay toggles ("and stuff") in Settings + info boxes -> **I4a** (+ audit existing
  toggles) + **I4d**; shuffle mini-popup -> **I4c**; autoplay button in bottom bar before
  Bluetooth, teal -> **I4b**.
- point 5a: on-device testing AND LOGGING -> **I5a** (instrument the art-bind path + device test).
- point 5b: add to Settings menu + info boxes; "triple check the coding" -> **I5b**.
- point 6 (podcasts in Library) -> **I6**.
- "full control of my phone, do all on-device testing" -> every predicate is a device test.
- "hold clearing app data for item 2 until last" -> I2 ordered last, data-clear in Step 2.
- read project files / Context7 / Superpowers -> done (PROJECT_RULES + TASKS read; 7 investigators
  used Context7 on AppAuth + Media3/Cast).

Nothing from either message is unmapped.
