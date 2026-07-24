# Google Drive Picker + OAuth saga — full findings (2026-07-22 → 2026-07-24)

**Purpose:** a self-contained, richly detailed record of the multi-day Google Drive
picker / OAuth investigation, so that after any context loss the whole thing can be
understood without re-deriving it. Reading priority: the **TL;DR** and **"The
correct model"** first; the **"Wrong theories"** section documents every dead end
and *why* it was wrong (the user explicitly asked for this — the same wrong turns
were reached repeatedly and must not be repeated).

Current state at time of writing: **vc55 / 1.5.0** cut and signed
(`dist/PowerMediaPlayer-release.aab`), embedded Drive picker restored as the
add-folder path, cold-start scaling fixed, consent screen published to production.

---

## 0. TL;DR (the resolved truth)

- The app has **two independent Drive access paths**. Only one was ever the point of
  confusion:
  1. **System / SAF picker** (`GoogleDriveProvider`): `ACTION_OPEN_DOCUMENT_TREE` →
     `content://` tree URI → streamed via `ContentResolver` = the Google Drive app's
     **full** access. Sees everything. Looks like the system Files/Drive UI.
  2. **Embedded picker** (`DriveOAuthProvider` + `DrivePickerActivity`): Google JS
     Picker in a WebView, `drive.file` OAuth scope → stores
     `…/files/{id}?alt=media` → streamed via the Drive REST API. The "in-app,
     double sign-in" picker the user likes.
- **The embedded `drive.file` picker DOES work** and DOES grant access to a picked
  folder's existing files. Proven by: (a) the user's friend downloading the user's
  shared files through the real app on a Samsung; (b) the Google Cloud OAuth client
  `com.powermediaplayer` + debug-SHA-1 ("client #3") showing **2138 auth calls in 30
  days** — an active, working install fleet.
- **Every "it can't work / 404" conclusion during the investigation was a testing
  artifact of the `.test` debug build**, which is a *different OAuth client*
  (`com.powermediaplayer.test`, "client #1") with no accumulated `drive.file` grants,
  and which additionally shares the debug SHA-1 with the live client #3 (a
  fingerprint collision that mis-attributes its picks' grants to #3). Real builds
  (Play `com.powermediaplayer`+`C8:FE`, or sideloaded base-package
  `com.powermediaplayer`+debug) use clients that hold the grants and work fine.
- No recent Google change caused any of this. No Google Cloud change was needed or
  made — in particular **client #3 must NOT be deleted** (it is live traffic).

---

## 1. The two Drive access paths (architecture)

`GoogleDriveProvider.kt` (misleadingly named — it's the SAF provider):
- `buildSignInIntent()` → `ACTION_OPEN_DOCUMENT_TREE` (pinned to
  `com.google.android.documentsui` when present).
- Returns a persistable `content://com.google.android.apps.docs.storage/…` tree URI.
- `listChildrenFast` enumerates via `DocumentsContract`, with an `EXTRA_LOADING`
  wait-for-load poll (Drive's DocumentsProvider returns an empty cursor first).
- Streams via `ContentResolver` (ExoPlayer `DefaultDataSource` handles `content://`).
- **Uses the Google Drive app's own full access → sees all files, shared or not.**

`DriveOAuthProvider.kt` + `DrivePickerActivity.kt` (the embedded picker):
- `GoogleSignIn` with scope `https://www.googleapis.com/auth/drive.file` (no
  `google-services.json`; the OAuth client is auto-resolved by *package + signing
  SHA-1*).
- `DrivePickerActivity` loads Google's JS Picker in a WebView
  (`setOAuthToken`, `setDeveloperKey`, `setAppId`, folder-select enabled).
- On pick, stores the Drive folder id; `listFiles` queries
  `files?q='<id>' in parents…`; downloads/streams via `…/files/{id}?alt=media`.
- **`drive.file` = per-app access to files the app created or that the user granted
  via the Picker. Picking a folder grants the app that folder AND its contents** (see
  §3 — this is the crucial fact that was doubted and is actually true for real
  clients).

Which path a stored Drive folder uses is decided at add-time: SAF folders are
`content://` (`SettingsDataStore.drivePickedRoots` / `DrivePickedRoot`), OAuth
folders are Drive ids (`driveOauthPickedFolders`). `DriveOfflineResolver` /
`OfflineMediaManager` disambiguate by URI prefix (`content://` = SAF, `…/files/{id}?alt=media` = OAuth).

---

## 2. Google Cloud / OAuth configuration (ground truth from console audit)

- **Project:** `power-media-player-495022`, **project number `184142114356`**
  (the project number is the Picker `setAppId` / `DRIVE_PICKER_APP_ID`).
- **Picker developer key** `DRIVE_PICKER_API_KEY` = `AIzaSy…` (in `local.properties`,
  redacted here; it is client-side visible in the picker URL so not a hard secret).
- **Four OAuth clients** (from the 2026-07-24 console audit):
  | # | Name | Type | Package | SHA-1 | Notes |
  |---|------|------|---------|-------|-------|
  | 1 | "Debug Testing" (22 May→Jul 2026) | Android | `com.powermediaplayer.test` | debug `BD:78…AE` | the `.test` build's client. Created recently; **no grants → 404s** in testing. |
  | 2 | "Power Media Player (Play production)" (9 May 2026) | Android | `com.powermediaplayer` | Play-App-Signing `C8:FE…A8` | the real distributed Play client. |
  | 3 | "Power Media Player" (1 May 2026) | Android | `com.powermediaplayer` | debug `BD:78…AE` | **ACTIVE: 2138 calls/30 days. DO NOT DELETE.** Serves base-package debug/sideloaded installs (the friend's app). Client id `184142114356-hgjp9crjfabivu0e8t99abub2tilm2f7…`. |
  | 4 | "Web client 1" (1 May 2026) | Web | — | — | needed by the JS Picker. |
- **Signing fingerprints** (all public, not secret):
  - debug SHA-1 `BD:78:32:1D:87:BC:14:76:F8:F3:D6:A5:3E:22:07:7E:B6:DE:BF:AE`,
    SHA-256 `F8:08:70:3B:CE:7E:10:D2:F2:93:C3:58:1D:1D:61:4E:3F:C8:C2:99:4A:2C:A1:22:24:9B:EF:A8:A9:A7:49:07`
    (from `~/.android/debug.keystore`, alias `androiddebugkey`, storepass `android`).
  - Play App Signing SHA-1 `C8:FE:B1:BC:7E:74:6E:81:64:82:C6:A6:60:41:FE:13:85:34:00:A8`.
  - Upload key SHA-1 `55:80…` (no dedicated Android client; Play re-signs to C8:FE
    for distribution, so the distributed app matches client #2).
- **OAuth consent screen:** user type **External**; scope `drive.file` is **non-sensitive**.
  Test users: `bhasin.kabir@gmail.com`, `harnessjack@gmail.com`.
  **Publishing status: In production** (published 2026-07-24). Because the only scope
  is non-sensitive, publishing needed **no CASA / no security review / no brand
  verification** (the three verification triggers — >10 authorized domains, an
  uploaded logo, or a sensitive/restricted scope — none apply; 1 domain
  `emberorkabir.github.io`, no logo). **Keep the logo blank** — uploading one would
  trigger verification.
- **The 100-user OAuth cap does NOT apply**: per Google's own text it only limits
  users "when requesting *unapproved sensitive or restricted* scopes." With only
  non-sensitive `drive.file`, the cap is displayed but inert — unlimited users.

---

## 3. The correct model (what is actually true)

1. **`drive.file` + Picker folder-pick grants access to the folder's existing files.**
   Picking a folder records a per-(OAuth-client, user, folder-tree) grant;
   `files.list('<id>' in parents)` and `files.get(fileId)` then succeed for that
   client. This contradicts some online dev lore ("drive.file only sees files your
   app created") and Google's terse scope doc — but the friend's working downloads
   and client #3's live traffic prove it.
2. **Grants are per OAuth client_id, not per device and not per project.** A grant
   accumulated by client #3 is invisible to client #1 (`.test`). This is the whole
   reason the `.test` build 404s while real builds work.
3. **The `.test` build is a different app to Google** (`com.powermediaplayer.test`).
   Never conclude a Drive capability is impossible from it. It also *shares the debug
   SHA-1 with the older live client #3*, so its own picks likely register grants
   against #3 (fingerprint-based attribution), leaving client #1 with nothing → 404.
4. **The system (SAF) picker also works** and needs no OAuth/verification, but looks
   like the system UI (the "opens the Google Drive app" the user disliked) and adds a
   "USE THIS FOLDER" tap.
5. **No recent Google change is responsible.** The Fold 7 ("SM-F966B", Android 16,
   One UI 8.5.0) that "worked" was the same OS as the Oppo (Android 16); the working
   path there was a real client (base-package build → client #3, or SAF), never the
   `.test` identity.

---

## 4. Wrong theories and WHY each was wrong (the dead ends)

Documented deliberately — these were each reached with apparent confidence and were
each wrong. The recurring root error: **testing the Drive picker through the `.test`
debug build and treating its OAuth-client-specific 404 as a property of `drive.file`
or of the app.**

1. **"`drive.file` fundamentally cannot list/read a picked folder's existing files
   (files.get → 404, files.list → [])."**
   - Evidence cited at the time: on-device `files.get` on a user-owned `.m4b` →
     `HTTP 404 "File not found"`; every list form → `"files": []`; Google's scope doc
     says "files you open… or that the user shares" (read as per-file, no folder
     cascade); a dev forum thread said drive.file needs `drive.readonly` for folders.
   - **Why wrong:** all of that was measured on the `.test` client (#1) which holds no
     grants. The friend's real client downloads the same files fine. `drive.file`
     folder-pick *does* grant folder contents for a client that made the pick.
2. **"A recent Google change (last few weeks) broke it."**
   - Investigated by web-searching Google's own sources. Findings: the Feb-2025→
     Feb-2026 "limited/expansive access" overhaul is about *human user sharing
     permissions*, explicitly not OAuth apps / `drive.file` / the Picker. Google's
     embedded-WebView OAuth block (2023) and third-party-cookie deprecation (2024) are
     years old. **No recent change explains it.** (This conclusion still stands — it
     was never a Google-side change.)
3. **"It's a `drive.file` shared-folder limitation" / "the files are owned by someone
   else."**
   - **Why wrong:** the Google Drive connector (full access) confirmed every file
     (Jurassic Park `.m4b`/`.aax`, This Inevitable Ruin `.m4b`/`.aax`) is owned by
     `bhasin.kabir@gmail.com`. Ownership was never the issue.
4. **"`corpora=allDrives` I added is the regression making folders list empty."**
   - Partly acted on: removed `corpora=allDrives` + `includeItemsFromAllDrives` to
     match the vc46 scope. Folders then listed in the `.test` run.
   - **Why over-attributed:** the empty results were confounded by the client-identity
     404 and by testing an empty top-level "2 The Lost World" shortcut. Removing
     `corpora` is still the *conservative correct* choice (vc46 worked without it),
     but it was NOT the root cause of the download failure. Comment in code corrected
     to not overclaim "device-verified."
5. **"This Inevitable Ruin worked because it was recently *opened* → it has a per-file
   grant that Jurassic Park lacks."**
   - Tested head-to-head with one token: `files.get` on both → **both 404**. The
     `viewedByMeTime` difference (2026-07-23 vs 2025-02-15) was irrelevant.
   - **Why wrong:** same `.test` client, neither had a grant.
6. **"Picking the *direct parent* book folder (not an ancestor) will grant the
   files."**
   - Tested: picked "This Inevitable Ruin" itself → still `driveReturned=0`, files
     still 404. **Why wrong:** still the `.test` client.
7. **"The `.test` package is missing its OAuth client — create one."**
   - Console audit: client #1 for `com.powermediaplayer.test` + debug SHA-1 **already
     exists**. Nothing to create.
8. **"Client #3 (`com.powermediaplayer` + debug SHA-1) is stale/misconfigured — delete
   it to fix the SHA-1 collision."**
   - **Nearly a disaster.** Claude-for-Chrome's pre-delete check surfaced **"used 2138
     times in 30 days."** Client #3 is the *live* client for base-package debug/
     sideloaded installs (the friend's working app). **Deleting it would break real
     users.** Cancelled. **DO NOT DELETE client #3.**

Meta-lesson: the user's direct evidence (empty folders on first open; the Fold 7;
"my friend downloads fine"; the 2138-uses warning) corrected the course every time,
against confidently-wrong technical conclusions drawn from a flawed test harness.
Trust real-world evidence over a `.test`-build measurement.

---

## 5. The cold-start scaling bug (fixed)

- **Symptom:** the FIRST open of the embedded picker in a fresh process rendered
  edge-to-edge *under* the status/navigation bars and zoomed-out (5 tiny columns);
  backing out and reopening rendered correctly (centred dialog, 3 columns).
- **Cause:** the WebView's system-bar inset padding
  (`ViewCompat.setOnApplyWindowInsetsListener` in `DrivePickerActivity`) is applied
  *after* the picker's first `pmpRebuildPicker()` runs, so the picker sized itself
  from the pre-inset full-bleed `window.innerHeight`. The existing resize handler
  rebuilds on **width** changes only (deliberately, to avoid the soft keyboard's
  height churn disposing the focused search box), so the inset-driven **height**
  change was never caught → stayed wrong until a later width/reopen event.
- **Fix (committed `1c1b581`):** a one-time settle watcher in the `gapi.load`
  callback rebuilds the picker once when `innerHeight` first changes, running only in
  the first ~2.5 s (before any input is focused, so it can't fight the keyboard) and
  no-oping on a warm reopen. **Device-verified**: first cold open now a correct dialog.

---

## 6. The "second sign-in" (inherent, one-time)

- After the native Google account chooser, the embedded picker shows a *second*
  Google login *inside the WebView* ("You must sign in to access this content").
- **Cause:** the Google Picker's web frame (`docs.google.com/picker`) requires a
  Google **web-session cookie** in the app's isolated WebView. The OAuth token
  authorises the API but not the web frame; the app's WebView starts with no Google
  web login → the frame demands one.
- **Behaviour:** it's **one-time per WebView cookie lifetime.** Complete it once and
  the cookie persists; later folder-adds skip straight to the picker (observed).
- **Cannot be removed** for the embedded picker — it's Google's requirement. The only
  way to avoid it entirely is the system (SAF) picker, which the user moved away from.
- Not to be confused with the earlier one-off `401` on the *brand-new Oppo*: that was
  the same cookie mechanism the very first time, plus modern WebView (Chromium 150)
  third-party-cookie strictness. Completing the in-WebView login resolves it.

---

## 7. Testing gotchas (important for future device work)

- **You cannot test the Drive picker's file access on the `.test` debug build.** It
  authenticates as client #1 (`com.powermediaplayer.test`), which holds no grants and
  collides on the debug SHA-1 with the live client #3 → guaranteed 404 on files.
  The picker *renders* (so scaling/layout IS testable on `.test`), but any download/
  list of real files will 404. Don't read that as a regression.
- **To get a real on-device Drive test without touching the Play app:** give the
  `.test` build its own dedicated signing key (unique SHA-1) and register a matching
  Android OAuth client for `com.powermediaplayer.test` + that new SHA-1. Then its
  picks land clean grants (no #3 collision) and it works.
- **Alternative (disruptive):** build a base-package (`com.powermediaplayer`) debug
  build — it uses the working client #3 — but installing it requires uninstalling the
  Play app (signature clash), losing Play app data. User asked NOT to touch the Play
  app, so avoid this.
- Emulator note: `PMP_Fold` AVD (`android-36/google_apis_playstore`) works, WebView
  Chromium 133; set `hw.keyboard = yes` in its `config.ini` or the PC keyboard can't
  type the Google sign-in. The emulator also 401'd on the picker first time (same
  cookie mechanism) and needs the user's Google password to add an account.

---

## 8. Other findings from the same sessions (non-Drive)

- **Restore last played after a reclaimed process (commit `c47a6e4`):** restore was
  armed only in `onCreate` with `savedInstanceState == null`. Swipe-away (task
  dropped) → cold start → restore fires. But when Android *reclaims the backgrounded
  process*, the relaunch carries `savedInstanceState != null` and the old guard
  skipped it → "Nothing's playing yet". Also `PlaybackConnection.onDisconnected`
  dropped the controller but nothing re-called `connect()`. Fix: `onStart` calls
  `connect()` (idempotent) and a foreground re-arm runs on return-to-foreground /
  recreate-with-saved-state, only when the player is genuinely empty (rotation / PiP
  / mid-listen all no-op). Swipe-away teardown (`onTaskRemoved`) unchanged and
  re-verified. Device-verified.
- **Podcast search-as-you-type (commit `49928b4`):** the podcast field only searched
  on the Add/search button; wired to debounced (350 ms, ≥3 chars) as-you-type,
  skipping URLs, not polluting recent searches; added spinner + clear (x). Library/
  Cloud already did live filtering.
- **Drive reconciliation (commits `e135cc2`, `0427d3b`):** after the vc46 revert,
  restored: download-progress total from `pfd.statSize` when the listing omits size;
  SAF `EXTRA_LOADING` wait-for-load; octet-stream media kept via
  `MediaClassifier.isMediaByName` (`.m4b` labelled `application/octet-stream`); added
  `nextPageToken` paging (Drive caps a page at 1000). The `corpora=allDrives` added
  here was later removed (see §4.4).

---

## 9. Key ids / reproduction / evidence

- **Files probed** (owner `bhasin.kabir@gmail.com`, all real, all 404 on `.test`):
  - Jurassic Park folder `1fengAYxuOl57YGJxszq3rJBBvq01AG9s`;
    `.m4b` `1q79EHz7EdK4_-Elz4hwbP-Cmhkg98_3I`.
  - This Inevitable Ruin folder `1GugflEIzQydW8uFEQjJC6k_mLovpbCmr`;
    `.m4b` `1rMd-x5R-UNBko333TRnahSHWEQcBbPE_`.
- **How to see a file's real owner** (bypasses the app entirely): the claude.ai
  Google Drive connector — `search_files` with `parentId = '<folderId>'` returns
  children + `owner`. This is how ownership was settled.
- **`drive.file` access probe** (temporary code, since removed): inside
  `DriveOAuthProvider.listFiles`, `files.get(fileId)` with the app token → `HTTP 404`
  proved the `.test` client has no grant; the same on the real client returns 200.
  Reproduce by temporarily re-adding a `files.get` diag + reading `PMP_DIAG_FILE`
  (debug always mirrors DiagLog to logcat).
- **Devices:** Oppo Find X9 Ultra `CPH2841` (Android 16, WebView 150), adb serial
  `3B166N000CZ00000`; Fold 7 `SM-F966B` (Android 16, One UI 8.5.0, WebView ~149).
- **Relevant commits:** `e135cc2`, `0427d3b` (reconcile), `c47a6e4` (resume),
  `49928b4` (podcast search), `d45b482` (vc54), `4f477f8` (embedded picker path),
  `1c1b581` (scaling fix), `88dc07e` (vc55/1.5.0 release).

---

## 10. Open / unverified items

- **The download itself is NOT verified by us on a Play-signed build** — only by the
  friend's real-world use and client #3's live traffic. Before a wide Play rollout,
  ship vc55 to an **internal/closed track** and confirm a download on the user's own
  device from the Play-signed build (client #2), which has not itself been exercised
  with the embedded picker (the live Play app used SAF).
- If `.test` on-device Drive testing is wanted, do the dedicated-`.test`-client setup
  (§7).

---

## 11. One-line rules to carry forward

- **Do NOT delete OAuth client #3** (`184142114356-hgjp9crjfabivu0e8t99abub2tilm2f7`);
  it is live (2138 calls/30 days).
- **Do NOT judge Drive file access on the `.test` build** — wrong OAuth client.
- `drive.file` folder-pick **does** grant the folder's existing files (for a client
  that made the pick).
- Consent screen is **in production**; keep the **logo blank** to stay
  verification-free; the 100-user cap is inert for non-sensitive scopes.
- The embedded picker's **second sign-in is one-time and inherent**; its **cold-start
  scaling is fixed** as of vc55.
