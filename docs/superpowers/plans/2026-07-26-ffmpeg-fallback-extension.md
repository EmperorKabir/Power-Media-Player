# FFmpeg fallback decoder — integration status + remaining native build

**Goal:** let the app play *non-DRM* audio codecs Android's platform decoders lack
(incl. the audio track of videos that otherwise play silent), WITHOUT touching any
existing behaviour. Fallback-only: platform MediaCodec first, FFmpeg only when nothing
else can decode the track.

## Done (commit pending) — the safe integration wiring
- `PlaybackService.kt` — the main player's `RenderersFactory` extension mode changed
  from `EXTENSION_RENDERER_MODE_OFF` → **`EXTENSION_RENDERER_MODE_ON` (fallback-only)**
  for the default (HW) case; the "Prefer software decoding" setting still escalates to
  `PREFER`.
- `app/proguard-rules.pro` — keep rule for `FfmpegAudioRenderer`'s constructor (it is
  loaded by reflection from `DefaultRenderersFactory`; would be stripped in release
  otherwise) + `-dontwarn`.

### Why this is safe / verified (risk map — see the investigation)
- Every audio EFFECT (EQ, reverb, stereo/mono, speed, pitch, volume boost, voice
  boost, delay, ReplayGain, Hue tap) is an `AudioProcessor` in the ONE shared
  `DefaultAudioSink` (the factory overrides `buildAudioSink`, NOT `buildAudioRenderers`),
  or is player-level. An FFmpeg renderer feeds that same sink → effects apply
  identically. No effect is bound to `MediaCodecAudioRenderer` or an audio session id
  (native `audiofx.*` effects were already removed — grep finds only doc comments).
- No per-file SLOWDOWN: `ON` only changes one-time renderer SELECTION (which ExoPlayer
  already does); the per-sample decode path is unchanged for files MediaCodec handles.
- The **crossfade second player** uses the DEFAULT factory (`ExoPlayer.Builder(context)`
  in `CrossfadeController.kt:113`, no custom factory) — the change to the MAIN player's
  factory cannot touch it. (Only two `ExoPlayer.Builder` sites exist: the main player
  and the crossfade player.) Untouched, as required.
- Metadata (title/cover/chapters) comes from the extractor / `MediaMetadataRetriever` /
  `M4bChapterParser` — all upstream of the decoder — so a decoder change is irrelevant.
- Video decode + video effects are separate (view-layer colour matrix; MediaCodec video
  renderer) — the FFmpeg AUDIO extension adds no video renderer.
- **Until the native library is bundled, `ON` behaves exactly like `OFF`**: the
  reflective lookup of `FfmpegAudioRenderer` finds no class and adds no renderer. So this
  wiring is INERT (zero behaviour change) today, and becomes active the moment the .so
  libraries are added.

## REMAINING — the native FFmpeg libraries (the real blocker)
Media3 publishes **no prebuilt** `media3-decoder-ffmpeg` on Maven; the native `.so`
files must be **cross-compiled from source**. Not doable in the current Windows session
(no NDK installed; the FFmpeg build is Linux/macOS-oriented). Do it on a Linux/macOS
build machine (or CI):

1. `git clone https://github.com/androidx/media.git` and `cd media`, checkout the tag
   matching the app's Media3 version (currently **1.6.0**).
2. Install the **Android NDK** (matching `libraries/decoder_ffmpeg/README.md`) + `make`,
   `nasm`/`yasm`. Set `ANDROID_NDK_HOME`.
3. `cd libraries/decoder_ffmpeg/src/main/jni` and clone FFmpeg at the version the README
   pins.
4. Run `./build_ffmpeg.sh` with the decoders to enable — for a media player, a sensible
   **non-DRM** set: `flac opus vorbis alac ac3 eac3 dca mlp truehd amrnb amrwb`. (Do NOT
   enable anything DRM/Audible — out of scope + legal.) This produces `libavcodec` etc.
   `.so` per ABI (armeabi-v7a, arm64-v8a, x86, x86_64).
5. Add the extension to the app: either include the built `media3-decoder-ffmpeg` module
   / AAR, or `implementation("androidx.media3:media3-decoder-ffmpeg:1.6.0")` pointing at
   your locally-published build. Uncomment the line at `app/build.gradle.kts` (~L252).
6. Rebuild. `ON` now routes unsupported audio to FFmpeg automatically (no further code
   change — the reflective load + the ProGuard rule are already in place).

**Cost:** APK grows ~10-30 MB (the .so per ABI); a one-time build to maintain per Media3
bump. **Do it only when a real non-DRM file fails to play** — for the current library
(AAC audiobooks) FFmpeg would sit idle.

**Trust note:** build the .so from source (above). Do NOT drop in a random third-party
prebuilt FFmpeg AAR into a shipped app — it's unvetted native code.
