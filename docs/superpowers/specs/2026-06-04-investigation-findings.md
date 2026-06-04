# Investigation findings — 2026-06-04 (Phase: INVESTIGATE only, no fixes)

Evidence base: full logcat dump (13:44–18:41, 330k lines) →
`deeplogs/logcat-2026-06-04-full.txt` / `app-slice.txt` (4.5k app lines).
Installed build: **vc30, 22 May, release-signed** — emits ZERO app-side logcat
(no PMP_ tags) and `diag/` was empty (Diagnostic toggle off). All findings below
are from system-side logs + source trace. Phase breakdowns need the instrumented
vc31 build (blocked on install consent — see TASKS.md T227).

## 1. Back-button: what actually happened (T223)

One app session: pid 19055, launched 18:04:15, alive at pull time.

| # | Time | Context | Result |
|---|------|---------|--------|
| 1 | 18:05:14 | 4 s after resume tap (18:05:10 FGS) | `onBackPressed` → **moveTaskToBack** (app → launcher) |
| 2 | 18:05:59 | still waiting, re-entered app | same |
| 3 | 18:06:22 | still waiting (playback came 18:06:43) | same |
| 4 | 18:32:15 | 7 s after Spotify-recent tap (18:32:08 FGS) | same |
| 5a | 18:32:20.4 | keyboard up in our window | back consumed by **IME** (keyboard closed) |
| 5b | 18:32:20.8 | popup/in-app | consumed in-app (no transition) |
| 6 | 18:32:24 | still waiting (Spotify audio 18:32:40) | **moveTaskToBack**; re-entered 18:32:25 |

Pattern: the user pressed back **while waiting for slow resumes**, and each press
threw them out of the app entirely; they re-opened it within 1–2 s each time
(launcher `START` records). This is a frustration loop, not intended navigation.

### Why every back exited the app
Tapping a Last-Played item calls `onNavigateToPlayer()` =
`navigate(Player){ popUpTo(start){saveState}; launchSingleTop; restoreState }` —
this **wipes Last Played off the back stack**, landing on Player as the only
entry. Back from there = task-to-back (Android-12+ default for root). So back
could never return to the list the user came from.

### Assessment (would different behaviour be better?)
- **Yes, for drill-ins:** tap-recent→Player should PUSH Player (plain
  `navigate`, no popUpTo) so back returns to Last Played (Android nav principle:
  back retraces the way you came). Same applies to Library/Cloud →Player taps.
- **No change at true root:** back-at-root → task-to-back is correct for a media
  app (playback continues via service); do NOT add a confirm-exit interceptor.
- The REAL fix for the observed loop is the loading feedback (§3) — users
  pressed back because nothing indicated progress.

## 2. Back-button: app-wide code map (T224)

| Surface | Handler | Behaviour |
|---------|---------|-----------|
| NavHost tabs | none (default) | tab taps = canonical popUpTo(start){saveState} pattern; back on a non-start tab pops to Player; back on Player → task-to-back |
| Drill-in to Player (LastPlayed/Library/Cloud) | `navigateToPlayer` popUpTo-wipe | **deviation** — destroys return path (see §1) |
| CloudBrowser | `BackHandler(activeProvider != null)` | back exits provider/folder first — good |
| Library multi-select | `BackHandler(multiSelectMode)` | back exits selection mode first — good |
| Dialogs/popups/IME | own windows | consume back natively — correct |
| Video fullscreen / PiP | none | PiP auto-enter is Home/leave-driven, back simply backgrounds; acceptable |

## 3. Resume delay + missing loading warning (T225/T226)

### Measured gaps (user replication, today)
- **Drive audiobook (+Cast active):** FGS start 18:05:10 → `dataPlaying=true`
  18:06:43.9 ≈ **93 s** with zero on-screen indication (vc30).
- **Spotify "Stealing Society":** tap 18:32:08 → Spotify focus-gain + metadata
  18:32:40 ≈ **32 s**, zero indication; Spotify's own app/session played it
  (Connect handoff), our app mirrors after.

### Why no warning appears — three confirmed coverage gaps
1. **Spotify (the reported one):** `SpotifyProvider.startPlaybackPolling()` sets
   the banner ON (line 967) but the first `/v1/me/player` poll during a
   device-wake handoff returns null → line 1007 sets
   `_spotifyMetadataFetching=false` ~1 s in ("no activity → nothing to wait
   for" assumption is wrong mid-handoff). Banner dies 30 s before audio.
2. **Drive resume:** `setCloudFetchInProgress(true)` is called ONLY from
   `CloudViewModel` (browse path, lines 1101/1129). The Last-Played resume path
   (`LastPlayedViewModel.playLocalAt`) never sets it.
3. **Local + the vc31 spinner's blind spot:** the new `isLoading` render keys
   off ExoPlayer `onIsLoadingChanged` — it cannot fire during the pre-player
   phase (SAF resolve + `M4bChapterParser` box-walk), which is exactly where
   the Drive/local time goes. So even vc31 would look frozen during parse.
   → expansion confirmed: the delay class exists on ALL THREE sources
   (Drive=parse+network, local=parse, Spotify=handoff), each with its own
   uncovered window. Per-phase numbers require the instrumented build (T229).

### What instrumented vc31 will give (already coded, awaiting install)
DiagLog RESUME/PERF per-phase timing, cold-start `dec` guard decisions,
Spotify `startPlaybackPolling gen` lines, DeepLogger touch/frame forensics.

## 4. Evidence gaps / next-round requirements
- Install consent needed: vc30 on device is release-signed →
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; replacing requires uninstall (data
  wipe: settings, recents, Hue pairing) OR Play internal-testing update path.
- Diagnostic logging toggle must be ON for next replication.
- All numbers above are system-clock correlations, not in-app phase timings.
