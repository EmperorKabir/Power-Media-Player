# Speed/efficiency + form-factor audit — 2026-06-11 (T278)

Method: five parallel read-only auditors (playback core / Compose UI / IO-network /
form factors / memory-lifecycle) over all 173 Kotlin files (~42k lines), followed by
direct verification of every single-source high-stakes claim (grep/read evidence noted
inline). No code changed. Severity ordering within each section.

Cross-references: T279 (launch-restore position bug) is tracked separately — reproduced
on-device, see TASKS.md.

Legend: CONF = confidence. DEP = interdependency that any fix must preserve.

---

## 1 — Verified-by-grep structural defects (fix-worthy, low risk)

**1.1 Audio focus requested, never abandoned** — `PlaybackService.kt:1422-1448`
requests focus with a listener capturing the service; zero `abandonAudioFocus*`
calls app-wide (grep verified). Each service generation leaks into AudioManager;
app stays a registered focus holder after playback stops.
Fix: store the `AudioFocusRequest`, abandon in `onDestroy`. CONF high.

**1.2 Cast `SessionManagerListener` never removed** — added `PlaybackService.kt:1333`,
zero `removeSessionManagerListener` app-wide (grep verified). Process-static
CastContext accumulates one listener + one released CastPlayer per service
generation; stale listeners act on dead players at session end.
Fix: keep listener in a field, remove in `onDestroy`. CONF high.

**1.3 `senderMetadataByMediaId` / `senderItemByMediaId` unbounded** —
`PlaybackService.kt:283-301`; populated on EVERY `onMediaMetadataChanged`
(`:1552-1558`) and per queued item (`:2365-2368`); zero evictions (grep verified).
Each entry can retain `artworkData` (100KB-2MB) → tens-to-hundreds of MB across a
long session. Fix: LRU keyed on mediaId with live-queue ids pinned, or strip
artworkData from cached copies. DEP: load-bearing for cast artwork survival +
cleartext URI restore on cast-stop (`:1812-1815`) — never evict live-queue ids.
CONF high.

**1.4 HueEntertainment stream loop lacks try/finally** — `HueEntertainment.kt:435-444`:
DTLS send failure calls `cancel()` inside the loop; cleanup at `:478-480`
(dtls/socket close + `stopEntertainmentStream` PUT) sits AFTER the loop, not in a
finally → CancellationException skips it (read-verified). Leaks the socket, leaves
the bridge thinking the stream is active; next `start()` overwrites the fields.
Also `connectDtls` (`:504-530`) assigns the socket before the handshake — a
handshake throw leaks one fd per retry. Plausible contributor to the open
disconnect→reconnect regression (memory: project_hue_reconnect_regression).
Fix: wrap loop + handshake in try/finally closing dtls/socket + firing the stop PUT.
CONF high.

**1.5 PlaybackService.onDestroy never stops the Hue stream** — engine runs in its own
singleton scope (`HueEntertainment.kt:97`); `stop()` is only called from the
serviceScope collector (`PlaybackService.kt:1152,1247`), which dies with the
service. Swipe-away mid-stream → 25 Hz DTLS loop + dimmable PUTs continue until
process death. Fix: `hueEntertainment.stop()` in `onDestroy`. CONF high.

**1.6 MediaMetadataRetriever leak** — `PlayerViewModel.kt:930-943`: `mmr.release()`
not in finally (the only such site; four other MMR users are clean — verified).
Folded into 3.6 below (the whole pipeline should go). CONF high.

---

## 2 — Cold start / startup latency

**2.1 Main-thread `runBlocking` cluster in service onCreate** — `PlaybackService.kt`
`:388-394` (200ms cap), `:726-737` (200ms), `:791-815` (600ms), `:824-832` (200ms):
four sequential DataStore blocks on Main during FGS start; worst case ~1.2s added
to tap-to-audio. All read the SAME preferences file — one combined snapshot read
covers all of them. DEP: pitch/speed seed must still complete before first audio
(`:786-790`). CONF high.

**2.2 `onTaskRemoved` runBlocking has NO timeout** — `PlaybackService.kt:1981-1985`:
unbounded main-thread block if the DataStore actor is busy → ANR vector on
swipe-away. Every other service runBlocking is timeout-capped. Fix:
`withTimeoutOrNull(200) + default`. CONF high.

**2.3 App.onCreate blocking DataStore first-read** — `PowerMediaPlayerApp.kt:52-54`:
`runBlocking { diagLogEnabled.first() }` — first-ever DataStore read = file
open+parse on the cold-start critical path (10-50ms+ on slow storage; the "~1-5ms"
comment is optimistic). Fix: buffer DiagLog events until an async read completes
(keeps the crash-handler-first rationale). CONF high.

**2.4 No baseline profile** — no `androidx.profileinstaller`/baselineprofile module
(build.gradle.kts verified). Compose + Media3 cold start typically gains 15-30%
from a startup profile. Discussion §8.6. CONF high (mechanism), magnitude device-dependent.

**2.5 Theme subscribes to the 75-field SettingsUiState** — `Theme.kt:77-78`,
`ThemeAccent.kt:25-26`: app-root collects the full 75-flow combine (plain
`collectAsState`, keeps collecting in background; warm-up before first frame).
Fix: dedicated small flows (fontSizeScale, accentHex) + `collectAsStateWithLifecycle`.
CONF high.

---

## 3 — Steady-state playback cost (CPU / battery / jank)

**3.1 PlayerViewModel instance multiplication** — instances at `MiniPlayerBar.kt:43`,
`PlayerScreen.kt:72` + `:1221`, `VideoSurface.kt:78`, `FloatingVideoMiniPlayer.kt:54`,
each NavBackStackEntry-scoped and coexisting. Each runs ~14 collectors, a 5s
Room-persist ticker (`PlayerViewModel.kt:570`), two `SharingStarted.Eagerly`
stateIns (`:1013-1020`, `:1055-1060`), duplicate enrichment/SRT/RG side-effects.
Only the cold-start branch is process-guarded (`coldStartGuard:1932`). The logged
"10 identical effect resets in 1ms" bug was this. THE structural cost of the UI layer.
Fix: hoist playback-scoped side effects (persist tick, enrichment, SRT, RG apply)
into one singleton owner (PlaybackConnection or a session coordinator);
`WhileSubscribed(5000)` for uiState/artwork. DEP: coldStartGuard + ResumeGate token
flow move with the code, not duplicated; 5s tick must keep the reverse-cache skip
(`:603-604`) and Spotify-mirror branch (`:579-591`). CONF high.

**3.2 `mapToUiState` re-normalises the whole chapter list at 2-3 Hz per instance** —
`PlayerViewModel.kt:1836` inside `:1765-1880`, driven by the 500ms poll. Per tick:
N× `ChapterInfo.copy` + N× `TextNormalizer.normalize` (NFC + 8 replaces + regex,
`TextNormalizer.kt:64-79`) + 8 `String.format` + a fresh ~45-field PlayerUiState —
for strings that change only on track change. 300-chapter M4B ≈ 600+ allocs/sec ×
instances. Fix: normalise once per track change (cache keyed on mediaId/chapters
identity); per-tick work = position fields only. DEP: `isLoading` latency feeds the
450ms banner; Spotify overlay fields stay together. CONF high.

**3.3 Position tick recomposes the entire player tree** — `PlayerScreen.kt:74`
collects the whole uiState at the top; `TrackInfoSection`/`ChapterPickerChip` take
full uiState (`:628-630`, `:903-905`) → unskippable at 2 Hz; the `:577-583` comment
claiming smart-skip is wrong (value changes per tick). MiniPlayerBar +
FloatingVideoMiniPlayer subscribe to the same per-tick state on every other tab
(`MiniPlayerBar.kt:45`, `FloatingVideoMiniPlayer.kt:56`). Fix: narrow position into
its own flow read inside ProgressSliders/lyrics only; `MiniBarUi` projection +
`distinctUntilChanged`. DEP: keep hasMedia/isLoading/cloudFetchInProgress/
isVideoContent/isSpotifyActive in the top-level state (layout branches + empty-state
gate, `:111-113`). CONF high.

**3.4 Hue FFT runs for ALL local playback, ungated** — `HueAnalyserAudioProcessor.kt:179-264`
has no enabled check: mono downmix + 512-pt FFT on the audio thread even when Hue
is unpaired. Hann window recomputed with `cos()` per sample per buffer (`:221-224`;
twiddles ARE precomputed, the window is not); per-frame `TimedSnapshot` + 2×
`copyOf()` allocs (`:243-254`); `medianAndMad` boxes a Pair per frame
(`HueAudioAnalyser.kt:350-359`). ~0.5-2% of a core for every user, always.
Fix: `@Volatile` enabled supplier (paired && intensity>0 && streaming) → passthrough;
precompute Hann table; field-write instead of Pair. DEP: ring continuity — gate must
reset state on enable like `onFlush`; drive the flag from the PlaybackService
collector (`:1095-1250`). CONF high.

**3.5 Crossfade 10 Hz forever-ticker** — `PlaybackService.kt:1568-1574`: launched
unconditionally in onCreate, never paused (disabled, paused, idle background — still
10 wakeups/s); per-tick `albumTitle?.toString()` ×2 while playing (`:1601-1608`).
Fix: gate on `isPlaying && crossfadeMs>0` via the existing playingFlow pattern.
DEP: crossfade factor is shared with sleep-timer fades, pause/resume ramps
(`PlayerViewModel.kt:1095-1128`) and the RG mixer (`:334-345`); the ms==0
restore-to-1.0 branch (`:1579-1584`) must still run once after disable; a mid-fade
factor <1 must not be stranded. Note: `CrossfadeController.kt:140-157` overlap loop
is wall-clock and keeps stepping while paused (contradicts `pauseAll`, `:172-181`) —
behavioural bug to fix alongside. CONF high.

**3.6 Duplicate ReplayGain pipelines; one bypasses the volume mixer** —
Pipeline A `PlayerViewModel.kt:265-312` writes `player.volume` directly (`:303-305`),
sidestepping `applyMixedVolume`'s factor product — next crossfade tick silently
overwrites it. Pipeline B `:899-966` opens a full `MediaMetadataRetriever` per track
change hunting "dB" in GENRE (`:930-943`, network fetch for Drive URIs, release not
in finally). Fix: delete B (A already covers embedded tags + Room); route A through
`setReplayGainAttenuation`. DEP: RG-off must never reset user boost
(userBoostMb/replayGainBoostMb split, `:1908-1913`, `:1727-1737`). CONF high.

**3.7 PlaybackConnection 500ms poller never pauses** — `PlaybackConnection.kt:846-861`:
no isPlaying gate; `MainActivity.kt:239-241` disconnects only `if (isFinishing)` →
Home-press leaves it ticking indefinitely (PlayerState copy 2×/s). Doc drift: class
comment says 250ms. Fix: gate on isPlaying + lifecycle; emit once on pause so the
UI freezes at the right position. DEP: feeds slider, banner plumbing, PiP-params
collector (already distinct-filtered, `MainActivity.kt:143-163`). CONF high.

**3.8 Spotify 1 Hz poll never stops in background** — `SpotifyProvider.kt:108-110`
claims a >30s background stop; no such mechanism exists (no ProcessLifecycleOwner
anywhere — grep verified). Mirror active + Home = indefinite 1 Hz HTTPS + radio
wake. Per-iteration: full `AuthState.jsonDeserialize` of a multi-KB JSON
(`:1350-1373`) + Gson DOM of the tens-of-KB `/me/player` payload. Fix: lifecycle
observer pauses/resumes the poll; cache the deserialised AuthState next to the
existing `lastSerializedAuthState` debounce. DEP: pollGen generation guard, 45s
handoff grace, provisional mirror, 5×400ms warm-up burst. CONF high.

**3.9 Active Cast discovery whenever the Player tab is visible** —
`CastSwitcher.kt:111`: `CALLBACK_FLAG_REQUEST_DISCOVERY` for the button's lifetime
(mDNS/Wi-Fi battery drain), not just while the route sheet is open. Fix: passive
callback for icon state; REQUEST_DISCOVERY only while sheet open. CONF high.

**3.10 Widget refresh decodes full-res art on Main up to 3× per event** —
`NowPlayingWidgetProvider.kt:83-100` builds 3 RemoteViews variants (API 31+), each
`decodeByteArray` with no downsampling/cache (`:120-127`), triggered from
`onMediaItemTransition`/`onIsPlayingChanged`/`onMediaMetadataChanged`
(`PlaybackService.kt:1527-1565` — metadata fires repeatedly per track).
TransactionTooLarge risk with big art. Fix: decode once per mediaId with
inSampleSize to widget dp; debounce; skip building when `getAppWidgetIds` empty.
CONF high.

**3.11 Smaller per-tick churn** — six PlayerViewModel collectors re-run map/pair
lambdas per 500ms tick before their distinctUntilChanged
(`PlayerViewModel.kt:109-119, 201-207, 232-236, 265-271, 474-487, 644-648`) —
key them off the existing `currentMediaIdFlow` instead of position-bearing
playerState. `crossfadeAutoRevertReason` is a 750ms poll of a static field
(`:60-72`) — make it a StateFlow. Volume read = 2 binder IPCs per recomposition
(`PlayerScreen.kt:686-687`, `:947-948` → `getStreamVolume`) — hold in state updated
by a VOLUME_CHANGED receiver. BluetoothButton polls A2DP every 2s
(`BluetoothButton.kt:52-57`) — use AudioDeviceCallback. Teal shade getters
recompute HSL per read (`Color.kt:26-42`) — cache 11 shades, invalidate on accent
change (DEP: live-accent recolour). Effect chain: five processors memcpy every
buffer even when bypassed (`PlaybackService.kt:437-449`) — keep, but move
`duplicate()` below the bypass test (`StereoTransformProcessor.kt:62`) and memoise
the pow() in `GainAudioProcessor.kt:36-37` (DEP: per-buffer supplier reads are by
design for live toggling; bypass glide thresholds are click-free logic). CONF high.

---

## 4 — Interaction latency (scales with library size)

**4.1 Library search: full re-sort per keystroke, on Main, with a new Collator per
comparison** — `LibraryViewModel.kt:254-257` (no debounce), `:381-430`;
`TextNormalizer.kt:86-90` (`compare` = 2× normalize + `Collator.getInstance` PER
COMPARISON). 2k tracks ≈ ~22k comparisons ≈ 44k normalisations + 22k Collator
constructions per keystroke, twice (audio+video). Severe typing jank at scale; also
fires on every favourites/hidden emission. Fix: 250ms debounce +
`Dispatchers.Default`; precompute normalised haystack + `CollationKey` per file at
scan; one cached Collator. CONF high.

**4.2 Settings catalog rebuilt on every uiState emission** — `SettingsScreen.kt:145`
`remember(uiState)` reallocates 8 groups/20 items/keyword lists on EVERY settings
write (incl. continuous slider drags, e.g. font scale `:698-700`); non-lazy Column
composes all matching sections per search keystroke (`:768-811`). Fix:
`remember(Unit)` catalog with `rememberUpdatedState` readers; consider LazyColumn.
DEP: "expanded || searching" + keyed rememberSaveable semantics must hold exactly.
CONF high.

**4.3 Palette generated twice per artwork, on Main** — `PaletteHelper.kt:46-57`
(`extractColorSet` runs `generate()` then `extractStatusBarColor` runs it AGAIN);
call sites on Main (`CoverArtBackground.kt:79-83, 107-112`). Track-change jank.
Fix: one generate() reused, wrapped in `Dispatchers.Default`. CONF high.

**4.4 Full-res bitmap decodes for small targets** — `MiniPlayerBar.kt:80-86`
(3000² JPEG → ~36MB ARGB behind a 40dp box) + `CoverArtBackground.kt:50-76`
re-decodes the same bytes full-res (justified for背景, not the bar);
`allowHardware(false)` keeps software copies. Fix: inSampleSize to ~80px for the
bar; software copy only for the palette path. DEP: bytes-vs-URI precedence
(Spotify null-bytes fallback `PlayerViewModel.kt:1050-1060`). CONF high.

**4.5 Per-row linear scans** — `LibraryScreen.kt:629-630` `indexOfFirst` over the
whole library per favourite row (O(F×N) on scroll) — build an id→index map in
`remember(files)`. `CloudBrowserScreen.kt:790-801` three `.any{}` scans per row —
remember a HashSet. `:824` `hasOfflineCopy` reads a StateFlow `.value` in
composition (snapshot-invisible → stale Offline chip) — collect as state. CONF high.

**4.6 LastPlayed Room flows** — `LastPlayedScreen.kt:336, 622` plain
`collectAsState` (background requeries) + per-row bookmark observers (table-level
invalidation re-runs all visible rows on any write). Fix: withLifecycle + one
session-bookmarks query. Also `LastPlayedRepository.kt:77-96`: `combine` with
`flowOf(Unit)` emits once — pinned-album trackCount never refreshes (stale-count
bug); use a JOIN count. CONF high.

**4.7 Cloud `forceRefresh()` on every ON_RESUME** — `CloudBrowserScreen.kt:87-95`
bypasses the 30s staleness gate on every notification-shade peek/app switch.
Fix: keep forced refresh on the launcher-result paths (`:210, :235`); plain resume
→ `refreshIfStale(5_000)`. CONF med-high (OAuth-return UX must not regress).

---

## 5 — IO / network efficiency

**5.1 `M4bChapterParser.isRemote` misses `content://` Drive URIs** —
`M4bChapterParser.kt:59-60` (http/https only — read-verified). SAF-picked Drive
items (`content://com.google.android.apps.docs.storage/...`) are network-backed but
route to the full inline parse on first play/resume (`PlayerViewModel.kt:837-846`,
`LibraryViewModel.kt:443-444`, LastPlayedViewModel ×3) — the 75.9s incident class,
once per uncached file. Fix: treat non-local content authorities as remote
(cache-or-none + background fill). DEP: change only the predicate; keep
cache-or-none policy + markFilling dedup. CONF high (gap), med-high (hit frequency).

**5.2 DocumentFile N+1** — `GoogleDriveProvider.kt:295-298` + `toCloudItem:472-481`:
name/type/isDirectory/length = 4 separate ContentResolver IPCs per child; 300-file
folder ≈ 1200+ queries; `walkForSearch:330-349` recurses whole trees (match cap
doesn't cap traversal). Fix: one `buildChildDocumentsUriUsingTree` cursor with a
4-column projection per folder. CONF high.

**5.3 OkHttp fragmentation** — 12 separate clients app-wide; no `okhttp3.Cache`
anywhere; `SpotifyProvider.kt:206` + `DriveOAuthProvider.kt:64` bare clients with
NO callTimeout (stalled body read hangs a poll/play tap). Serial fan-outs:
`SpotifyProvider.fetchPerType:464-491` (3 endpoints), `DriveOAuthProvider
.searchFiles:253-276` (one call per folder), `CloudViewModel.kt:1296-1298`.
Fix: one shared base client + newBuilder() per concern; 10MB cache; callTimeouts;
async+awaitAll. CONF high.

**5.4 Alarm ring path blocks Main** — `FullScreenAlarmActivity.kt:84,103-109,132`:
`runBlocking` Room read (`:227`), unfiltered `scanLibraryAudioFiles().take(2000)`
MediaStore query (`:250-253`), synchronous `MediaPlayer.prepare()` (`:163`) — all
on Main at ring time. ANR/late-alarm risk for smart-playlist alarms.
Fix: hop to IO / prepareAsync. CONF high.

**5.5 Podcast sync** — `PodcastSyncWorker.kt:77-80`: constraint is CONNECTED not
UNMETERED (comment says Wi-Fi) → auto-downloads up to 5 full episodes per show over
mobile data every 6h; feeds fetched sequentially; blocking OkHttp on Default
dispatcher. Fix: UNMETERED (or per-show setting) + IO dispatcher + modest
parallelism. CONF high.

**5.6 SettingsDataStore write amplification** — single prefs file, ~120 keys, ~113
mapped flows; every `edit{}` rewrites + fsyncs the whole file and wakes every
collector. High-frequency writers: speed override per slider movement
(`PlayerViewModel.kt:1201-1210`), Hue intensity per drag tick
(`SettingsViewModel.kt:515-519`). Fix: debounce slider persists (commit on
release/300ms); optionally split hot keys into a second DataStore. Positive: the 5s
position persist goes to Room (indexed), not DataStore. CONF high (mechanism),
med (visible magnitude).

**5.7 Hue CLIP control plane** — `HueProvider.kt:311-313` re-reads bridge ip/key
from DataStore per `put()`; `setAll:950-955`/`applyScene:977-1043` loop lights
sequentially (20 lights = 40 reads + 20 serial HTTPS round-trips; Party ×10 rounds).
Fix: snapshot ip/key per operation; use the already-parsed `grouped_light` rid.
DEP: leave the dimmable driver + DTLS engine alone (already tuned). CONF high.

**5.8 ReplayGainScanner** — `ReplayGainScanner.kt:75` one Room upsert per file
(batch in one transaction); `:94-98` reflection per file; auto-scan feeds the
PLAYING Drive URI to MMR in parallel with playback (`PlayerViewModel.kt:241-254`,
default-off toggle); whole-library "Scan now" has no WorkManager wrapper (dies on
screen-off). CONF high (code), med (frequency).

**5.9 Receivers lack `goAsync()`** — `TaskerReceiver.kt:38-43`,
`AlarmReceiver.kt:36-43`, `BootCompletedReceiver.kt:28`: work launched into an
unawaited scope; process can die before the DataStore read + action completes —
BOOT_COMPLETED alarm reschedule is the riskiest. Fix: goAsync()+finally, or
expedited work. CONF high.

**5.10 CastRelayServer** — `CastRelayServer.kt:46` plain mutableMap read from worker
threads while writes are @Synchronized → visibility race = spurious 404/cast
flakiness (one-word fix: ConcurrentHashMap). `:101-113` InputStream leaked when
serve throws after open; `clear()` never called per queue swap (bounded-ish).
Range start via `skipFully` degrades to read-and-discard on pipe-backed providers —
prefer AssetFileDescriptor offset when available. CONF high (race), med (skip cost).

**5.11 Misc** — `ChapterCache.kt:48-93` disk IO inside @Synchronized (compute file
IO outside the monitor; DEP: write-through + markFilling). `markFilling` done in
the guard expression but unmark lives in a coroutine the VM may never launch
(`LastPlayedViewModel.kt:494-506`) — mark inside the coroutine. Drive head/tail
caches 32-64MB per item never pruned (`GoogleDriveProvider.kt:387,429-439`) — LRU
the `drive_*` namespace. ChapterCache disk tier unbounded + stale-keyed entries
never deleted (`ChapterCache.kt:40-46,97-102`). `LibraryViewModel
.createSingleMediaItem:787-805` parses chapters with no dispatcher and has no live
call sites — delete. DiagLog: `Channel.UNLIMITED` + FileWriter-per-line + crash
breadcrumb not flushed synchronously (`DiagLog.kt:77,146,242-260`;
`PowerMediaPlayerApp.kt:62-74`) — bounded channel, persistent BufferedWriter,
synchronous FATAL append. AppAuth `AuthorizationService` never `dispose()`d
(`SpotifyProvider.kt:205`) — browser ServiceConnection held for process life.
DrivePicker WebView destroyed while attached (`DrivePickerActivity.kt:175-181`) —
remove from parent first. CrossfadeController scope never cancelled / not aborted in
service onDestroy (`CrossfadeController.kt:50`; abort only at skip commands) —
secondary ExoPlayer can outlive the service. CONF high each, small individual impact.

---

## 6 — Form-factor bugs (phones / tablets / foldables)

**6.1 WindowSizeClass used ONLY by PlayerScreen** — `PlayerScreen.kt:133-155` is the
sole consumer; `AppNavigation.kt:159` passes it nowhere else; zero LazyVerticalGrid
app-wide; bottom NavigationBar always (no rail). Tablets/unfolded foldables get
stretched phone layouts everywhere. Play large-screen quality tiers fail.
Discussion §8.1. CONF high.

**6.2 Widget deep-link re-fires on every recreation** — `MainActivity.kt:94-96`
re-reads EXTRA_OPEN_TAB from the sticky launch intent; `pendingOpenTab` is NEVER
cleared (writes only at `:96`/`:218` — verified in-context; the `:212` comment
claims otherwise); `AppNavigation.kt:70-78` LaunchedEffect re-navigates. Since
configChanges omits density/uiMode/fontScale (manifest `:104`), fold/unfold or
theme/font change → user dumped onto the Player tab. Fix: null the state + 
`intent.removeExtra` after consumption. CONF high.

**6.3 Recreation while in PiP shows full chrome in the PiP window** —
`MainActivity.kt:70`: `isInPip` starts false, never seeded from
`isInPictureInPictureMode` in onCreate. Fix: seed it. CONF high.

**6.4 Video is letterboxed by global systemBarsPadding; no immersive mode; cutout
unhandled** — `MainActivity.kt:190-193` wraps ALL content; zero
`WindowInsetsControllerCompat.hide` / `displayCutoutPadding` / `safeDrawing`
app-wide. Bars stay visible for entire films; landscape cutout can overlap the
overlay controls (`PlayerScreen.kt:607-612`). Fix: per-screen insets + auto-hide
bars during video. CONF high (letterbox), med-high (cutout, OEM-dependent).

**6.5 Status-bar icons invisible on system-light devices** — `MainActivity.kt:97`
bare `enableEdgeToEdge()` = SystemBarStyle.auto (follows SYSTEM theme) while the
app is hard-forced dark (OledBlack): dark icons on black. Fix:
`SystemBarStyle.dark(TRANSPARENT)` both bars. Directly relevant to the open
edge-to-edge Play warning. CONF high.

**6.6 IME insets handled nowhere** — `adjustResize` is inert under edge-to-edge;
zero `imePadding()` app-wide. Keyboard covers: EQ band text fields
(`EqualizerScreen.kt:239-267, 358-397`), Settings fields, Library search results.
Worst in compact-height/split-screen. Fix: imePadding on scrolling containers.
CONF med-high.

**6.7 Rotation/fold during a ringing alarm restarts the alarm** — manifest
`:271-278`: FullScreenAlarmActivity has NO configChanges; onDestroy→stopRinging,
onCreate→startRinging: volume ramp, hold countdown, vibration, math challenge all
reset. Also deprecated systemUiVisibility + no insets (clock under cutout) and a
fixed ~500dp non-scrolling stack (Stop control can clip off-screen in
landscape/cover displays). Fix: configChanges parity + scrollable inset-aware
column (or service-owned ring state). CONF high.

**6.8 Compact-height overflow** — video overlay Column is non-scrolling when
`isVideoContent` (`PlayerScreen.kt:614-618`; ≈500dp+ of controls clip at <500dp
window heights — split screen/landscape phones); Library fixed header stack leaves
~0dp for the list (`LibraryScreen.kt:304-513`); LastPlayed pinned block rendered
with forEach above the weighted list (`LastPlayedScreen.kt:194-250` — 10 pins
exceed compact heights, recents collapse); `AlarmMediaPickerSheet.kt:179` fixed
540dp sheet taller than landscape windows. `heightSizeClass` is computed and
ignored. CONF high.

**6.9 Transport row overflows 320-345dp widths** — `PlaybackControls.kt:79-131`:
fixed 4×64dp + 72dp + spacers = 344dp minimum, no scroll/scaling → outer buttons
clip on small phones/split-portrait/narrow desktop windows. CONF high.

**6.10 Font-scale clipping; the documented fix landed in the UNUSED component** —
`SecondaryControls.kt:283-293`: `PreparedSpeedComponent` (the one PlayerScreen
calls) keeps hard `width(110.dp)`; sibling `SpeedControl:185-188` carries the
"clips at scale ≥1.4×" comment AND the `widthIn` fix — but is dead code.
Compounding: `Theme.kt:79-86` multiplies user font setting (≤2.0) on top of system
fontScale (≤2.0 on A14) → effective 4×. Other fixed-height victims:
`MiniPlayerBar.kt:56` (56dp, two text lines), `EqualizerScreen.kt:383-385`.
CONF high.

**6.11 FloatingVideoMiniPlayer drag unclamped** — `FloatingVideoMiniPlayer.kt:62-81`:
raw delta accumulation, no bounds vs container; fold/resize can strand the window
(and its ✕) off-screen; offsets reset only by accident (remember, not saveable).
Fix: clamp against parent constraints + re-clamp on size change. CONF high.

**6.12 PiP polish** — no `sourceRectHint` (jarring enter), no `setActions`
(no play/pause in the PiP chip; Media3 1.6 adds none automatically). Aspect clamp
verified correct in both paths. CONF high (absence), med (impact).

**6.13 Minor** — DrivePickerActivity configChanges lacks density (fold rebuilds the
WebView mid-pick; in-window resizes ARE handled via JS); widget XML lacks
minResizeWidth/Height and the API-30 path serves the 4×2 layout at all sizes with
40dp (<48dp) prev/next targets. T276's savedInstanceState guard verified correct
for its actual catch-set (density/uiMode/fontScale/locale/process-death — rotation
never recreates given configChanges). CONF high.

---

## 7 — Debug-build hygiene

**7.1** Hot-path log string assembly in `updatePlayerState`
(`PlaybackConnection.kt:903-914` — ConcurrentHashMap key iteration + multi-line
string per coalesced update). Release-stripped, but device-test builds are debug —
it skews the very profiles used for perf investigations. Sample or gate it.
**7.2** No StrictMode in debug — adding it (penaltyLog) would have caught 2.1-2.3
and 5.4 at commit time. CONF high.

---

## 8 — DISCUSSION ITEMS (decisions, not edits)

**8.1 Tablet/foldable layout strategy.** Everything below Expanded is a stretched
phone UI (6.1). Options: (a) NavigationRail + LazyVerticalGrid on Expanded widths +
two-pane Library/now-playing — the already-plumbed windowSizeClass makes this
mostly mechanical; (b) full adaptive redesign with list-detail scaffolds. (a) is
the pragmatic Play-large-screen-tier fix. Needs a decision on which screens matter
first (Library + LastPlayed are the heavy daily-use ones).

**8.2 Fold-posture awareness.** No `androidx.window` dependency — tabletop mode
(video top-half / controls bottom-half) and hinge-avoidance are unimplementable
today. Worth adopting before any foldable-focused marketing; otherwise the app is
"fold-safe" (after 6.2/6.3) but not "fold-aware".

**8.3 Video fullscreen/rotate affordance.** Zero orientation locks (good for A12+/
A16 large-screen policies), but a portrait-phone user watching 16:9 video has no
rotate-to-fullscreen button and bars never hide (6.4). Standard player affordance;
needs a UX decision (auto-hide on tap vs explicit button vs sensor-follow).

**8.4 PlayerViewModel consolidation (3.1) is an architecture change.** The cheap
fix (guards per side-effect) preserves today's shape; the right fix (playback-
scoped singleton coordinator) touches the cold-start/ResumeGate machinery that took
several rounds to stabilise. Recommend doing it as its own task with the existing
ResumeGateTest/ChapterCacheTest suites as the harness, not as a drive-by.

**8.5 Effect-chain bypass (3.11).** Media3 cannot drop a processor from the chain
mid-stream without an audible flush; the five-copy cost is real but modest. Verdict:
keep the chain, fix the per-buffer waste, do NOT attempt dynamic chain surgery.

**8.6 Baseline profile + R8 startup profile (2.4).** One-time build-infra task,
measurable cold-start win, zero runtime risk. Recommend before the Production push
alongside the edge-to-edge fix (6.5) which Play already warns about.

**8.7 Spotify poll lifecycle (3.8).** Pausing the poll in background trades
background-resume freshness (provisional mirror covers it) for battery. Recommend:
pause after 30s background as the code comment already promises, keep the warm-up
burst on foreground return.

**8.8 minSdk 30 + API-30-only widget path (6.13).** If Play stats show negligible
API 30-32 installs, raising minSdk to 33 deletes the legacy permission + widget
paths outright. Data-driven decision.

---

## 9 — Checked-clean (coverage record)

ResumeGate (atomics, bounded set); MediaOverrideRepository (event-driven);
EqualizerEffectController (dedup + release-on-rebuild); AudioDelayProcessor scratch
reuse; HueAudioAnalyser vc29.25 pre-allocations; ReverseAudio (disk-streamed,
SHA-keyed, capped); Spotify pollGen + token-write debounce; cumulativeSkip
debounce; PlaybackConnection.scheduleUpdate coalescer; audio-focus policy caching;
Hue start/stop collector (event-driven) + DTLS frame-buffer reuse + dimmable driver
tuning; sleep/A-B bounded polls; Room: zero main-thread queries, indices match the
hot writes; MediaStore projections narrow + stale-gated; RSS streaming parser;
CloudViewModel Drive metadata parses the downloaded temp file (75.9s-class fix
holds on that path); ProgressSliders/brightness drag patterns; VideoSurface binding
(no per-recomposition re-attach; healing stack sound); Lazy list keys present;
Cloud search debounced; SettingsViewModel combine itself (WhileSubscribed +
conflate); rememberSaveable holds primitives only; RTL hygiene; PiP aspect clamps;
FullScreenAlarmActivity lockscreen plumbing + teardown; SpotifyBounce teardown;
DeepLogger (debug-only, bounded queue, crash flush); BluetoothHelper proxy close;
AppModule (no activity contexts in singletons); no GlobalScope/Timer/Choreographer
in main source set; relay lifecycle bounded; onDestroy teardown otherwise complete.
