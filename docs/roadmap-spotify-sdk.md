# Spotify SDK integration — roadmap

## Status: not yet implemented

The in-app Cloud → Spotify → Spotify Connect picker is **shipped but
limited**. Logcat-confirmed (2026-05-09) that even when the official
Spotify app is actively casting to a Google Home / Fire Stick / Sonos
device via Spotify Connect, our `GET /me/player/devices` Web API call
only returns the phone the app is installed on.

## Why the Web API alone is not enough

Spotify exposes two separate device-discovery channels:

1. **Spotify Connect SDK** — devices that register through Spotify's
   official SDK appear in `/me/player/devices`. Examples: an open
   Spotify app on another phone, browser, or speaker that uses the
   Connect SDK natively.
2. **Cast / vendor protocols (mDNS, Cast, AirPlay, vendor-private
   channels)** — Google Home / Nest / Chromecast / Fire Stick / Sonos
   route Spotify audio via these and **do NOT publish themselves to
   the public Web API**. Spotify's own picker discovers them via these
   parallel channels but third-party apps cannot.

Result: every workaround we add to the Web-API path
(`bounce-out-to-Spotify`, longer polling windows, multiple retries)
gives us nothing more — Spotify deliberately keeps Cast-discovered
device names out of `/me/player/devices`.

## Proof from logcat

```
I MediaPlayerWrapper: ... PACKAGENAME : com.spotify.music ... DEVICE : Living Area
I PMP_DIAG: Spotify.listDevices count=1 body={
  "devices" : [ {
    "id" : "42b9e3ed6a7b7205b97af0e8f17a57ab73389cab",
    "name" : "Kabir's Z Fold7",
    "type" : "Tablet"
  } ]
}
```

System notification metadata says Spotify is casting to Living Area.
Our Web API call says only the phone exists.

## Workaround currently shipped

The Cloud → Spotify → Connect picker now shows an amber "Not yet
working" banner pointing the user at the Cast icon on the Player tab,
which uses our own Cast SDK integration (mDNS-based) and discovers all
Cast-capable devices on the local Wi-Fi.

## Native SDK option — what it would take

Adding the **Spotify Android SDK** (`com.spotify.android:auth` plus
`spotify-app-remote`) would give us:

- Connect-SDK-level device enumeration (the same list the Spotify app
  shows).
- Direct playback control on those devices.

Costs:

- **Spotify dev-app approval required.** Each app's quota / scope
  expansion is reviewed by Spotify; they have been progressively
  closing third-party SDK access since 2018.
- **Requires Spotify app installed** on the user's device (the
  spotify-app-remote SDK is an IPC bridge to the local Spotify app).
- **Adds an APK dependency.** spotify-app-remote is ~2 MB but pins
  Kotlin runtime versions that historically lagged AndroidX.
- **Extra UI flow** for the Spotify-app sign-in handoff (different
  from our existing OAuth code path).
- Spotify SDK terms-of-service apply — review needed before ship.

## Alternative: stop pretending to support Spotify Connect

Cleanest no-code path: remove the in-app Connect picker entirely and
replace it with an "Open Spotify Connect device picker in the Spotify
app" button that simply launches Spotify and lets users pick the
device there. Cuts our maintenance burden and aligns user expectations
with the platform reality.

## Decision pending

User to confirm whether to:
1. Pursue Spotify SDK integration (file dev-app approval, integrate
   the SDK, ship as v2 feature).
2. Drop our own Connect picker and route to Spotify's UI.
3. Keep the current state (Cast icon as the workaround, Web-API
   picker shown with the "Not yet working" notice).
