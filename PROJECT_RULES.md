# PROJECT_RULES.md — Power Media Player

> **Fresh-context primer. Read this first, in full, every new session.** It is the single
> current source of truth for what this app is, how it is built, the preferences that govern
> the work, and the delicate code contingencies that must not be broken. Auto-loaded as binding
> rules via the user's global `~/.claude/CLAUDE.md`.
>
> Live task ledger: `/TASKS.md` (slim — live items only). Closed history: `docs/archive/`.
> Deeper detail pointers: §8.
>
> *Last refreshed 2026-06-26 at versionCode 39 / versionName 1.3.5. When a fact here drifts
> from code, fix the code-derived fact here in the same turn.*

---

## 1. Purpose & objectives

- Offline-first Android **audio + video player** for personal / friends-and-family use (≤100 users).
- **No analytics, no ads, no telemetry.** Privacy-respecting by design.
- Distribution: Google Play **Closed/Internal testing → Production**. Power-user app: deep,
  finely-controllable feature set is the point — favour finer control over hiding options.
- Privacy policy: `https://emperorkabir.github.io/Power-Media-Player/privacy.html`.

## 2. Current build coordinates (verify against `app/build.gradle.kts`)

- **versionCode 39 / versionName 1.3.5.** `38/1.3.4` is already published — next Play upload MUST be
  a higher versionCode. Each release gets a distinct versionName.
- minSdk 30, targetSdk 35, compileSdk 35. `applicationId = com.powermediaplayer`.
- **Media3 (ExoPlayer) 1.6.0** — do not bump casually; the custom audio chain + tests are tuned to it.
- **Room 2.7.1, DB `version = 22`** with an explicit, chained migration path 7→8→…→21→22 (no
  destructive migration any more — every bump ships an `ALTER`-based `Migration` registered in `AppModule`).
- Hilt 2.54 + KSP; Compose BOM 2025.04.00; Coil 3.1.0; OkHttp 4.12.0; AppAuth 0.11.1;
  Cast framework 21.5.0; BouncyCastle 1.78.1 (Hue DTLS); NanoHTTPD 2.3.1 (Cast relay).
- JDK: Java 17 source/target, **`kotlinOptions.jvmTarget = "17"`** pinned (Gradle runs on JDK 21;
  mismatch breaks `kspDebugKotlin` with "Inconsistent JVM-target compatibility").

## 3. Feature map (one line each)

**Playback**
- Local audio + video, every Media3 format (MP3/FLAC/AAC/OGG/OPUS/WAV/M4A/M4B; MP4/MKV/WEBM/MOV/AVI/FLV/WMV/TS).
- Per-track speed 0.5×–2.0× (pitch-independent), ReplayGain ±15 dB, A-B loop, frame-by-frame step.
- Background foreground-service + lock-screen notification; PiP auto-enter; persistent MiniPlayerBar.
- Sleep timer + sleep-at-end-of-chapter. Crossfade 0–10 s. Skip silence (Media3 + per-file axis).
- Bluetooth A2DP/AVRCP with re-mappable next/prev; Chromecast streaming; audio-output indicator
  (codec · channel-layout · sample-rate, incl. Atmos/TrueHD/DTS).

**Audio effects** (LOCAL pipeline only — see §5)
- 10-band equaliser (custom biquad), audibility-tuned presets + user presets; bands show inclusive
  frequency **ranges** (centre/√2 … centre×√2), not centre frequencies, and **no caption**.
- Reverb (5 presets), Voice boost (speech-clarity presence biquad ~2.7 kHz +5 dB), Stereo flip,
  Mono mix, multi-channel passthrough, audio delay 0–2000 ms, gain.
- True-mono outputs disable Stereo flip / Mono mix with a hint.
- Philips **Hue Entertainment** audio-reactive lighting (DTLS-PSK via BouncyCastle; tap at end of chain).

**Video effects** (local surface only) — Mirror H, Flip V, B&W, Sepia, Invert, 0/90/180/270, stackable.

**Library / Last Played**
- Library with media classification (audiobook / podcast / music / video icons + sub-kind labels), video thumbnails.
- Last Played: Recents (per-session) + Pinned (independent snapshots, drag-reorder), bookmark dropdowns,
  swipe-to-dismiss. Favourites strips (Drive / Spotify / Library) — star icon throughout, never hearts.
- Bookmarks anywhere; folder-as-audiobook aggregator with absolute cross-file chapters; M4B chapter parser.

**Podcasts** — RSS/Apple-id ingestion, episode metadata, auto-sync (WorkManager); **auto-play next
episode** (global toggle, default ON; queues the show from the tapped episode forward).

**Cloud**
- Google Drive: OAuth `drive.file` (non-sensitive, no Google verification) via OkHttp REST + WebView
  JS Picker. **READ-ONLY today** (GET/list; no `files.create`).
- Spotify: Web API + Connect (PKCE via AppAuth, Premium full playback, LRCLib synced lyrics,
  auto-launch + bounce-back on cold-start). Artist → top-tracks + albums/singles views.
- SAF retained for OneDrive / USB / phone storage.

**Per-file overrides** — any audio/video axis can be overridden per media item (and per podcast
episode → show → global) via the Tune popup; see §4.

## 4. Architecture spine

- **Single-activity Compose** host; Hilt DI; MediaSessionService (`PlaybackService`) +
  `PlaybackConnection` (MediaController IPC).
- **Custom audio processor chain** (Media3 `DefaultAudioSink.DefaultAudioProcessorChain`), in order:
  `stereoTransform → reverb → EQ → voiceBoost → audioDelay → gain → hueTap`.
  - All are `BaseAudioProcessor` subclasses: `onConfigure` returns `NOT_SET` for non-PCM16 (so
    passthrough/surround is untouched), `queueInput` uses `replaceOutputBuffer(size)`, `onFlush` resets state.
  - New processors MUST mirror `EqualizerAudioProcessor` (proven reference) and ship a unit test that
    exercises configure→queueInput→output→flush, including an exact off=pass-through assertion.
- **Per-file override system**: `MediaOverrideEntity` (keyed by mediaUri) + `MediaOverrideRepository`.
  `activeOverride` is a StateFlow keyed by `PlaybackService.currentOverrideKeyFlow`. Podcast merge is
  per-axis episode→show→global (`mergeEpisodeOverShow`). Global setting + override combine via
  `withOverrideBool(globalFlow){ it.axis }`. Adding an axis = entity column + migration + merge + popup
  tri-state + global Settings toggle + service collector (all five, or it is half-wired).
- **Settings** in `SettingsDataStore` (DataStore-Preferences). Prefer separate flows over giant
  indexed `combine()` (a 70+-flow combine is off-by-one fragile — historical bug).
- **Auto-resume / auto-load**: on launch, restore last item; resume must re-apply ALL retained settings
  (speed, audio effects, video effects). Granular auto-play gates: by kind (spoken/music/video),
  only-if-was-playing, on-launch, on-BT/headphone/cast, fade-in. `autoplayOnLaunch` defaults OFF.
- **Two logging systems** (correlate by wall-clock): DeepLogger (debug-only NDJSON forensics,
  `deeplog/session-*.ndjson`, App-Startup auto-install) + DiagLog (hand-instrumented app-logic timing,
  `diag/log-current.txt`, Settings→Diagnostic logging). See project `CLAUDE.md` for the runbooks; never
  read a whole raw session into context — query with `tools/deeplog/parse_logs.py`.

## 5. Delicate interactions / do-NOT-break (highest-value contingencies)

- **Audio effects are LOCAL-pipeline only.** They work over Bluetooth / wired / phone speaker but NOT
  over Spotify Connect or Chromecast (remote routes decode on the receiver — our PCM chain never runs).
  Never claim an effect applies to Spotify/Cast. Same for video effects (local surface only).
- **Effect behaviour varies by source/route**: local file vs Drive-streamed vs Spotify vs Cast, and by
  file type (PCM16 stereo gets processed; multichannel/passthrough bypasses). Factor route + type into
  any effect change.
- **Offline copies → `filesDir`, never `cacheDir`** (`OfflineStorage.toDurable`): cacheDir is OS-evicted
  and collides with `DriveTagEnricher`'s temp `drive_<hash>_full` (which it deletes). Enricher must
  `fixMojibake` MMR tags at source.
- **Spotify `/artists/{id}/albums` returns HTTP 400 "Invalid limit" if ANY `limit` param is sent** —
  omit it (Spotify defaults to 5; page via the `next` URL). `/me/top/artists` 403s in dev mode.
- **Album-art gate**: containers (albums/artists) have `isFolder = type != "track" && type != "episode"`,
  so an art gate must key on `thumbnailUri != null`, NOT `!isFolder` (the latter shows a folder icon on albums).
- **Media3 sticky `artworkData`** can bleed across tracks — clear per-track or art carries over.
- **Hue**: area is a collector source; intensity is preserved on disconnect; engine restarts on re-pick.
  Single teardown path on disconnect (double stop made lights keep changing — past regression).
- **Lint disabled for release builds** (`checkReleaseBuilds = false`, `abortOnError = false`): AGP's
  `NonNullableMutableLiveDataDetector` throws `IncompatibleClassChangeError` on this project's Kotlin
  bytecode. APK is unaffected; per-PR `lintDebug` still runs. Do not "fix" by re-enabling.
- **BouncyCastle OSGi manifest excludes** in `packaging.resources.excludes` are required (duplicate
  manifests across bctls/bcprov/bcutil); do not remove.
- **Release signing** comes from `local.properties` (`RELEASE_STORE_FILE` etc., gitignored). Missing keys
  → unsigned AAB rather than build failure (intended). AAB bundles `ndk.debugSymbolLevel = "SYMBOL_TABLE"`.
- **Subtitle delay slider is UI-only** (logged placeholder) until a custom subtitle pipeline exists.
- **Audio delay is positive-only** by design (a processor cannot pull audio earlier than video).

## 6. Working preferences (binding)

- **No deferral.** Never skip / stub / postpone / TODO / "for now" any in-scope item. A task is DONE only
  when every named + implied sub-item is implemented AND verified (builds / tests pass). Multi-part
  features ship only when ALL parts work in both apps and compile. Genuine external blockers (user GUI
  action, credentials, third-party API gap) are not deferral — state the exact blocker, finish everything
  else automatable around it.
- **Evidence-gated completion.** No "done" without fresh verification output in the same turn (build exit 0,
  test count 0 failures, device log line, etc.). Honesty is non-negotiable.
- **No guesswork on bugs.** Evidence-locked diagnosis before any player/video fix; past sessions broke
  unrelated features by speculative deletion. Instrument ALL permutations with timestamped logs; let the
  log decide — never ask a narrowing question that drops coverage.
- **Use Context7 (MCP)** for any external library / API / SDK / CLI doc before relying on memory.
- **After commits**: push to `origin/main` AND adb-install the debug APK on the connected phone — but only
  after confirming `dumpsys media_session` shows nothing PLAYING. Never `--no-verify` / `--amend` pushed /
  `push --force` to main without explicit approval. Never commit secrets / `local.properties` / `*.jks` /
  `*.aab` / `*.apk` / debug screenshots.
- **Device**: phone `RFCY70BARDJ`; emulator for form-factor simulation. Phone uses custom density 311
  (physical 420) + font_scale 0.85 — NEVER `wm density reset` (clobbers to 420); restore `wm density 311`.
  Cast only to "Living Area TV" or "Kabir Stereo" — never Bedroom speaker.
- **Writing style**: British spelling; bullets not paragraphs; maximum semantic compression. Banned:
  "literally" as intensifier, "go ahead and", "just/simply/feel free", "owe you", "honestly/to be honest",
  Americanisms/slang. Don't output code explanations unless asked.
- **Images**: never Read an image >500 KB; resize to 800px first (PIL/magick).

## 7. App-behaviour preferences

- Power-user app: expose finer control across options; sensible Settings ordering and UX grouping matter.
- All settings are retained and re-applied on resume (speed, audio effects, video effects, etc.).
- Star icon everywhere (no hearts). Layman-friendly Settings descriptions.

## 8. Where deeper detail lives (read on demand, not by default)

- **`/TASKS.md`** — live tasks only (PROTOCOL block governs: evidence-gated check-offs, legal statuses,
  phase lock investigate→plan→implement, no skip/defer). Read every turn; it is now slim.
- **`docs/archive/TASKS-history.md`** — closed/verified task history.
- **`docs/archive/superpowers/{plans,specs,investigation,audits}`** — full historical design/plan/investigation
  record. Discover via **`docs/archive/INDEX.md`**; load a specific file only when needed.
- **`docs/archive/project_operational_state.md`** — superseded 2026-05-05 snapshot (kept for history; this
  file replaces it).
- **`release/PLAYBOOK.md`** — Play Console / Spotify-extension release steps.
- **Auto-memory** (`~/.claude/.../memory/`, indexed by `MEMORY.md`) — preferences + resolved-regression notes,
  auto-loaded each session.
- **Project `CLAUDE.md`** — deep-logger + DiagLog runbooks and the TASKS.md mandate.
