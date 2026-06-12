# Full audit remediation + adaptive redesign — master implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement every verified audit finding (§1-§7) and every approved §8 discussion item across phones, tablets and foldables, in dependency-ordered release batches, with evidence-gated check-offs.

**Architecture:** Six batches (A correctness/leaks → B startup/IO → C steady-state efficiency incl. ViewModel consolidation → D form-factor correctness → E video fullscreen UX → F adaptive redesign + fold posture → G release readiness). Each batch compiles, passes the full unit suite, and satisfies its predicate table before the next begins. Playback-core invariants (ResumeGate generations, Spotify provisional mirror, 450ms banner debounce, reverse-cache tick skip, RG/user-boost split, surface-handoff healing stack) are named per task wherever a task borders them.

**Tech Stack:** Kotlin 2.1.20, Compose BOM 2025.04 (+ `material3-adaptive`/`material3-adaptive-navigation-suite` in Batch F), Media3 1.6.0, Hilt, Room, DataStore, androidx.window (transitively via adaptive), `androidx.baselineprofile` (Batch G).

**Verification sources:** `docs/superpowers/specs/2026-06-11-perf-formfactor-audit.md` (findings) and `docs/superpowers/specs/2026-06-11-audit-verification-matrix.md` (per-finding verdicts; 2 amendments + 1 refinement noted there are reflected here).

---

## BINDING EXECUTION PROTOCOL (mirrors TASKS.md)

1. A checkbox may be ticked ONLY with evidence (command output, grep, test
   result, emulator dumpsys/diag line). Self-attestation is invalid.
2. No skipping, no deferring, no "later". If a task is blocked, record
   `BLOCKED(<reason> → <exact unblock>)` next to its checkbox and continue
   with independent tasks.
3. Batch gates are mandatory: run the batch's GATE section verbatim and paste
   the pass/fail table into this file under the gate before starting the next
   batch.
4. Update `TASKS.md` (one row per batch) the same turn a batch starts/ends.
5. Commit after every task (or tight task pair); push after every turn with
   commits. Phone install is BLOCKED(signature mismatch → user consents to
   data wipe, or ship via next Play build) — emulator is the device gate until
   then.
6. Credit-resilience: this file is the resume point. On session start, find
   the first unticked checkbox and continue from it.

**Standing invariants — every task in every batch must keep these true:**
- `ResumeGate.begin()/isCurrent()/end()` token flow around every restore/queue-swap path.
- Spotify provisional mirror: armed at tap sites, cleared on failure paths, 45s handoff grace, null-snap grace hold; `playPause` routes by `isSpotifyActive`.
- Loading banner: 450ms sustained `isLoading` debounce.
- 5s persist tick: skips `/reverse-cache/` paths and routes Spotify-mirror positions to the Spotify row.
- RG attenuation and user boost are separate channels; RG-off never resets user boost.
- `VideoSurfaceBinding` healing stack untouched.
- `startPositionMs` rides atomically inside `setMediaItems` (T279) — never reintroduce set-then-seek.

**Standard gate commands (referenced as GATE-STD in every batch):**
```powershell
Set-Location "C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player"
.\gradlew assembleDebug testDebugUnitTest --quiet; "EXIT=$LASTEXITCODE"   # expect EXIT=0
```
Emulator smoke (boot AVD `Medium_Phone_API_36.0` headless, install
`app\build\outputs\apk\debug\app-debug.apk`, launch, exercise the batch's
listed flows, pull `files/diag/log-current.txt` if assertions need it).

---

# BATCH A — correctness & leak fixes (target release: vc33)

## Task A1: Abandon audio focus (finding 1.1)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (~:1404-1448 request site; ~:2003-2026 onDestroy)

- [ ] **Step 1 — hold the request + listener in fields.** In the audio-focus install block (where `am.requestAudioFocus(req)` is called at ~:1440), the `AudioFocusRequest` (O+ path) and the listener are currently locals. Promote them:

```kotlin
// fields near the other service-scoped audio state
private var audioFocusRequest: android.media.AudioFocusRequest? = null
private var audioFocusListener: android.media.AudioManager.OnAudioFocusChangeListener? = null
```
Assign both where they are built (`audioFocusListener = listener`, and in the `Build.VERSION >= O` branch `audioFocusRequest = req`).

- [ ] **Step 2 — abandon in onDestroy.** Add to `onDestroy()` teardown (alongside the existing receiver unregistrations):

```kotlin
runCatching {
    val am = getSystemService(android.media.AudioManager::class.java)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
    } else {
        @Suppress("DEPRECATION")
        audioFocusListener?.let { am.abandonAudioFocus(it) }
    }
}
```

- [ ] **Step 3 — predicate.** Run:
`grep -rn "abandonAudioFocus" app/src/main/java/` → expect ≥1 hit in PlaybackService.kt onDestroy. Build green (GATE-STD compile only is fine here; full gate at batch end).

- [ ] **Step 4 — commit** `fix(service): abandon audio focus on destroy (audit 1.1)`.

## Task A2: Remove the Cast SessionManagerListener (finding 1.2)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (~:1333-1353 add site; onDestroy)

- [ ] **Step 1 — name the listener.** Replace the anonymous `object : SessionManagerListener<CastSession>` with a field:

```kotlin
private var castSessionListener: com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession>? = null
```
At the add site: build the object into a local `val l = object : SessionManagerListener<CastSession> { ... }`, then `castSessionListener = l; castContext.sessionManager.addSessionManagerListener(l, CastSession::class.java)` (keep the existing class argument exactly as currently written).

- [ ] **Step 2 — remove in onDestroy:**

```kotlin
runCatching {
    castSessionListener?.let {
        com.google.android.gms.cast.framework.CastContext.getSharedInstance()
            ?.sessionManager?.removeSessionManagerListener(it, com.google.android.gms.cast.framework.CastSession::class.java)
    }
}
```
(Use the same CastContext acquisition pattern the add site uses — if it holds a `castContext` field, prefer that.)

- [ ] **Step 3 — predicate.** `grep -rn "removeSessionManagerListener" app/src/main/java/` → ≥1 hit. Build green.

- [ ] **Step 4 — commit** `fix(cast): unregister session listener on service destroy (audit 1.2)`.

## Task A3: Bound the sender metadata/item caches (finding 1.3)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (:283-301 declarations; `onMediaItemTransition` listener ~:1527)

**Constraint (matrix DEP):** never evict ids present in the live queue — the caches are load-bearing for cast artwork survival and the cleartext-URI restore on cast-stop.

- [ ] **Step 1 — add an eviction helper** next to the companion maps:

```kotlin
/** Evict cache entries whose mediaId is no longer in the player's timeline.
 *  Live-queue ids must survive (cast artwork + cleartext restore depend on
 *  them); anything else is history and only wastes heap (artworkData can be
 *  megabytes per entry). */
fun pruneSenderCaches(liveIds: Set<String>) {
    senderMetadataByMediaId.keys.retainAll { it in liveIds }
    senderItemByMediaId.keys.retainAll { it in liveIds }
}
```

- [ ] **Step 2 — call it on every media-item transition.** In the service's `onMediaItemTransition` Player.Listener (the block that already calls `NowPlayingWidgetProvider.refresh`), append:

```kotlin
val p = exoPlayerRef?.get()
if (p != null) {
    val live = buildSet {
        for (i in 0 until p.mediaItemCount) add(p.getMediaItemAt(i).mediaId)
    }
    pruneSenderCaches(live)
}
```
Use whatever non-null player reference the surrounding listener already uses (it receives events from the session player — mirror the existing access pattern in that listener rather than `exoPlayerRef` if the listener body uses a different handle; when casting, the CastPlayer timeline ids are the live set, which is exactly right).

- [ ] **Step 3 — unit test** (JVM, no Robolectric needed — extract the retain logic if the inline version resists testing):
Test file: `app/src/test/java/com/powermediaplayer/SenderCachePruneTest.kt`

```kotlin
class SenderCachePruneTest {
    @Test fun `prune drops dead ids and keeps live ones`() {
        PlaybackService.senderMetadataByMediaId.clear()
        PlaybackService.senderItemByMediaId.clear()
        PlaybackService.senderMetadataByMediaId["live"] = androidx.media3.common.MediaMetadata.EMPTY
        PlaybackService.senderMetadataByMediaId["dead"] = androidx.media3.common.MediaMetadata.EMPTY
        PlaybackService.pruneSenderCaches(setOf("live"))
        assertEquals(setOf("live"), PlaybackService.senderMetadataByMediaId.keys)
    }
}
```
Run: `.\gradlew testDebugUnitTest --tests "*SenderCachePruneTest*"` → PASS.

- [ ] **Step 4 — device predicate (batch gate):** play 3 different tracks on the emulator, then `adb shell dumpsys meminfo com.powermediaplayer | grep TOTAL` recorded before/after (informational), and assert via DiagLog/debugger is NOT required — the unit test + transition wiring grep (`grep -n "pruneSenderCaches" PlaybackService.kt` → 2 hits: definition + call) is the evidence.

- [ ] **Step 5 — commit** `fix(service): evict sender caches to live-queue ids (audit 1.3)`.

## Task A4: HueEntertainment try/finally + handshake socket safety (finding 1.4)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/hue/HueEntertainment.kt` (stream loop ~:300-483; `connectDtls` :504-533; `stop()` :485-500)

**Why this likely feeds the open disconnect→reconnect regression:** the send-failure `cancel()` skips the close + `stopEntertainmentStream` PUT, so the bridge still believes a stream is active when the next start arrives.

- [ ] **Step 1 — wrap the streaming body.** Inside `streamJob = scope.launch { ... }`, wrap everything from after the handshake through the `while` loop in `try { ... } finally { ... }` and move the existing cleanup lines (currently after the loop at ~:478-481) into the `finally`:

```kotlin
try {
    // existing: header build, while (isActive) { ...frame send... delay(frameMs) }
} finally {
    runCatching { dtls?.close() }
    runCatching { socket?.close() }
    dtls = null
    socket = null
    val a = areaId
    if (a.isNotBlank()) {
        // own scope: the job is dying; the PUT must outlive it
        scope.launch { hueProvider.stopEntertainmentStream(a) }
    }
    DiagLog.event("HUE", "entertainment STOPPED (finally)")
}
```
Keep `stop()` as-is (it cancels the job → the finally now runs; its own close calls become harmless no-ops on nulled fields — keep them for the not-yet-started case).

- [ ] **Step 2 — close the socket on handshake failure.** In `connectDtls`, wrap from socket creation to handshake:

```kotlin
val sock = DatagramSocket()
try {
    sock.connect(address, BRIDGE_DTLS_PORT)
    socket = sock
    // ... existing transport/crypto/client setup ...
    dtls = DTLSClientProtocol().connect(client, transport)
} catch (t: Throwable) {
    runCatching { sock.close() }
    socket = null
    throw t
}
```

- [ ] **Step 3 — predicate.** `grep -n "finally" app/src/main/java/com/powermediaplayer/hue/HueEntertainment.kt` → ≥2 hits (stream body + connectDtls). Build green.

- [ ] **Step 4 — device predicate (batch gate, needs Hue hardware → run on the PHONE pass):** with diag logging on: start Hue stream → kill Wi-Fi mid-stream → re-enable → re-pick area → lights respond. Diag shows `entertainment STOPPED (finally)` after the send failure. If phone unavailable, record `BLOCKED(needs Hue bridge → phone device pass)` on THIS STEP ONLY; steps 1-3 still complete the code task.

- [ ] **Step 5 — commit** `fix(hue): stream cleanup in finally + handshake socket safety (audit 1.4)`.

## Task A5: Stop the Hue stream in service onDestroy (finding 1.5)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (onDestroy ~:2003-2026)

- [ ] **Step 1 —** add `runCatching { hueEntertainment.stop() }` to `onDestroy()` before `serviceScope.cancel()` (the field is already injected — same instance the collector uses at :1152/:1247).
- [ ] **Step 2 — predicate.** `grep -n "hueEntertainment.stop()" PlaybackService.kt` → ≥3 hits (2 collector + 1 onDestroy). Build green.
- [ ] **Step 3 — commit** `fix(hue): stop entertainment stream on service destroy (audit 1.5)`.

## Task A6: CrossfadeController teardown (finding 5.11g)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/CrossfadeController.kt` (:50 scope)
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (onDestroy)

- [ ] **Step 1 —** add to CrossfadeController:

```kotlin
/** Service is dying: abort any in-flight overlap (releases the secondary
 *  player) and stop the scope so no step coroutine outlives the service. */
fun shutdown() {
    abort()
    scope.cancel()
}
```
(`abort()` already exists — it is called at the skip-command sites; verify it releases the secondary player; if `abort()` only cancels the job, add `secondary?.release(); secondary = null` inside it guarded by `runCatching`.)

- [ ] **Step 2 —** call `runCatching { crossfadeController.shutdown() }` in `PlaybackService.onDestroy()`.
- [ ] **Step 3 — predicate.** grep `shutdown()` 2 hits; build green. Commit `fix(crossfade): release secondary player on service destroy (audit 5.11)`.

## Task A7: CastRelayServer race + stream hygiene (finding 5.10)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/CastRelayServer.kt` (:46 map; serve() :76-130)

- [ ] **Step 1 —** `private val items: MutableMap<String, RelayItem> = mutableMapOf()` → `java.util.concurrent.ConcurrentHashMap<String, RelayItem>()`. Remove now-redundant `@Synchronized` from pure-read paths only if any exist; keep it on compound write methods.
- [ ] **Step 2 —** in `serve()`, ensure the opened `InputStream` is closed on every failure path between `openInputStream` and the `newChunkedResponse` handover:

```kotlin
val input = resolver.openInputStream(item.uri) ?: return notFound()
try {
    if (start > 0) input.skipFully(start)
    return buildResponse(input, ...)   // success: NanoHTTPD owns the stream
} catch (t: Throwable) {
    runCatching { input.close() }
    throw t
}
```
(Adapt names to the actual local structure — the rule: any throw after open and before a Response wraps the stream must close it.)
- [ ] **Step 3 — predicate.** grep `ConcurrentHashMap` in CastRelayServer.kt → 1 hit; build green. Commit `fix(cast-relay): concurrent token map + close stream on failed serve (audit 5.10)`.

## Task A8: goAsync in manifest receivers (finding 5.9)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/integration/TaskerReceiver.kt` (:38-43)
- Modify: `app/src/main/java/com/powermediaplayer/alarm/AlarmReceiver.kt` (:36-43)
- Modify: `app/src/main/java/com/powermediaplayer/alarm/BootCompletedReceiver.kt` (:28 area)

- [ ] **Step 1 —** identical pattern in each `onReceive` (TaskerReceiver shown; mirror in the other two with their existing bodies):

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (!action.startsWith("com.powermediaplayer.action.")) return
    val pending = goAsync()
    scope.launch {
        try {
            // existing body unchanged
        } finally {
            pending.finish()
        }
    }
}
```
(BootCompletedReceiver keeps its own action filtering; the `goAsync` + `finally finish` wrapper is the change.)
- [ ] **Step 2 — predicate.** `grep -rn "goAsync" app/src/main/java/` → 3 hits. Build green. Commit `fix(receivers): goAsync so work survives process teardown (audit 5.9)`.

## Task A9: onTaskRemoved timeout (finding 2.2)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (:1981-1985)

- [ ] **Step 1 —** wrap the read:

```kotlin
val stopUnconditionally = runCatching {
    runBlocking { kotlinx.coroutines.withTimeoutOrNull(200) {
        settingsDataStore.stopOnTaskRemoved.first()
    } ?: false }
}.getOrDefault(false)
```
(Default false = the safer behaviour: do not stop playback if the read can't complete.)
- [ ] **Step 2 — predicate.** grep `withTimeoutOrNull` in the onTaskRemoved region → 1 hit. Build green. Commit `fix(service): bound onTaskRemoved DataStore read (audit 2.2)`.

## Task A10: AppAuth dispose + WebView detach + ChapterCache markFilling (findings 5.11)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt` (:205 lazy authService; signOut path)
- Modify: `app/src/main/java/com/powermediaplayer/cloud/DrivePickerActivity.kt` (:175-181)
- Modify: `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedViewModel.kt` (:490-506)

- [ ] **Step 1 — AppAuth.** Replace the `by lazy` singleton with create-per-flow: make `authService` a `private var authService: AuthorizationService? = null`; add `private fun authService(): AuthorizationService = authService ?: AuthorizationService(context).also { authService = it }` used at the existing call sites; add `fun disposeAuthService() { runCatching { authService?.dispose() }; authService = null }` and call it (a) at the end of the OAuth completion path (after token exchange persists) and (b) in `signOut()`.
- [ ] **Step 2 — WebView.** In `DrivePickerActivity.onDestroy()` before `webView.destroy()`:

```kotlin
runCatching {
    webView.stopLoading()
    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
}
```
- [ ] **Step 3 — markFilling placement.** At LastPlayedViewModel :490-506, the dedup mark currently happens in the guard expression while `unmarkFilling` lives inside the launched coroutine's `finally`. Move the mark inside the coroutine:

```kotlin
if (isRemote && mediaItem.mediaMetadata.extras?.getInt("chapter_count", 0) == 0) {
    viewModelScope.launch(Dispatchers.IO) {
        if (!com.powermediaplayer.util.ChapterCache.shared.markFilling(item.mediaUri)) return@launch
        val tFill = com.powermediaplayer.diag.DiagLog.now()
        try {
            // existing parse + inject body unchanged
        } finally {
            com.powermediaplayer.util.ChapterCache.shared.unmarkFilling(item.mediaUri)
        }
    }
}
```
- [ ] **Step 4 — predicates.** grep `dispose()` in SpotifyProvider → ≥1; grep `removeView` in DrivePickerActivity → 1; grep `markFilling` inside the launch in LastPlayedViewModel (read the region). Build green. Commit `fix(lifecycle): AppAuth dispose, WebView detach-before-destroy, markFilling inside fill coroutine (audit 5.11)`.

## BATCH A GATE

- [ ] GATE-STD green (`assembleDebug testDebugUnitTest` EXIT=0) — paste output line.
- [ ] Predicate greps (one command, all must hit): `grep -rn "abandonAudioFocus|removeSessionManagerListener|pruneSenderCaches|goAsync|withTimeoutOrNull(200)" app/src/main/java/ | wc -l` ≥ 7.
- [ ] Emulator smoke: install, launch, play local test tone, force-stop, relaunch → restore still lands at saved−backoff (T279 regression check; dumpsys position ≠ 0).
- [ ] `TASKS.md` row updated with evidence; commit `docs(ledger)`; push.

---

# BATCH B — startup & IO (target release: vc33)

## Task B1: Collapse service-onCreate runBlocking cluster to one snapshot (finding 2.1)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/data/preferences/SettingsDataStore.kt` (add snapshot accessor)
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (:388-394, :726-737, :791-815, :824-832)

- [ ] **Step 1 — snapshot accessor** in SettingsDataStore:

```kotlin
/** One blocking-capable read of the whole preferences file. The service
 *  cold-start needs ~6 values before first audio; reading them through one
 *  Preferences snapshot costs one disk hit instead of four sequential
 *  runBlocking round-trips on the main thread. */
suspend fun snapshot(): androidx.datastore.preferences.core.Preferences =
    context.dataStore.data.first()
```
- [ ] **Step 2 — service onCreate.** Replace the four `runBlocking { ...first() }` blocks with ONE:

```kotlin
val prefsSnap = runCatching {
    runBlocking { kotlinx.coroutines.withTimeoutOrNull(600) { settingsDataStore.snapshot() } }
}.getOrNull()
```
then read each value from `prefsSnap` via the SAME keys the individual flows use. The keys are private to SettingsDataStore today — add narrow accessors beside `snapshot()`:

```kotlin
fun swDecodingFrom(p: Preferences?): Boolean = p?.get(SW_DECODING) ?: false
fun btMappingFrom(p: Preferences?): BtMapping = /* same defaulting logic the flow's map{} uses */
fun pitchSpeedSeedFrom(p: Preferences?): Pair<Float, Float> = /* ditto */
fun focusPolicyFrom(p: Preferences?): FocusPolicy = /* ditto */
```
IMPORTANT: copy the defaulting logic from each existing flow's `map {}` body verbatim so semantics are identical; the flows themselves stay untouched (live updates unaffected). Keep the pitch/speed seed applied before first playback (standing invariant from :786-790).
- [ ] **Step 3 — measure.** Debug build, diag on, cold start: `PlaybackService.onCreate START`→`DONE` delta in diag log recorded before/after; expect reduction (typical 100-400ms cold-disk). Evidence = the two diag lines.
- [ ] **Step 4 —** GATE-STD; commit `perf(startup): single preferences snapshot for service cold-start seeds (audit 2.1)`.

## Task B2: DiagLog buffered init off the critical path (finding 2.3)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/diag/DiagLog.kt`
- Modify: `app/src/main/java/com/powermediaplayer/PowerMediaPlayerApp.kt` (:46-60)

- [ ] **Step 1 —** add to DiagLog a pre-init buffer: events logged before `init` land in a bounded ArrayDeque(256) and flush into the channel on `init`. (DiagLog already no-ops when disabled; the buffer only matters for the first ~50ms.)

```kotlin
private val preInit = ArrayDeque<String>()
@Volatile private var initialised = false
// in event(): if (!initialised) { synchronized(preInit) { if (preInit.size < 256) preInit += line }; return }
// at end of init(): initialised = true; synchronized(preInit) { preInit.forEach(channel::trySend); preInit.clear() }
```
- [ ] **Step 2 —** in `PowerMediaPlayerApp.onCreate`, delete the `runBlocking { diagLogEnabled.first() }` + replace with async:

```kotlin
DiagLog.init(this, startEnabled = false)   // safe default; buffer catches early events
appScope.launch {
    settingsDataStore.diagLogEnabled.collect { DiagLog.setEnabled(it) }
}
```
(The crash handler still installs after `init`, so FATAL events always have a sink; with the toggle on, the first collected emission enables the logger within ~50ms and the buffer preserves anything earlier. NOTE: `DiagLog.setEnabled(true)` must trigger session-header writing if init wrote nothing — check `setEnabled`'s existing behaviour and keep the "fresh file per enable" semantics.)
- [ ] **Step 3 —** predicate: grep `runBlocking` in PowerMediaPlayerApp.kt → 0 hits. GATE-STD. Commit `perf(startup): remove blocking DataStore read from Application.onCreate (audit 2.3)`.

## Task B3: `isRemote` covers network-backed content:// (finding 5.1)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/util/M4bChapterParser.kt` (:56-60)
- Test: `app/src/test/java/com/powermediaplayer/M4bIsRemoteTest.kt`

- [ ] **Step 1 — failing test first** (Robolectric for Uri.parse):

```kotlin
@RunWith(org.robolectric.RobolectricTestRunner::class)
class M4bIsRemoteTest {
    @Test fun `http and https are remote`() {
        assertTrue(M4bChapterParser.isRemote(Uri.parse("https://x/y.m4b")))
        assertTrue(M4bChapterParser.isRemote(Uri.parse("http://x/y.m4b")))
    }
    @Test fun `drive documents provider is remote`() {
        assertTrue(M4bChapterParser.isRemote(Uri.parse(
            "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dabc")))
    }
    @Test fun `local mediastore and external storage are NOT remote`() {
        assertFalse(M4bChapterParser.isRemote(Uri.parse("content://media/external/audio/media/71")))
        assertFalse(M4bChapterParser.isRemote(Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.m4b")))
        assertFalse(M4bChapterParser.isRemote(Uri.parse("file:///sdcard/a.m4b")))
    }
}
```
Run → FAIL on the Drive case.
- [ ] **Step 2 — implementation:**

```kotlin
/** Local authorities whose streams are file-backed (seek = lseek). Anything
 *  else reachable over a DocumentsProvider may stream from the network —
 *  both chapter strategies would pull most of a multi-GB file. */
private val LOCAL_CONTENT_AUTHORITIES = setOf(
    "media",
    "com.android.externalstorage.documents",
    "com.android.providers.media.documents",
    "com.android.providers.downloads.documents"
)
fun isRemote(uri: Uri): Boolean = when (uri.scheme) {
    "http", "https" -> true
    "content" -> uri.authority !in LOCAL_CONTENT_AUTHORITIES
    else -> false
}
```
- [ ] **Step 3 —** run the test class → 3/3 PASS. Full unit suite (ChapterCacheTest must stay green).
- [ ] **Step 4 —** commit `fix(chapters): treat cloud DocumentsProvider URIs as remote (audit 5.1)`.

## Task B4: Drive/SAF folder listing via one child cursor (finding 5.2)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/cloud/GoogleDriveProvider.kt` (:295-298 listing; :330-349 walkForSearch; :472-481 toCloudItem)

- [ ] **Step 1 —** add a cursor-based lister:

```kotlin
private data class ChildDoc(val documentId: String, val name: String, val mime: String, val size: Long)

/** One ContentResolver query per folder instead of 3-4 IPC round-trips per
 *  child via DocumentFile (documented N+1: every .name/.type/.length is a
 *  separate query on TreeDocumentFile). */
private fun listChildrenFast(treeUri: Uri, parentDocumentId: String): List<ChildDoc> {
    val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri, parentDocumentId)
    val out = mutableListOf<ChildDoc>()
    context.contentResolver.query(
        childrenUri,
        arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_SIZE
        ), null, null, null
    )?.use { c ->
        while (c.moveToNext()) {
            out += ChildDoc(c.getString(0), c.getString(1) ?: "",
                c.getString(2) ?: "", if (c.isNull(3)) -1L else c.getLong(3))
        }
    }
    return out
}
```
- [ ] **Step 2 —** rewire `listFolder` (:295-298) and `walkForSearch` (:330-349) to consume `ChildDoc` (build the same `CloudMediaItem` fields `toCloudItem` produced: id from `DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)`, directory test = `mime == DocumentsContract.Document.MIME_TYPE_DIR`). Keep the 200-match cap AND add a traversal cap (`maxNodesVisited = 5_000`) to walkForSearch so giant trees stop even without matches; surface the truncation in the existing search-result path the same way the match cap does.
- [ ] **Step 3 —** emulator predicate: SAF-browse any folder (the emulator's Downloads works through the same DocumentsProvider API), listing renders; diag/log unchanged. GATE-STD.
- [ ] **Step 4 —** commit `perf(drive): single child-cursor folder listing + bounded search traversal (audit 5.2)`.

## Task B5: OkHttp consolidation (finding 5.3)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/di/AppModule.kt`
- Modify (call sites): `cloud/SpotifyProvider.kt` (:206), `cloud/DriveOAuthProvider.kt` (:64), `cloud/GoogleDriveProvider.kt`, `podcast/PodcastDownloader.kt`, `podcast/ITunesPodcastSearch.kt`, `podcast/RssFeedParser.kt`, `enrichment/MusicBrainzClient.kt`, `enrichment/DiscogsClient.kt`, `hue/HueProvider.kt`, `hue/HueDimmableDriver.kt`, `service/CastRelayServer.kt` (token fetch), `webhooks/WebhookEmitter.kt`

- [ ] **Step 1 —** provide a base client in AppModule:

```kotlin
@Provides @Singleton
fun provideBaseOkHttp(@ApplicationContext ctx: Context): okhttp3.OkHttpClient =
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .cache(okhttp3.Cache(java.io.File(ctx.cacheDir, "http_cache"), 10L * 1024 * 1024))
        .build()
```
- [ ] **Step 2 —** each consumer takes the base client (constructor-inject where the class is Hilt-managed; pass through where constructed manually) and derives per-concern variants via `baseClient.newBuilder()...build()` — derived clients share the pool/dispatcher/cache. Specific derivations: Hue bridge client keeps its existing TLS-trust customisation (`newBuilder()` from base, re-apply the existing sslSocketFactory/hostnameVerifier block verbatim); WebhookEmitter keeps its 4s timeouts (`newBuilder().callTimeout(4, SECONDS)...`); streaming media fetches (Drive range downloads) use `newBuilder().readTimeout(0, SECONDS).callTimeout(0, SECONDS)` — call timeouts must NOT cap long media transfers; the two bare `OkHttpClient()` sites (:206/:64) just take the base.
- [ ] **Step 3 —** parallelise the serial fan-outs (same task, they're 3 lines each now the client is shared): `SpotifyProvider.fetchPerType` :464-491 → `coroutineScope { val a = async{...}; val b = async{...}; val c = async{...} }`; `DriveOAuthProvider.searchFiles` :253-276 → `awaitAll` over folder queries; `CloudViewModel` :1296-1298 → two `async`.
- [ ] **Step 4 —** predicate: `grep -rn "OkHttpClient()" app/src/main/java/ | wc -l` → 0; `grep -rn "newBuilder()" app/src/main/java/ | wc -l` ≥ 6. GATE-STD. Emulator smoke: Spotify sign-in survives (token refresh path), Drive listing works, podcast search works.
- [ ] **Step 5 —** commit `perf(net): shared OkHttp pool + cache + call timeouts; parallel fan-outs (audit 5.3)`.

## Task B6: Alarm ring path off Main (finding 5.4)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/alarm/FullScreenAlarmActivity.kt` (:103-174, :219-253)

- [ ] **Step 1 —** make `startRinging` suspend and hop the heavy part:

```kotlin
private suspend fun startRinging(alarm: AlarmRecord) {
    // volume + vibration setup stays on Main (cheap, UI-adjacent)
    ...
    val uri = withContext(Dispatchers.IO) {
        val resolved = resolveAlarmMediaUri(alarm.mediaUri)   // Room + MediaStore scan now off-Main
        if (resolved.isNotBlank()) Uri.parse(resolved)
        else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }
    runCatching {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(attrs)
            isLooping = true
            setDataSource(this@FullScreenAlarmActivity, uri)
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }...
}
```
Inside `resolveAlarmMediaUri`, replace its internal `runBlocking { dao... }` with a direct suspend call (the function is now called from IO context) — change its signature to `private suspend fun resolveAlarmMediaUri(...)`.
- [ ] **Step 2 —** ring start latency guard: `prepareAsync` may add ~100ms before audio; acceptable (alarm fires the moment prepared). The vibration (already started before prepare) covers the gap.
- [ ] **Step 3 —** predicate: grep `runBlocking` in FullScreenAlarmActivity.kt → 0; grep `prepareAsync` → 1. GATE-STD. Emulator: create an alarm 1 minute out (UI drive), let it fire, ring + Stop work.
- [ ] **Step 4 —** commit `fix(alarm): resolve media + prepare off the main thread (audit 5.4)`.

## Task B7: Podcast sync constraints + dispatcher + parallel feeds (finding 5.5)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/podcast/PodcastSyncWorker.kt` (:54-80)

- [ ] **Step 1 —** constraint `NetworkType.CONNECTED` → `NetworkType.UNMETERED` (matches the "Runs every 6h on Wi-Fi" comment).
- [ ] **Step 2 —** in `doWork`, wrap feed work in `withContext(Dispatchers.IO)` and fetch feeds with bounded parallelism:

```kotlin
val sem = kotlinx.coroutines.sync.Semaphore(3)
coroutineScope {
    shows.map { show -> async { sem.withPermit { syncShow(show) } } }.awaitAll()
}
```
(`syncShow` = the existing per-show body extracted into a private suspend fun, unchanged.)
- [ ] **Step 3 —** predicate: grep `UNMETERED` → 1; GATE-STD. Commit `fix(podcast): Wi-Fi-only auto-sync, IO dispatcher, bounded parallel feeds (audit 5.5)`.

## Task B8: Hue CLIP snapshot + scene loop (finding 5.7)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/hue/HueProvider.kt` (:311-313 put(); :950-955 setAll; :977-1043 applyScene)

- [ ] **Step 1 —** `put()` gains an overload taking `(ip, key)`; `setAll`/`applyScene` read `hueBridgeIp.first()` + `hueAppKey.first()` ONCE at entry and pass them through the loop.
- [ ] **Step 2 —** where a grouped-light rid is already parsed (:521-524), `setAll` uses ONE `grouped_light` PUT instead of per-light loops when a group rid exists for the target area; fall back to the per-light loop otherwise. `applyScene`'s per-light colour assignments stay per-light (distinct colours per light is the point of a scene) but run with `async` parallelism capped at 4 — the bridge tolerates this; the dimmable driver's own backoff machinery is untouched (matrix DEP).
- [ ] **Step 3 —** predicate: grep `first()` inside `put(` body → 0 (moved to entry points). GATE-STD. Device check on phone pass (Hue hardware): presets apply visibly faster; record BLOCKED on this step only if phone unavailable.
- [ ] **Step 4 —** commit `perf(hue): snapshot bridge creds once per op; grouped PUT; capped parallel scene writes (audit 5.7)`.

## Task B9: ReplayGain scanner batching (finding 5.8 — write path only; pipeline merge is C6)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/replaygain/ReplayGainScanner.kt` (:75, :94-98)
- Modify: `app/src/main/java/com/powermediaplayer/data/db/dao/ReplayGainDao.kt`

- [ ] **Step 1 —** add `@Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(rows: List<ReplayGainEntity>)` to the DAO; scanner calls it once (`dao.upsertAll(final)`) instead of the per-row loop.
- [ ] **Step 2 —** hoist the reflection lookup (:94-98) to a `companion object { private val LOUDNESS_KEY: Int? = runCatching { ... }.getOrNull() }` evaluated once.
- [ ] **Step 3 —** GATE-STD; commit `perf(replaygain): batch upserts + one-time reflection (audit 5.8)`.

## Task B10: Slider persist debounce (finding 5.6)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` (:1201-1210 speed override persist)
- Modify: `app/src/main/java/com/powermediaplayer/ui/settings/SettingsViewModel.kt` (:515-519 hue intensity)

- [ ] **Step 1 —** debounce both writers with a conflated channel pattern:

```kotlin
private val speedPersist = MutableStateFlow<Float?>(null)
init { viewModelScope.launch {
    speedPersist.filterNotNull().debounce(300).collect { v ->
        /* the existing persist call body */
    }
} }
// setPlaybackSpeed(): apply to player immediately (unchanged), then speedPersist.value = speed
```
Mirror the same shape for `setHueReactiveIntensity` (apply live value immediately — the engine reads the flow; only the DataStore WRITE debounces).
- [ ] **Step 2 —** verify the final value always lands (debounce flushes the last emission). Unit test the debounce shape if extracted; otherwise emulator predicate: drag speed slider rapidly, final speed persists across restart (cold-start seed reads it).
- [ ] **Step 3 —** GATE-STD; commit `perf(datastore): debounce slider persists (audit 5.6)`.

## Task B11: ChapterCache IO outside monitor + drive cache trim + DiagLog writer (findings 5.11a/c/d)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/util/ChapterCache.kt` (:40-102)
- Modify: `app/src/main/java/com/powermediaplayer/cloud/GoogleDriveProvider.kt` (:429-439 download cache)
- Modify: `app/src/main/java/com/powermediaplayer/diag/DiagLog.kt` (:77, :146, :242-260)

- [ ] **Step 1 — ChapterCache:** compute SHA + serialise OUTSIDE the lock; the `@Synchronized` block shrinks to map/file-pointer updates. On `put`, delete sibling disk entries for the same uri (different mtime/size keys) — list the cache dir filtered by the uri-hash prefix (make the filename `«uriHash»-«tokenHash».json` so the prefix scan is cheap; migrate by ignoring old-format files, they become unreachable cold entries — ALSO add a one-shot size-cap sweep (drop oldest beyond 5MB total) on first attach.
- [ ] **Step 2 — Drive head/tail caches:** after a successful parse in the download path (:429-439 callers), delete the `drive_<hash>_head`/`_tail` files; add an LRU trim of the `drive_*` namespace to ≤256MB at each new download start.
- [ ] **Step 3 — DiagLog:** bounded channel `Channel(capacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)`; writer keeps ONE `BufferedWriter` open per file, `flush()` every 32 lines or 500ms (whichever first), reopen on rotation; crash handler writes the FATAL line synchronously (`runCatching { File(cur).appendText(line + "\n") }`) in addition to the channel.
- [ ] **Step 4 —** unit: ChapterCacheTest stays green + add a test asserting stale-sibling deletion on put. GATE-STD. Commit `perf(io): chapter-cache lock scope + eviction, drive cache trim, diag writer (audit 5.11)`.

## Task B12: StrictMode in debug (finding 7.2)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/PowerMediaPlayerApp.kt`

- [ ] **Step 1 —**

```kotlin
if (BuildConfig.DEBUG) {
    android.os.StrictMode.setThreadPolicy(
        android.os.StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork()
            .penaltyLog().build())
    android.os.StrictMode.setVmPolicy(
        android.os.StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects().detectActivityLeaks()
            .penaltyLog().build())
}
```
(penaltyLog only — never penaltyDeath; existing known-blocking seeds in the service are deliberate and bounded.)
- [ ] **Step 2 —** GATE-STD; emulator boot with logcat `StrictMode` review — file NEW findings to TASKS.md, fix only regressions introduced by this plan. Commit `chore(debug): StrictMode logging (audit 7.2)`.

## BATCH B GATE

- [ ] GATE-STD EXIT=0 (paste line). Unit suites incl. new M4bIsRemoteTest 3/3.
- [ ] Greps: `runBlocking` count in PowerMediaPlayerApp.kt = 0, FullScreenAlarmActivity.kt = 0; `OkHttpClient()` = 0 app-wide.
- [ ] Emulator: cold-start restore regression check (position ≠ 0); Drive/SAF browse OK; podcast search OK.
- [ ] Diag before/after `PlaybackService.onCreate START→DONE` delta recorded in this file.
- [ ] `TASKS.md` updated; commit + push.

---

# BATCH C — steady-state playback efficiency (target release: vc34)

> Order inside C matters: C1 (coordinator) first — C2/C3/C6 build on the
> slimmed ViewModel. C1 is the highest-risk task in the whole plan; it moves
> code that took multiple debugging rounds to stabilise. Move code VERBATIM
> wherever possible; the diff should read as relocation, not rewrite.

## Task C1: PlaybackSessionCoordinator — single owner for playback-scoped side effects (findings 3.1, 8.4)

**Files:**
- Create: `app/src/main/java/com/powermediaplayer/playback/PlaybackSessionCoordinator.kt`
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` (remove moved init blocks)
- Modify: `app/src/main/java/com/powermediaplayer/di/AppModule.kt` (provide @Singleton + an @ApplicationScope CoroutineScope)
- Modify: `app/src/main/java/com/powermediaplayer/MainActivity.kt` (ensure coordinator started — inject it; Hilt eager-init via injection into PlaybackConnection alternative below)

**What moves (and what does NOT):**
- MOVES: the 5s position-persist tick (PlayerViewModel :560-637 verbatim, including the Spotify-mirror branch and the `/reverse-cache/` skip); the cold-start restore block (:700-898 verbatim, including `coldStartGuard`, ResumeGate token flow, banner set/clear); the enrichment collector (:109-196); the SRT auto-fetch collector (:201-228); the per-track override apply collector (:638-???, the §C7 block); the RG pipeline (after C6 merges it — C1 moves pipeline A only, C6 then edits it in place in the coordinator).
- STAYS in PlayerViewModel: uiState mapping, artworkBytes, transport command methods, sleep timer, A-B loop, bookmarks UI flows, reverse-flip command (it is user-initiated UI), volume/boost setters, crossfadeAutoRevertReason (replaced in C5).

- [ ] **Step 1 — application scope provider** in AppModule:

```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Provides @Singleton @ApplicationScope
fun provideApplicationScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
```

- [ ] **Step 2 — coordinator skeleton:**

```kotlin
/** Owns playback-session side effects exactly once per process. These used
 *  to live in PlayerViewModel.init — with up to three coexisting VM
 *  instances (player screen, mini-bar, floating player) every collector,
 *  Room write and network lookup ran in triplicate. The coordinator is a
 *  @Singleton; ViewModels are now pure UI adapters. */
@Singleton
class PlaybackSessionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val playbackConnection: PlaybackConnection,
    private val settingsDataStore: SettingsDataStore,
    private val lastPlayedRepo: LastPlayedRepository,
    private val spotifyProvider: SpotifyProvider,
    /* + the exact deps the moved blocks reference: enrichment clients,
       replayGainDao, mediaOverrideRepo, subtitle fetcher — copy the
       PlayerViewModel constructor params each moved block touches */
) {
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        startPositionPersistTick()   // moved :560-637
        startColdStartRestore()      // moved :700-898 (keeps coldStartGuard + ResumeGate)
        startEnrichment()            // moved :109-196
        startSubtitleAutoFetch()     // moved :201-228
        startOverrideApply()         // moved §C7 block
        startReplayGainApply()       // moved pipeline A (C6 edits it here)
    }
    // each startX() = the verbatim moved block with viewModelScope→scope
}
```

- [ ] **Step 3 — start it.** `PlaybackConnection.connect()` (called from MainActivity.onCreate) is the natural ignition: inject the coordinator into MainActivity and call `coordinator.start()` immediately after `playbackConnection.connect()`. (Not inside PlaybackConnection — avoid a dependency cycle.) The AtomicBoolean makes repeat calls free.

- [ ] **Step 4 — delete the moved blocks from PlayerViewModel.** The init block shrinks accordingly. KEEP the `companion object` `coldStartGuard` declaration only if other code references it; otherwise it moves into the coordinator as a plain `AtomicBoolean` field (single instance — the guard is now belt-and-braces).

- [ ] **Step 5 — invariants check (read, don't trust memory):** in the moved cold-start block confirm intact: `ResumeGate.begin/isCurrent/end`, the 800ms grace, spotify-mirror skip, session-adopted skip, restore-toggle clear-leftover branch, cache-or-none remote chapter policy, `startPositionMs` atomic resume (T279), `adoptSession`. In the moved tick: `/reverse-cache/` skip, spotify branch, synth-session fallback.

- [ ] **Step 6 — gates.** Full unit suite green (ResumeGateTest 3/3, ChapterCacheTest, SpotifyBannerGraceTest, SettingsSearchTest). Greps: `grep -c "updatePositionByUri" ui/player/PlayerViewModel.kt` → 0; same grep in PlaybackSessionCoordinator.kt → ≥1. Emulator regression battery: cold-start restore lands at saved−backoff; tap-resume lands at saved; play 12s→force-stop→relaunch→position persisted (proves exactly-one tick works); rapid tab switching while playing (mini-bar↔player) shows no duplicate diag writes (diag `5s-tick` lines appear once per 5s, not ×N).

- [ ] **Step 7 — commit** `refactor(playback): single PlaybackSessionCoordinator owns session side effects (audit 3.1/8.4)`.

## Task C2: Per-track normalisation cache in mapToUiState + WhileSubscribed (finding 3.2)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` (mapToUiState :1765-1880; stateIn :1013-1021, :1055-1060)

- [ ] **Step 1 —** add a cache keyed on track identity:

```kotlin
private data class NormalisedTrackText(
    val key: String, val title: String, val artist: String, val album: String,
    val chapters: List<ChapterInfo>, val chaptersSource: List<ChapterInfo>)
private var normCache: NormalisedTrackText? = null

private fun normalisedFor(ps: PlayerState): NormalisedTrackText {
    val key = "${ps.mediaId}|${ps.title}"
    val cached = normCache
    // chaptersSource reference check catches late chapter injection
    if (cached != null && cached.key == key && cached.chaptersSource === ps.chapters) return cached
    return NormalisedTrackText(
        key,
        TextNormalizer.normalize(ps.title),
        TextNormalizer.normalize(ps.artist),
        TextNormalizer.normalize(ps.album),
        ps.chapters.map { it.copy(title = TextNormalizer.normalize(it.title)) },
        ps.chapters
    ).also { normCache = it }
}
```
`mapToUiState` uses `normalisedFor(playerState)` fields instead of inline normalize calls. (Use the actual field names PlayerState exposes — `mediaId` if present, else the title+uri pair the file already uses for track-change detection.) The cached `chapters` list is now reference-stable per track → ChapterPickerDialog list diffing (finding in B-agent #21) also heals; STILL add `key = { i, c -> c.startTimeMs }` to the two `itemsIndexed` in `ChapterPickerDialog.kt:81,95`.

- [ ] **Step 2 —** stateIn: `SharingStarted.Eagerly` → `SharingStarted.WhileSubscribed(5000)` for `uiState` AND `artworkBytes`. The Eagerly comment cited a first-nav layout flash — re-test: emulator, navigate Library→Player; if a flash is visible, keep Eagerly for `uiState` ONLY and record the observation here (the per-tick cost is already neutralised by Step 1 + C3).

- [ ] **Step 3 —** GATE-STD + emulator: chaptered m4b plays, chapter titles render normalised, no per-tick churn (optional: `adb shell top -p <pid>` informal before/after). Commit `perf(player): per-track normalisation cache; WhileSubscribed state (audit 3.2)`.

## Task C3: Take the position tick out of the whole-tree recomposition (findings 3.3, 3.11-volume, B-agent 11)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt`
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt` (:74, OverlayContent :586-700, TrackInfoSection/ChapterPickerChip signatures)
- Modify: `app/src/main/java/com/powermediaplayer/ui/components/MiniPlayerBar.kt` (:45)
- Modify: `app/src/main/java/com/powermediaplayer/ui/components/FloatingVideoMiniPlayer.kt` (:56)

- [ ] **Step 1 — split the flows in the VM:**

```kotlin
@Immutable data class PositionUi(
    val trackProgress: Float, val currentPositionFormatted: String,
    val durationFormatted: String, val trackRemainingFormatted: String,
    val playlistProgress: Float, val playlistPositionFormatted: String,
    val playlistDurationFormatted: String, val playlistRemainingFormatted: String,
    val trackIndexDisplay: String, val chapterStartMs: Long,
    val duration: Long, val totalPlaylistDuration: Long)

val positionUi: StateFlow<PositionUi> = /* map playerState (+spotify overlay
    position) to ONLY these fields — same maths mapToUiState uses today */
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PositionUi(...zeros))

// uiState: zero the position-bearing fields before emission so its
// equality check stops firing per tick:
val uiState: StateFlow<PlayerUiState> = combine(...)
    .map { mapToUiState(...) }
    .distinctUntilChanged { old, new -> old.withoutPosition() == new.withoutPosition() }
    ... // or remove the position fields from PlayerUiState entirely and fix
        // the ~6 consumers — PREFERRED, do the field removal; the compiler
        // lists every consumer.
```
Do the preferred form: DELETE position fields from `PlayerUiState` (keep `isPlaying`, `isLoading`, `hasMedia`, `isVideoContent`, `isSpotifyActive`, `cloudFetchInProgress` — layout-branch fields stay; matrix DEP) and chase compile errors to consumers.

- [ ] **Step 2 — consumers.** `ProgressSliders` call site moves inside a small wrapper composable that collects `positionUi` itself:

```kotlin
@Composable private fun PositionSection(viewModel: PlayerViewModel, controls: ControlsEnabledState) {
    val pos by viewModel.positionUi.collectAsStateWithLifecycle()
    ProgressSliders(
        trackPosition = pos.trackProgress, /* …all fields from pos… */
        onTrackSeek = { f -> viewModel.seekTo(pos.chapterStartMs + (f * pos.duration).toLong()) },
        onPlaylistSeek = { f -> viewModel.seekToPlaylistPosition((f * pos.totalPlaylistDuration).toLong()) },
        trackSliderEnabled = controls.trackSlider, playlistSliderEnabled = controls.playlistSlider)
}
```
`TrackInfoSection(uiState, coverColors)` → `TrackInfoSection(title, artist, album, mediaKind, coverColors)` (stable primitives). `ChapterPickerChip(uiState, ...)` → `(currentChapterTitle: String?, onClick)`. SyncedLyricsPanel collects `positionUi` directly (B-agent #20: switch its scan to binary search over start times while touching it — 6 lines).

- [ ] **Step 3 — volume state (3.11).** VM gains:

```kotlin
val volumeUi: StateFlow<Pair<Int, Int>> = callbackFlow {
    val am = context.getSystemService(AudioManager::class.java)
    fun snap() = trySend(am.getStreamVolume(AudioManager.STREAM_MUSIC) to
                         am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    snap()
    val r = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { snap() }
    }
    context.registerReceiver(r, IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
        Context.RECEIVER_NOT_EXPORTED)
    awaitClose { context.unregisterReceiver(r) }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0 to 15)
```
NOTE: `VOLUME_CHANGED_ACTION` is a protected system broadcast — `RECEIVER_NOT_EXPORTED` registration of a system action requires `RECEIVER_EXPORTED`-vs-NOT semantics check on API 34: system broadcasts are exempt from the export requirement; if the runtime rejects it, fall back to `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)`. TertiaryControls takes `(volume, maxVolume)` from this flow.
`getCurrentVolume()/getMaxVolume()` direct calls leave the composition.

- [ ] **Step 4 — narrow projections for the bars (B-agent 11):**

```kotlin
@Immutable data class MiniBarUi(val title: String, val artist: String,
    val isPlaying: Boolean, val hasMedia: Boolean, val artworkKey: Int)
val miniBarUi: StateFlow<MiniBarUi> = uiState.map { s -> MiniBarUi(
    s.title, s.artist, s.isPlaying, s.hasMedia, /* artwork identity */ 0)
}.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MiniBarUi("","",false,false,0))
```
MiniPlayerBar + FloatingVideoMiniPlayer collect `miniBarUi` (+ their existing artworkBytes flow) instead of full uiState. KEEP the Spotify-overlaid-title hide semantics (matrix DEP: empty-bar gate keys off the overlaid title — the projection must use the OVERLAID title, i.e. map from the same combined source uiState maps from).

- [ ] **Step 5 — Teal shade caching (3.11).** `Color.kt:26-42`: replace `get()` properties with a small cache invalidated by accent writes:

```kotlin
private var shadeCacheAccent: Int = 0
private val shadeCache = HashMap<Int, Color>()   // key = (lightness*1000).toInt()
private fun shadeOfAccent(lightness: Float): Color {
    val accent = currentAccentArgb()             // existing source of truth
    if (accent != shadeCacheAccent) { shadeCache.clear(); shadeCacheAccent = accent }
    return shadeCache.getOrPut((lightness * 1000).toInt()) { /* existing HSL maths */ }
}
```
(Live-accent recolour invariant: cache clears whenever the accent ARGB changes — covered by the comparison.)

- [ ] **Step 6 — BluetoothButton (3.11).** Replace the 2s poll (:52-57) with `AudioDeviceCallback` registration in a DisposableEffect (mirror AudioOutputDetector's callback pattern); set `a2dpActive` on add/remove events + once at registration.

- [ ] **Step 7 — processor micro-waste (3.11, matrix 8.5 verdict: micro-fixes only).** `StereoTransformProcessor.kt:62`: move `duplicate()` below the identity-bypass test. `GainAudioProcessor.kt:36-37`: cache `lastMb→gain`:

```kotlin
private var lastMb = Int.MIN_VALUE; private var lastGain = 1f
// per buffer: val mb = gainMbSupplier(); if (mb != lastMb) { lastMb = mb; lastGain = 10.0.pow(mb / 2000.0).toFloat() }
```
(Per-buffer supplier READS stay — live-toggle invariant.)

- [ ] **Step 8 — gates.** GATE-STD. Emulator: playback running, navigate all tabs — UI updates correctly; slider tracks position; volume slider reacts to hardware volume keys; chapter chip updates on chapter change. Layout-inspector-style proof is not scriptable headless — the structural evidence is the compile-time removal of position fields from PlayerUiState (grep `currentPositionFormatted` in PlayerUiState.kt → 0).
- [ ] **Step 9 — commit** `perf(compose): position tick isolated from player tree; event-driven volume/BT; shade cache (audit 3.3/3.11)`.

## Task C4: Gate the Hue analyser (finding 3.4)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/hue/HueAnalyserAudioProcessor.kt`
- Modify: `app/src/main/java/com/powermediaplayer/hue/HueAudioAnalyser.kt` (:350-359)
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (hue collector :1095-1250)

- [ ] **Step 1 —** processor gains an enable flag + Hann table:

```kotlin
@Volatile var analysisEnabled: Boolean = false
    // setter side effect not allowed on @Volatile field — use fun:
fun setAnalysis(enabled: Boolean) {
    if (enabled && !analysisEnabled) onFlush()   // fresh window; stale ring must not leak into a new stream (matrix DEP)
    analysisEnabled = enabled
}
private val hannWindow = FloatArray(FFT_SIZE) { i ->
    0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)).toFloat())
}
```
`queueInput`: first line after the empty check —

```kotlin
if (!analysisEnabled) {   // pure passthrough — zero analysis cost for non-Hue users
    val out = replaceOutputBuffer(inputBuffer.remaining())
    out.put(inputBuffer); out.flip(); return
}
```
The windowing loop uses `fftReal[i] *= hannWindow[i]`.

- [ ] **Step 2 —** `medianAndMad` returns into fields (mirror the existing `percentile20And90` field-write pattern in the same file): `private var lastMedian = 0f; private var lastMad = 0f` set by `private fun computeMedianAndMad(...)`; call sites read the fields. Delete the Pair return.

- [ ] **Step 3 —** drive the flag from the service's hue collector: where the collector computes whether streaming should run (it already has paired/intensity/area state), add `hueAnalyserProcessor.setAnalysis(shouldStream)` on every evaluation (cheap; idempotent), and `setAnalysis(false)` in the stop path + `onDestroy`.

- [ ] **Step 4 —** TimedSnapshot ring slot reuse (the per-frame allocs): preallocate `RING_SIZE` mutable slots:

```kotlin
private class MutableSnapshot { var uptimeMs = 0L; val bands = FloatArray(BAND_COUNT); val normalisedBands = FloatArray(BAND_COUNT); var beat=false; var beatStrength=0f; var normalisedBeatStrength=0f; var bpm=0f; var dynamics=0f; var normalisedDynamics=0f; var paletteHz=0f }
```
write-into-slot instead of constructing; `getSnapshotAt` copies OUT into the caller's struct (HueEntertainment already copies per frame — check its read pattern and keep the copy on the read side only).

- [ ] **Step 5 —** gates: GATE-STD; grep `analysisEnabled` 3+ hits; emulator: local playback with Hue unpaired — add a temporary diag counter? NO — evidence: unit-test the gate by instantiating the processor and asserting passthrough fills output without touching the analyser (analyser.process call count via a fake if seams allow; if not, the grep + review suffice and the phone Hue pass covers behaviour). Commit `perf(hue): analyser gated off when not streaming; precomputed window; alloc-free ring (audit 3.4)`.

## Task C5: Crossfade ticker gating + pause-aware overlap (finding 3.5)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (:1568-1574 + crossfade flag collector)
- Modify: `app/src/main/java/com/powermediaplayer/service/CrossfadeController.kt` (:140-181)
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` (:60-72 crossfadeAutoRevertReason poll)

- [ ] **Step 1 —** replace the unconditional launch with a gated one driven by the existing `playingFlow` (the event-driven pattern already used by the Hue collector at :1074-1089) combined with the crossfade-ms flag:

```kotlin
serviceScope.launch {
    combine(playingFlow, crossfadeMsFlow) { playing, ms -> playing && ms > 0 }
        .distinctUntilChanged()
        .collectLatest { active ->
            if (active) {
                while (kotlin.coroutines.coroutineContext.isActive) { applyCrossfadeTick(); delay(100) }
            } else {
                applyCrossfadeTick()   // one settle tick: ms==0 branch force-restores factor 1.0 (matrix DEP); also lands a mid-fade factor
            }
        }
}
```
(`crossfadeMsFlow` = whatever flow currently feeds the crossfade duration flag; if only a @Volatile exists, lift it to a MutableStateFlow in the same place it's written.)
- [ ] **Step 2 —** CrossfadeController overlap loop honours pause: the controller has the `pauseAll`/resume contract (:172-181); add `@Volatile private var paused = false` set by pauseAll/resumeAll, and inside the step loop:

```kotlin
while (paused && scope.isActive) delay(50)   // hold the ramp; do not advance steps while both players are paused
```
Also clear `lastInitiatedForItemId` in `abort()` (A-agent note: re-entering the same track's window currently can't restart).
- [ ] **Step 3 —** replace the static `crossfadeAutoRevertReason` + 750ms poll: service exposes `val crossfadeAutoRevertReasonFlow = MutableStateFlow<String?>(null)` in the companion (written where the static was written); PlayerViewModel's flow (:60-72) becomes `PlaybackService.crossfadeAutoRevertReasonFlow.asStateFlow()` — delete the poll loop.
- [ ] **Step 4 —** gates: GATE-STD; emulator: enable crossfade in settings, queue two tracks, hear/observe transition (state assertions via diag `crossfade` lines); disable crossfade → no 10Hz ticks (add a one-line diag in the gated collect on/off for evidence). Commit `perf(crossfade): event-gated ticker; pause-aware overlap; push-based revert reason (audit 3.5)`.

## Task C6: Merge the ReplayGain pipelines (findings 3.6 + 1.6)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/playback/PlaybackSessionCoordinator.kt` (pipeline A now lives here after C1)
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerViewModel.kt` (delete pipeline B :899-966 — pre-C1 numbering)

- [ ] **Step 1 —** delete pipeline B entirely (the MediaMetadataRetriever GENRE-sniffing block — its MMR leak (1.6) disappears with it). Pipeline A already covers embedded tags via `PlayerState.replayGainTrackDb` + the Room pre-scan table.
- [ ] **Step 2 —** pipeline A stops writing `player.volume` directly (:303-305 pre-move): route through the service's RG channel instead:

```kotlin
com.powermediaplayer.service.PlaybackService.setReplayGainAttenuation(targetDb)
```
(The service multiplies `replayGainFactor × crossfadeFactor` in `applyMixedVolume` :334-345 — the direct write raced that product.) Confirm `setReplayGainAttenuation` exists with that name (T264 shipped the RG/user split — use the actual setter the service exposes; grep `setReplayGain` in PlaybackService.kt and use the real symbol).
- [ ] **Step 3 —** invariant: RG-off resets ONLY attenuation, never user boost — already encoded in the T264 split; assert by grep that the collector's RG-disabled branch calls only the attenuation setter.
- [ ] **Step 4 —** GATE-STD; grep `MediaMetadataRetriever` in PlayerViewModel/Coordinator → 0. Commit `fix(replaygain): single pipeline through the mixer; GENRE-sniff MMR path deleted (audit 3.6/1.6)`.

## Task C7: Gate the 500ms position poller (finding 3.7)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackConnection.kt` (:850-868 + the isPlaying listener path)

- [ ] **Step 1 —**

```kotlin
/** Poll position every 500ms WHILE PLAYING to smoothly update sliders
 *  without callback storms. Paused/idle: one final emit freezes the UI at
 *  the right position and the loop suspends until isPlaying flips. */
private fun startPositionPolling() {
    positionPollingJob?.cancel()
    positionPollingJob = scope.launch {
        playingFlow.collectLatest { playing ->
            if (!playing) { pollOnce(); return@collectLatest }
            while (isActive) { pollOnce(); delay(500) }
        }
    }
}
private fun pollOnce() {
    controller?.let { c ->
        _playerState.value = _playerState.value.copy(
            currentPosition = c.currentPosition.coerceAtLeast(0L),
            bufferedPercentage = c.bufferedPercentage,
            totalPlaylistPosition = cachedPlaylistPosition(c))
    }
}
```
`playingFlow` here = a `MutableStateFlow<Boolean>` updated in the existing `onIsPlayingChanged` listener in this class (add if absent — the class already listens for events; mirror that path). ALSO fix the comment ("250ms" → "500ms").
- [ ] **Step 2 —** seek-while-paused check: a paused seek must still move the slider — `updatePlayerState` (the discrete-event path) already rebuilds on seek discontinuity events; verify by emulator: pause → seek via slider → position label updates. If it does not, add `pollOnce()` to the discontinuity event branch.
- [ ] **Step 3 —** GATE-STD; emulator predicate above; commit `perf(connection): position poll gated on isPlaying (audit 3.7)`.

## Task C8: Spotify poll lifecycle + AuthState cache (findings 3.8 / 8.7)

**Files:**
- Modify: `app/build.gradle.kts` (add `implementation("androidx.lifecycle:lifecycle-process:2.9.0")`)
- Modify: `app/src/main/java/com/powermediaplayer/cloud/SpotifyProvider.kt` (:1029-1131 poll, :1350-1373 token)

- [ ] **Step 1 — AuthState object cache.** Beside the existing `lastSerializedAuthState` debounce var, add `private var cachedAuthState: net.openid.appauth.AuthState? = null`; `currentAccessToken()` deserialises ONLY when the serialised string changed:

```kotlin
val json = tokenStore.read() ?: return null
val state = if (json == lastSerializedAuthState && cachedAuthState != null) cachedAuthState!!
            else net.openid.appauth.AuthState.jsonDeserialize(json).also {
                cachedAuthState = it; lastSerializedAuthState = json }
```
(Adapt to the function's actual locals; the rule: one deserialise per token CHANGE, not per second.)
- [ ] **Step 2 — background pause.** In the provider's init:

```kotlin
scope.launch(Dispatchers.Main) {
    androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
        object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                backgroundPauseJob = pollScope.launch {
                    delay(30_000)
                    if (_spotifyState.value != null) pausedByBackground = true
                    stopPlaybackPollingKeepingState()
                }
            }
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                backgroundPauseJob?.cancel()
                if (pausedByBackground) { pausedByBackground = false; startPlaybackPolling() }
            }
        })
}
```
`stopPlaybackPollingKeepingState()` = cancel `pollJob` + bump `pollGen` WITHOUT clearing `_spotifyState`/banner (the mirror survives; positions stop updating while backgrounded — acceptable per 8.7 decision). The 45s grace, provisional mirror and pollGen semantics are untouched (matrix DEP); restart goes through the normal `startPlaybackPolling()` warm burst.
- [ ] **Step 3 —** the :108-110 comment now becomes TRUE — update it to describe the implemented behaviour.
- [ ] **Step 4 —** GATE-STD; SpotifyBannerGraceTest stays green. Emulator can't run Spotify — phone-pass item: mirror active → Home → 35s → logcat shows poll stop; reopen → mirror resumes. Record BLOCKED on the device step only. Commit `perf(spotify): background-pause polling; cached AuthState (audit 3.8/8.7)`.

## Task C9: Passive Cast discovery (finding 3.9)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/components/CastSwitcher.kt` (:100-130 region)

- [ ] **Step 1 —** two-tier callback: the always-on DisposableEffect registers with NO flags (`router.addCallback(selector, cb, 0)` — connected-state updates only); a second `DisposableEffect(sheetOpen)` adds `CALLBACK_FLAG_REQUEST_DISCOVERY` (same callback object, re-added with the flag) while the route sheet is open and removes it on close:

```kotlin
DisposableEffect(sheetOpen) {
    if (sheetOpen) router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
    onDispose { if (sheetOpen) { router.removeCallback(cb); router.addCallback(selector, cb, 0) } }
}
```
- [ ] **Step 2 —** GATE-STD; phone-pass item (cast hardware): open switcher → Living Area TV appears within normal discovery time; icon still reflects connection when sheet closed. Commit `perf(cast): active discovery only while the route sheet is open (audit 3.9)`.

## Task C10: Widget art decode cache + debounce (finding 3.10 — amended: 2 decodes/refresh)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/widget/NowPlayingWidgetProvider.kt` (:78-132 + refresh entry)
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackService.kt` (:1527-1565 refresh call sites)

- [ ] **Step 1 —** scale + cache:

```kotlin
private var artCacheKey: String? = null
private var artCacheBmp: android.graphics.Bitmap? = null
private fun scaledArt(mediaId: String, art: ByteArray): android.graphics.Bitmap? {
    if (mediaId == artCacheKey) return artCacheBmp
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
    val target = 192   // px — widget art cell at ~64dp × up-to-3x density
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size, opts)
    }.getOrNull().also { artCacheKey = mediaId; artCacheBmp = it }
}
```
`buildVariant` calls `scaledArt(player?.currentMediaItem?.mediaId.orEmpty(), art)` — both variants share the one decode.
- [ ] **Step 2 —** debounce + empty-skip at the service: wrap the three `NowPlayingWidgetProvider.refresh(...)` call sites in a coalescer:

```kotlin
private var widgetRefreshJob: Job? = null
private fun scheduleWidgetRefresh() {
    widgetRefreshJob?.cancel()
    widgetRefreshJob = serviceScope.launch { delay(250); NowPlayingWidgetProvider.refresh(applicationContext) }
}
```
and inside `refresh()` first line: `if (AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java)).isEmpty()) return` (already short-circuits — verify and keep).
- [ ] **Step 3 —** GATE-STD; emulator: place the widget (uiautomator longpress flow is flaky — acceptable alternative: `adb shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE -n com.powermediaplayer/.widget.NowPlayingWidgetProvider` renders without crash; art still appears after track change). Commit `perf(widget): single sampled decode per track + debounced refresh (audit 3.10)`.

## Task C11: Interaction batch (findings 4.1-4.7)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/library/LibraryViewModel.kt` (search/sort)
- Modify: `app/src/main/java/com/powermediaplayer/util/TextNormalizer.kt` (cached collator)
- Modify: `app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt` (:141-145)
- Modify: `app/src/main/java/com/powermediaplayer/util/PaletteHelper.kt` (:46-57) + `ui/player/components/CoverArtBackground.kt` (:79-83, :107-112)
- Modify: `app/src/main/java/com/powermediaplayer/ui/components/MiniPlayerBar.kt` (:80-86)
- Modify: `app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt` (:606, :629-630)
- Modify: `app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt` (:87-95, :790-824)
- Modify: `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedScreen.kt` (:336, :622) + `data/repository/LastPlayedRepository.kt` (:77-96) + `data/db/dao/PinnedAlbumDao.kt`

- [ ] **Step 1 — search (4.1).** In LibraryViewModel: `setSearchQuery` updates the field immediately but recompute moves to a debounced worker:

```kotlin
private val recomputeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
init { viewModelScope.launch(Dispatchers.Default) {
    recomputeRequests.debounce(250).collect { recomputeDisplayedNow() }
} }
fun setSearchQuery(query: String) { _uiState.update { it.copy(searchQuery = query) }; recomputeRequests.tryEmit(Unit) }
// every other recomputeDisplayed() caller → recomputeRequests.tryEmit(Unit)
```
`recomputeDisplayedNow()` = the existing body, now running on Default. Precompute per-file sort/search keys at scan time — extend `MediaFileInfo` (or a parallel map keyed by uri if the data class is widely constructed) with:

```kotlin
val searchHay: String   // TextNormalizer.normalize("$title $artist $album").lowercase(), built in the scan mapper
val collationKey: java.text.CollationKey   // TextNormalizer.collator().getCollationKey(normalizedTitle)
```
Comparators become `compareBy { it.collationKey }` / reversed; filter uses `f.searchHay.contains(q)`. TextNormalizer gains a cached default-locale collator:

```kotlin
@Volatile private var cachedCollator: Collator? = null
@Volatile private var cachedLocale: Locale? = null
fun collator(locale: Locale = Locale.getDefault()): Collator {
    val c = cachedCollator
    if (c != null && locale == cachedLocale) return c
    return (Collator.getInstance(locale).apply { strength = Collator.PRIMARY })
        .also { cachedCollator = it; cachedLocale = locale }
}
```
CAUTION: Collator instances aren't thread-safe for concurrent compare — recompute now runs ONLY on the single debounced Default-dispatcher collector, and CollationKey comparison (`compareTo`) is safe; the cached instance is used for key GENERATION at scan time (also single-threaded scan path). Note this in a comment.
- [ ] **Step 2 — settings catalogue (4.2) + theme subscription (2.5).** `remember(uiState)` → `remember { ... }` with `val state by rememberUpdatedState(uiState)` declared above; content lambdas read `state.x` instead of `uiState.x` (closure now stable). The "expanded || searching" + keyed `rememberSaveable` semantics are untouched (matrix DEP). THEN the theme stops subscribing to the 75-field combine: SettingsViewModel exposes two narrow flows —

```kotlin
val fontSizeScale: StateFlow<Float> = settingsDataStore.fontSizeScale
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)
val themeAccentHex: StateFlow<Int> = settingsDataStore.themeAccentArgb
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_ACCENT)
```
(use the actual DataStore flow names backing those two uiState fields); `Theme.kt:77-78` collects `fontSizeScale` and `ThemeAccent.kt:25-26` collects `themeAccentHex`, both via `collectAsStateWithLifecycle`. Also extend this step's file list: `ui/theme/Theme.kt`, `ui/theme/ThemeAccent.kt`, `ui/settings/SettingsViewModel.kt`.
- [ ] **Step 3 — palette (4.3).** `extractColorSet` computes BOTH colour sets from ONE `Palette.from(bitmap).generate()` (pass the palette into `extractStatusBarColor(palette)`); both call sites wrap in `withContext(Dispatchers.Default)`.
- [ ] **Step 4 — mini-bar decode (4.4).** :80-86 produceState body decodes with bounds-check + `inSampleSize` targeting 160px (same pattern as C10 Step 1; factor the sampler into a small shared util `util/SampledDecode.kt` used by both).
- [ ] **Step 5 — per-row scans (4.5).** LibraryScreen :606 region: `val indexById = remember(files) { files.withIndex().associate { (i, f) -> f.id to i } }`; row uses `indexById[file.id] ?: 0`. CloudBrowserScreen: `val driveFavIds = remember(uiState.driveFavouriteTracks) { uiState.driveFavouriteTracks.mapTo(HashSet()) { it.id } }` (and the two sibling sets); `hasOfflineCopy` → collect `offlineDrivePairs` as state at screen level and derive a `Set<String>`.
- [ ] **Step 6 — LastPlayed (4.6).** Both `collectAsState` → `collectAsStateWithLifecycle`. `observePinnedAlbums` flowOf(Unit) bug: add to PinnedAlbumDao a JOIN count query:

```kotlin
@Query("SELECT pa.*, (SELECT COUNT(*) FROM pinned_album_tracks t WHERE t.albumId = pa.id) AS trackCount FROM pinned_albums pa ORDER BY pa.sortOrder")
fun observeWithCounts(): Flow<List<PinnedAlbumWithCount>>
```
(match real table/column names from the entities; create the `PinnedAlbumWithCount` POJO) and rebuild `observePinnedAlbums` on it — the `combine(flowOf(Unit))` disappears.
- [ ] **Step 7 — cloud resume refresh (4.7).** :87-95 ON_RESUME branch → `viewModel.refreshIfStale(5_000)` (add the method mirroring LibraryViewModel's :287-292 stale-gate over the existing forceRefresh); the launcher-result paths (:210, :235) keep `forceRefresh()` (OAuth-return invariant).
- [ ] **Step 8 — tests + gates.** SettingsSearchTest 6/6 (semantics preserved); add `LibrarySearchDebounceTest` (JVM):

```kotlin
class LibrarySearchDebounceTest {
    @Test fun `collation keys order like collator compare`() {
        val c = TextNormalizer.collator(java.util.Locale.UK)
        val names = listOf("Émile", "emil", "Zebra", "apple")
        val byKey = names.sortedBy { c.getCollationKey(TextNormalizer.normalize(it)) }
        val byCompare = names.sortedWith { a, b -> TextNormalizer.compare(a, b, java.util.Locale.UK) }
        assertEquals(byCompare, byKey)
    }
}
```
GATE-STD; emulator: type rapidly in library search with the test tone present — list filters; settings search still finds collapsed-group items. Commit `perf(ui): debounced background search w/ collation keys; stable settings catalogue; single palette pass; sampled decodes; row-scan maps; lifecycle collects (audit 4.x)`.

## Task C12: Debug log gating (finding 7.1)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/service/PlaybackConnection.kt` (:903-914)

- [ ] **Step 1 —** wrap the expensive string assembly in a sampling gate:

```kotlin
if (BuildConfig.DEBUG && (updateSeq++ and 0xF) == 0) {   // 1-in-16 sampling — keeps the trace, kills the cost skew
    com.powermediaplayer.util.Diag.i("PMP_DIAG", buildString { /* existing content */ })
}
```
- [ ] **Step 2 —** GATE-STD; commit `chore(debug): sample hot-path state logs (audit 7.1)`.

## BATCH C GATE

- [ ] GATE-STD EXIT=0; ALL suites green (ResumeGateTest, ChapterCacheTest, SpotifyBannerGraceTest, SettingsSearchTest, M4bIsRemoteTest, LibrarySearchDebounceTest, SenderCachePruneTest).
- [ ] Greps: `updatePositionByUri` not in PlayerViewModel; `MediaMetadataRetriever` not in PlayerViewModel/Coordinator; `currentPositionFormatted` not in PlayerUiState; `CALLBACK_FLAG_REQUEST_DISCOVERY` only in the sheet-scoped effect; `analysisEnabled` wired.
- [ ] Emulator regression battery (scripted): cold-start restore position ≠ 0 AND = saved−backoff; tap-resume = saved; 12s-play→force-stop→relaunch persists; library search filters; settings search OK; widget broadcast renders.
- [ ] Phone-pass items recorded as BLOCKED rows (Hue audibility, Spotify mirror background pause, Cast discovery): listed in TASKS.md under the consolidated device pass.
- [ ] `TASKS.md` updated; commit + push.

---

# BATCH D — form-factor correctness (target release: vc35)

## Task D1: Clear the widget deep-link after consumption (finding 6.2)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/MainActivity.kt` (:94-96, :212-219)
- Modify: `app/src/main/java/com/powermediaplayer/ui/navigation/AppNavigation.kt` (:70-78)

- [ ] **Step 1 —** MainActivity exposes a consume callback; both intent-read sites also strip the extra from the sticky intent:

```kotlin
private fun readOpenTabExtra(i: android.content.Intent) {
    i.getStringExtra(NowPlayingWidgetProvider.EXTRA_OPEN_TAB)?.let {
        pendingOpenTab.value = it
        i.removeExtra(NowPlayingWidgetProvider.EXTRA_OPEN_TAB)   // recreation re-reads this intent — never re-navigate
    }
}
fun consumeOpenTab() { pendingOpenTab.value = null }
```
(onCreate calls `readOpenTabExtra(intent)`; onNewIntent calls `readOpenTabExtra(intent)` after `setIntent(intent)` — add `setIntent` so the sticky intent is the stripped one.)
- [ ] **Step 2 —** AppNavigation's `LaunchedEffect(initialOpenTab)` invokes an `onConsumed: () -> Unit` parameter after navigating (wire `consumeOpenTab` through the `AppNavigation(...)` call).
- [ ] **Step 3 —** emulator predicate: launch via widget broadcast extra (`adb shell am start -n com.powermediaplayer/.MainActivity --es open_tab player` — use the real extra key), land on Player; navigate to Settings; trigger recreation (`adb shell cmd uimode night yes` then `night no`) → app STAYS on Settings. Commit `fix(nav): widget deep-link consumed once (audit 6.2)`.

## Task D2: Seed isInPip on recreation (finding 6.3)

**Files:** Modify: `MainActivity.kt` (:88-97)
- [ ] **Step 1 —** in onCreate after super: `isInPip.value = isInPictureInPictureMode` (guard API: `if (Build.VERSION.SDK_INT >= 24)` — minSdk 30, so call directly).
- [ ] **Step 2 —** GATE-STD; commit `fix(pip): seed PiP flag across recreation (audit 6.3)`.

## Task D3: Explicit dark system-bar styles (finding 6.5)

**Files:** Modify: `MainActivity.kt` (:97)
- [ ] **Step 1 —** `enableEdgeToEdge()` → 

```kotlin
enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
)
```
(App UI is hard-forced dark; `auto` keyed off the SYSTEM theme — light-mode devices got dark icons on black. c7-verified.)
- [ ] **Step 2 —** emulator predicate: `adb shell cmd uimode night no`, relaunch, screenshot (`adb exec-out screencap -p > d3.png`) → status-bar icons LIGHT over black. Commit `fix(edge2edge): dark bar styles regardless of system theme (audit 6.5)`.

## Task D4: IME insets (finding 6.6)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/equalizer/EqualizerScreen.kt` (root scroll container)
- Modify: `app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt` (root Column/scroll)
- Modify: `app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt` (list container)
- Modify: `app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt` (list container)

- [ ] **Step 1 —** append `.imePadding()` to each screen's scrolling container modifier chain (EQ's `verticalScroll` Column; Settings' scroll Column; Library + Cloud list parents). Root `systemBarsPadding()` does NOT consume ime insets, so no double-padding.
- [ ] **Step 2 —** emulator predicate: focus the EQ band text field with soft keyboard forced (`adb shell settings put secure show_ime_with_hard_keyboard 1`), uiautomator dump → field bounds bottom < keyboard top (or visually via screenshot). Commit `fix(insets): imePadding on text-entry screens (audit 6.6)`.

## Task D5: Alarm activity — configChanges + scrollable inset-aware layout (finding 6.7)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (:271-278)
- Modify: `app/src/main/java/com/powermediaplayer/alarm/FullScreenAlarmActivity.kt` (renderUi :364-590 region)

- [ ] **Step 1 —** manifest: add to FullScreenAlarmActivity `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|density"` (parity with MainActivity + density so fold/rotate never restarts the ring; ring idempotence not needed once recreation is gone for these classes — process death mid-ring remains the OS's problem).
- [ ] **Step 2 —** layout: wrap the root Column content in `verticalScroll(rememberScrollState())` + replace the deprecated `systemUiVisibility` flags with WindowInsetsControllerCompat (hide bars is NOT wanted here — instead pad):

```kotlin
// replace systemUiVisibility block:
androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
// compose root: Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
```
- [ ] **Step 3 —** emulator predicate: fire a test alarm, rotate (`adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1`) → ring does NOT restart (volume ramp continues; diag/logcat shows no second `startRinging`); Stop control reachable in landscape (uiautomator dump). Commit `fix(alarm): no recreation on rotate/fold; scrollable inset-aware ring UI (audit 6.7)`.

## Task D6: Compact-height overflow (finding 6.8)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt` (:614-618)
- Modify: `app/src/main/java/com/powermediaplayer/ui/library/LibraryScreen.kt` (:304-513 header stack)
- Modify: `app/src/main/java/com/powermediaplayer/ui/lastplayed/LastPlayedScreen.kt` (:194-250)
- Modify: `app/src/main/java/com/powermediaplayer/alarm/AlarmMediaPickerSheet.kt` (:179)

- [ ] **Step 1 — video overlay at compact height.** :614-618 — scroll when the window is short, even for video:

```kotlin
val compactHeight = LocalConfiguration.current.screenHeightDp < 500
val scrollMod = if (uiState.isVideoContent && !compactHeight) Modifier
                else Modifier.verticalScroll(rememberScrollState())
```
(Batch F replaces LocalConfiguration with the passed-down heightSizeClass; this is the correct minimal fix now and F refits it.)
- [ ] **Step 2 — Library headers into the list.** Convert the fixed header stack (search field, tabs, smart-playlist rail, editor) into `item {}` entries of the main LazyColumn so compact heights scroll everything. KEEP the TopAppBar fixed. (Mechanical move; state hoisting unchanged.)
- [ ] **Step 3 — LastPlayed pinned sections into the LazyColumn** as `item {}`/`items()` entries above the recents items (delete the outer non-scrolling Column split; the weighted recents list becomes the same LazyColumn's tail).
- [ ] **Step 4 — sheet height.** `:179` `.height(540.dp)` → `.heightIn(max = 540.dp).fillMaxHeight(0.8f)`.
- [ ] **Step 5 —** emulator predicate: `adb shell wm size 1080x900` (split-screen-ish height) → Player video overlay scrolls to reach all controls; Library list visible; `adb shell wm size reset`. Commit `fix(layout): compact-height reachability (audit 6.8)`.

## Task D7: Transport row fits 320dp (finding 6.9)

**Files:** Modify: `app/src/main/java/com/powermediaplayer/ui/player/components/PlaybackControls.kt` (:79-131, :300-322)
- [ ] **Step 1 —** compute button size from available width with `BoxWithConstraints` around Row1: `val side = (maxWidth - 32.dp) / 5.5f` → `LabelledNavigationButton` size = `side.coerceIn(44.dp, 64.dp)`, play button = `side * 1.12f` coerced ≤72dp. Buttons keep ≥44dp touch targets (under the 48dp guideline floor only on sub-340dp windows — acceptable trade; note it).
- [ ] **Step 2 —** emulator predicate: `adb shell wm size 640x1280` (320dp-class at 2x density… emulator density 420 → use `wm size 720x1600` + `wm density 560` for a 320dp-wide window), uiautomator dump → outer File buttons fully inside screen bounds; reset size+density. Commit `fix(player): transport row scales to narrow windows (audit 6.9)`.

## Task D8: Font-scale clipping (finding 6.10)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/components/SecondaryControls.kt` (:283-293; delete dead `SpeedControl` :150-280 region)
- Modify: `app/src/main/java/com/powermediaplayer/ui/components/MiniPlayerBar.kt` (:56)
- Modify: `app/src/main/java/com/powermediaplayer/ui/equalizer/EqualizerScreen.kt` (:383-385)

- [ ] **Step 1 —** `PreparedSpeedComponent`: `Modifier.width(110.dp)` → `widthIn(min = 110.dp)`; `.height(48.dp)` → `heightIn(min = 48.dp)` (port the exact fix that sits in the dead sibling).
- [ ] **Step 2 —** DELETE the unused `SpeedControl` composable entirely (grep confirms zero call sites; it exists only to mislead).
- [ ] **Step 3 —** MiniPlayerBar `.height(56.dp)` → `heightIn(min = 56.dp)`; EQ band cell `.height(52.dp)` → `heightIn(min = 52.dp)`.
- [ ] **Step 4 —** emulator predicate: `adb shell settings put system font_scale 2.0` + app font slider at max → speed chip shows "1.75×" unclipped (screenshot), mini-bar two lines visible; reset font_scale 1.0. Commit `fix(a11y): minimum-size constraints replace fixed sizes; dead SpeedControl deleted (audit 6.10)`.

## Task D9: Clamp the floating mini-player drag (finding 6.11)

**Files:** Modify: `app/src/main/java/com/powermediaplayer/ui/components/FloatingVideoMiniPlayer.kt` (:62-81)
- [ ] **Step 1 —** track container + own size via `onSizeChanged` on the parent Box (passed in or measured around the floating card) and clamp on every delta AND on container-size change:

```kotlin
var container by remember { mutableStateOf(IntSize.Zero) }
var self by remember { mutableStateOf(IntSize.Zero) }
fun clamp() {
    // anchored BottomEnd: offsets are ≤0 leftwards/upwards
    offsetX = offsetX.coerceIn(-(container.width - self.width).coerceAtLeast(0).toFloat(), 0f)
    offsetY = offsetY.coerceIn(-(container.height - self.height).coerceAtLeast(0).toFloat(), 0f)
}
LaunchedEffect(container, self) { clamp() }
// in detectDragGestures onDrag: offsetX += dragAmount.x; offsetY += dragAmount.y; clamp()
```
(The host Box that positions the player gets `Modifier.onSizeChanged { container = it }`; the card gets `.onSizeChanged { self = it }`.)
- [ ] **Step 2 —** emulator predicate: play video, switch tab (floating player appears), drag hard right/down → card stays fully visible; `wm size 800x1280` → card re-clamps into view; reset. Commit `fix(pip-mini): drag clamped to window; re-clamp on resize (audit 6.11)`.

## Task D10: PiP polish — sourceRectHint + actions (finding 6.12)

**Files:** Modify: `MainActivity.kt` (both PiP params builders), `ui/player/components/VideoSurface.kt` (report bounds)
- [ ] **Step 1 — sourceRectHint.** VideoSurface reports its on-screen rect up via a callback → MainActivity holds `@Volatile var videoBoundsOnScreen: android.graphics.Rect?`; both `PictureInPictureParams.Builder()` sites add `.setSourceRectHint(videoBoundsOnScreen)` when non-null. In VideoSurface's AndroidView `update`/layout: `view.getGlobalVisibleRect(r)` posted on layout changes (cheap listener).
- [ ] **Step 2 — actions.** Build two RemoteActions (play/pause toggle + forward 15s) from the existing TaskerReceiver actions (exported receiver; PendingIntent.getBroadcast with the documented action strings — they bypass the toggle gate? NO: the Tasker gate would swallow them. Use dedicated intents instead): add a tiny `exported=false` receiver `widget/PipActionReceiver.kt` registered in the manifest, actions `com.powermediaplayer.pip.PLAY_PAUSE` / `com.powermediaplayer.pip.FFWD15`, handler calls `PlaybackService.getExoPlayer()` like TaskerReceiver does (no settings gate — PiP actions are first-party UI):

```kotlin
.setActions(listOf(
    RemoteAction(Icon.createWithResource(this, R.drawable.ic_pip_playpause), "Play/Pause", "Play/Pause",
        PendingIntent.getBroadcast(this, 1, Intent(this, PipActionReceiver::class.java).setAction(PIP_PLAY_PAUSE), PendingIntent.FLAG_IMMUTABLE)),
    RemoteAction(Icon.createWithResource(this, R.drawable.ic_pip_ffwd), "+15s", "+15s",
        PendingIntent.getBroadcast(this, 2, Intent(this, PipActionReceiver::class.java).setAction(PIP_FFWD15), PendingIntent.FLAG_IMMUTABLE))))
```
(Reuse existing drawables `@android:drawable/ic_media_play`-class resources if no in-app icons fit; update the params on isPlaying changes via the existing PiP params collector so the toggle icon flips.)
- [ ] **Step 3 —** emulator predicate: play video → Home → PiP window shows the two actions; tapping play/pause toggles (dumpsys media_session state flips). Commit `feat(pip): smooth enter via sourceRectHint + play/pause//+15s actions (audit 6.12)`.

## Task D11: Minor manifest/widget fixes (finding 6.13)

**Files:** Modify: `AndroidManifest.xml` (:129 DrivePickerActivity), `app/src/main/res/xml/widget_now_playing_info.xml`
- [ ] **Step 1 —** DrivePickerActivity configChanges += `|density`.
- [ ] **Step 2 —** widget info: add `android:minResizeWidth="60dp" android:minResizeHeight="60dp"` (compact variant becomes reachable by resize); widget layout `widget_now_playing.xml` prev/next touch targets 40dp → 48dp (pad, not icon size).
- [ ] **Step 3 —** GATE-STD; commit `fix(formfactor): picker density survival; widget resize floor + touch targets (audit 6.13)`.

## Task D12: Cutout-safe player overlay (finding 6.4 — insets part)

**Files:** Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt` (InfoIcon + overlay root :606-623)
- [ ] **Step 1 —** the overlay's outer Box gains `.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))` so landscape cutouts can't overlap the InfoIcon/controls (status/nav bars already handled by the root; Batch E removes the root padding for video — this line keeps cutout safety when immersive).
- [ ] **Step 2 —** emulator predicate: `adb shell cmd display set-cutout double` (emulator supports simulated cutouts via developer settings overlay: `adb shell settings put secure display_cutout_emulation double`... use the overlay package: `adb shell cmd overlay enable com.android.internal.display.cutout.emulation.double`) → landscape player → InfoIcon not under cutout (screenshot); disable overlay. Commit `fix(player): cutout-safe overlay insets (audit 6.4a)`.

## BATCH D GATE

- [ ] GATE-STD EXIT=0; suites green.
- [ ] Emulator script: D1 recreation-stays-on-tab; D3 light-mode screenshot; D5 alarm rotate; D6 wm-size 1080x900 reachability; D7 narrow width; D8 font-scale 2.0; D9 drag clamp; D10 PiP actions. Each predicate's output/screenshot path pasted next to its checkbox.
- [ ] `TASKS.md` updated; commit + push.

---

# BATCH E — video fullscreen UX (8.3 + 6.4 immersive) (target release: vc35)

## Task E1: Immersive mode tied to controls visibility

**Files:**
- Modify: `MainActivity.kt` (insets controller plumbing)
- Modify: `app/src/main/java/com/powermediaplayer/ui/navigation/AppNavigation.kt` / `MainActivity.kt` setContent root (conditional systemBarsPadding)
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/PlayerScreen.kt` (controls-visibility state already exists for video — locate the AnimatedVisibility flag)

- [ ] **Step 1 — root padding becomes video-aware.** MainActivity setContent: the `Box.systemBarsPadding()` wrapper applies `systemBarsPadding()` ONLY when not in full-bleed video (`val fullBleed = playerStateIsVideo && onPlayerTab && controlsHidden` — drive from a `mutableStateOf` the player screen writes via a CompositionLocal or a hoisted lambda; simplest: a `MutableStateFlow<Boolean>` on PlaybackConnection-adjacent UI holder is over-engineering — use a `mutableStateOf(false)` in MainActivity passed down as `onFullBleedChange: (Boolean) -> Unit`).
- [ ] **Step 2 — hide/show bars with controls.** Where the video controls visibility flag flips (the existing tap-to-show/hide in PlayerScreen), call:

```kotlin
val window = (LocalContext.current as Activity).window
val ctrl = remember { WindowCompat.getInsetsController(window, window.decorView) }
LaunchedEffect(controlsVisible, isVideoContent) {
    if (isVideoContent && !controlsVisible) {
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
    } else ctrl.show(WindowInsetsCompat.Type.systemBars())
    onFullBleedChange(isVideoContent && !controlsVisible)
}
DisposableEffect(Unit) { onDispose { ctrl.show(WindowInsetsCompat.Type.systemBars()); onFullBleedChange(false) } }
```
(c7-verified API; cutout padding from D12 keeps controls safe when they ARE shown.)
- [ ] **Step 3 —** emulator predicate: play video → tap video → bars hide (uiautomator dump: no status bar node / `dumpsys window | grep mSystemUiVisibility`-class check via `wm dismiss-keyguard`… simplest robust: screenshot shows no status bar pixels region) → tap again → bars return; leaving the tab restores bars. Commit `feat(video): immersive bars follow controls visibility (8.3a/6.4)`.

## Task E2: Rotate-to-fullscreen button (policy-aware)

**Files:**
- Modify: `app/src/main/java/com/powermediaplayer/ui/player/components/SecondaryControls.kt` (or the video controls row in PlayerScreen — place beside the existing video controls)
- Modify: `MainActivity.kt` (orientation request helper)

- [ ] **Step 1 —** helper on MainActivity:

```kotlin
/** Activity-level orientation request: overrides the user's auto-rotate
 *  quick-setting on phones with NO permission (c7-verified). Large-screen
 *  devices (12L+) may ignore it by policy — callers gate on window width. */
fun requestVideoOrientation(landscape: Boolean) {
    requestedOrientation = if (landscape)
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
```
- [ ] **Step 2 —** button appears only for video AND `windowSizeClass.widthSizeClass == Compact` (policy caveat: expanded windows go immersive without forcing). Icon `Icons.Filled.ScreenRotation`; state toggles landscape↔release; ALSO auto-release in `DisposableEffect(Unit) { onDispose { activity.requestVideoOrientation(false) } }` so leaving the player never strands a lock. Since rotation does NOT recreate (configChanges), playback is seamless.
- [ ] **Step 3 —** emulator predicate: portrait phone, play video, tap rotate → `dumpsys window | grep mCurrentRotation` (or `wm get-user-rotation`) shows landscape with auto-rotate OFF; tap again → returns. Commit `feat(video): rotate-to-fullscreen button on compact widths (8.3b)`.

## BATCH E GATE
- [ ] GATE-STD; immersive + rotate predicates pasted; bars restored on every exit path (tab switch, back, PiP enter). `TASKS.md`; commit + push.

---

# BATCH F — full adaptive redesign (8.1) + fold posture (8.2) (target release: vc36)

> Decision locked by user: FULL adaptive redesign. Structure: F1 foundations,
> F2 navigation shell, F3-F7 per-screen, F8 posture, F9 verification matrix
> across simulated form factors. Every screen keeps its existing ViewModel —
> this is a presentation-layer restructure; no business logic moves.

## Task F1: Dependencies + adaptive info plumbing

**Files:** Modify: `app/build.gradle.kts`; `MainActivity.kt`; new `app/src/main/java/com/powermediaplayer/ui/adaptive/AdaptiveInfo.kt`

- [ ] **Step 1 —** dependencies (use the latest stable at implementation time; verified family):

```kotlin
implementation("androidx.compose.material3.adaptive:adaptive:1.1.0")
implementation("androidx.compose.material3.adaptive:adaptive-layout:1.1.0")
implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.1.0")
implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.3.2")
```
(If the resolver offers 1.2.x stable, take it; record the chosen versions here.)
- [ ] **Step 2 —** `AdaptiveInfo.kt`:

```kotlin
/** Single source of adaptive truth passed through the UI tree. windowSizeClass
 *  was already computed in MainActivity; posture comes from material3-adaptive
 *  (wraps androidx.window WindowInfoTracker — c7-verified API). */
@Immutable data class AdaptiveInfo(
    val widthClass: WindowWidthSizeClass,
    val heightClass: WindowHeightSizeClass,
    val isTabletop: Boolean,
    val hingeBounds: androidx.compose.ui.geometry.Rect?)

@Composable fun rememberAdaptiveInfo(windowSizeClass: WindowSizeClass): AdaptiveInfo {
    val adaptive = androidx.compose.material3.adaptive.currentWindowAdaptiveInfo()
    val posture = adaptive.windowPosture
    return AdaptiveInfo(
        windowSizeClass.widthSizeClass, windowSizeClass.heightSizeClass,
        posture.isTabletop,
        posture.hingeList.firstOrNull()?.bounds)
}
```
(Exact `Posture`/`hingeList` property names per the c7-verified API surface; adjust to the resolved artifact's actual fields at compile time — the matrix records `isTabletop` + `hingeList` as the confirmed names.)
- [ ] **Step 3 —** GATE-STD (deps resolve); commit `feat(adaptive): foundations — adaptive artifacts + AdaptiveInfo (8.1/8.2)`.

## Task F2: Navigation shell — NavigationSuiteScaffold

**Files:** Modify: `app/src/main/java/com/powermediaplayer/ui/navigation/AppNavigation.kt` (:108-146 bottom bar region)

- [ ] **Step 1 —** replace the always-`NavigationBar` Scaffold bottomBar with `NavigationSuiteScaffold`:

```kotlin
NavigationSuiteScaffold(
    layoutType = when {
        fullBleed -> NavigationSuiteType.None   // immersive video (Batch E flag)
        adaptive.widthClass == WindowWidthSizeClass.Expanded -> NavigationSuiteType.NavigationRail
        adaptive.widthClass == WindowWidthSizeClass.Medium -> NavigationSuiteType.NavigationRail
        else -> NavigationSuiteType.NavigationBar
    },
    navigationSuiteItems = {
        tabs.forEach { tab -> item(
            selected = currentRoute == tab.route,
            onClick = { /* existing popUpTo(start){saveState} tab onClick body — UNCHANGED */ },
            icon = { Icon(tab.icon, tab.label) },
            label = { Text(tab.label) }) }
    },
    containerColor = OledBlack
) { /* existing NavHost */ }
```
The MiniPlayerBar moves from Scaffold bottomBar to a Column wrapper above/below the NavHost content so it renders adjacent to the rail on wide layouts (Column { Box(weight 1f){ NavHost }; MiniPlayerBar() }) — bar spans content width, rail sits left.
- [ ] **Step 2 —** emulator predicates: phone size → bottom bar (uiautomator: NavigationBar node); `wm size 2560x1600` + `wm density 320` (tablet) → rail on the left; tab state preserved switching sizes. Commit `feat(adaptive): navigation rail on medium/expanded widths (8.1)`.

## Task F3: Library — adaptive grid + two-pane

**Files:** Modify: `ui/library/LibraryScreen.kt` (list region :600-700)
- [ ] **Step 1 —** list rendering switches by width: Compact = existing LazyColumn (untouched); Medium/Expanded = `LazyVerticalGrid(GridCells.Adaptive(minSize = 360.dp))` rendering the SAME row composable (rows are self-contained cards already; grid cells reuse them). Keys preserved (`key = { it.id }` → `items(files, key={it.id})` equivalents in grid form). Favourites strip + headers stay as `item(span = { GridItemSpan(maxLineSpan) })` full-width entries (D6 already moved them into the list).
- [ ] **Step 2 —** Expanded two-pane: `ListDetailPaneScaffold` is the wrong shape for Library→Player (Player is a separate tab); instead Expanded gets the grid at 2+ columns — two-pane lives in F6 (Player tab) where it belongs. Record this design note.
- [ ] **Step 3 —** emulator: tablet size → ≥2 columns (uiautomator: two row nodes share a y-band); phone unchanged. Commit `feat(adaptive): library grid on wide windows (8.1)`.

## Task F4: Last Played + Cloud + Settings adaptive

**Files:** Modify: `ui/lastplayed/LastPlayedScreen.kt`, `ui/cloud/CloudBrowserScreen.kt`, `ui/settings/SettingsScreen.kt`
- [ ] **Step 1 — LastPlayed:** same grid treatment as F3 for recents/pinned (full-width section headers via span).
- [ ] **Step 2 — Cloud:** browser list → grid on wide; provider chooser row already wraps.
- [ ] **Step 3 — Settings:** Expanded = two-pane via `ListDetailPaneScaffold` (groups list left, selected group's items right; search results span the detail pane):

```kotlin
val navigator = rememberListDetailPaneScaffoldNavigator<String>()   // value = group key
ListDetailPaneScaffold(
    directive = navigator.scaffoldDirective, value = navigator.scaffoldValue,
    listPane = { AnimatedPane { SettingsGroupList(groups, onPick = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, it) } }) } },
    detailPane = { AnimatedPane { SettingsGroupDetail(groups, navigator.currentDestination?.contentKey) } })
```
Compact keeps today's single column EXACTLY (the expandable-groups + search semantics — matrix DEP — live in the compact path unchanged; the two-pane path reuses the same item composables with `expanded=true` always).
- [ ] **Step 4 —** emulator: tablet → settings two-pane (dump shows group list + detail simultaneously); search still finds collapsed/compact items (SettingsSearchTest 6/6 + manual probe). Commit `feat(adaptive): wide layouts for LastPlayed/Cloud/Settings (8.1)`.

## Task F5: EQ + dialogs on wide windows

**Files:** Modify: `ui/equalizer/EqualizerScreen.kt`, `ui/overrides/MediaOverridesPopup.kt`, `alarm/AlarmsSheet.kt`
- [ ] **Step 1 —** EQ: band grid takes `widthIn(max = 720.dp)` centred on wide windows (stretch fix); preset row wraps.
- [ ] **Step 2 —** popups/sheets: `Modifier.widthIn(max = 560.dp)` on their content roots so full-width dialogs stop happening on tablets.
- [ ] **Step 3 —** emulator screenshots at tablet size; commit `feat(adaptive): width caps for EQ + dialogs (8.1)`.

## Task F6: Player tab on Expanded — refine the existing two-pane

**Files:** Modify: `ui/player/PlayerScreen.kt` (:866-894 Expanded split; :133-155 size routing)
- [ ] **Step 1 —** replace the fixed `weight(1f)/weight(1f)` split with hinge-aware proportions: when `adaptive.hingeBounds != null` and the hinge is vertical and separating, split panes AT the hinge x (left pane width = hinge left edge); otherwise 45/55 (art/controls). Route Medium-width PORTRAIT (unfolded-fold portrait, the D-agent note) into the two-pane when height < width × 0.9 is false… keep it simple and verified: `Medium && aspect > 1.2` stays single-column; `Expanded || (Medium && landscape)` gets two-pane.
- [ ] **Step 2 —** emulator: tablet landscape → two-pane player; `wm size` phone → compact unchanged. Commit `feat(adaptive): hinge-aware expanded player split (8.1/8.2)`.

## Task F7: Compact-height refits from D6 now size-class-driven

**Files:** Modify: `ui/player/PlayerScreen.kt` (D6 Step 1 site)
- [ ] **Step 1 —** `LocalConfiguration.current.screenHeightDp < 500` → `adaptive.heightClass == WindowHeightSizeClass.Compact` (thread `adaptive` down from AppNavigation — it already passes windowSizeClass to PlayerScreen; extend the signature).
- [ ] **Step 2 —** GATE-STD; commit `refactor(adaptive): height checks via size class (8.1)`.

## Task F8: Tabletop video (8.2)

**Files:** Modify: `ui/player/PlayerScreen.kt` (video layout branch)
- [ ] **Step 1 —** when `adaptive.isTabletop && uiState.isVideoContent`: vertical split at the (horizontal) hinge — VideoSurface in the top half (`Modifier.height(hingeTopDp)` computed from `hingeBounds.top` via LocalDensity), controls column in the bottom half (always-visible controls in tabletop; immersive logic from E1 suspends in tabletop — bars shown).
- [ ] **Step 2 —** emulator predicate: fold-capable AVD required — create one (`avdmanager` device "7.6 Fold-in with outer display" image API 34/36) once; `adb emu fold`/`unfold` + half-fold posture via `adb shell cmd device_state state 2` (half-opened). Dump shows VideoSurface bottom edge ≈ hinge line. If the host machine can't fit another AVD, record BLOCKED(needs foldable AVD → create when disk allows) on THIS predicate only; the code path is reviewable + the Posture flag is c7-verified.
- [ ] **Step 3 —** commit `feat(fold): tabletop video layout at the hinge (8.2)`.

## Task F9: Adaptive verification sweep

- [ ] Run the form-factor matrix on the emulator and paste results here: phone 1080x2400/420dpi; narrow 720x1600/560dpi; tablet 2560x1600/320dpi; compact-height 1080x900; each × {Library, LastPlayed, Cloud, Settings, EQ, Player audio, Player video}: no clipped controls (uiautomator bounds within screen), correct nav (bar vs rail), grid vs list. 28 cells; script the dumps, record PASS/FAIL per cell.
- [ ] `wm size reset; wm density reset` after.

## BATCH F GATE
- [ ] GATE-STD; ALL suites; F9 28-cell table green; `TASKS.md`; commit + push.

---

# BATCH G — release readiness (target release: vc36 → Play)

## Task G1: Baseline profile (8.6)

**Files:** Create: `baselineprofile/` module (build.gradle.kts, src/main/java/.../BaselineProfileGenerator.kt); Modify: root `settings.gradle.kts`, root `build.gradle.kts` (plugin), `app/build.gradle.kts`

- [ ] **Step 1 —** app side: `implementation("androidx.profileinstaller:profileinstaller:1.4.1")`; plugin `id("androidx.baselineprofile") version "1.3.4"` (root + app `plugins { id("androidx.baselineprofile") }` + `baselineProfile { }` defaults; generator module per the c7-verified `androidx.benchmark.enabledRules=BaselineProfile` flow).
- [ ] **Step 2 —** generator module: `com.android.test` module targeting app, managed device:

```kotlin
// baselineprofile/build.gradle.kts essentials
plugins { id("com.android.test"); id("org.jetbrains.kotlin.android"); id("androidx.baselineprofile") }
android { targetProjectPath = ":app"; testOptions.managedDevices.devices {
    create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api34") {
        device = "Pixel 6"; apiLevel = 34; systemImageSource = "aosp" } } }
baselineProfile { managedDevices += "pixel6Api34"; useConnectedDevices = false }
dependencies { implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0") }
```

```kotlin
class BaselineProfileGenerator {
    @get:Rule val rule = androidx.benchmark.macro.junit4.BaselineProfileRule()
    @Test fun startup() = rule.collect("com.powermediaplayer") {
        pressHome(); startActivityAndWait()
        device.findObject(By.text("Library"))?.click(); device.waitForIdle()
        device.findObject(By.text("Settings"))?.click(); device.waitForIdle()
    }
}
```
- [ ] **Step 3 —** generate: `.\gradlew :app:generateBaselineProfile` → `app/src/release/generated/baselineProfiles/baseline-prof.txt` exists (commit it); release build embeds it.
- [ ] **Step 4 —** evidence: profile file line count > 1000; commit `feat(perf): baseline profile generation + embed (8.6)`.

## Task G2: minSdk decision gate (8.8)

- [ ] AWAITING-USER: user checks Play Console → Reach and devices → Android-version split for API 30-32. If ≤ ~2%: raise `minSdk = 33`, delete `READ_EXTERNAL_STORAGE` maxSdk block + pre-31 Bluetooth permissions + the API-30 widget single-layout branch (`NowPlayingWidgetProvider` :97-99) + any `Build.VERSION.SDK_INT >= 31/33` guards now constant-true (grep `SDK_INT` sweep). If > 2%: record "keep 30" here and close the item. EITHER outcome is a decision + recorded evidence, not a deferral.

## Task G3: Release assembly

- [ ] Full gate: GATE-STD + `lintDebug` clean of NEW warnings vs the pre-plan baseline (capture baseline first run).
- [ ] Authoring sweep (project convention): tell-sweep grep for session/tracker references in comments → clean.
- [ ] `versionCode` bump (vc32 if A-C ship alone; final adaptive release vc33+ — set at ship time per batch grouping the user approves); `bundleRelease`; AAB staged in `dist/`; ledger row.
- [ ] AWAITING-USER: Play Console upload + closed-test rollout.

## Task G4: Consolidated on-device pass (phone)

- [ ] AWAITING-USER(phone wipe consent or Play-build install): run the accumulated `[DEVICE]` list — T257 backlog + Hue A4/B8/C4 audibility/behaviour + Spotify C8 background pause + Cast C9 discovery + D-batch visuals on the physical phone + fold checks if a foldable is available. Record per-item evidence in TASKS.md.

---

# FINAL ANTI-SKIP GATE (run before declaring the plan complete)

- [ ] `grep -c "\- \[ \]" docs/superpowers/plans/2026-06-11-vc33-master-plan.md` → **0 unticked** (BLOCKED/AWAITING-USER boxes excepted ONLY if their row carries the exact unblock condition).
- [ ] Every batch gate has its pasted evidence (build line, grep counts, predicate outputs).
- [ ] TASKS.md table mirrors final statuses; matrix + plan + ledger pushed.

---

## Self-review (writing-plans skill, completed 2026-06-12)

1. **Spec coverage:** every CONFIRMED finding in the verification matrix maps to a task: §1→A1-A5(+A6/A10 for 5.11g/e/f); §2→B1/B2/A9(+G1 for 2.4, C2/C3 for 2.5 via WhileSubscribed+narrow theme flows — NOTE: 2.5's dedicated small-flows fix is folded into C11 Step 2's rememberUpdatedState? NO — added explicitly: Theme.kt/ThemeAccent.kt narrow flows ride with C11 Step 2 scope; see C11 files list — ADDED to C11 Step 2: also change Theme.kt:77-78 + ThemeAccent.kt:25-26 to collect a dedicated `fontSizeScale`/`accentHex` flow from SettingsViewModel with collectAsStateWithLifecycle); §3→C1-C10,C12; §4→C11; §5→B3-B11,A7,A8,A10; §6→D1-D12(+E for 6.4 immersive); §7→C12/B12; 8.1→F2-F7; 8.2→F1/F8; 8.3→E1/E2; 8.4→C1; 8.5→C3 Step 7 (micro-fixes only, chain surgery rejected per matrix); 8.6→G1; 8.7→C8; 8.8→G2.
2. **Placeholder scan:** no TBD/TODO/"handle errors"; the two "adjust to actual symbol" notes (C6 setter name, F1 Posture fields) are compile-time-checkable instructions referencing verified APIs, with the verification route named.
3. **Type consistency:** PositionUi fields used by PositionSection match its declaration; AdaptiveInfo fields match rememberAdaptiveInfo; pruneSenderCaches signature consistent between definition and call; MiniBarUi declared before use.
