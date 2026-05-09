# Claude-for-Chrome — Play Console autopilot prompt

Paste the entire block below into the Claude-for-Chrome extension *while
the user is logged into [https://play.google.com/console](https://play.google.com/console)*.

The user has authorised you to **proceed without per-step confirmation**.
Do NOT ask "are you sure" before submitting forms or uploading the AAB.
Move through the workflow end-to-end. If you can't find a field, search
inside the page or take the closest equivalent. Save drafts every step.

---

## TASK

Create the Play Console listing for the Android app **Power Media
Player** (`com.powermediaplayer`), upload its signed Release AAB, fill
out every required form, and submit for review.

## INPUTS YOU HAVE

| Input | Value |
|---|---|
| Local AAB path on the user's machine | `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\dist\PowerMediaPlayer-1.0.0-release-2026-05-09.aab` |
| Backup AAB path | `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\app\build\outputs\bundle\release\app-release.aab` |
| Privacy policy URL | `https://EmperorKabir.github.io/Power-Media-Player/privacy.html` (if 404, fall back to raw GitHub: `https://raw.githubusercontent.com/EmperorKabir/Power-Media-Player/main/docs/privacy.html`) |
| Source repo | `https://github.com/EmperorKabir/Power-Media-Player` |
| Contact email | `bhasin.kabir@gmail.com` |
| Developer country | United Kingdom |
| Package name | `com.powermediaplayer` |
| Version code | `10` |
| Version name | `1.0.0` |
| Min SDK | 30 (Android 11) |
| Target SDK | 35 (Android 15) |
| Upload-key SHA-1 | `55:80:9E:9B:87:BE:F5:46:A7:8A:C9:4F:2B:C7:3A:3E:0F:F2:60:CC` |
| Upload-key SHA-256 | `39:81:A2:82:A6:FA:AF:F3:94:68:A7:6D:D4:9E:3D:11:A2:DD:30:0B:E9:F2:1E:77:5C:3F:CC:44:CD:2B:BC:E4` |

---

## STEP 1 — Create the app

1. Click **Create app**.
2. App name: `Power Media Player`
3. Default language: `English (United Kingdom) – en-GB` (fall back to `en-US` if en-GB unavailable).
4. App or game: **App**
5. Free or paid: **Free**
6. Tick:
   - Developer Program Policies — **agree**
   - US export laws — **agree**
7. Click **Create app**.

## STEP 2 — Set up your app (left-rail "Dashboard" → "Set up your app")

Fill each card. If the form changes order, follow the on-screen flow.

### Card: **App access**
- Select: **All functionality is available without special access**.
  (Spotify integration is OPTIONAL; the app works without sign-in.)

### Card: **Ads**
- Select: **No, my app does not contain ads**.

### Card: **Content rating**
- Click **Start questionnaire**.
- Email: `bhasin.kabir@gmail.com`
- Category: **Music or audio app** (closest fit; if "Reference, news or entertainment" is offered for media players pick that).
- Answer EVERY content question with **No**:
  - No violence (cartoon / fantasy / realistic / sexual / extreme)
  - No sexual content
  - No nudity
  - No profanity / crude humour
  - No drugs / alcohol / tobacco references
  - No simulated gambling / real-money gambling
  - No user-generated content **except** a media library scoped to the user's own files (if asked, declare: yes, the app reads files the user already owns; no it does not allow user-to-user content sharing).
  - No location sharing.
  - No personal info shared between users.
  - No unrestricted internet access **inside the app** (we only fetch from named services: Google Drive, Spotify, OpenSubtitles, MusicBrainz, Discogs, podcast feeds, the user's own Cast device on the LAN).
  - No miscellaneous illegal/regulated content.
- Submit. Expected rating: **Everyone / All ages (PEGI 3 / IARC Generally suitable for all)**.

### Card: **Target audience and content**
- Target age groups: tick **18 and over** ONLY (the app is general-purpose, not designed for kids; this avoids COPPA/GDPR-K obligations).
- If asked "Do you have a privacy policy?" → **Yes** → paste the privacy URL above.
- "Is your app designed for families?" → **No**.
- "Does your app appeal to children?" → **No**.

### Card: **News app**
- **No, my app is not a news app**.

### Card: **COVID-19 contact tracing and status**
- **No, my app is not a publicly-available COVID-19 contact tracing or status app**.

### Card: **Data safety**
- **Does your app collect or share any required user data?** → **No**.
- **Is all of the user data collected by your app encrypted in transit?** → **Yes** (HTTPS for every external endpoint).
- **Do you provide a way for users to request that their data be deleted?** → **Yes** (in-app: Settings → About → Reset all settings; uninstalling removes all local data).
- Add this notice in any free-text field that asks for explanation:
  > Power Media Player processes the user's local media library on-device. The app supports OPTIONAL cloud sign-ins (Google Drive, Spotify, OpenSubtitles) and OPTIONAL podcast subscriptions; OAuth tokens for those are stored locally on the device only (Spotify token in private DataStore, OpenSubtitles credentials in EncryptedSharedPreferences). The developer operates no servers and does not collect, share, or transmit any user data. Local audio/video file metadata never leaves the device. See https://EmperorKabir.github.io/Power-Media-Player/privacy.html for the full policy.

### Card: **Government apps**
- **No**.

### Card: **Financial features**
- **No**.

### Card: **Health**
- **No**.

### Card: **Store listing**

- **App name**: `Power Media Player`
- **Short description** (80 chars):
  > Offline-first audio + video player. Cast, Drive, Spotify, podcasts, alarms.
- **Full description** (paste verbatim, 4000-char limit):
  > Power Media Player is an offline-first Android audio and video player built around three ideas: your media library should live on your device; cloud sources should plug in optionally without taking over; and every control should be reachable without diving through menus.
  >
  > Local playback
  > • Plays MP3, M4A/M4B, FLAC, OGG/Opus, WAV, MP4, M4V, MKV, WebM and more.
  > • Audiobook chapter detection (M4B chapter atoms) with chapter-aware Sleep Timer (end-of-track / chapter / queue / album).
  > • Per-file overrides via long-press: speed, pitch, EQ preset, ReplayGain mode, reverb, video flips, A-B loop. Saved overrides persist across plays.
  > • Bookmarks with replay-context; cold-start resume backoff; folder-mode chapter view.
  > • Wake-up alarms with volume ramp, hold, wind-down, snooze (continue ramp or restart from start), DND-override, full-screen UI, plus stop methods (math, shake, swipe-to-confirm).
  >
  > Audio quality
  > • 10-band Equalizer with named presets and custom user presets.
  > • Headphone-aware EQ — auto-applies a different preset per paired Bluetooth audio device.
  > • Reverb (Off / Room / Medium hall / Large hall / Plate / Cave), stereo-flip, mono mix, multi-channel passthrough, volume boost up to +20 dB, independent pitch.
  > • ReplayGain track + album modes with -14 LUFS target.
  > • True 2-player crossfade with equal-power / linear / exponential / logarithmic curves, skip-silence, configurable pre-fade trigger, audiobook auto-greying.
  >
  > Video
  > • Hardware-decoded by default; software fallback for awkward containers.
  > • Mirror H/V, B&W, sepia, invert colours, rotation 0°/90°/180°/270°.
  > • Picture-in-Picture on home press.
  > • Subtitle delay, audio delay, subtitle format preference (SRT / VTT / ASS).
  > • OpenSubtitles auto-fetch (optional sign-in): per-language chip set, match-by-hash radio, save next to video or in app cache, override-existing switch.
  >
  > Cloud sources (all optional, all OAuth)
  > • Google Drive: stream and download via Drive Picker (drive.file scope — no Google verification required).
  > • Spotify: browse Liked Songs, Saved Albums, Top Tracks, Saved Episodes, podcasts; full-track playback requires Spotify Premium per Spotify's API rules. Spotify Connect device picker (Web-API limitation noted in-app).
  > • Podcasts: subscribe by RSS feed URL or via iTunes / Apple Podcasts directory search; per-show settings (auto-download, retention last N, notify on new); episodes saved to Movies/PowerMediaPlayer/podcasts/.
  > • Online metadata enrichment (off by default): MusicBrainz / Discogs / both. Locally cached.
  >
  > Cast
  > • Tap the Cast icon on the Player tab to send local or Drive content to Chromecast, Google Home / Nest, smart TVs. The app runs an embedded LAN HTTP relay so receivers can fetch your phone-local content.
  >
  > Other
  > • Bluetooth car-stereo button remap (Previous / Next).
  > • Tasker / Macrodroid intent integration (off by default).
  > • Configurable auto-hide timers per popup, controls, info sheet.
  > • OLED-black theme with user-configurable accent colour (full palette tracks the chosen accent via HSL).
  > • Fully responsive layout — phone / Z Fold inner+outer / tablet.
  >
  > No ads. No analytics. No tracking. The developer operates no servers. Open source.
- **App icon**: upload `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` (resize to 512×512 if Play complains).
- **Feature graphic**: 1024×500 PNG. **If the user hasn't supplied one, generate a simple gradient (OLED black → teal accent) with the words "Power Media Player" centred in white**, save as PNG, upload it.
- **Phone screenshots**: at minimum 2, ideally 4–8 (1080×1920 or higher). Use the existing `dbg_*.png` files in the repo root if quality permits, or generate fresh via `adb -s RFCY70BARDJ shell screencap`. **Skip if not available; Play allows draft submission without screenshots, but production rollout requires them.**
- **Tablet screenshots**: optional, recommended.
- **Categorization**:
  - App category: **Music & Audio** (primary). Secondary: **Video Players & Editors** if a multi-select.
  - Tags: pick `Music`, `Audio`, `Video player`, `Podcasts`, `Audiobook`, `Cast`.
- **Contact details**:
  - Email: `bhasin.kabir@gmail.com`
  - Website: `https://github.com/EmperorKabir/Power-Media-Player`
  - Phone: leave blank.
- **External marketing**: tick **No, do not promote outside Google Play**.

### Card: **Pricing & distribution**
- Free.
- Available in: tick **all countries**, except where the user has explicitly excluded any (none specified).
- Contains ads: **No**.
- Content guidelines + US export laws: **agree**.

---

## STEP 3 — Production release

1. Left rail → **Production** → **Create new release**.
2. Click **Continue** when asked about Play App Signing (let Google manage the production signing key — they generate it from our upload).
3. Upload the AAB:
   - Local path: `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\dist\PowerMediaPlayer-1.0.0-release-2026-05-09.aab`
   - If the OS file picker can't navigate to that path, fall back to: `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\app\build\outputs\bundle\release\app-release.aab`
4. Release name: `1.0.0 (10)`
5. Release notes (paste in the en-GB box; copy to other locales if Play asks):
   > First public release. Local audio + video playback, audiobook chapters, wake-up alarms, equaliser with headphone presets, true 2-player crossfade, ReplayGain, optional Google Drive / Spotify / OpenSubtitles / podcasts integrations, Cast support, Bluetooth car-button remap. No ads. No tracking.
6. Save.
7. Click **Review release** → fix any warnings (typically: the IARC questionnaire link, if not yet completed).
8. Click **Start rollout to Production**.

If Play forces a closed/internal testing track first (it sometimes does
for first-time accounts):
- Use **Internal testing** → add the user's own Gmail (`bhasin.kabir@gmail.com`) as a tester → start rollout there → then promote to Production.

---

## STEP 4 — App content section (will be auto-populated from STEP 2 above; double-check)

- Privacy policy URL set: yes
- App access: open
- Ads: none
- Content rating: completed
- Target audience: 18+
- News app: no
- COVID-19: no
- Data safety: completed
- Government apps: no
- Financial features: no
- Health: no
- Family Policy compliance: not applicable (not a family-targeted app)

If any card shows a red dot, click into it and complete it. Use the
exact same answers documented above.

---

## STEP 5 — Verify and report

After clicking "Start rollout":

1. Take a screenshot of the **Production track release status** page (showing "In review" or "Available on Google Play" if instant) and save it.
2. Note the rollout percentage.
3. Print to the user any warnings, rejections, or required follow-ups.
4. Report **all** of these in the final summary back to the user:
   - The new app's Play Store URL (will be `https://play.google.com/store/apps/details?id=com.powermediaplayer`).
   - The tracking ID for this submission (visible in Play Console under "Releases overview").
   - Any review-blocking warnings still outstanding.
   - Whether the screenshots / feature graphic still need to be supplied (they probably do).

---

## RULES while running this

- Do NOT pause to ask the user to confirm form values — proceed and report back at the end.
- If you encounter a checkbox you genuinely cannot determine the answer to from the inputs above, default to the most-restrictive choice (e.g. "no" for any "does your app collect X?" question) and note it in the final report.
- If a step requires a human captcha or 2FA, surface that one item back to the user and continue with everything else you can do without them.
- After the AAB upload step, take a screenshot if possible.
- Save drafts before navigating between cards so partial work isn't lost.
- If Play throws a validation error (e.g. "icon too small"), generate or resize the asset as needed and re-upload — don't escalate to the user unless you have no automated path.

End of prompt.
