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

---

# Round 2 — instrumented run (vc32 build, 19:48-19:55) — T241-T246 verdicts

Evidence: `deeplogs/run2/diag-log.txt` (DiagLog RESUME/PERF/PLAYER/DEC) +
4 DeepLogger sessions. All timings from session 8b75dc31.

## T246 — Drive resume delay: CONVICTED, exact phase split

| Phase | Time |
|---|---|
| chapterParse textTrack (MediaExtractor over https) | **38,004 ms** |
| chapterParse neroChpl fallback (re-streams the SAME file) | **37,910 ms** |
| setMediaItems + seek | 2 ms |
| **Total tap→loaded** | **75,918 ms** |

Local files: 19 ms parse, 140 ms total — local is fine. The remote item is
parsed TWICE, each pass streaming a ~1.2 GB file over https. Plan Task 10
gate (parse ≥50% of gap) met at ~100% → **chapter cache GO**, plus an
obvious second win: single-pass / skip-fallback for remote schemes.

## T245 — ghost audiobook under Spotify: CONFIRMED, full mechanism

```
19:52:37  tap Drive audiobook → 76 s parse begins (coroutine A)
19:53:17  tap LOCAL item — debounce saw activeBefore=0 (!) — played fine
19:53:28  tap SPOTIFY Drive-thru → local player paused; Connect play ok=189ms
19:53:53  coroutine A FINISHES → REMOVE + mediaItemTransition(audiobook)
          + playWhenReady=true + seek(83535ms)
19:53:55  PLAYER isPlaying=true mediaId=cab8aadc — audiobook AUDIBLY
          playing under the Spotify mirror; Player tab shows the Spotify
          overlay → looks like "nothing".
```

Two defects:
1. **No generation/cancel on resume coroutines** — a stale resume may
   setMediaItems+play long after the user moved on.
2. **Debounce hole** — `resumeActive` is a per-ViewModel-instance counter;
   multiple LastPlayedViewModel instances exist across nav entries
   (DEC "guard-already-fired (other VM instance won)" proves multi-instance),
   so the 19:53:17/19:53:28 taps read 0 mid-flight. Needs a process-wide
   counter + a resume generation token checked after parse.

## T243 — stale SOAD metadata on Spotify switch: MECHANISM CONFIRMED

"System of a Down" = Stealing Society = the PREVIOUS Spotify track.
Spotify's `/v1/me/player` is eventually-consistent after `PUT /play`: the
first non-null snap returns the OLD track for 1-2 s. Our poll displays any
non-null snap and the vc32 grace clears on it. Fix direction (plan): pass
the requested trackUri with expectPlayback and hold banner/state until
`snap.trackUri == requested` (or grace expiry).

## T244 — Spotify position retention: DOES NOT WORK (answer: no)

The 5 s persist tick (PlayerViewModel ~573-585) reads the LOCAL player's
`currentMediaItem.mediaId` + `isPlaying` — paused/stale during a mirror →
Spotify rows never get a position update. Log: `tap source=SPOTIFY
targetPos=0ms`. The resume path DOES seek when targetPos>0 — it just never
has one. Fix direction (plan): while `spotifyState != null`, tick writes
`spotifyState.positionMs` against the Spotify row's mediaUri.

## T241 — Drive folder add: no visible feedback/refresh

Add = `CloudViewModel.toggleDriveFavourite*` → DataStore → strip updates
reactively (CloudViewModel:285-291) but nothing scrolls/expands/confirms,
and the added FOLDER looks like a track row. Fix direction (plan): snackbar
confirm + auto-browse into the added folder (or visibly refresh listing).

## T242 — favourites strip left icon

Folder favourites render a star-family kind icon on the LEFT while the
right side already carries the favourite star (CloudBrowserScreen
favourite-row composables, ~1438-1546 region). Fix direction (plan):
left icon = Icons.Filled.Folder for folders / kind icon for tracks; right
star stays the favourite toggle.

## T247 — "play/pause slow to respond" (reported live during round 2)

Touch-to-command latency measured from DeepLogger touches vs DiagLog player
events: tap ACTION_UP 18:53:21.265Z → playWhenReady flips 18:53:21.276Z =
**11 ms**; second case 18:53:28.027Z → .038Z = 11 ms. The LOCAL pipeline is
instant. Two real sources of perceived slowness:
1. **Spotify mirror**: play/pause routes via Web API and the BUTTON ICON
   only flips when the next 1 Hz poll returns the changed state (plus
   Spotify's eventual consistency) → 1-2 s+ of "did it register?".
   Fix direction (plan): optimistic isPlaying flip in the mirror state on
   command success, reconciled by the next poll.
2. **The T245 ghost**: during the overlap the pause button paused a
   DIFFERENT stream than the one audible — perceived as unresponsive.
Also ruled out: the 25.5 s frame gap at 18:51:16Z is NOT a stall — frames
simply aren't drawn while MainActivity sat stopped behind the Spotify OAuth
Custom Tab (lifecycle rows 1303-1326 bracket it exactly).

---

# Round 3 — DEEP investigation (adversarial re-check + second-order causes)

New evidence: fresh logcat (`deeplogs/run2/logcat-evening.txt`, 351k lines —
PMP_DIAG Spotify/Drive lines DiagLog doesn't carry) + full parser/nav code
trace + context7 (Media3 /androidx/media, Compose/Hilt nav scoping docs).

## T246 — three stacked causes, not one

1. **Wrong URI class on resume.** `recordCloudPlay` stores `mediaUri =
   item.downloadUrl` (raw https). The ORIGINAL Cloud play downloads to a
   cache file (file:// — HP m4b played in ~3 s at 19:49:51), but every
   Last-Played resume uses the https URL and never looks at that cache.
2. **Both parser strategies full-stream the remote file.** textTrack =
   framework `MediaExtractor.setDataSource(https)` (m4b moov-at-end → full
   ~1.2 GB stream, 38.0 s); on 0 chapters the neroChpl fallback re-streams
   the SAME file again (37.9 s). This book has NO chapters — 76 s to find
   nothing, and it will repeat on EVERY resume (caching the EMPTY result
   matters as much as caching chapters).
3. **The https URI is range-capable** — ExoPlayer buffered to READY in
   2.5 s after setMediaItems. The full-streams are purely the parsers'
   access pattern, not the network. Context7: Media3 1.8+ instance-based
   `MetadataRetriever` (extractor/range-based; 1.9 moves it to
   `media3-inspector`) is the doc-blessed no-playback metadata path —
   candidate replacement; equally valid: parse from the cache file, or
   parse chapters ASYNC after playback starts (playback needs none).

## T245 — two mechanisms, both now proven

1. **`PlaybackConnection.setMediaItems` (lines 393-403) unconditionally
   `prepare()+play()`** — any stale loader auto-plays. (Cold-start works
   around it by flipping playWhenReady=false AFTER — itself racy.)
   Fix shape: `setMediaItems(..., playWhenReady: Boolean = true)` +
   resume-generation check before the call.
2. **Per-instance guards, doc-confirmed.** Android docs: a
   destination-scoped ViewModel "is cleared when the destination is
   removed from the backstack" — tab re-navigation (popUpTo) recreates
   LastPlayedViewModel. Evidence: both taps logged `attempt=1`
   (instance-field counters reset). Guards must be process-wide
   (companion AtomicInteger + generation token).

## T243 — stale window measured: 11 s, and the OVERLAY is the gap

PMP_DIAG sequence (first Drive-Thru play, 19:51:20-32):
`playRequest 404 (no device)` → `listDevices` → `activate "Kabir's Z
Fold7"` → `transferPlayback 204` → `playRequest 204` → poll gen=1
expectPlayback=true → **first snap 19:51:21.756 returns the OLD track**
(Hypnotize artwork f5e7…) → ~11 s of null snaps while the device wakes →
correct Drive-Thru snaps from 19:51:32.791 → LRCLib lyrics +6.4 s.
The vc32 grace only guards the BANNER; `_spotifyState` was SET to the
stale snap, so the OVERLAY showed SOAD for ~11 s. Fix must gate the state
emit on `snap.trackUri == requestedUri` (we log the requested uri:
`spotify:track:6oaZvhLj…`) with the same grace expiry.

## T247 — closed: 11 ms locally; two named perception sources

- DeepLogger touch ACTION_UP → DiagLog playWhenReady flip: **11 ms** (×2).
- Audio route at the time: **phone SPEAKER** (ROUTE lines) — BT/A2DP
  buffer-drain factor ELIMINATED.
- The 25.5 s render.frame gap = MainActivity stopped behind the Spotify
  OAuth Custom Tab (lifecycle rows bracket it) — NOT jank.
- Remaining real lag: (a) mirror icon waits on the 1 Hz poll (+11 s stale
  window above); (b) the T245 ghost made pause act on the wrong stream.

## T241 — refresh exists but is conditional AND silent

`CloudViewModel.rememberPickedDriveFolder` (622-634) refreshes ONLY when
`activeProvider == GOOGLE_DRIVE` at pick-time, and even then re-lists with
no confirmation/scroll/highlight. User timeline: picked "Stephen Fry"
19:48:50 → first found/opened it 19:49:35 (45 s of looking). Fix:
unconditional snackbar confirm + auto-browse into the added folder.

## T244 / T242 — verdicts unchanged after re-check

T244: Spotify rows DO carry `mediaUri = spotify:track:…` so
`updatePositionByUri` would match — the tick just never writes during
mirror. T242: left icon = kind/star instead of Folder for folder rows
(CloudBrowserScreen fav rows ~1438-1546).

---

# Round 4 — T250/T251 (post-GATE-B device run, 21:47 window)

Evidence: `deeplogs/run3/diag-log.txt` sess=b8cd38fa.

## One defect, two symptoms: the unrouted handoff gap

```
21:47:29.521 tap SPOTIFY token=11 targetPos=38127
21:47:29.555 local player paused (branch pause) ✓
21:47:29.791 spotifyPlayCall ok=269ms
21:47:31.453 PLAYER playWhenReady=true reason=USER mediaId=2514d2e4 ← LOCAL
21:47:32-38  five more USER toggles, ALL on mediaId=2514d2e4 (local)
```

Between the tap and the first ACCEPTED mirror snap, `spotifyState` is null:
- Player tab shows the PREVIOUS item's metadata (T251), and
- `playPause()` routes by `isSpotifyActive=false` → controls the LOCAL
  player → user resumed the local track under Spotify (T250's "two at
  once"). The round-2 stale-snap suppression LENGTHENED this gap.

ALSO: the null-snap branch sets `_spotifyState.value = null`
unconditionally — any provisional/held state would be wiped by the
device-wake null snaps, so the fix must respect the grace there too.

## Confirmed working in the same log
- Drive/local resume: `chapterParse.cacheHit took=0ms`, tap→loaded 61 ms.
- The 75.7 s parse ran as `resume.asyncChapterFill` (background) and is
  now cached (empty result included). ResumeGate tokens active (token=8
  fill-in declined injection: count=0).

## Fix design (T252 — provisional mirror state)
On a user Spotify play: synthesise SpotifyPlaybackState from the tapped
row (title/artist/durationMs, positionMs=targetPos, isPlaying=true,
trackUri=requested) and set `_spotifyState` IMMEDIATELY →
`isSpotifyActive` true at tap time (controls route to Spotify Web API;
no local-player window), UI shows the REQUESTED track + loading banner.
First matching snap replaces it; null snaps during grace no longer null
the state; grace expiry (45 s) clears provisional + banner as failsafe.
