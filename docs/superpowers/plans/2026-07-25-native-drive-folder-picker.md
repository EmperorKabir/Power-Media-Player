# Native Drive Folder Picker Implementation Plan

> **For agentic workers:** implement task-by-task. Steps use checkbox syntax.
> **Verification note:** the Drive network + Google-account path is not unit-testable
> here; every task's "test" is on-device emulator verification (`emulator-5554`,
> account already signed in). This mirrors how all Drive features in this repo are
> verified. Do NOT touch the Oppo (`3B166N000CZ00000`).

**Goal:** Replace the WebView Google Drive Picker with a native in-app folder browser,
permanently eliminating the second sign-in and the cold-start scaling, while keeping
`drive.readonly` file access. Then cut the release AAB.

**Architecture:** A Compose `DriveFolderBrowser` dialog lists a Drive folder's
sub-folders via a new `DriveOAuthProvider.listSubFolders(parentId)` (drive.readonly
REST, `mimeType = folder`). User navigates in/up and taps "Add this folder" →
existing `rememberPickedDriveFolder`. Sign-in (if needed) uses the existing NATIVE
`GoogleSignIn` flow (no WebView). The WebView picker is archived then deleted.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Google Drive REST v3,
play-services-auth GoogleSignIn, OkHttp.

---

## File map

- Modify `app/src/main/java/com/powermediaplayer/cloud/DriveOAuthProvider.kt` — add
  `listSubFolders(parentId)`.
- Modify `app/src/main/java/com/powermediaplayer/ui/cloud/CloudViewModel.kt` — add
  `listDriveSubFolders`, `driveReadyForBrowse`.
- Modify `app/src/main/java/com/powermediaplayer/ui/cloud/CloudBrowserScreen.kt` — add
  `DriveFolderBrowser` composable + `showDriveBrowser` state; repurpose
  `launchDriveOAuth()` to open the native browser (sign-in first if needed); remove
  `drivePickerLauncher` + the WebView launch.
- Delete `app/src/main/java/com/powermediaplayer/cloud/DrivePickerActivity.kt`,
  `app/src/main/assets/drive_picker.html`, and the `DrivePickerActivity` entries in
  `app/src/main/AndroidManifest.xml` (~L129) + `app/src/debug/AndroidManifest.xml` (~L19).
- Create `archive/webview-drive-picker-REMOVED-2026-07-25.md` — code snapshot for restore.
- Modify `app/build.gradle.kts` — `versionCode 55→56`, `versionName "1.5.0"→"1.5.1"`.

---

## Task 1: Archive the WebView picker code before deleting

- [ ] Copy the full current contents of `DrivePickerActivity.kt` and
  `assets/drive_picker.html`, plus the two manifest `<activity>` snippets, into
  `archive/webview-drive-picker-REMOVED-2026-07-25.md` with a header explaining it was
  removed in favour of the native browser and how to restore (also in git history at
  commit before this change).
- [ ] Commit: `docs(drive): archive WebView picker code before removal`.

## Task 2: `DriveOAuthProvider.listSubFolders`

**Files:** Modify `DriveOAuthProvider.kt` (add method near `listFiles`).

- [ ] Add:
```kotlin
/**
 * Native folder-browser support: list ONLY the sub-folders of [parentId]
 * ("root" = My Drive top level). drive.readonly reads the whole Drive, so
 * this needs no per-folder grant. Replaces the WebView Picker for CHOOSING
 * a folder; access still flows through listFiles/download afterwards.
 */
suspend fun listSubFolders(parentId: String): Result<List<CloudMediaItem>> =
    withContext(Dispatchers.IO) {
        try {
            val token = fetchAccessTokenBlocking()
                ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))
            val q = "mimeType = '$MIME_FOLDER' and '$parentId' in parents and trashed = false"
            val out = mutableListOf<CloudMediaItem>()
            var pageToken: String? = null
            do {
                val url = "https://www.googleapis.com/drive/v3/files?" +
                    "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                    "&fields=nextPageToken,files(id,name)&orderBy=name&pageSize=1000" +
                    (pageToken?.let { "&pageToken=$it" } ?: "")
                http.newCall(Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer $token").build()).execute().use { resp ->
                    if (!resp.isSuccessful)
                        return@withContext Result.failure(IllegalStateException("Drive folders HTTP ${resp.code}"))
                    val json = JSONObject(resp.body?.string().orEmpty())
                    json.optJSONArray("files")?.let { files ->
                        for (i in 0 until files.length()) {
                            val f = files.getJSONObject(i)
                            out += CloudMediaItem(
                                id = f.getString("id"), name = f.optString("name"),
                                mimeType = MIME_FOLDER, size = 0L, downloadUrl = "",
                                sourceProvider = CloudProviderType.GOOGLE_DRIVE,
                                isFolder = true, parentId = parentId)
                        }
                    }
                    pageToken = json.optString("nextPageToken").ifBlank { null }
                }
            } while (pageToken != null)
            Result.success(out)
        } catch (e: Exception) { Result.failure(e) }
    }
```

## Task 3: CloudViewModel wrappers

**Files:** Modify `CloudViewModel.kt` (near `rememberPickedDriveFolder`).

- [ ] Add:
```kotlin
/** Native folder browser: sub-folders of [parentId] ("root" = My Drive top). */
suspend fun listDriveSubFolders(parentId: String): Result<List<CloudMediaItem>> =
    driveOAuthProvider.listSubFolders(parentId)

/** True when signed in AND read access already granted (skip sign-in). */
fun driveReadyForBrowse(): Boolean =
    driveOAuthProvider.currentAccountEmail() != null && !driveOAuthProvider.needsReadConsent()
```

## Task 4: Native browser UI + wiring; remove WebView launch

**Files:** Modify `CloudBrowserScreen.kt`.

- [ ] Remove `drivePickerLauncher` (the `rememberLauncherForActivityResult` block) and,
  inside `driveOAuthLauncher`, replace the token-fetch + `drivePickerLauncher.launch`
  with `showDriveBrowser = true` on sign-in success.
- [ ] Add state near the other `remember`s: `var showDriveBrowser by remember { mutableStateOf(false) }`.
- [ ] Repurpose `launchDriveOAuth()` (keep the name; all ~6 call sites stay):
```kotlin
fun launchDriveOAuth() {
    if (!firstPickWarningSeen) { pendingFirstPickWarning = true; return }
    if (viewModel.driveReadyForBrowse()) showDriveBrowser = true
    else driveOAuthLauncher.launch(viewModel.buildDriveOAuthSignInIntent())
}
```
- [ ] Render the browser (near the other dialogs):
```kotlin
if (showDriveBrowser) {
    DriveFolderBrowser(
        listSubFolders = { pid -> viewModel.listDriveSubFolders(pid) },
        onAdd = { id, name -> showDriveBrowser = false; viewModel.rememberPickedDriveFolder(id, name) },
        onDismiss = { showDriveBrowser = false }
    )
}
```
- [ ] Add the composable (bottom of file), imports `TextOverflow`, `Alignment`, `Arrangement`:
```kotlin
@Composable
private fun DriveFolderBrowser(
    listSubFolders: suspend (String) -> Result<List<com.powermediaplayer.cloud.CloudMediaItem>>,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val stack = remember { mutableStateListOf("root" to "My Drive") }
    val current = stack.last()
    var folders by remember { mutableStateOf<List<com.powermediaplayer.cloud.CloudMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current.first) {
        loading = true; error = null
        listSubFolders(current.first)
            .onSuccess { folders = it }.onFailure { error = it.message ?: "Could not load folders" }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stack.size > 1) IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up a folder", tint = TealAccent)
                }
                Text(current.second, color = TealAccent, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    loading -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = TealAccent)
                    }
                    error != null -> Text("Couldn't load folders: $error", color = TextPrimary)
                    folders.isEmpty() -> Text(
                        "No sub-folders here. Tap “Add this folder” to add “${current.second}”.",
                        color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(folders, key = { it.id }) { f ->
                            Row(Modifier.fillMaxWidth().clickable { stack.add(f.id to f.name) }
                                .padding(vertical = 14.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Folder, null, tint = TealAccent)
                                Spacer(Modifier.width(12.dp))
                                Text(f.name, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = current.first != "root",
                onClick = { onAdd(current.first, current.second) },
                modifier = Modifier.defaultMinSize(minWidth = 120.dp, minHeight = 56.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Add this folder",
                    color = if (current.first != "root") TealAccent else TextPrimary.copy(alpha = 0.4f))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextPrimary) } }
    )
}
```

## Task 5: Delete the WebView picker

- [ ] Delete `DrivePickerActivity.kt` and `assets/drive_picker.html`.
- [ ] Remove the `<activity ... DrivePickerActivity ...>` block from
  `app/src/main/AndroidManifest.xml` and `app/src/debug/AndroidManifest.xml`.
- [ ] `grep -rn DrivePickerActivity app/src` → expect no code refs remain.

## Task 6: Build debug + verify on emulator

- [ ] `./gradlew :app:assembleDebug` → expect BUILD SUCCESSFUL.
- [ ] `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Drive: Cloud → Add folder → native browser opens (NO WebView, NO second sign-in,
  NO scaling) → navigate Fiction → Dungeon Crawler Carl Series → This Inevitable Ruin
  → Add this folder → folder opens → `listFiles driveReturned=5`, `.m4b` shows.
- [ ] Confirm via logcat `driveReturned=5` and the book row.

## Task 7: Version bump + release AAB

- [ ] `app/build.gradle.kts`: `versionCode = 56`, `versionName = "1.5.1"`.
- [ ] `./gradlew :app:bundleRelease` → sign with upload key → copy
  `app/build/outputs/bundle/release/app-release.aab` to `dist/PowerMediaPlayer-release.aab`.
- [ ] Report the versionCode + that drive.readonly needs Google verification for public.

## Task 8: Commit + push

- [ ] Commit all, push to origin/main.

---

## Self-review

- Spec coverage: kill 2nd sign-in + scaling (Tasks 4–5 remove WebView) ✓; keep readonly
  access (unchanged) ✓; native picker (Task 4) ✓; AAB (Task 7) ✓; archive-before-delete
  (Task 1) ✓.
- Type consistency: `listSubFolders`/`listDriveSubFolders` return
  `Result<List<CloudMediaItem>>` throughout; `driveReadyForBrowse` used in Task 4 defined
  in Task 3; `showDriveBrowser` defined + used in Task 4.
- Placeholder scan: none — all code is concrete.
