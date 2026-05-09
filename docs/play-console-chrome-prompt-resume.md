# Claude-for-Chrome — Play Console autopilot RESUME prompt

Paste this whole block into Claude-for-Chrome while logged in to
[https://play.google.com/console](https://play.google.com/console).
This is the resumption after the prior run hit the package-key
verification blocker.

The user has authorised proceeding without per-step confirmation.

## Background (from the prior run)

- Developer account: KabAlr (ID 8867642483657842477).
- Package `com.powermediaplayer` is in **Draft** state.
- Play already has ONE key attached: SHA-256 starting `F8:08:70:3B…`
  ending `…A9:A7:49:07`. **That is the user's old DEBUG keystore**
  (verified locally — debug.keystore SHA-256 matches except for ONE
  byte your OCR likely misread; the rest of the 32 bytes are
  identical). It is NOT a production-suitable key.
- Zero verifications.
- New release keystore generated and confirmed (kept on disk):
  - SHA-1   `55:80:9E:9B:87:BE:F5:46:A7:8A:C9:4F:2B:C7:3A:3E:0F:F2:60:CC`
  - SHA-256 `39:81:A2:82:A6:FA:AF:F3:94:68:A7:6D:D4:9E:3D:11:A2:DD:30:0B:E9:F2:1E:77:5C:3F:CC:44:CD:2B:BC:E4`

## What's been built locally and is ready for upload

| Artifact | Path |
|---|---|
| **Registration APK** (signed with new release keystore, contains `assets/adi-registration.properties` = `DPUWCI6IVRYCWAAAAAAAAAAAA`) | `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\dist\PowerMediaPlayer-1.0.0-registration-2026-05-09.apk` |
| Backup registration APK | `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\app\build\outputs\apk\release\app-release.apk` |
| **Production AAB** (signed with same new release keystore) | `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\dist\PowerMediaPlayer-1.0.0-release-2026-05-09.aab` |
| Backup AAB | `C:\Users\Kabir\.gemini\antigravity\scratch\Power Media Player\app\build\outputs\bundle\release\app-release.aab` |
| Privacy policy | `https://EmperorKabir.github.io/Power-Media-Player/privacy.html` (host on Pages first; fall back to raw GitHub) |

## STEP A — Swap the registered key on the package draft

1. Navigate to `https://play.google.com/console/u/0/developers/8867642483657842477/android-developer-verification/packages/com.powermediaplayer`.
2. The page shows the package draft with the F8:08… key attached.
3. Click **Change key** (or **Manage keys** → **Add key** if Change isn't visible) and add the new release keystore's certificate by uploading the registration APK below — Play will read its signing cert and accept it as the registered key.
4. If the page strictly asks you to "Sign and upload an APK" without a separate "change key" affordance, simply upload the registration APK at `dist\PowerMediaPlayer-1.0.0-registration-2026-05-09.apk`. That APK's signing cert (SHA-256 `39:81…CD:2B:BC:E4`) will become the new registered key for the package, AND it contains the verification snippet `DPUWCI6IVRYCWAAAAAAAAAAAA` in `assets/adi-registration.properties` so verification succeeds in the same step.
5. Wait for the page to confirm "Verified" / green tick.

## STEP B — Resume the original Play Console workflow

Once STEP A shows the package is verified with the new key
(`39:81…CD:2B:BC:E4`), continue with the original
`docs/play-console-chrome-prompt.md` from STEP 1 ("Create the app")
through to STEP 5. All inputs in that document are still correct — the
**Upload-key SHA-256** column there matches the key you just registered.

When you reach STEP 3 (Production release), upload the AAB at
`dist\PowerMediaPlayer-1.0.0-release-2026-05-09.aab`. It is signed with
the same key as the registration APK above, so Play will accept it.

## If Play insists on KEEPING the F8:08… debug key

Two options, in order of preference:

1. **Use the in-page "Change key" / "Manage keys" → "Remove" / "Replace"
   workflow** to drop the debug key entry, then upload the new
   registration APK to register the new key.
2. If neither is offered (rare), the package draft cannot be cleaned up
   from the browser — surface that back to the user with a screenshot
   of the available options on the Manage keys page so they can either
   (a) delete the draft and re-create with the new key, or (b) raise a
   Play Console support ticket to wipe the stale debug-key registration.

## What NOT to do

- Do NOT upload anything signed with the debug keystore — debug-signed
  builds will be rejected at the Production track in any case.
- Do NOT delete or reassign keys on the user's other app (Simple Live
  Solar System).
- Do NOT touch any other developer account.

## Final report

When done, return:

- The registered package key SHA-256 (should be `39:81…CD:2B:BC:E4`).
- Whether STEP A required "Change key" or just "Upload APK".
- The new app URL `https://play.google.com/store/apps/details?id=com.powermediaplayer`.
- Anything still red/blocking on the Set-up-your-app dashboard.
