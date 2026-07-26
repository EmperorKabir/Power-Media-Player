# Google Drive Picker + OAuth saga — full findings (2026-07-22 → 2026-07-25)

**Purpose:** a self-contained, richly detailed record of the multi-day Google Drive
picker / OAuth investigation, so that after any context loss the whole thing can be
understood without re-deriving it. Reading priority: **§0-NEW (2026-07-25 resolved
truth) FIRST** — it supersedes the old §0 TL;DR and §3. The **"Wrong theories"**
section (§4) documents every dead end and *why* it was wrong (the user explicitly
asked for this); note §4 itself has been corrected on 2026-07-25 where the earlier
"why wrong" was itself wrong.

Current state: **RESOLVED 2026-07-25.** Root cause is a Google-side restriction of
`drive.file` folder-grants; fix shipped is a switch to **`drive.readonly`** (commit
`c047004`), emulator-verified end to end. The **2026-07-24 conclusion below (§0-OLD,
§3) was WRONG** — it blamed the `.test` OAuth client; this session proved the
base-package client #3 (the friend's exact client) *also* 404s on fresh picks.

---

## 0-NEW. RESOLVED TRUTH (2026-07-25) — supersedes §0-OLD and §3

- **Root cause (device-verified):** Google RESTRICTED the `drive.file` scope's
  folder-selection. Picking a FOLDER now grants access to the **folder resource only,
  NOT its pre-existing files.** A decisive probe (real file IDs, the user's account)
  on **both** the Oppo (WebView 150) **and** a fresh emulator (WebView 133), under
  **both** OAuth clients (.test #1 AND base-package #3 = the friend's exact client):
  - `files.get` on the picked FOLDER → **HTTP 200** (folder reachable)
  - `files.get` on a real child `.m4b` → **HTTP 404**
  - `files.list('<folderId>' in parents)` → **0 of 5** real children (raw
    `driveReturned=0`)
  - `+ supportsAllDrives/includeItemsFromAllDrives/corpora=allDrives` → still
    `{"files":[]}` (it is My Drive — chain owned by the user, no `driveId`)
  - re-check **25 min** after the pick → still 404 (not slow grant propagation)
- **`FOLDER.get 200` is the key fact** — it rules out phone, WebView version, OAuth
  client, `appId`, signing, and the "WebView web-session/cookie" theory in one shot.
  The grant works; Google just no longer includes the folder's existing files.
- **This DEMOLISHES the old §0/§3 conclusion.** The old doc said the 404s were a
  `.test`-client artifact and that client #3 "holds the grants and works". Reality:
  client #3 (base-package, the friend's client) **also 404s on a FRESH pick**. What
  actually works for the friend (and the old Fold) is **grandfathered older grants** —
  `drive.file` grants persist per file, and folders picked *before* the restriction
  keep the broader access. Fresh picks after it get folder-only.
- **vc46 (2026-07-03) used the IDENTICAL path** (same `drive.file` scope, same
  `DrivePickerActivity` WebView Picker, same `files/{id}?alt=media` download —
  git-proven byte-identical picker config) and "worked" only via those grandfathered
  grants. Same code returns 404 on any fresh pick now. Google's exact change date
  could NOT be pinned from public docs; the mechanism is proven by identical-code-
  worked-then-404s + grandfathering, not a changelog line.
- **FIX (shipped, commit `c047004`):** request **`drive.readonly`** (reads the user's
  Drive unconditionally, independent of per-folder grants) so a freshly-picked
  folder's files list/download at once. **Keep `drive.file` alongside** for the backup
  UPLOAD path (readonly cannot write). Added `DriveOAuthProvider.needsReadConsent()`
  so returning users (old drive.file-only grant) re-consent once. Constants
  `SCOPE_DRIVE_READONLY`/`SCOPE_DRIVE_FILE` in `DriveOAuthProvider.kt`.
  **Emulator-verified:** after granting readonly, `listFiles driveReturned=5` (was 0),
  the `.m4b` shows with a Download button, download runs (~7 MB/s).
- **Cost of the fix:** `drive.readonly` is a **sensitive** scope → Google OAuth
  verification is needed to clear the "unverified app" screen + lift the 100-user cap
  for PUBLIC release. For the owner + friend it works now by tapping through the
  unverified screen once. Add `drive.readonly` to the OAuth consent screen scopes
  (console step; via Claude-for-Chrome or the user). This REVERSES the old §2 note
  that the app could stay verification-free — that was true only for `drive.file`.
- **The 2nd sign-in (§6) and cold-start scaling (§5) are SEPARATE** fresh-install
  WebView-Picker artifacts, not the files cause; they merely surfaced together because
  a fresh install triggers all three.
- **WebView Picker REMOVED 2026-07-25 (vc56/1.5.1).** Because readonly makes the WebView
  Picker unnecessary for ACCESS (only for folder CHOICE), it was replaced by a NATIVE
  in-app folder browser (`DriveFolderBrowser` in `CloudBrowserScreen.kt` +
  `DriveOAuthProvider.listSubFolders`). No WebView ⇒ the 2nd sign-in and scaling are now
  structurally impossible. `DrivePickerActivity.kt` + `assets/drive_picker.html` + both
  manifest entries deleted (archived at `archive/webview-drive-picker-REMOVED-2026-07-25.md`,
  restorable from git blob `2a6053a`). Emulator-verified: navigate → "Add this folder" →
  `driveReturned=5`, `.m4b` lists + downloads; no WebView/2nd-signin/scaling.

---

## 0-OLD. TL;DR — **SUPERSEDED / WRONG (believed 2026-07-24, disproved 2026-07-25 — see §0-NEW)**

> The bullets below were the 2026-07-24 conclusion. They are preserved as part of the
> "what was believed and why it was wrong" record the user asked for. **Do not act on
> them** — the base-package client #3 was later proven to 404 on fresh picks too, so
> "it's a `.test`-client artifact" is false; the true cause is the `drive.file`
> folder-grant restriction (§0-NEW).

- The app has **two independent Drive access paths**. Only one was ever the point of
  confusion:
  1. **System / SAF picker** (`GoogleDriveProvider`): `ACTION_OPEN_DOCUMENT_TREE` →
     `content://` tree URI → streamed via `ContentResolver` = the Google Drive app's
     **full** access. Sees everything. Looks like the system Files/Drive UI.
  2. **Embedded picker** (`DriveOAuthProvider` + `DrivePickerActivity`): Google JS
     Picker in a WebView, `drive.file` OAuth scope → stores
     `…/files/{id}?alt=media` → streamed via the Drive REST API. The "in-app,
     double sign-in" picker the user likes.
- ~~**The embedded `drive.file` picker DOES work** and DOES grant access to a picked
  folder's existing files.~~ **WRONG** — it grants the folder only; fresh picks 404 on
  the files (§0-NEW). The friend/Fold worked via grandfathered grants, not because the
  client is special.
- ~~**Every "it can't work / 404" conclusion was a `.test` debug build artifact.**~~
  **WRONG** — client #3 (base-package, the friend's exact client) also 404s on fresh
  picks. The 404 is `drive.file` behaviour, not a client identity problem.
- ~~No recent Google change caused any of this.~~ **WRONG in spirit** — the cause IS a
  Google-side `drive.file` folder-grant restriction (date unpinned). No Google *Cloud
  console* change was needed for the diagnosis, and **client #3 must still NOT be
  deleted** (it is live traffic) — that part stands.

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

## 3. The "correct model" — **SUPERSEDED / WRONG (see §0-NEW)**

> Preserved as the 2026-07-24 belief. **Points 1–3 and 5 are FALSE** (2026-07-25
> proof in §0-NEW): `drive.file` folder-pick grants the folder only, not its existing
> files; the 404 is NOT a per-client artifact (client #3 also 404s on fresh picks);
> and a Google-side `drive.file` folder-grant restriction IS responsible. Point 4
> (SAF also works) stands. Corrected model is §0-NEW.

1. ~~`drive.file` + Picker folder-pick grants access to the folder's existing files.~~
   **FALSE.** Fresh picks: `FOLDER.get 200`, child `files.get 404`, list `0-of-5`.
2. ~~Grants are per OAuth client_id… the `.test` client is why it 404s.~~ **FALSE** as
   the explanation — client #3 (not `.test`) 404s on fresh picks too. Grants ARE
   per-client and persistent, which is why *grandfathered old* picks work; but a fresh
   pick on ANY client gets folder-only.
3. ~~Never conclude a capability is impossible from the `.test` build.~~ Good general
   caution, but here the base-package build reproduced the same 404 — so it was NOT a
   `.test` artifact.
4. **The system (SAF) picker also works** and needs no OAuth/verification, but looks
   like the system UI (the "opens the Google Drive app" the user disliked) and adds a
   "USE THIS FOLDER" tap. *(Still true.)*
5. ~~No recent Google change is responsible.~~ **FALSE** — a `drive.file` folder-grant
   restriction is the cause (date unpinned).

---

## 4. Wrong theories and WHY each was wrong (the dead ends)

Documented deliberately — these were each reached with apparent confidence. **NOTE
(2026-07-25): the §4 framing itself was wrong.** The recurring error was NOT "testing
through the `.test` build" — it was the OPPOSITE over-correction: after the `.test`
theory, the investigation wrongly concluded the 404 was *only* a client artifact and
that `drive.file` folder-pick works. It doesn't. Theories #1 and #2 below, marked
"wrong" on 2026-07-24, were **actually RIGHT** (with refinements). Verdicts corrected
inline.

1. **"`drive.file` fundamentally cannot list/read a picked folder's existing files
   (files.get → 404, files.list → [])."** — **VERDICT CORRECTED 2026-07-25: this was
   RIGHT.**
   - Evidence at the time: `files.get` on a user-owned `.m4b` → `HTTP 404`; every list
     form → `"files": []`; Google's scope doc says per-file access; a dev forum thread
     said drive.file needs `drive.readonly` for folders. **All correct.**
   - The 2026-07-24 rebuttal ("just the `.test` client with no grants") was itself
     wrong: the base-package client #3 (the friend's exact client) *also* 404s on a
     FRESH pick, and `FOLDER.get 200` proves the grant registered. `drive.file`
     folder-pick grants the folder resource only. The friend downloads fine via
     **grandfathered older grants**, not because his client can read fresh picks.
2. **"A recent Google change broke it."** — **VERDICT CORRECTED 2026-07-25: RIGHT in
   substance.**
   - The specific mechanism is a restriction of `drive.file` **folder-selection**
     (picked folder no longer grants descendants). The Feb-2025 "limited/expansive
     access" blog is indeed about human sharing (that part of the old note was fine),
     so the exact Google announcement/date for the `drive.file` folder change could
     NOT be pinned from public docs — but the behaviour change is proven by identical
     vc46 code working then and 404ing now on fresh picks (§0-NEW). Do NOT restate "no
     Google change" as fact.
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
     `viewedByMeTime` difference was irrelevant. *(Result correct.)*
   - **Corrected reason (2026-07-25):** not "same `.test` client" — folder-pick grants
     neither file (folder-only), on ANY client. Confirmed on client #3.
6. **"Picking the *direct parent* book folder (not an ancestor) will grant the
   files."**
   - Tested: picked "This Inevitable Ruin" itself → still `driveReturned=0`, files
     still 404. *(Result correct.)*
   - **Corrected reason (2026-07-25):** direct-parent vs ancestor is irrelevant —
     `drive.file` folder-pick never grants a folder's pre-existing descendants.
7. **"The `.test` package is missing its OAuth client — create one."**
   - Console audit: client #1 for `com.powermediaplayer.test` + debug SHA-1 **already
     exists**. Nothing to create.
8. **"Client #3 (`com.powermediaplayer` + debug SHA-1) is stale/misconfigured — delete
   it to fix the SHA-1 collision."**
   - **Nearly a disaster.** Claude-for-Chrome's pre-delete check surfaced **"used 2138
     times in 30 days."** Client #3 is the *live* client for base-package debug/
     sideloaded installs (the friend's working app). **Deleting it would break real
     users.** Cancelled. **DO NOT DELETE client #3.**

Meta-lesson (corrected 2026-07-25): the danger cut **both** ways. First the `.test`
404 was dismissed as "the app can't do it"; then it over-corrected to "the 404 is only
a `.test`-client artifact, so the picker works" — which the friend's real downloads
seemed to confirm but actually MISLED, because his access is **grandfathered** (old
grants), not proof fresh picks work. The reliable test is the one finally run: probe
`files.get` on the FOLDER vs a CHILD, on the **same client, freshly**, and distinguish
fresh grants from grandfathered ones. "It works for X" is not proof a fresh install
will — ask *when* X's access was granted.

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

## 10. Open / unverified items (updated 2026-07-25)

- **DONE:** fresh-pick download now verified on the emulator with the `drive.readonly`
  build (base package, client #3): `driveReturned=5`, `.m4b` downloads (~7 MB/s).
- **Google verification for `drive.readonly` (sensitive scope):** to remove the
  "unverified app" screen and 100-user cap for public release, add `drive.readonly` to
  the OAuth consent-screen scopes and submit for verification (console step). Owner +
  test users work now by tapping through the unverified screen.
- **Native folder list — DONE (vc56/1.5.1, 2026-07-25):** WebView Picker replaced by
  `DriveFolderBrowser`; 2nd sign-in + scaling structurally gone. Emulator-verified.
- **`.aax` handling:** `MediaClassifier` keeps `.m4b` and drops `.aax` (Audible DRM).
  Books that exist ONLY as `.aax` won't list — revisit if it affects the library.

---

## 11. One-line rules to carry forward (updated 2026-07-25)

- **Scope is now `drive.readonly` (read) + `drive.file` (backup write).** `drive.file`
  folder-pick grants the folder ONLY, not its existing files — do NOT rely on it for
  library access. readonly is the access path (commit `c047004`).
- **Do NOT delete OAuth client #3** (`184142114356-hgjp9crjfabivu0e8t99abub2tilm2f7`);
  it is live (2138 calls/30 days). *(Still holds — but note client #3 also 404s on
  FRESH drive.file folder-picks; its live traffic is grandfathered/auth calls.)*
- **`drive.readonly` is SENSITIVE** → needs Google verification for public (unverified
  screen + 100-user cap until approved). Reverses the old "keep the logo blank to stay
  verification-free" rule, which applied only to the all-`drive.file` config.
- When testing Drive access, probe FOLDER.get vs CHILD.get on the **same client,
  freshly**; do NOT infer "it works" from a friend/old install (grandfathered grants).
- The embedded picker's **second sign-in** and **cold-start scaling** are separate
  fresh-install WebView-Picker artifacts (scaling patched `1c1b581`), not the files
  cause; both may vanish once the WebView Picker is dropped in favour of a native list.

---

## 12. Continuation — Drive/OAuth evolution after the readonly fix (vc57 → vc63, 2026-07-26)

The saga above ends at vc56 (native browser introduced). The Drive sign-on kept
evolving; recorded here so the history is complete.

- **vc57/1.5.2 (`a86c52a`) — audit of the readonly + native-browser work.** Code-review
  hardening of the migration; no scope change.

- **vc62/1.5.7 (`84eb33d`) — sparse-metadata + BACKUP-TOKEN audit (skill: android-efficiency-audit).**
  Drive-sign-on-relevant finding = the **backup token split was inconsistent**: after the
  readonly migration, `findNewestFileByName` (restore FIND) had silently flipped to the
  readonly token (Drive-wide) while `downloadTextFile`/`upload`/`update` used `drive.file`.
  A same-named file elsewhere in the user's Drive could false-match, then 404 on the
  drive.file download. **Fix: the WHOLE backup triad now uses `fetchWriteTokenBlocking`
  (`drive.file`)** — find + restore-read + upload + update share ONE app-created corpus, so
  no Drive-wide false-match and backup/restore no longer depends on the sensitive readonly
  grant. **Rule: reads = `drive.readonly` (`fetchAccessTokenBlocking`); the backup triad =
  `drive.file` (`fetchWriteTokenBlocking`). Do not cross them.**

- **vc63/1.5.8 (`89b5994` + audit `fd6e075`) — Shared Drives + Shared-with-me + readonly re-consent.**
  - **U7 (non-negotiable):** the native browser was My-Drive-only (a parity regression vs
    the removed WebView Picker). Now `listSubFolders` opens on a **location chooser** — My
    Drive, **Shared with me** (`q: sharedWithMe = true and mimeType = folder`), and each
    **Shared Drive** (`GET /drive/v3/drives` = `drives.list`). Shared items resolve via
    **`supportsAllDrives=true&includeItemsFromAllDrives=true`** on the folder/file queries
    (`listSubFolders`/`listFiles`/`searchOneFolder`) and **`supportsAllDrives=true`** on the
    by-id GETs (`getFileMetadata`/`fetchFileName`/download URL). **Deliberately NOT
    `corpora=allDrives`** — that broadens a `'<id>' in parents` query and was removed on
    2026-07-24; the two flags above do NOT change a My-Drive parent's children (device-verified
    unregressed). Context7-verified against the Drive v3 reference.
  - **U8:** the Drive browse entry points (`onSelectDrive`/`onBrowseDrive`) now gate on
    `driveReadyForBrowse()` and fall back to `launchDriveOAuth()` (incremental re-consent) when
    a returning `drive.file`-only user lacks the readonly grant — instead of browsing blind and
    failing on a null token. `signInOptions` requests readonly at sign-in.
  - **Emulator-verified (real account):** chooser shows My Drive + Shared with me; My Drive
    lists real folders (no regression); Shared with me lists real shared folders; adding a
    Shared-with-me folder → `listFiles driveReturned=60`; playing a shared file → PLAYING with
    metadata. **Shared DRIVE (team drive) happy path is UNVERIFIED** — the test account belongs
    to no shared drive (`drives.list` returned empty, graceful); needs a team-drive account.

- **Carry-forward rules (superset the §11 ones):** do NOT re-introduce the WebView Picker; do
  NOT narrow media reads back to `drive.file`; keep the read/backup token split; keep
  `supportsAllDrives`+`includeItemsFromAllDrives` (never `corpora=allDrives`) for shared content.
