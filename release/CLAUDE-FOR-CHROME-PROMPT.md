# Claude-for-Chrome Master Prompt

Copy everything between the `===BEGIN PROMPT===` and `===END PROMPT===` markers and paste it into Claude for Chrome on a fresh Chrome window.

Before you paste:
1. Make sure you are signed in to **Chrome with the Google account `bhasin.kabir@gmail.com`** (the one tied to your Google Play Console + Google Cloud project).
2. Make sure you have the following files ready on your PC, with their absolute paths handy:
   - `app-release.aab` — built via `./gradlew :app:bundleRelease`
   - `release.jks` — already created and backed up
   - 4–6 phone screenshots (PNG)
   - `feature-graphic.png` (1024×500)
   - `icon-512.png` (512×512)
   - `oauth-demo-video.mp4` (~3 min)
   - `spotify-demo-video.mp4` (~3 min)
3. Open three tabs in Chrome (Claude for Chrome will use them):
   - https://play.google.com/console
   - https://console.cloud.google.com/auth/audience
   - https://developer.spotify.com/dashboard

---

```
===BEGIN PROMPT===

# ROLE

You are Claude for Chrome, helping me ship my Android app "Power Media Player" to Google Play Store Internal Testing, submit a Spotify Extension Request, and submit a Google OAuth verification.

I am the developer. My identity is already verified with Google ($25 fee paid). I have already prepared:
- The signed AAB (app-release.aab)
- Screenshots, icons, feature graphic
- Two demo videos (one for Google OAuth, one for Spotify)
- All required text content (descriptions, justifications, etc.)

You will navigate three dashboards on my behalf, fill out forms with the exact text I provide below, and pause for me to handle any step that requires my hardware (file uploads from local disk, screenshots, recordings, 2FA codes, ID verification, signing legal agreements, or final "Submit/Publish" clicks).

# CRITICAL RULES

1. **NEVER click "Submit for review", "Publish", "Roll out", "Send for verification", "Make Live", or any other final/irreversible button without first pausing and asking me to confirm.**
2. **NEVER click "Delete", "Remove", "Reset", or any destructive button without explicit confirmation.**
3. **NEVER navigate away from a form with unsaved changes — always click Save first.**
4. If a page looks unfamiliar, the UI has changed, or you're unsure, **STOP and describe what you see**. Don't guess.
5. If you encounter a CAPTCHA, 2FA prompt, identity re-verification, payment, or any login wall, **STOP** and tell me to handle it.
6. After every form submission, **read back the field values to me before saving** so I can spot mistakes.
7. Use **only** the text I provide below. Do not paraphrase or improvise content.
8. Output progress as: `[CHECKPOINT N.M] <action>` so I can track which step you're on.

# PAUSE / RESUME PROTOCOL

When you hit a manual gate, output exactly this format and stop:

```
🛑 PAUSED at checkpoint N.M
Reason: <what I need to do>
What you should see when you've finished: <expected state>
Reply "resume N.M" when done.
```

On `resume N.M`, verify the expected state (current URL + key visible elements). If state matches, continue. If not, output:

```
⚠️ State mismatch at N.M
Expected: <expected>
Actual: <what you see>
Should I retry, skip, or wait?
```

# EXECUTION PLAN

There are three workstreams. Do them in this order. Pause between each so I can confirm I'm ready.

  Workstream A: Google Play Console — Internal Testing release
  Workstream B: Spotify Extension Request submission
  Workstream C: Google OAuth verification submission (only if I tell you to start it; this needs CASA Tier 2 work)

Start with Workstream A.

────────────────────────────────────────────────────────────────────
WORKSTREAM A — PLAY CONSOLE INTERNAL TESTING
────────────────────────────────────────────────────────────────────

[CHECKPOINT A.1] Navigate to Play Console
- Go to https://play.google.com/console
- Confirm you are signed in as bhasin.kabir@gmail.com
- If not signed in or asked to verify identity → 🛑 PAUSED at A.1, reason: sign in / verify identity, expected: see "All apps" or developer account dashboard

[CHECKPOINT A.2] Create the app
- Click "Create app" button
- Fill in:
  • App name: Power Media Player
  • Default language: English (United Kingdom) — or English (United States) if UK isn't offered
  • App or game: App
  • Free or paid: Free
- Tick BOTH declaration checkboxes (Developer Programme Policies + US export laws)
- Click Create app

[CHECKPOINT A.3] Confirm app shell created
- Should land on the "Dashboard" of the new app
- 🛑 PAUSED at A.3 if anything looks different. Otherwise continue.

[CHECKPOINT A.4] Set up your app — App access
- Left sidebar → "Set up your app" expand → click "App access"
- Select: "All functionality is available without restrictions"
- Click Save

[CHECKPOINT A.5] Set up your app — Ads
- Click "Ads"
- Select: "No, my app does not contain ads"
- Click Save

[CHECKPOINT A.6] Set up your app — Content rating
- Click "Content rating"
- Click Start questionnaire
- Email: bhasin.kabir@gmail.com
- Category: choose "Music or media app" if available, else "Reference, news, or educational" → No, then move on
- Answer ALL of these with **No** (they appear in sequence; verify each):
  • Violence
  • Sexual content / nudity
  • Profanity / crude humour
  • Drugs, alcohol, tobacco
  • Gambling
  • Hateful or discriminatory content
  • Horror / fear-themed content
  • User-generated content / chat
  • Sharing personal info
  • Sharing user location
  • Digital purchases
- Submit questionnaire
- Apply ratings
- Click Save

[CHECKPOINT A.7] Set up your app — Target audience
- Click "Target audience and content"
- Tick ONLY: "18 and over"
- "Does your app unintentionally appeal to children?": No
- Click Save / Next through all sub-pages

[CHECKPOINT A.8] Set up your app — News app
- Click "News apps" → "No, my app is not a news app" → Save

[CHECKPOINT A.9] Set up your app — Health / COVID
- Click "Health / COVID-19" (if present) → Both options No → Save
- If not present, skip.

[CHECKPOINT A.10] Set up your app — Government / Financial features
- Click "Government apps" → No → Save
- Click "Financial features" (if present) → "My app doesn't offer any of these features" → Save

[CHECKPOINT A.11] Set up your app — Data Safety
- Click "Data safety"
- Start the form

  Page 1: Data collection and security
    • "Does your app collect or share any of the required user data types?": No
    • "Is all of the user data collected by your app encrypted in transit?": Yes (use HTTPS for all OAuth + Drive + Spotify traffic)
    • "Do you provide a way for users to request that their data be deleted?": Yes — explain: "All app data is stored locally. Users can clear all data via Android Settings → Apps → Power Media Player → Clear data, or by uninstalling the app."

  Page 2: Data types — should be skipped automatically because you said "No" on Page 1.

  Page 3: Review & submit
    • Save the form

[CHECKPOINT A.12] Set up your app — Advertising ID
- Click "Advertising ID" (if present)
- Select: "No, my app does not use advertising ID"
- Save

[CHECKPOINT A.13] Set up your app — Government IDs
- Click "Government IDs" (if present) → "No, my app does not collect government IDs" → Save

[CHECKPOINT A.14] Main store listing
- Left sidebar → "Grow" expand → "Store presence" → "Main store listing"
- Fill in:
  • App name: Power Media Player
  • Short description (paste exactly): Offline-first audio and video player with cloud streaming and equaliser.
  • Full description (paste exactly):

[FULL DESCRIPTION TO PASTE — copy verbatim including line breaks and emoji]
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
[END FULL DESCRIPTION]

[CHECKPOINT A.15] Upload graphics — PAUSE for me to drag files
🛑 PAUSED at A.15
Reason: I need to drag-drop my icon, feature graphic, and screenshots onto the upload widgets:
  • App icon: drop my 512×512 PNG (icon-512.png) onto the icon widget
  • Feature graphic: drop my 1024×500 PNG (feature-graphic.png)
  • Phone screenshots: drop my 4–6 phone PNGs onto the phone screenshots widget
What you should see when I'm done: All graphic widgets show thumbnails of my uploaded images.
Reply "resume A.15" when done.

[CHECKPOINT A.16] Store listing — remaining fields
- App category: Music & Audio
- Tags: pick "Music & Audio Player", "Audiobooks", "Podcasts", "Video Player", "Streaming" if offered (3–5 tags)
- Email: bhasin.kabir@gmail.com
- Website: https://emperorkabir.github.io/Power-Media-Player/
- Phone: leave blank
- Privacy policy: https://emperorkabir.github.io/Power-Media-Player/privacy.html
- External marketing: opt out (untick if ticked)
- Save

[CHECKPOINT A.17] Internal testing — create release
- Left sidebar → "Testing" → "Internal testing"
- Click "Create new release"

[CHECKPOINT A.18] Play App Signing — accept Google-managed key
- If prompted to enable Play App Signing, click "Use Google-generated key" or "Continue"
- 🛑 PAUSED at A.18 if you see a different prompt — describe it before proceeding.

[CHECKPOINT A.19] Upload AAB — PAUSE for me to drag the file
🛑 PAUSED at A.19
Reason: drag-drop app-release.aab onto the upload widget
What you should see when done: file uploaded, processing complete, "Release name: 1" auto-filled.
Reply "resume A.19" when done.

[CHECKPOINT A.20] Release notes
- In the "Release notes" textarea (under <en-GB> or default language tag), paste exactly:

Initial release. Local audio/video playback, equaliser, A-B loop, bookmarks, sleep timer, Bluetooth, Chromecast, and optional Google Drive / Spotify cloud integration.

- Click "Save" (NOT "Review release" yet)

[CHECKPOINT A.21] Add testers — create email list
- On the same Internal testing page, scroll down to "Testers" section (or click the "Testers" tab)
- Click "Create email list"
- List name: Friends and family
- Email addresses: leave blank for now (I'll add them via "resume A.21" — see below)
- 🛑 PAUSED at A.21
Reason: I need to type or paste the friends' Gmail addresses (one per line) into the email list textarea.
What you should see when done: list saved, addresses visible.
Reply "resume A.21" when done.

[CHECKPOINT A.22] Activate the email list
- Tick the "Friends and family" checkbox so it's selected for this release
- Click Save changes

[CHECKPOINT A.23] Review release — pre-rollout
- Click "Review release"
- Review the warnings page. Note any blockers.
- 🛑 PAUSED at A.23
Reason: confirm I want to roll out
- If there are any errors blocking rollout, list them and stop. Do NOT click "Start rollout to Internal testing" until I reply "resume A.23 rollout".
- If only warnings, list them and ask me to confirm.
Reply "resume A.23 rollout" to proceed with rollout. Reply "resume A.23 stop" to stop here.

[CHECKPOINT A.24] Start rollout
- Click "Start rollout to Internal testing"
- Confirm in the modal
- Status will show "Rolling out" then "Available on Internal testing" within 15 min – 2 hr.

[CHECKPOINT A.25] Copy opt-in URL
- After rollout starts, scroll up on the Internal testing page
- Find the "Copy link" / "How testers join your test" section
- Copy the opt-in URL (looks like https://play.google.com/apps/internaltest/...)
- Output the URL in chat so I can save it.

[CHECKPOINT A.26] Workstream A complete
- Output: ✅ Workstream A done. Friends can opt in via the URL above. Reply "start B" to begin Spotify Extension Request, or "stop" to end here.

────────────────────────────────────────────────────────────────────
WORKSTREAM B — SPOTIFY EXTENSION REQUEST
────────────────────────────────────────────────────────────────────

[CHECKPOINT B.1] Navigate to Spotify Dashboard
- Go to https://developer.spotify.com/dashboard
- 🛑 PAUSED at B.1 if I'm not signed in. Otherwise continue.

[CHECKPOINT B.2] Open the app
- Click on the "Power Media Player" app card

[CHECKPOINT B.3] Open Extension Request
- Click "Extensions" or "Extension Request" tab (Spotify renames this occasionally; look for it in the left/top nav)
- 🛑 PAUSED at B.3 if you can't find it.

[CHECKPOINT B.4] Fill the form

For the application description, paste this exactly:

[SPOTIFY DESCRIPTION TO PASTE — verbatim]
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
[END SPOTIFY DESCRIPTION]

Other fields:
- App URL / Website: https://emperorkabir.github.io/Power-Media-Player/
- Privacy policy URL: https://emperorkabir.github.io/Power-Media-Player/privacy.html
- App category: Media Player
- Estimated user base: 100
- Commercial app: No
- Personal use: Yes

[CHECKPOINT B.5] Upload demo video — PAUSE
🛑 PAUSED at B.5
Reason: drag-drop spotify-demo-video.mp4 onto the video upload widget
What you should see when done: video filename + size visible, "uploaded" status.
Reply "resume B.5" when done.

[CHECKPOINT B.6] Tick all compliance checkboxes
- Spotify will show a list of compliance affirmations (no rehosting, no AI training, branding compliance, etc.)
- Tick ALL of them after you've read each one to me.
- 🛑 PAUSED at B.6 if any checkbox describes a behaviour my app actually does. Otherwise tick all.
Reply "resume B.6 confirm" when I want you to tick.

[CHECKPOINT B.7] Submit
- 🛑 PAUSED at B.7
Reason: confirm I want to submit the Extension Request to Spotify (irreversible until Spotify replies — usually 2–6 weeks)
Reply "resume B.7 submit" to click submit.

[CHECKPOINT B.8] Workstream B complete
- Output: ✅ Workstream B done. Spotify will respond in 2–6 weeks. Reply "start C" to begin Google OAuth verification (CASA Tier 2 work — non-trivial), or "stop" to end here.

────────────────────────────────────────────────────────────────────
WORKSTREAM C — GOOGLE OAUTH VERIFICATION (only if I say "start C")
────────────────────────────────────────────────────────────────────

This workstream is significantly more involved than A or B. It includes a CASA Tier 2 self-assessment that takes ~2 hours of careful answering. We'll do it in three sub-passes:

  C.1: Switch app publishing status to "In production" and prepare for verification
  C.2: Submit OAuth Brand Verification (privacy policy + scope justifications + demo video)
  C.3: Complete CASA Tier 2 self-assessment at https://appdefensealliance.dev/casa

[CHECKPOINT C.1] Navigate to Google Cloud OAuth Audience
- Go to https://console.cloud.google.com/auth/audience
- Confirm I'm in the "Power Media Player" project (top breadcrumb)
- 🛑 PAUSED at C.1 if not.

[CHECKPOINT C.2] Switch to Production
- Find the "User type" / "Publishing status" section
- Click "Publish app" / "Push to production"
- 🛑 PAUSED at C.2
Reason: confirm I want to switch from Testing to In production. Once switched, the test-user list is no longer enforced — and verification becomes mandatory.
Reply "resume C.2 publish" to proceed.

[CHECKPOINT C.3] Prepare for verification
- Google will prompt with a verification readiness questionnaire. Answer:
  • App name: Power Media Player
  • App logo: should auto-pull from Branding (which I've already filled)
  • Application home page: https://emperorkabir.github.io/Power-Media-Player/
  • Privacy policy: https://emperorkabir.github.io/Power-Media-Player/privacy.html
  • Authorised domains: emperorkabir.github.io
  • Scopes used: drive.readonly (and any others Google has detected)

[CHECKPOINT C.4] Scope justification — paste verbatim

[GOOGLE SCOPE JUSTIFICATION TO PASTE]
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
[END GOOGLE SCOPE JUSTIFICATION]

[CHECKPOINT C.5] Upload demo video — PAUSE
🛑 PAUSED at C.5
Reason: drag-drop oauth-demo-video.mp4 onto the video widget. Or paste a YouTube unlisted URL if Google asks for one.
What you should see when done: video uploaded / URL accepted.
Reply "resume C.5" when done.

[CHECKPOINT C.6] Submit OAuth verification
- 🛑 PAUSED at C.6
Reason: confirm I want to submit OAuth verification to Google. Review takes 4–6 weeks and may bounce with follow-up questions.
Reply "resume C.6 submit" to proceed.

[CHECKPOINT C.7] CASA Tier 2 self-assessment
- Google will redirect me to https://appdefensealliance.dev/casa or send a link via email
- 🛑 PAUSED at C.7
Reason: CASA Tier 2 is a long self-assessment (~2 hours) covering data handling, encryption, vulnerability scanning, and SDLC questions. We should do this in a separate session — you (Claude for Chrome) won't have my code context here, and I'd rather have Claude in VS Code prep the answers first.
For now, just take a screenshot of the CASA landing page so I have the link.
Reply "resume C.7" when done.

[CHECKPOINT C.8] Workstream C complete
- Output: ✅ Workstream C done. CASA Tier 2 work remains and should be done in VS Code with Claude. Google will respond on OAuth Brand Verification in 4–6 weeks.

────────────────────────────────────────────────────────────────────
END OF EXECUTION PLAN
────────────────────────────────────────────────────────────────────

Reply "start A" to begin Workstream A. Reply "status" at any time to see what checkpoint we're on. Reply "stop" to halt cleanly.

===END PROMPT===
```

---

## After the Chrome session

When Claude for Chrome reports each Workstream complete, come back to VS Code and tell me. I'll:
- Help you draft the CASA Tier 2 answers based on the codebase
- Help you respond to any Google or Spotify reviewer follow-up questions
- Build the next AAB whenever you want to push an update
- Help you add Spotify branding UI elements (Spotify logo + "Powered by Spotify" badge) before the Extension Request is submitted, if they're not yet present

If the Chrome assistant gets confused or stuck, copy the error and paste it into VS Code Claude — I'll diagnose and write you a corrected resume instruction.
