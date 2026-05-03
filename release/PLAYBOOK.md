# Power Media Player — Release Playbook

End-to-end guide for shipping to Play Store Internal Testing + completing Spotify Extension Request + Google OAuth verification. Designed to be operated partly by you and partly by Claude for Chrome.

---

## What's already done (by Claude in VS Code)

- ✅ Privacy policy live at https://emperorkabir.github.io/Power-Media-Player/privacy.html
- ✅ Release signing config added to `app/build.gradle.kts` (reads from gitignored `local.properties`)
- ✅ `local.properties.example` template added — shows what fields you need
- ✅ Master playbook (this file) written
- ✅ Claude-for-Chrome prompt drafted at `release/CLAUDE-FOR-CHROME-PROMPT.md`

## What you need to gather (before invoking Chrome)

| Item | How | Time |
|---|---|---|
| Release keystore (`release.jks`) | `keytool` command below | 2 min |
| 3–5 phone screenshots of the app | Power+Volume-down on phone, transfer to PC | 10 min |
| Feature graphic 1024×500 PNG | Canva free template (search "feature graphic") | 15 min |
| Demo video for OAuth verification (~3 min) | Phone screen recorder — see script in §7 | 15 min |
| Demo video for Spotify Extension Request (~3 min) | Phone screen recorder — see script in §8 | 15 min |

Total: ~1 hour of manual prep work. Everything else is filling forms, which the Chrome prompt automates.

---

## §1 — Generate the release keystore (one-time, ~2 min)

### Step 1a. Open PowerShell in the project folder

```powershell
cd "C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player"
```

### Step 1b. Run the keystore generator

```powershell
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias release
```

Prompts you'll see:
- **Enter keystore password**: invent one — write it down. You can use the same one for both passwords.
- **Re-enter new password**: same password.
- **What is your first and last name?**: `Kabir Bhasin`
- **What is the name of your organizational unit?**: leave blank, press Enter
- **What is the name of your organization?**: leave blank, press Enter
- **What is the name of your City or Locality?**: your city
- **What is the name of your State or Province?**: leave blank, press Enter
- **What is the two-letter country code for this unit?**: `GB` (or your country)
- **Is CN=Kabir Bhasin... correct?**: `yes`
- **Enter key password for <release>**: press Enter to use the same as the keystore password (recommended).

A file `release.jks` is now in the project root.

### Step 1c. Add the keystore details to `local.properties`

Open `local.properties` in VS Code. Add (or update) these lines:

```properties
RELEASE_STORE_FILE=release.jks
RELEASE_STORE_PASSWORD=<your password>
RELEASE_KEY_ALIAS=release
RELEASE_KEY_PASSWORD=<same password if you pressed Enter at the last prompt>
```

### Step 1d. Verify `release.jks` is gitignored

Open `.gitignore`. If the line `release.jks` is not there, add it. Then save and commit `.gitignore`.

(The current `.gitignore` already excludes `local.properties`, but `release.jks` should also be excluded.)

### Step 1e. **CRITICAL — back up the keystore**

Losing this file means you can never update the app on Play Store again. Back it up in TWO places:

1. Copy `release.jks` to a USB stick / external drive and physically store it somewhere safe.
2. Copy `release.jks` to a personal cloud account (your own Google Drive, Dropbox, etc.) — outside the project folder.
3. Save the keystore password in your password manager OR a synced note.

---

## §2 — Build the signed AAB

After keystore + `local.properties` is set up:

```powershell
.\gradlew :app:bundleRelease
```

Output appears at: `app\build\outputs\bundle\release\app-release.aab`

That's the file you upload to Play Store. Build takes 2–5 min. To rebuild for future updates, bump `versionCode` in `app/build.gradle.kts` (line 29) and run the same command.

---

## §3 — Play Store listing copy (paste-ready)

### App name
```
Power Media Player
```

### Short description (max 80 chars)
```
Offline-first audio and video player with cloud streaming and equaliser.
```

### Full description (max 4000 chars)
```
Power Media Player is a privacy-respecting, offline-first audio and video player for Android.

▶ FEATURES

• Plays virtually any audio or video format on your device — MP3, FLAC, AAC, OGG, WAV, M4A, M4B audiobooks, MP4, MKV, WEBM and more
• 10-band equaliser with custom presets, bass boost, and loudness normalisation
• Picture-in-picture (PiP) video playback
• A-B loop for repeating sections of audio or video
• Frame-by-frame video stepping
• Sleep timer and "stop at end of chapter" for audiobooks
• Bookmark any position in any track
• Persistent mini-player bar that follows you across the app
• Last-played list with pinned favourites
• Background playback with full lock-screen and notification controls
• Bluetooth A2DP / AVRCP support — connect to any audio device
• Chromecast streaming to TVs and speakers
• Per-track speed control, pitch preservation, and seek precision

☁ CLOUD INTEGRATIONS (optional)

• Google Drive — stream your audio and video library directly from Drive without downloading
• Spotify (Premium required for full playback) — browse your library, play tracks via Spotify Connect, and view synced lyrics

🔒 PRIVACY

• No analytics, no tracking, no advertising
• No account or sign-in required for core features
• Cloud sign-in (Drive, Spotify) is optional and uses official OAuth — your credentials never touch the developer
• All listening history, equaliser settings, bookmarks and preferences stay on your device
• Open source: github.com/EmperorKabir/Power-Media-Player

🎬 BUILT FOR POWER USERS

Designed for people who want a serious media player — large libraries, audiobooks, podcasts, music collections, films — without ads, telemetry, or social features getting in the way. The interface stays out of your way; the controls do what they say.

📩 SUPPORT

Issues and feature requests: github.com/EmperorKabir/Power-Media-Player/issues
Email: bhasin.kabir@gmail.com
```

### Release notes (first release)
```
Initial release. Local audio/video playback, equaliser, A-B loop, bookmarks, sleep timer, Bluetooth, Chromecast, and optional Google Drive / Spotify cloud integration.
```

### App category
```
Music & Audio
```

### Tags (pick from Play Console list)
```
Music & Audio Player, Audiobooks, Podcasts, Video Player, Streaming
```

### Contact details
- Email: `bhasin.kabir@gmail.com`
- Website: `https://emperorkabir.github.io/Power-Media-Player/`
- Privacy policy: `https://emperorkabir.github.io/Power-Media-Player/privacy.html`

---

## §4 — Data Safety questionnaire answers (paste-ready)

Play Console → App content → Data safety. Answer every question with these:

### Section 1: Data collection and security

**Does your app collect or share any of the required user data types?**
→ **No**

(The app does not transmit any user data to the developer or any third party. OAuth tokens for Drive and Spotify are stored locally on the user's device only. Drive files and Spotify data flow directly between the user's device and those services — they do not pass through any server operated by the developer.)

**Is all of the user data collected by your app encrypted in transit?**
→ N/A (since you answered "No" above)

**Do you provide a way for users to request that their data be deleted?**
→ N/A

### Section 2: Data types

Skip the entire matrix — Play Console will not require it because you said no data is collected.

### Section 3: Third-party libraries

If asked specifically about libraries:
- **Google Sign-In + Drive API** — used only when user opts in to cloud features. Data flows directly between user's device and Google. No data shared with developer.
- **Spotify Web API (via OAuth 2.0 PKCE)** — used only when user opts in. Data flows directly between user's device and Spotify. No data shared with developer.
- **Google Cast SDK** — local network only, used for Chromecast. No data leaves device.
- **LRCLib** (lyrics provider) — read-only HTTP requests with track title/artist/album. Anonymous, no user identifier sent.
- **ML Kit** — text recognition runs entirely on-device (subtitle OCR). No data sent.
- **Coil image loader** — loads album art images only.

---

## §5 — Content rating questionnaire answers

Play Console → App content → Content rating. Almost all No.

| Question | Answer |
|---|---|
| Category | Reference, News, or Educational → **No**. Music or media app → **Yes** |
| Violence | **No** |
| Sexual content | **No** |
| Gambling | **No** |
| Drugs, alcohol, tobacco | **No** |
| Hateful or discriminatory content | **No** |
| Coarse language | **No** |
| Crude humour | **No** |
| Horror or fear-themed content | **No** |
| User-generated content | **No** |
| Social features (chat, sharing) | **No** |
| Location sharing | **No** |
| Digital purchases | **No** |
| Personal info sharing | **No** |

Result: **PEGI 3 / Everyone / IARC Generic All Ages** rating.

---

## §6 — Other Play Console form answers

| Form | Answer |
|---|---|
| App access | **All functionality available without restrictions** (cloud sign-in is optional, doesn't gate any feature) |
| Ads | **No, my app does not contain ads** |
| Target audience and content | Age groups: **18 and over**. Appeals to children: **No** |
| News app | **No** |
| Health app / COVID-19 contact tracing | **No** |
| Government app | **No** |
| Financial features | **No** |
| Use of advertising ID | **No** |
| Data Safety: data deletion | **N/A** (no data collected) |
| Government IDs | **No** |
| Use of permissions list (the new "permissions declaration") | If asked about FOREGROUND_SERVICE_MEDIA_PLAYBACK: declare as media playback foreground service — required for background music/video playback while the app is not in the foreground. |

---

## §7 — Google OAuth verification submission

**When**: only required if you want >100 users to be able to sign in with Google Drive (Internal Testing for friends does not require this — test users are sufficient up to 100).

### 7a. Where to submit
- Google Cloud Console → **Audience** → status currently "Testing" → click **Publish app** → Google will prompt for verification.
- Or: https://console.cloud.google.com/auth/audience

### 7b. Scope justification text (paste verbatim)
```
Application: Power Media Player
Scope requested: https://www.googleapis.com/auth/drive.readonly

Reason for requesting this scope:

Power Media Player is an offline-first audio and video player that gives users the option to stream their personal audio and video library directly from Google Drive. To deliver this feature, the application needs to:

1. List folders and files within the user's Drive so the user can navigate to and select audio/video content.
2. Read file metadata (name, MIME type, size, parent folder) to display a navigable browser UI.
3. Stream the file's bytes via the Drive REST v3 ?alt=media endpoint with HTTP Range requests, allowing seekable playback of large media files without requiring a full download.

The application uses drive.readonly (rather than the broader drive scope) because it never modifies, creates, or deletes any files. It is purely a read-only consumer of the user's existing media library.

The application:
• Does not transmit Drive content or metadata to any server operated by the developer.
• Does not store Drive file content beyond a transient cache used during playback.
• Does not share Drive data with any third party.
• Does not use Drive data for advertising, profiling, or training machine-learning models.
• Operates entirely on the user's device. OAuth access tokens are stored only in the device's encrypted Android account/preferences storage.
• Provides a clear sign-out option that revokes the token locally; users can additionally revoke at https://myaccount.google.com/permissions.

The Drive integration is optional. The application's core media-playback features work without any Google account. The Drive sign-in flow is only initiated when the user explicitly taps "Sign in to Google Drive" within the Cloud tab of the application.

Privacy policy: https://emperorkabir.github.io/Power-Media-Player/privacy.html
Source code: https://github.com/EmperorKabir/Power-Media-Player
Application home page: https://emperorkabir.github.io/Power-Media-Player/
```

### 7c. Demo video script (record on your phone, ~3 min)

You'll need to upload a video showing the OAuth flow. Record screen on your Android device (Quick Settings → Screen recorder).

**Script:**
1. (0:00 – 0:10) Open the app. Show the home screen / Library tab. Voiceover: "This is Power Media Player. The core features work entirely offline — here's the local library."
2. (0:10 – 0:20) Tap the **Cloud** tab at the bottom.
3. (0:20 – 0:35) Tap **Sign in with Google**. Voiceover: "Cloud features are optional. I'm tapping Sign in with Google now."
4. (0:35 – 1:00) On the Google consent screen, **slowly read aloud the scopes Google requests** (e.g. "See, edit, create, and delete only the specific Google Drive files you use with this app" — the exact wording Google shows). Voiceover: "Power Media Player is requesting Drive read access to stream my media files."
5. (1:00 – 1:10) Tap **Allow**.
6. (1:10 – 1:40) Back in the app, navigate into a Drive folder. Tap an audio file. Show it streaming.
7. (1:40 – 2:00) Tap **Sign out**. Confirm.
8. (2:00 – 2:20) Open https://myaccount.google.com/permissions in the phone browser. Show that the user can revoke access from there too.
9. (2:20 – 2:30) Voiceover: "All Drive data flows directly between my device and Google. Nothing passes through any server I operate."

Upload via the Google verification form when prompted.

### 7d. CASA Tier 2 self-assessment

Google may require a **CASA Tier 2 self-assessment** because `drive.readonly` is a restricted scope. This is a free, self-administered checklist.

- Submit at https://appdefensealliance.dev/casa
- Tier 2 = self-assessment, no auditor required, free
- Form takes ~2 hours to fill correctly
- Required answers reflect the privacy posture above (no data leaves device, encrypted local storage, etc.)

Wait time after submission: **typically 4–6 weeks**, sometimes longer.

**While waiting**: friends can still use Drive sign-in by adding their Gmail addresses as **test users** at https://console.cloud.google.com/auth/audience. 100-user cap.

---

## §8 — Spotify Extension Request

**When**: required to lift the 25-user cap on Spotify sign-in.

### 8a. Where to submit
- Spotify Developer Dashboard → your app → **Extension Request**
- Or: https://developer.spotify.com/dashboard → app → tab **Extension Request**

### 8b. Application description (paste verbatim)
```
Power Media Player is an Android audio and video player that offers Spotify integration as an optional feature. With Spotify, the user can:

• Browse their saved tracks, albums, playlists, podcasts and recently-played history.
• Search the Spotify catalogue.
• Initiate playback on their existing Spotify Connect devices via the Spotify Connect API (PUT /v1/me/player/play). Spotify Premium is required for full-track playback; this is enforced by Spotify and surfaced to the user as a clear in-app message when the account is not Premium.
• View synced and plain-text lyrics for the currently-playing track. Lyrics are fetched from LRCLib (a free, anonymous, community-maintained lyrics provider). They are never transmitted to or stored on a server operated by the developer.

Power Media Player does not:
• Download, cache, or rehost any audio content from Spotify.
• Store Spotify track data on any server operated by the developer.
• Train machine-learning models on Spotify data.
• Modify Spotify-provided metadata.
• Display Spotify content alongside other licensed audio sources within the same playback unit.
• Use Spotify data for advertising or profiling.

The Spotify integration is optional. The application's core media-playback features (local files, Google Drive) work without any Spotify account. The Spotify sign-in flow is only initiated when the user explicitly taps "Sign in with Spotify" within the Cloud tab of the application.

Authentication uses OAuth 2.0 with PKCE (Authorization Code with Proof Key for Code Exchange) via the AppAuth-Android library — no client secret is required or used. Tokens are stored only on the user's device using Android's DataStore preferences.

Visual branding follows Spotify's design guidelines — the official Spotify logo and "Powered by Spotify" attribution are displayed on the cloud sign-in screen and in the now-playing UI when the source is Spotify.

Estimated user base in the first year: ~100 users (personal-use / friends + family distribution via Play Store Internal Testing track).

Privacy policy: https://emperorkabir.github.io/Power-Media-Player/privacy.html
Source code: https://github.com/EmperorKabir/Power-Media-Player
```

### 8c. Demo video script (~3 min, screen-record on your phone)

1. (0:00 – 0:10) Open the app. Voiceover: "Power Media Player is an offline-first media player. Spotify is one of several optional cloud sources."
2. (0:10 – 0:20) Tap the **Cloud** tab.
3. (0:20 – 0:30) Tap **Sign in with Spotify**.
4. (0:30 – 0:50) Spotify's OAuth consent page loads. **Read the requested permissions aloud**. Tap **Agree**.
5. (0:50 – 1:10) Back in the app, the Spotify section loads. Show "Liked Songs", "Saved Albums", "Saved Playlists" headings.
6. (1:10 – 1:40) Tap a track. Show it playing on the user's active Spotify Connect device. Voiceover: "Power Media Player uses the Spotify Connect API to control playback on the user's existing Spotify devices. Audio is streamed by Spotify itself, not by my app."
7. (1:40 – 2:00) Show the now-playing screen with synced lyrics highlighting the current line. Voiceover: "Lyrics are fetched from LRCLib — a free, anonymous, community-maintained source. Spotify lyrics are not used."
8. (2:00 – 2:20) Tap pause, skip, seek. Show transport controls.
9. (2:20 – 2:40) Tap **Sign out**. Show that revocation is also available at spotify.com/account/apps.
10. (2:40 – 3:00) Voiceover: "All Spotify data flows directly between my device and Spotify. The app shows the official Spotify logo and a 'powered by Spotify' note in the cloud picker."

### 8d. Spotify branding compliance checklist

Before submitting, the app should display:
- Spotify's official logo on any UI surface that uses Spotify data
- "Powered by Spotify" attribution near the logo
- Track titles, artist names, and album art exactly as provided by Spotify (no modification)

If your app does not yet have these UI elements, add them before submitting (Claude in VS Code can do this — ask afterwards).

### 8e. Submission

- Form takes ~30 min to fill if all the text above is ready to paste.
- Spotify reviews in **2–6 weeks**, free.
- Until approved: add friends as test users at Spotify Dashboard → app → **User Management**. 25-user cap.

---

## §9 — Play Console: full step-by-step

This is what Claude for Chrome will walk through. You can do it manually if you prefer.

### 9.1. Create the app
- https://play.google.com/console → **Create app**
- App details:
  - **App name**: Power Media Player
  - **Default language**: English (en-GB or en-US)
  - **App or game**: App
  - **Free or paid**: Free
- Tick the declarations (developer programme policies + US export laws)
- Click **Create app**

### 9.2. Set up your app — checklist
Click each item in the left sidebar under "Set up your app" and fill it. Use answers from §4, §5, §6 above.

### 9.3. Store listing
Click **Main store listing** in left sidebar. Use copy from §3.

Upload:
- **App icon**: 512×512 PNG. Use the existing launcher icon (`app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` — extract from APK or re-export from Android Studio).
- **Feature graphic**: 1024×500 PNG. Make in Canva.
- **Phone screenshots**: minimum 2, recommended 4–6. PNG/JPEG, 16:9 or 9:16. Take with phone power+volume-down.
- **Tablet screenshots**: optional, skip.

### 9.4. Internal testing release
- Left sidebar → **Testing** → **Internal testing** → **Create new release**
- **App signing**: accept Google's offer to manage your app signing key (Play App Signing). Click **Continue** when prompted.
- Upload `app-release.aab`
- **Release name**: 1 (auto-filled from versionCode)
- **Release notes**: paste from §3
- Click **Save** → **Review release** → **Start rollout to Internal testing**

### 9.5. Add testers
- Same screen → **Testers** tab → **Create email list**
- List name: "Friends and family"
- Paste tester Gmail addresses (one per line)
- Save list → tick the list → **Save changes**
- Copy the **Opt-in URL** (looks like `https://play.google.com/apps/internaltest/...`)
- Send to friends. They open the link → tap "Become a tester" → install from Play Store.

### 9.6. Wait for the policy scan
- Status will show "Available on Internal testing" within 15 min – 2 hr.
- Once green, the opt-in URL works.

---

## §10 — Future updates

Every code change → new AAB → upload:

```powershell
# 1. Bump versionCode in app/build.gradle.kts (line 29): 1 → 2 → 3 ...
# 2. Build:
.\gradlew :app:bundleRelease

# 3. Upload via Play Console → Internal testing → Create new release
```

Once you've done it once via Chrome automation, the pattern is identical for every subsequent update. Claude for Chrome can rerun just the upload step.

---

## §11 — When all three approvals land (Drive + Spotify + Play Production)

If/when you decide to go to Production (public Play Store listing instead of Internal testing only):
- Play Console → **Production** track → upload AAB → submit for full Google review (1–7 days).
- Requires content rating approval (already done in §5).
- Requires both OAuth verifications already approved (§7, §8) — otherwise users hit the "Access blocked" screen.
- For a hobby app shared with friends, Internal testing is enough indefinitely. Don't promote to Production unless you actually want public discoverability.

---

## Summary cheat sheet

| Action | Who | Time |
|---|---|---|
| Generate keystore | You, in PowerShell | 2 min |
| Back up keystore | You, USB + cloud | 5 min |
| Build AAB | `./gradlew bundleRelease` | 5 min |
| Take screenshots | You, on phone | 10 min |
| Make feature graphic | You, in Canva | 15 min |
| Record OAuth demo video | You, on phone | 15 min |
| Record Spotify demo video | You, on phone | 15 min |
| Fill Play Console forms | Claude for Chrome (with you supervising) | 30 min |
| Submit Spotify Extension Request | Claude for Chrome | 10 min |
| Submit Google OAuth verification | Claude for Chrome | 15 min |
| Wait for Spotify approval | — | 2–6 weeks |
| Wait for Google approval | — | 4–6 weeks |
| Send opt-in URL to friends | You | 1 min |
| **Total active time** | | **~2 hours** |
| **Total elapsed time to fully unlocked** | | **4–6 weeks** |

Friends can install + use Drive (with whitelisting) + use Spotify (with whitelisting) starting **today**, the moment the AAB is on Internal testing.
