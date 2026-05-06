# Cast Failures + S25 Ultra Launch Crash — Investigation Plan

> **For agentic workers:** This is a DIAGNOSIS plan. NO code fixes are written until each root cause is **evidence-locked** in Phase 7. Steps use checkbox (`- [ ]`) syntax. REQUIRED SUB-SKILL for execution: superpowers:executing-plans (one phase at a time, never skip ahead).

**Goal:** Identify, with logged on-device evidence, three distinct failure modes:

- **F1 — S25 Ultra launch crash.** APK `dist/PowerMediaPlayer-1.0.0-friends-2026-05-06.apk` (HEAD `d086c70`) installs and runs on the user's Samsung Galaxy Z Fold 6 (One UI 6 / Android 14). On a friend's Samsung Galaxy S25 Ultra (One UI 7 / Android 15), the same APK force-closes the moment the launcher icon is tapped.
- **F2 — Cast button tap crash on Z Fold 6.** User reports tapping the Cast button immediately force-closes the app. Earlier verification (HEAD `d086c70`) showed `MainActivity → FragmentActivity` change resolved an `IllegalStateException` on tap; either that fix didn't actually work, didn't ship in the APK they're running now, or a *different* tap-time crash exists. Must be re-verified end-to-end.
- **F3 — Cast device discovery zero-result.** User states unequivocally that other apps (YouTube, Spotify, Google Cast Sample Receiver, Google Home) DO see Cast devices on his Wi-Fi network from the same phone. Our app's CastButton chooser dialog shows nothing. **The plan accepts this as fact and does NOT re-question it; investigation focuses on why our discovery code path misses devices that other apps see.**

**Hard constraints (non-negotiable):**

- **No guessing.** A hypothesis is only "considered" when it appears on the ranked falsification list (Phase 5) with a defined evidence threshold. A hypothesis is only "accepted" once Phase 6 produces matching evidence.
- **No code deletion** during the diagnosis phases (1–6). Any new code is purely additive (a crash reporter + diagnostic logs gated by a `PMP_DIAG_CAST` constant) so it can be removed in a single revert after the RCA is filed.
- **Reproduce before instrumenting** for each failure. If we cannot reproduce F1 on a runnable rig (the friend's S25 Ultra OR an Android 15 emulator on this machine), the plan stops and tells the user. We do not guess at One UI 7 specifics.
- **Evidence types are explicit per step.** Every step states what artefact must be produced (logcat capture, MediaRouter dump, crash file, screen recording).
- **One change at a time** during instrumentation.
- **adb / logcat / direct device control are pre-authorised** per `project_operational_state.md` §2 for the Z Fold 6 (`RFCY70BARDJ`). The S25 Ultra is not USB-connected to this machine, so its evidence comes via an on-device crash reporter file, not adb.

**Test rig:**

- Primary device (mine, USB-connected): Samsung Galaxy Z Fold 6, `RFCY70BARDJ`, One UI 6, Android 14, app v1.0.0-friends.
- Secondary device (friend's, off-network): Samsung Galaxy S25 Ultra, One UI 7, Android 15. Reachable only via WhatsApp APK share + crash-file retrieval.
- Optional fallback for S25 Ultra: Android 15 emulator (`Pixel_8_API_35`) launched via Android Studio's avdmanager — we'd need to install Android Studio CLI tools and the API 35 system image; flagged as Phase 4.B contingency.
- adb: `C:\Users\Kabir\AppData\Local\Android\Sdk\platform-tools\adb.exe` (NOT on PATH; always invoke by full path).

**Evidence directory:** `docs/superpowers/investigation/2026-05-06-cast-failures-and-s25-launch/`. Created in Phase 0. All artefacts (logcat captures, MediaRouter dumps, crash dump files retrieved from S25 Ultra, screen recordings) land here, named `phase<N>-step<M>-<short-tag>.<ext>`.

---

## Phase 0 — Set-up

- [ ] **0.1 Create the evidence directory and CHANGELOG.md.** Use the same template as prior investigations (top-of-file stop-the-world rule).

```powershell
$dir = "C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\docs\superpowers\investigation\2026-05-06-cast-failures-and-s25-launch"
New-Item -ItemType Directory -Path $dir -Force | Out-Null
```

- [ ] **0.2 Capture device fingerprint of the Z Fold 6 (USB-connected) and current HEAD.**

```powershell
$adb = 'C:\Users\Kabir\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell getprop ro.build.fingerprint > "$dir\phase0-step02-zfold6-fingerprint.txt"
git rev-parse HEAD >> "$dir\phase0-step02-zfold6-fingerprint.txt"
```

- [ ] **0.3 Record the friend's device claim verbatim** in CHANGELOG: model, OS as user reports it, timing of crash ("immediately on launch icon tap", "before any UI is drawn"). Don't elaborate or guess at detail not provided. If anything is unclear, ask the user before proceeding to Phase 4.

- [ ] **0.4 Verify the on-device APK matches HEAD.**

```powershell
& $adb shell dumpsys package com.powermediaplayer | Select-String 'versionName=|versionCode=|lastUpdateTime'
```

Must show `versionCode=2 versionName=1.0.0-friends`. If the timestamp predates the cast fixes (`d086c70`), `adb install -r` the latest APK before any further capture.

---

## Phase 1 — Build an on-device crash reporter (so the S25 Ultra can produce evidence without USB)

The S25 Ultra is the friend's device. We cannot run adb against it. We need crashes to be persisted to a file on the device's external-files dir, plus a one-tap "Share logs" button that the friend can use to send the file via WhatsApp / email.

This is purely additive — `PowerMediaPlayerApp.onCreate` already installs an `UncaughtExceptionHandler`; we extend it to also write to disk. Settings gets one new row.

### Files

- `app/src/main/java/com/powermediaplayer/util/CrashReporter.kt` (new) — installs the handler, writes `crash_<wallclock>.log` files under `getExternalFilesDir(null)/crashes/`.
- `app/src/main/java/com/powermediaplayer/PowerMediaPlayerApp.kt` (modify `onCreate`) — call `CrashReporter.install(this)` instead of the inline handler.
- `app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt` (modify) — new "Share crash logs" row that fires `Intent.ACTION_SEND_MULTIPLE` with attached crash files.
- `app/src/main/AndroidManifest.xml` (modify) — declare a `FileProvider` so the share intent can grant read URI permission for the attached files.
- `app/src/main/res/xml/file_paths.xml` (new) — `<external-files-path>` declaration for the FileProvider authority.

### Steps

- [ ] **1.1 Create `CrashReporter.kt` (new file).**

Concrete code (write verbatim):

```kotlin
package com.powermediaplayer.util

import android.content.Context
import com.powermediaplayer.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists every uncaught exception to a file under
 * `getExternalFilesDir("crashes")/crash_<timestamp>.log` so the user
 * can retrieve and share it without USB / adb. Also delegates to the
 * platform default handler so the system still shows the standard
 * "App keeps stopping" dialog and quits the process — without that,
 * the dialog gets suppressed and the user just sees a frozen screen.
 *
 * The file format is intentionally plain text:
 *   line 1: "PMP CRASH"
 *   line 2: ISO timestamp + version + Build.* fingerprint
 *   line 3: thread name + exception class + message
 *   rest:   full stack trace (incl. cause / suppressed)
 */
object CrashReporter {

    private const val DIR_NAME = "crashes"
    private const val MAX_FILES = 20  // ring-buffer; oldest pruned on next install
    private val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashFile(context, thread, throwable) }
            // Continue to platform default handler so the app actually exits.
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashesDir(context: Context): File =
        File(context.getExternalFilesDir(null), DIR_NAME).also { it.mkdirs() }

    fun listCrashFiles(context: Context): List<File> =
        crashesDir(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashesDir(context)
        // Prune to MAX_FILES.
        listCrashFiles(context).drop(MAX_FILES).forEach { runCatching { it.delete() } }
        val file = File(dir, "crash_${ts.format(Date())}.log")
        file.writeText(buildString {
            appendLine("PMP CRASH")
            appendLine(
                "${ts.format(Date())} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                    "fp=${android.os.Build.FINGERPRINT} sdk=${android.os.Build.VERSION.SDK_INT} " +
                    "model=${android.os.Build.MODEL}"
            )
            appendLine("thread=${thread.name} exc=${throwable.javaClass.name} msg=${throwable.message}")
            appendLine("--- stack ---")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            append(sw.toString())
        })
    }
}
```

- [ ] **1.2 Modify `PowerMediaPlayerApp.kt` to use `CrashReporter.install`.**

Replace the existing inline `Thread.setDefaultUncaughtExceptionHandler` block with a single call:

```kotlin
override fun onCreate() {
    super.onCreate()
    com.powermediaplayer.util.CrashReporter.install(this)
}
```

- [ ] **1.3 Create `app/src/main/res/xml/file_paths.xml` (new).**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="crashes" path="crashes/" />
</paths>
```

- [ ] **1.4 Declare the FileProvider in `AndroidManifest.xml`.** Add inside `<application>`, after the existing `<service>` blocks, before the `</application>` close:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

- [ ] **1.5 Add "Share crash logs" row to `SettingsScreen.kt`.** Place it at the very top of the screen (before the existing "Display" section) so the friend can find it without scrolling on first install. Concrete code:

```kotlin
// Crash-log share — top of screen so the friend can find it on a
// fresh install when reporting a crash. No-op when no crash files
// exist (the row simply hides).
val context = androidx.compose.ui.platform.LocalContext.current
val crashFiles = remember { com.powermediaplayer.util.CrashReporter.listCrashFiles(context) }
if (crashFiles.isNotEmpty()) {
    SettingsSectionHeader("Diagnostics")
    Surface(
        color = SurfaceElevated,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable {
                val authority = "${context.packageName}.fileprovider"
                val uris = ArrayList<android.net.Uri>(crashFiles.size).apply {
                    crashFiles.forEach { f ->
                        add(androidx.core.content.FileProvider.getUriForFile(context, authority, f))
                    }
                }
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/plain"
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share crash logs"))
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = null,
                tint = TealAccent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Share crash logs (${crashFiles.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = TealAccent
                )
                Text(
                    text = "Tap to attach the latest crash dumps to a message or email.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }
    }
    SettingsDivider()
}
```

- [ ] **1.6 Build the instrumented APK.**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Verify `dist/PowerMediaPlayer-1.0.0-friends-2026-05-06-instrumented.apk` exists by `cp app/build/outputs/apk/debug/app-debug.apk dist/PowerMediaPlayer-1.0.0-friends-2026-05-06-instrumented.apk`.

- [ ] **1.7 Commit.**

```bash
git add app/src/main/java/com/powermediaplayer/util/CrashReporter.kt \
        app/src/main/java/com/powermediaplayer/PowerMediaPlayerApp.kt \
        app/src/main/res/xml/file_paths.xml \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/com/powermediaplayer/ui/settings/SettingsScreen.kt
git commit -m "investigation(crash): on-device crash reporter + Share-logs UI"
git push
```

- [ ] **1.8 Smoke-test on Z Fold 6.** Trigger a deliberate crash to confirm files land:

```powershell
& $adb install -r "C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\app\build\outputs\apk\debug\app-debug.apk"
```

Tap the Cast button (currently crashes per F2). Re-launch app → Settings → "Share crash logs" appears. Tap it → share sheet opens with `crash_*.log` attached. Pull the log directly:

```powershell
& $adb shell run-as com.powermediaplayer ls -la /storage/emulated/0/Android/data/com.powermediaplayer/files/crashes/
& $adb pull /storage/emulated/0/Android/data/com.powermediaplayer/files/crashes "$dir\phase1-step08-zfold6-crashes/"
```

Move on only if a `crash_<ts>.log` is present and contains a stack trace.

---

## Phase 2 — Reproduce + capture F2 (Cast button tap crash) on Z Fold 6

### Steps

- [ ] **2.1 Reset state.**

```powershell
& $adb shell am force-stop com.powermediaplayer
& $adb logcat -c
Start-Sleep -Seconds 2
& $adb shell monkey -p com.powermediaplayer -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 5
```

- [ ] **2.2 Tap the Cast button** (manually — synthetic `input tap` does not faithfully emulate the View click event for this widget).

- [ ] **2.3 Capture the AndroidRuntime fatal log immediately.**

```powershell
& $adb logcat -d -v threadtime AndroidRuntime:E PowerMediaPlayer:* PMP_DIAG:I '*:S' > "$dir\phase2-step03-cast-tap-crash-zfold6.txt"
```

Expected: a `FATAL EXCEPTION: main` block. Document the exception class + the first 5 frames in CHANGELOG. Do not interpret yet.

- [ ] **2.4 Pull the crash file from the file-based reporter.**

```powershell
& $adb pull /storage/emulated/0/Android/data/com.powermediaplayer/files/crashes "$dir\phase2-step04-zfold6-crash-files/"
```

The file-based dump is a redundant capture — used to validate the reporter for Phase 4 use, AND to confirm the crash signature matches what logcat shows.

- [ ] **2.5 Capture MediaRouter state at the moment of crash.**

```powershell
& $adb shell dumpsys media_router > "$dir\phase2-step05-mediarouter-zfold6.txt"
```

Useful as cross-context for F3.

---

## Phase 3 — Reproduce + capture F3 (zero-result Cast discovery) on Z Fold 6

### Steps

- [ ] **3.1 Verify other apps DO see Cast devices on the same Wi-Fi (per user's stated fact).**

The user has confirmed unequivocally that other apps see devices. Do not re-ask. Record in CHANGELOG: "User confirmed (verbatim): other apps see Cast devices on the same Wi-Fi network — investigation accepts this and looks for why our app does not."

- [ ] **3.2 Capture the system-wide MediaRouter route list.** This shows what Cast devices the OS has discovered, regardless of any app.

```powershell
& $adb shell dumpsys media_router 2>&1 | Out-File "$dir\phase3-step02-system-media-router.txt"
```

Look for `RouteInfo{ name=<device-name>` blocks NOT named "Phone" / "Speaker" — those are the actual Cast devices.

- [ ] **3.3 Capture our app's route selector.** Open the app, navigate to Player tab, watch logcat for the MediaRouter announcements:

```powershell
& $adb shell am force-stop com.powermediaplayer
& $adb logcat -c
& $adb shell monkey -p com.powermediaplayer -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 8
& $adb logcat -d -v threadtime MediaRouter:* CastClient:* CastDiscoveryServiceController:* MediaRouteProviderProxy:* CastSocket:* '*:S' > "$dir\phase3-step03-our-cast-discovery-zfold6.txt"
```

Look for `Adding selector` / `Discovery started` / `Discovery stopped` / `Selector(...)` lines. The "selector" determines which routes are announced to our app. If our selector excludes the routes the OS has discovered, the chooser dialog renders empty.

- [ ] **3.4 Compare to a known-working app.** Open YouTube, navigate to a video, dismiss the cast chooser if it appears, capture the same logcat:

```powershell
& $adb logcat -c
& $adb shell monkey -p com.google.android.youtube -c android.intent.category.LAUNCHER 1 | Out-Null
# manually wait for player to be visible, ≈8 s
Start-Sleep -Seconds 12
& $adb logcat -d -v threadtime MediaRouter:* CastClient:* CastDiscoveryServiceController:* '*:S' > "$dir\phase3-step04-youtube-cast-discovery-zfold6.txt"
```

Diff: `phase3-step03` vs `phase3-step04`. Same Wi-Fi state, two apps; if YouTube's log shows route ADDED events with names matching the user's actual devices and ours doesn't, the asymmetry is locked. If our log shows the same route names but the chooser doesn't render them, the issue is dialog-side, not discovery-side.

- [ ] **3.5 Check Wi-Fi multicast lock state.** Some Samsung devices throttle / drop SSDP discovery without an explicit `WifiManager.MulticastLock`.

```powershell
& $adb shell dumpsys wifi | Select-String -Pattern 'MulticastLock|com.powermediaplayer'
```

If our app holds no MulticastLock and YouTube does, that's a Phase 5 candidate.

- [ ] **3.6 Verify Cast SDK initialised correctly at app startup.** Check for `CastContext.getSharedInstance()` failures:

```powershell
& $adb logcat -d -v threadtime CastClient:* CastContext:* CastOptionsProvider:* '*:S' > "$dir\phase3-step06-cast-context-init-zfold6.txt"
```

Specifically: `CastClient: Init success` (good) vs `CastClient: Init failed` (the issue is at Cast SDK init, not at discovery time).

---

## Phase 4 — Reproduce F1 (S25 Ultra launch crash) via the friend's device

### Plan A: Friend installs the instrumented APK and shares the crash dump file

- [ ] **4.A.1 Send the friend the instrumented APK** (`dist/PowerMediaPlayer-1.0.0-friends-2026-05-06-instrumented.apk`) via WhatsApp.

- [ ] **4.A.2 Friend installs.** Same install flow as before.

- [ ] **4.A.3 Friend launches the app.** Force-close occurs. Per the new crash reporter, a `crash_*.log` is written to `/sdcard/Android/data/com.powermediaplayer/files/crashes/` BEFORE the platform handler exits the process. Verify by friend re-launching the app — if the launch crashes again BUT the second instance lives long enough to write the dump, then `getExternalFilesDir(null)` is actually returning a writable path.

  - **Concern: if the crash is in `Application.attachBaseContext` or before `Application.onCreate` finishes**, the reporter's `install` may not have run yet. In that case the dump file won't be created on the first crash. **Mitigation**: log `CrashReporter.install` BEFORE everything else in `Application.onCreate`, and accept that crashes during ContentProvider init (which runs before Application.onCreate) cannot be captured this way. If the dump is empty, fall back to Plan B.

- [ ] **4.A.4 Friend opens app a second time.** App force-closes again. Friend opens app a third time (if needed) and navigates to Settings → Diagnostics → Share crash logs (this is why the row is at the top of Settings — they don't have to scroll). Friend sends the file via WhatsApp.

  - **Failure mode for 4.A.4**: the launch crash makes the app unable to even open Settings. **Mitigation**: Plan B.

- [ ] **4.A.5 User receives the file, places it under** `$dir\phase4-step05-s25-ultra-crash-files\`. CHANGELOG is updated with file name + first 30 lines (the stack header).

### Plan B: Out-of-app file retrieval

If the launch crash prevents reaching Settings → Share, the friend retrieves the file via Android's file manager:

- [ ] **4.B.1 Friend opens "Files by Google" / "My Files" (Samsung).**
- [ ] **4.B.2 Navigate to** `Android/data/com.powermediaplayer/files/crashes/`. (On Android 11+ this requires SAF; on Samsung One UI 7 the My Files app handles this transparently in 99% of cases.)
- [ ] **4.B.3 Friend long-presses the latest `crash_*.log` and shares it via WhatsApp.**

### Plan C: Android 15 emulator

If both Plan A and Plan B fail (e.g. crash too early to write file, friend can't reach the directory), the contingency is local emulator on this machine:

- [ ] **4.C.1 Verify Android Studio CLI tools and an Android 15 system image are available.**

```powershell
& "C:\Users\Kabir\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed | Select-String "system-images.*35"
```

Expected: at least one `android-35` system image. If not, install:

```powershell
& "C:\Users\Kabir\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "system-images;android-35;google_apis;arm64-v8a"
```

(Adjust ABI to `x86_64` if the host is x86 with no ARM emulation; the Z Fold 6 was tested on ARM, which we want to match for One UI 7's parity surface.)

- [ ] **4.C.2 Create a test AVD.**

```powershell
& "C:\Users\Kabir\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n test_one_ui_7 -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_8
```

- [ ] **4.C.3 Launch the emulator.**

```powershell
& "C:\Users\Kabir\AppData\Local\Android\Sdk\emulator\emulator.exe" -avd test_one_ui_7 -no-snapshot-load -no-boot-anim
```

(Run-in-background; takes ~60-90 s to boot.)

- [ ] **4.C.4 Install the APK and launch.**

```powershell
& $adb -s emulator-5554 install -r "dist\PowerMediaPlayer-1.0.0-friends-2026-05-06-instrumented.apk"
& $adb -s emulator-5554 logcat -c
& $adb -s emulator-5554 shell monkey -p com.powermediaplayer -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 5
& $adb -s emulator-5554 logcat -d -v threadtime AndroidRuntime:E PowerMediaPlayer:* PMP_DIAG:I '*:S' > "$dir\phase4-step04-emulator-crash.txt"
```

Note: stock AOSP Android 15 in an emulator is NOT exactly One UI 7 — Samsung's customisations (heavier theme tree, BAL changes, custom AppCompat fork etc.) are absent. So the emulator's behaviour is necessary-but-not-sufficient evidence. If the emulator reproduces the crash, we have what we need. If it doesn't, the crash is Samsung-specific and the friend's file is the only path forward.

- [ ] **4.C.5 Run the same Cast-tap test and discovery test** on the emulator for cross-device comparison.

---

## Phase 5 — Pre-commit candidate substrates

Pre-committed BEFORE looking at evidence so we cannot retroactively narrow to a favourite suspect.

### F1 (S25 Ultra launch crash) — One UI 7 / Android 15 specifics

| # | Substrate | Falsifiable by … |
|---|---|---|
| L1 | `MainActivity` extends `FragmentActivity`; AppCompat-derived theme attributes required by FragmentActivity may be missing from our PowerMediaPlayer theme on One UI 7 — the prior fix forced `Theme.AppCompat.NoActionBar` on the CastButton, but the Activity-level theme is separate | crash file mentions `IllegalStateException: You need to use a Theme.AppCompat theme` or similar |
| L2 | `kotlin-android` plugin / Hilt 2.54 / AGP 8.7 produced a DEX layout that one of the new APIs in Android 15 rejects — e.g. predictive back gesture, edge-to-edge insets, EdgeToEdge call sites broken on One UI 7 | crash trace cites `EdgeToEdge` / `androidx.activity.enableEdgeToEdge` / window-insets methods |
| L3 | Hilt's component-tree generated for the new `FragmentActivity` superclass on AGP 8.7 differs from what AGP 9.x produced; some `@AndroidEntryPoint` injection point fails on Android 15 | crash trace cites `Hilt_*` class / Dagger / `inject(this)` |
| L4 | Media3 1.6.0 Cast init throws on Android 15 because Google Play services for Cast on One UI 7 changed the `CastContext.getSharedInstance` contract | crash trace cites `CastContext` / `play-services-cast` / `CastOptionsProvider` |
| L5 | Samsung's One UI 7 stricter foreground-service-from-background policy aborts the launch when our `AndroidEntryPoint` injects `playbackConnection` which then tries to bind to `MediaSessionService` from `MainActivity.onCreate` | crash trace cites `ForegroundServiceStartNotAllowedException` or `bindService` / `startForegroundService` |
| L6 | Samsung One UI 7 added stricter notification-channel requirements that our `SpotifyBounceService` channel violates; the violation surfaces only on app launch when the channel is created | crash trace cites `NotificationChannel` / `IllegalArgumentException` from `createNotificationChannel` |
| L7 | One UI 7 enforces AndroidX `mediarouter` v1.7.0+ requirements stricter; our `mediarouter:1.7.0` pin combined with AGP 8.7 produces a manifest merge that Android 15 rejects at install / first-launch | crash trace cites manifest merger / class-not-found for a mediarouter resource |
| L8 | The new `data_extraction_rules.xml` we added in the audit phase has an XML structure One UI 7 rejects on first-run AutoBackup probe | crash trace cites `BackupAgent` / `dataExtractionRules` |
| L9 | Compose Compiler 2.1.20 emitted bytecode using a JVM 21 feature that ART on One UI 7 (Android 15) doesn't accept | crash trace cites `VerifyError` / `NoSuchMethodError` |
| L10 | The crash is not in our code at all — it's a Google Play services Cast crash that Android 15's stricter policy now reports as our app's fault | crash trace cites `com.google.android.gms.*` exclusively |
| Lnull | Friend's device has a different APK than expected (e.g. they installed the older `dist/PowerMediaPlayer-7c7dbdd.apk` by accident) | crash file's `version` line doesn't match `1.0.0-friends (2)` |

### F2 (Cast tap crash on Z Fold 6) — what changed since `d086c70`

| # | Substrate | Falsifiable by … |
|---|---|---|
| C1 | The `FragmentActivity` change at `d086c70` did NOT actually ship in the APK the user is currently running (their phone has an older install) | Phase 0.4 dumpsys shows old `lastUpdateTime` |
| C2 | The dialog opens but immediately fails because the AppCompat ContextThemeWrapper does NOT chain back to a FragmentActivity the dialog can attach to (the dialog walks the context up looking for the Activity, and ContextThemeWrapper of an Application Context returns null) | crash trace cites `IllegalStateException: The activity must be a subclass of FragmentActivity` AGAIN with the SAME message but pointing to a different stack frame |
| C3 | The dialog opens but crashes during inflation because the AppCompat dark theme requires resources that aren't bundled (we pulled `Theme.AppCompat.NoActionBar` from `androidx.appcompat:R` — verify the dependency is actually on the classpath) | crash trace cites `Resources$NotFoundException` / `Theme.AppCompat` |
| C4 | The CastContext is null at click time because Google Play services for Cast crashed at startup; the dialog tries to query the routes and dereferences null | crash trace cites NPE in `CastContext` / `CastSession` |
| C5 | A new exception unrelated to the FragmentActivity issue: e.g. our route selector references a Cast Application ID that's wrong, and the dialog fails when trying to build the discovery filter | crash trace cites `MediaRouteSelector` / `CastMediaControlIntent` |
| C6 | The dialog opens fine on the FIRST tap but crashes on the SECOND tap because the first dismissed without releasing FragmentManager state | reproducible only on second tap |

### F3 (Cast device discovery zero-result on Z Fold 6) — selector / multicast / SDK init

| # | Substrate | Falsifiable by … |
|---|---|---|
| D1 | `CastOptionsProviderImpl` returns the default Cast Media Receiver `CC1AD845`; our app's MediaRouteSelector therefore restricts to routes that support that receiver. The user's actual devices may not advertise themselves as supporting `CC1AD845` (e.g. older Chromecasts, third-party Cast-capable TVs) — those routes are filtered out at our layer even though the OS-level `dumpsys media_router` lists them | Phase 3.2 system list includes routes that 3.3 our-app log doesn't, AND the system list shows `<categories>` not including `CC1AD845` |
| D2 | Our app doesn't acquire a `WifiManager.MulticastLock` before kicking off Cast discovery; on some Samsung builds the SSDP packets get dropped without it | `dumpsys wifi` MulticastLock count = 0 for our package; YouTube's MulticastLock count > 0 |
| D3 | Cast SDK initialisation (`CastContext.getSharedInstance`) failed silently and the route provider was never registered with MediaRouter; the chooser dialog literally has nothing to render | Phase 3.6 logs show `CastClient: Init failed` or absence of `CastDiscoveryServiceController: Started` |
| D4 | Our `CastOptionsProvider.getAdditionalSessionProviders` returns an empty list and the route selector therefore matches no provider | code review of `CastOptionsProviderImpl` |
| D5 | The user's home network has multicast packets blocked at the router, but YouTube uses Cast over a different transport (e.g. mDNS via the Google account) that doesn't go through the LAN multicast — meaning what works for YouTube isn't necessarily a Cast-SDK-LAN-discovery path. Our app uses the LAN-multicast path only and so genuinely can't see devices on this network | testable only by capturing pcap on the router; out of scope for this plan |
| D6 | Per-app permission "Local network" was added in Android 14+ and Samsung's One UI 6+ enforces it strictly; we have no manifest declaration for it; Cast SDK can't multicast | `dumpsys package com.powermediaplayer | grep -i ACCESS_LOCAL_NETWORK` returns nothing |
| Dnull | The user has multiple Wi-Fi networks at home (e.g. 2.4 GHz + 5 GHz + Guest); the phone is on one and the cast device on another | document network topology in CHANGELOG; unverifiable from code alone |

The list is exhaustive on purpose. New candidates may be appended ONLY with explicit Phase-2/3/4-evidence justification.

---

## Phase 6 — Falsify candidates against Phase 2/3/4 evidence

- [ ] **6.1 For each F1 candidate L1–L10 + Lnull, look up the Phase-4 crash file and write a one-line PASS / FAIL / INCONCLUSIVE verdict** with citation. Same template as the prior video-jump and Spotify-bounce investigations:

| # | Substrate | Verdict | Citation |
|---|---|---|---|
| L1 | … | PASS / FAIL / INCONCL. | `phase4-step05-s25-ultra-crash-files/crash_2026-05-06_HH-MM-SS.log:line N` | …

Hard rules:
- PASS requires a citation to a specific line in the crash file or logcat artefact.
- Without a citation it is INCONCLUSIVE, not "likely".
- A FAIL verdict requires positive evidence the substrate did NOT fire (e.g. `enableEdgeToEdge` not in stack), not just absence.

- [ ] **6.2 For each F2 candidate C1–C6, same process with Phase-2 evidence.**

- [ ] **6.3 For each F3 candidate D1–D6 + Dnull, same process with Phase-3 evidence.**

- [ ] **6.4 Two-substrate corroboration rule.** A candidate is only allowed onto the Phase-7 fix shortlist when it has supporting evidence from at least TWO substrates (e.g. crash file + logcat + dumpsys; or code-review + observed behaviour). Single-substrate signals are too easy to misread.

- [ ] **6.5 Build the per-failure shortlist** with predicted observations for the fix attempts.

---

## Phase 7 — Root-cause document + fix plan

- [ ] **7.1 Write `phase7-root-cause.md`** with the same template as prior RCAs (`phase6-root-cause.md` for video jump, `phase6-root-cause.md` for Spotify bounce):
  1. Symptom (cite recordings / crash files).
  2. Reproduction script (verbatim).
  3. Confirmed substrates with code citations.
  4. Causal chain.
  5. Why other devices / other apps are unaffected.
  6. Rejected candidates with evidence — protects future sessions.
  7. Outstanding unknowns.

- [ ] **7.2 Filing the fix plan is OUT OF SCOPE of this document.** Each confirmed root cause becomes its own separate fix-plan in `docs/superpowers/plans/`. This RCA names them as follow-ups but writes no code.

---

## Phase 8 — Decommission instrumentation

- [ ] **8.1 Stop. Wait for user explicit OK.**

The crash-reporter file output is genuinely useful in production (friends-tier release benefits from on-device crash dumps), so we may decide to keep it. Discuss with user before removing. The Settings "Share crash logs" row is also useful long-term.

If the user opts to remove, do it in a single revert commit:

```bash
git revert <crash-reporter-commit>
```

The Phase-7 RCA stays in the repo regardless.

---

## Anti-patterns this plan deliberately rules out

- "It's probably a One UI 7 theme issue" without a stack trace from the friend's device. We get the stack trace first, then decide.
- Asking the user to confirm AGAIN that other apps see Cast devices on his Wi-Fi. He has stated this unequivocally; the plan accepts it.
- Touching the Cast button code blindly because tapping it crashes. The crash log will tell us where; only then.
- Reproducing the S25 Ultra crash on the Z Fold 6 — they're different devices, different OS, different OEM customisations; a Z Fold 6 reproduction would be coincidence, not signal.
- Using an Android 15 emulator without acknowledging it's a STRIPPED AOSP build, not One UI 7. The emulator is Plan C contingency only.
- Capturing only one app's discovery log. YouTube's log is the control without which "our app doesn't see what other apps see" cannot be proved.

---

## What success looks like

For each of the three failure modes:
- A reproduction script.
- A crash file or logcat capture with a clear stack trace or system message.
- One or more confirmed substrates with two-substrate corroboration.
- A list of rejected substrates with citations.
- A separate fix-plan filed (or an explicit "out of scope for v1.0" note).

The user can read the RCA, hand it to any future contributor (human or AI), and they immediately understand what's broken, where, and what to leave alone in any future refactor.
