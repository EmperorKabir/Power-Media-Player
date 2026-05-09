# Play Store release — 2026-05-09

## Artifacts

| Artifact | Path |
|---|---|
| Signed Release AAB | `app/build/outputs/bundle/release/app-release.aab` |
| Signed Release AAB (timestamped copy) | `dist/PowerMediaPlayer-1.0.0-release-2026-05-09.aab` |
| **Package-registration APK** (signed with same release keystore; contains `assets/adi-registration.properties` snippet for Google's Sept-2026 Android developer verification) | `dist/PowerMediaPlayer-1.0.0-registration-2026-05-09.apk` |
| Backup registration APK | `app/build/outputs/apk/release/app-release.apk` |
| Release keystore (KEEP SAFE — losing this means a new app listing) | `powermediaplayer-release.keystore` |
| Privacy policy (HTML, public-hostable) | `docs/privacy.html` |
| Spotify SDK roadmap (referenced in store listing notes) | `docs/roadmap-spotify-sdk.md` |
| Privacy: Children policy reference | `docs/release-2026-05-09-play-store.md` (this file) |

## App identity

| Field | Value |
|---|---|
| Package name | `com.powermediaplayer` |
| Version code | `10` |
| Version name | `1.0.0` |
| Min SDK | 30 (Android 11) |
| Target SDK | 35 (Android 15) |
| Compile SDK | 35 |
| Install size (uncompressed) | ~33 MB AAB |

## Signing

| Field | Value |
|---|---|
| Keystore alias | `powermediaplayer` |
| Keystore type | PKCS12 |
| Algorithm | RSA 4096 (SHA384withRSA) |
| Validity | 10 000 days (until 2053-09-24) |
| Distinguished name | `CN=Kabir Bhasin, OU=Personal, O=Power Media Player, L=London, ST=London, C=GB` |
| SHA-1 fingerprint | `55:80:9E:9B:87:BE:F5:46:A7:8A:C9:4F:2B:C7:3A:3E:0F:F2:60:CC` |
| SHA-256 fingerprint | `39:81:A2:82:A6:FA:AF:F3:94:68:A7:6D:D4:9E:3D:11:A2:DD:30:0B:E9:F2:1E:77:5C:3F:CC:44:CD:2B:BC:E4` |

> Use **Play App Signing**: upload this AAB; Google holds the production app-signing key, your upload key is the keystore above. Save `powermediaplayer-release.keystore` + `local.properties` somewhere safe (cloud drive backup recommended) — losing the upload key requires a key reset request to Google.

## Manifest permissions (each one explained)

| Permission | Purpose | Play Console Data Safety category |
|---|---|---|
| `INTERNET` | Streaming, OAuth, OpenSubtitles, MusicBrainz/Discogs, podcast feeds | n/a (transport only) |
| `ACCESS_NETWORK_STATE` | Detect Wi-Fi for Cast relay availability | n/a |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep media playback alive in the background | none collected |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Spotify-bounce notification (~10 s) so the system allows the bounce-back activity launch on Android 14+ | none collected |
| `WAKE_LOCK` | Keep CPU awake during playback | n/a |
| `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO` | Scan + play user's local audio/video library (Android 13+) | "Audio files" + "Videos" — collected? **No, processed locally only** |
| `WRITE_SETTINGS` | Optional brightness override during video playback | n/a |
| `POST_NOTIFICATIONS` | Media playback notification, alarm full-screen notification | n/a |
| `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM` | Wake-up alarms feature | none collected |
| `USE_FULL_SCREEN_INTENT` | Wake-up alarm full-screen UI | n/a |
| `VIBRATE`, `TURN_SCREEN_ON` | Wake-up alarm haptic + screen wake | n/a |
| `RECEIVE_BOOT_COMPLETED` | Re-arm wake-up alarms after device reboot | n/a |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | Show paired BT devices in audio output picker | none collected |

## Data Safety form — quick answers

- **Does your app collect or share any of the required user data types?** **No.**
- **Is all data collected encrypted in transit?** Yes (HTTPS for every external endpoint).
- **Do you provide a way for users to request that their data be deleted?** Yes (Reset all settings + Uninstall — fully wipes local data including OAuth tokens).

## Content rating — IARC

- Target audience: **Everyone / All ages.** No violence, sexual content, profanity, gambling, user-generated communication.
- Includes user-supplied content (their own media library) — disclose this.
- Cloud connections (Drive, Spotify, OpenSubtitles) — disclose.

## Store listing copy (shippable)

**Short description (≤80 chars)**
```
Offline-first audio + video player. Cast, Drive, Spotify, podcasts, alarms.
```

**Full description (4000 chars max — see Chrome prompt for the full text)**

See the Chrome extension prompt below — it contains the full long-description copy.

## Pre-launch checks

- [x] Privacy policy URL is public (`docs/privacy.html` hosted on GitHub Pages)
- [x] Signed Release AAB built successfully
- [x] Version code 10 (was 2; bumped to 10 to leave headroom for any earlier internal releases)
- [x] App icon present (`app/src/main/res/mipmap-*/`)
- [x] Adaptive icon for Android 8+ (`mipmap-anydpi-v26/`)
- [ ] Feature graphic (1024×500 PNG) — **not yet drawn**; Play Console requires one
- [ ] Phone screenshots (≥2, recommended 4-8) — **not yet collected**; existing `dbg_*.png` files in repo root could be cleaned up
- [ ] Store-listing icon (512×512) — derive from `mipmap-xxxhdpi/ic_launcher.png`

## Play Console package-key blocker (resolved)

**Symptom seen 2026-05-09**: Chrome-extension first run failed at
"Create app" with "package name not registered". The package
`com.powermediaplayer` already existed in Draft state with one key
attached: SHA-256 starting `F8:08:70:3B…`. Local keystore scan
showed only two keystore files on the machine — the debug keystore
and the new release keystore I generated (`39:81…CD:2B:BC:E4`). The
F8:08… key is **the user's debug keystore** (`~/.android/debug.keystore`),
not a real release key — somebody pre-registered the package with a
debug-signed APK at some point.

**Resolution**: built a registration APK signed with the new release
keystore, placed `assets/adi-registration.properties` containing
`DPUWCI6IVRYCWAAAAAAAAAAAA` inside it. Resume Chrome-extension run
uses `docs/play-console-chrome-prompt-resume.md` which (a) swaps the
registered key on the package draft to the new release keystore and
(b) continues from STEP 1 of the original prompt.

## Outstanding (functional, not Play-Store-blocking)

- **Home-screen widget is broken.** `NowPlayingWidgetProvider` was shipped per §C20 in the locked spec but no longer renders the current-track info or responds to its transport buttons after recent commits. Needs end-to-end investigation: AppWidgetManager update path, RemoteViews construction, intent filters, `widget_now_playing_info.xml`, missing icon resources, the `onUpdate` schedule. Repro by re-adding the widget to the home screen on Z Fold 6 (RFCY70BARDJ) and watching `adb dumpsys appwidget` + logcat. Tracked as task #89. **Should ship before public Play Store listing** (or the widget should be removed from the manifest temporarily).
- **Info boxes need updating for new features.** The per-tab info sheets (the small "i" icon top-right of each tab) were authored before the latest features landed. Need new entries covering: long-press a track / cloud item to create a per-file override (speed, EQ, ReplayGain mode, video effects, A-B loop); wake-up alarms with the full editor (ramp / hold / wind-down / snooze / math/shake/swipe stop / DND override); smart playlists (rules editor in Library); OpenSubtitles auto-fetch + match-by-hash + save-next-to-video; podcasts (RSS or iTunes search, per-show settings, downloads to `Movies/PowerMediaPlayer/podcasts/`); headphone-aware EQ (per-paired-device preset auto-apply); Cast relay (LAN HTTP relay so the receiver can fetch local / Drive content; works per `192.168.x.x` IP); Spotify Connect picker limitation (Web API doesn't surface Google Home / Fire Stick — see `docs/roadmap-spotify-sdk.md`); enrichment cache; reverb chips on the long-press override panel; 1.3× speed preset. Audit `app/src/main/java/com/powermediaplayer/ui/info/InfoContent.kt` and add. Tracked as task #88.

## Outstanding manual steps (after Chrome prompt)

1. **Hosting privacy policy.** GitHub Pages: `https://EmperorKabir.github.io/Power-Media-Player/privacy.html` once enabled. Alternative: serve from any HTTPS URL.
2. **Generate feature graphic + screenshots** in any image editor.
3. **Provide test instructions** if Spotify integration is reviewed (test account credentials).

## History of previous Play-Store-prep work

- `docs/superpowers/audits/2026-05-06-pre-play-store-audit.md` — first Play Store readiness audit (logging gating, ProGuard rules, data extraction rules, db migration policy).
- `docs/superpowers/plans/2026-05-06-master-plan-pre-play-store.md` + `…-v2.md` — Cast / Drive / Spotify casting plan.
- `docs/MIGRATION_INSTRUCTIONS.md` — Room migration policy (no destructive migrations from v1.0).
- `docs/roadmap-spotify-sdk.md` — Spotify Connect device-discovery limitation, future SDK integration option.

## Closed-testing prerequisites — provider-side configuration

Closed testing will FAIL for Drive sign-in, Drive Picker and Spotify
until the **Play App Signing SHA-1** is added to each provider's
console. Google generates that SHA-1 only AFTER the first AAB upload
(see Play Console → Setup → App signing).

Once you have that SHA-1:

1. **Google Cloud Console** (project owning the existing OAuth client):
   - APIs & Services → Credentials → Android OAuth client → add the
     Play App Signing SHA-1 (keep the debug SHA-1 too).
   - OAuth consent screen → publishing status = **Testing** for
     closed testing; add every tester's Google account under "Test
     users" (cap 100). For prod launch, switch to "In production" —
     the `drive.file` scope is non-sensitive, no Google verification
     required.
   - Drive Picker API key → restrict to the Picker API only AND set
     Application Restriction = Android, package `com.powermediaplayer`,
     SHA-1 = Play App Signing SHA-1.

2. **Spotify Developer Dashboard** (app `9721f7d7d2e34f2a8d508f22e48d77db`):
   - Settings → Redirect URIs → confirm `powermediaplayer://callback`.
   - Settings → Android section (if visible) → package name +
     Play App Signing SHA-1.
   - Settings → User Management → add every tester's Spotify email
     (cap 25 in Development Mode). For >25, file an "Extended Quota
     Mode" request — typically 2-4 weeks review.
   - Spotify Premium is required by Spotify for full-track playback;
     Free testers will hit the 403 PREMIUM_REQUIRED path.

3. **OpenSubtitles** (optional in closed testing): create a Consumer
   API key at opensubtitles.com → Profile → Consumers → paste into
   `local.properties` as `OPENSUBS_API_KEY` and rebuild the AAB.

4. **Cast / MusicBrainz / Discogs / Podcasts / iTunes** — no
   provider-side configuration needed. Cast uses the public default
   receiver; the others have no per-app registration.

5. **Privacy policy URL** must be live before Play and Google OAuth
   will let the listing/consent screen go live. Host `docs/privacy.html`
   on GitHub Pages (`https://EmperorKabir.github.io/Power-Media-Player/privacy.html`)
   or any HTTPS endpoint.

## Risks for Play Store review

1. **`SCHEDULE_EXACT_ALARM` requires user-visible justification** — wake-up alarms are a documented use-case; should pass.
2. **`USE_FULL_SCREEN_INTENT`** — same; documented for alarms.
3. **`READ_MEDIA_AUDIO/VIDEO`** — declared as core media-library functionality; obvious for a player app.
4. **Spotify integration:** Spotify dev-app should be set to "in development" → "extended quota" before public launch if you want >25 concurrent users.
5. **OpenSubtitles:** their TOS allows third-party API consumers; fine.
