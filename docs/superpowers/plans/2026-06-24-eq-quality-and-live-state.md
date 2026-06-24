# EQ audio-quality (#7) + live-state clobber on settings-expand (#9)

> PLANNING ONLY. No code in this turn. Granular, TDD-first, evidence-gated.
> Source spec: `docs/superpowers/investigation/2026-06-24-19-item-investigation.md`
> (findings #7, #9). Both items READ-VERIFIED against current code (v1.3.4 /
> versionCode 38) before writing this plan.

## Goal

- **#7** Remove the "robotic / low-bitrate" artifact from the in-chain 10-band
  EQ. The biquad math is correct; the artifact is a memoryless soft-knee
  waveshaper firing at ≈ −2 dBFS on boosted transient peaks, with **no input
  headroom** and **no oversampling**, after a gain-summing resonant cascade →
  the hard non-linearity spawns high-order harmonics that **alias** back
  in-band (no oversampling) → inharmonic metallic timbre. Fix the DSP so total
  harmonic distortion (THD) + peak stay bounded for a loud tone with a band
  boost, while preserving the existing flat-pass-through and stability
  guarantees.
- **#9** Stop the EQ from audibly shifting when the user expands the Audio
  settings group. Root cause: expanding the group first-composes
  `HeadphoneEqSection` → `hiltViewModel()` constructs a **fresh**
  `EqualizerViewModel` whose `init{}` runs `restoreLastPreset → selectPreset →
  pushLevels → eqEffect.bandLevels.value = …`, **overwriting the live shared EQ
  state** (incl. any active per-track override). Construction must not mutate
  shared live state.

## Architecture (current, verified)

- **EQ DSP** — `audio/EqualizerAudioProcessor.kt`: a `BaseAudioProcessor`
  (Media3). 10 RBJ peaking biquads (Q=1.41), Transposed-Direct-Form-II, series
  cascade, per-band+per-channel state. Reads band levels (millibels) lazily per
  buffer via `bandLevelsMbSupplier`. Format gate: line 62 rejects anything but
  `ENCODING_PCM_16BIT` (returns `NOT_SET`). The soft-knee waveshaper +
  hard-clip is lines 117, 128–133. Flat → exact pass-through (line 107–111).
- **Supplier wiring** — `service/PlaybackService.kt:132–135`: `equalizerProcessor
  by lazy { EqualizerAudioProcessor(bandLevelsMbSupplier = {
  equalizerEffectController.bandLevels.value }) }`. The processor is element 3 of
  the `DefaultAudioProcessorChain` (`PlaybackService.kt:634–650`):
  `stereoTransform → reverb → EQ → audioDelay → gain → hueAnalyser`. Sink built
  with `setEnableFloatOutput(enableFloatOutput)` (line 632) — that flag governs
  the **sink-to-device** path, NOT the format the chain processors receive.
- **Live EQ state** — `audio/EqualizerEffectController.kt`: `@Singleton`,
  `val bandLevels = MutableStateFlow<List<Int>>(List(10){0})`. Single source of
  truth shared by: the audio processor (read), `EqualizerViewModel` (write via
  `pushLevels`), and per-track overrides
  (`audio/EqualizerOverrideRouter.applyPresetForUri` → `deps.eq().bandLevels.value
  = levels`, invoked from `PlayerViewModel.kt:230–242` on each track that carries
  an `eqPresetId`). **All three write/read the same `MutableStateFlow`.**
- **EqualizerViewModel** — `ui/equalizer/EqualizerViewModel.kt`. Hilt-injected
  (`presetDao`, `settingsDataStore`, `eqEffect`, `playbackConnection`,
  `audioOutputDetector`). `init{}` (75–118) launches: (1) `seedDefaultPresetsIfNeeded()
  → restoreLastPreset()` which calls `selectPreset(preset) → pushLevels →
  eqEffect.bandLevels.value = levels`; (2) `loadPresets()`; (3) a headphone-aware
  auto-swap collector that also calls `applyPresetSilently → pushLevels`. Settings
  and the EQ screen are **separate** nav destinations
  (`AppNavigation.kt:262–267`) → distinct `ViewModelStoreOwner`s → expanding the
  Audio group constructs a *fresh* VM instance whose `init{}` clobbers live state.
- **Settings expand path** — `ui/settings/SettingsScreen.kt`: Audio group body is
  composed only inside `AnimatedVisibility(visible = expanded || searching)`
  (≈869–890); first item is `HeadphoneEqSection()` (line 417);
  `HeadphoneEqSection.kt:46` does `eqVm: EqualizerViewModel = hiltViewModel()`.

## Tech stack

- Kotlin, Media3 1.6.0 (`androidx.media3:*:1.6.0`), Hilt, Jetpack Compose,
  Coroutines/StateFlow, Room.
- Tests: JUnit4 (`junit:junit:4.13.2`), Robolectric (`org.robolectric:robolectric:4.13`,
  `@Config(sdk=[33])`), MockWebServer. **No `mockk`, no `kotlinx-coroutines-test`,
  no Hilt test runner currently in `app/build.gradle.kts` test deps** (lines
  314–322). Plans below avoid adding heavy test deps; #9 is tested at the
  controller/guard seam, not by spinning a full Hilt VM graph.
- Test command (repo convention, used by every prior batch):
  `./gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"` from the project root.
- AudioProcessor JVM test harness already proven in
  `app/src/test/.../audio/ReverbAudioProcessorTest.kt` +
  `ReverbDiagnosticTest.kt`: `configure(format) → flush() → queueInput(buf) →
  drain(output)`, pure JVM (NO Robolectric needed for the processor itself).
  This is the harness #7's THD test reuses.

## Context7 verification (cited)

- `/androidx/media` — **Media3 1.6.0 (2025-03-26) release notes:** "*ChannelMappingAudioProcessor
  and TrimmingAudioProcessor now support float PCM.*" Full per-sample float PCM
  support for arbitrary processors did NOT land until **1.8.0** ("*Support has
  been added for all linear PCM sample formats within ChannelMappingAudioProcessor
  and TrimmingAudioProcessor*") and the injectable `AudioOutputProvider` custom
  output path is **1.9.0**. → On our pinned 1.6.0 a *custom* full-band
  float-output `BaseAudioProcessor` is unsupported infrastructure: every upstream
  chain processor (`stereoTransform`, `reverb`) and the decoder would have to
  emit `ENCODING_PCM_FLOAT`, and our processors all gate on
  `ENCODING_PCM_16BIT`. **This convicts option (d) "operate the cascade in float"
  as the highest-risk / out-of-version-budget option** (see Design decisions).
- `/androidx/media` — `BaseAudioProcessor.onConfigure(AudioFormat)` returns the
  **output** `AudioFormat`; a processor changes its output encoding by returning a
  format with a different encoding (and the sink negotiates downstream). Our gate
  at line 62 returns `NOT_SET` for non-16-bit, so the EQ only ever sees 16-bit
  today. `setEnableFloatOutput` on `DefaultAudioSink.Builder` controls the final
  device output, not the inter-processor buffer encoding.
- `/androidx/media` — AudioProcessor unit testing: `configure` → `queueInput` →
  `getOutput`/`output` drain loop is the canonical offline test shape (matches our
  existing Reverb tests).

> Note: oversampling (option c) and the RBJ/biquad reference are standard DSP
> (Robert Bristow-Johnson Audio EQ Cookbook); Context7 carries no library-API doc
> for them, so they are cited as standard technique, not a library call.

---

## File Structure

Files this plan creates or edits (exhaustive):

```
app/src/test/java/com/powermediaplayer/audio/
  EqualizerThdTest.kt            (NEW)  #7 — failing-then-passing THD/peak harness
app/src/main/java/com/powermediaplayer/audio/
  EqualizerAudioProcessor.kt     (EDIT) #7 — headroom + tanh soft-limiter (+ optional oversampling)
app/src/test/java/com/powermediaplayer/audio/
  EqualizerLiveStateTest.kt      (NEW)  #9 — failing-then-passing "no clobber on construct"
app/src/main/java/com/powermediaplayer/audio/
  EqualizerEffectController.kt    (EDIT) #9 — add a "touched" guard flag (live-state ownership)
app/src/main/java/com/powermediaplayer/ui/equalizer/
  EqualizerViewModel.kt           (EDIT) #9 — gate restore/auto-swap pushes so construction
                                          does not overwrite a live/override value
```

No new Gradle dependencies. No Room migration. No new Hilt bindings.

---

# PART A — #7 EQ DSP quality

## Task A1 — Failing THD/peak test (reproduce the investigation harness)

**Files**
- NEW `app/src/test/java/com/powermediaplayer/audio/EqualizerThdTest.kt`

**Goal of test**: encode the investigation's numeric finding (1 kHz @ 20000
amplitude + one band +6 dB → THD ~11 %; all +6 dB → 12–18 %; +12 dB → ~36 %) as
an executable assertion that FAILS on the current code and PASSES after the fix.
THD is measured offline by feeding a pure sine through the processor and
comparing fundamental energy to total non-fundamental energy via a Goertzel
bin at the fundamental (no FFT lib needed).

**Steps** (each 2–5 min)

1. Create the file with the same package + harness shape as
   `ReverbAudioProcessorTest.kt`: `AudioProcessor.AudioFormat(48_000, 2,
   C.ENCODING_PCM_16BIT)`; helper `sineStereo(frames, freqHz, amp, startFrame)`
   producing a `ByteBuffer` (native order, direct); helper `drain(p)` returning
   the output `ByteArray`; a `feed(p, buffers, framesPerBuffer)` that runs the
   sine through several buffers (let TDF-II state settle) and concatenates the
   later buffers' left-channel samples into a `DoubleArray`.

2. Add a `thd(samples, freqHz, sampleRate)` helper: compute fundamental
   magnitude with a Goertzel filter at `freqHz`; compute total RMS energy;
   `THD ≈ sqrt(totalEnergy − fundamentalEnergy) / fundamentalMag`. Document the
   formula in a comment. (Pure Kotlin math — no Android, no Robolectric.)

3. Add the FAILING assertions (these define "fixed"):
   - `oneBandBoostStaysClean`: 1 kHz sine at amplitude 20000, band index 5
     (1 kHz) at +600 mB (+6 dB) → assert `thd < 0.02` (2 %). Current code ≈ 11 %
     → FAILS now.
   - `allBandsBoostStaysClean`: same sine, all 10 bands +600 mB → assert
     `thd < 0.05` (5 %). Current ≈ 12–18 % → FAILS.
   - `loudBoostPeakBounded`: capture the absolute peak short value of the output
     and assert `peak <= 32767` (no wrap) AND assert no sample-to-sample delta
     exceeds a hard-clip discontinuity sentinel (re-uses the Reverb test's
     click-scan idea) → guards against re-introducing a hard knee.
   - `flatIsExactPassThrough`: all bands 0 → `assertArrayEquals(expectedBytes,
     out)` (random PCM in, identical out) — PINS the zero-cost flat path so the
     fix can't regress it.

4. Run it and capture the FAIL:
   - `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.EqualizerThdTest"`
   - **Expected: FAIL** on `oneBandBoostStaysClean` / `allBandsBoostStaysClean`
     (THD far above threshold), `flatIsExactPassThrough` PASSES already.
   - Paste the THD numbers printed (add `println("THD-DIAG …")` like
     `ReverbDiagnosticTest`) into the commit message as the before-baseline.

5. **Commit** (failing test): `test(eq): failing THD/peak harness for EQ
   waveshaper aliasing (#7)`.

## Task A2 — Fix the DSP: input headroom + tanh soft-limiter (recommended approach)

> Implements Design Decision D-7 (see end): **option (a) input headroom/makeup
> management + option (b) replace the hard soft-knee with a smooth tanh
> saturator at a higher effective threshold**, combined. Stays on the 16-bit
> path (no version risk), is the smallest change that removes the aliasing
> source, and keeps flat pass-through byte-exact. Oversampling (option c) is
> added in A3 ONLY if A2's THD does not clear the test thresholds.

**Files**
- EDIT `app/src/main/java/com/powermediaplayer/audio/EqualizerAudioProcessor.kt`
  - companion (33–41): add headroom + limiter constants.
  - `recompute(...)` (72–99): compute a per-configuration **makeup/peak-gain
    estimate** from the active bands so we know how much headroom to reserve.
  - `queueInput(...)` (101–138): replace the lines 115–133 soft-knee+hard-clip
    block with: pre-attenuate the cascade input by the reserved headroom, run
    the biquads, then apply a `tanh` soft-limiter that is *linear well below*
    full-scale and only bends near ±full-scale, then scale back. No oversampling.

**Steps** (each 2–5 min)

1. In the companion, add:
   ```kotlin
   // Reserve headroom so a boosted resonant peak no longer slams a hard knee
   // at ~-2 dBFS. We pre-attenuate by the worst-case linear band gain, run the
   // filters with room to spare, then a smooth tanh limiter rounds only the
   // rare sample that still approaches full scale. tanh is C-infinity smooth so
   // it injects far less high-order/aliasing energy than the old hard knee.
   const val FULL_SCALE = 32767f
   // tanh limiter: below LIMIT_LINEAR the transfer is ~unity (no colour); above
   // it bends to an asymptote at FULL_SCALE. ~-3 dBFS keeps headroom yet leaves
   // normal programme untouched.
   const val LIMIT_LINEAR = 0.85f            // |x|<0.85*FS → essentially linear
   ```

2. Add a private field `private var preGain = 1.0` and
   `private var postGain = 1.0`. In `recompute`, after the band loop, estimate
   the worst-case peak linear gain of the *active* cascade and set
   `preGain`/`postGain`:
   ```kotlin
   // Worst-case linear gain if every active band's boost aligned in phase.
   // Sum positive dB boosts only (cuts never increase peak); convert to linear.
   var maxBoostDb = 0.0
   for (i in 0 until N) {
       if (!bandActive[i]) continue
       val db = (levels.getOrElse(i) { 0 }) / 100.0
       if (db > 0) maxBoostDb += db          // pessimistic in-phase sum
   }
   // Cap the reserved headroom so heavy presets don't go inaudible; the tanh
   // limiter catches whatever the cap doesn't.
   val headroomDb = maxBoostDb.coerceAtMost(12.0)
   preGain = Math.pow(10.0, -headroomDb / 20.0)   // attenuate INTO the cascade
   postGain = Math.pow(10.0, headroomDb / 20.0)   // restore level after filtering
   ```
   Document why: pre-attenuation moves the resonant peaks down so they no longer
   cross a low threshold; the cap + tanh together keep loudness while preventing
   the per-sample hard non-linearity that aliased.

3. Replace the per-sample tail (current 128–133) with:
   ```kotlin
   // Filtered sample is already post-cascade; restore level then soft-limit.
   var v = (x * postGain).toFloat()
   val a = kotlin.math.abs(v)
   if (a > LIMIT_LINEAR * FULL_SCALE) {
       // Smooth tanh knee from LIMIT_LINEAR up to the FULL_SCALE asymptote.
       val sign = Math.signum(v)
       val over = (a - LIMIT_LINEAR * FULL_SCALE) /
                  ((1f - LIMIT_LINEAR) * FULL_SCALE)
       v = sign * FULL_SCALE *
           (LIMIT_LINEAR + (1f - LIMIT_LINEAR) *
            kotlin.math.tanh(over.toDouble()).toFloat())
   }
   out.putShort(v.coerceIn(-FULL_SCALE, FULL_SCALE).toInt().toShort())
   ```
   And apply `preGain` to the input sample before the band loop:
   `var x = inputBuffer.short.toDouble() * preGain`.

4. Keep `flatIsExactPassThrough` exact: the early-out at 107–111 (when
   `!anyActive`) is unchanged, so `preGain`/`postGain` never touch the flat path
   → byte-for-byte pass-through preserved.

5. Run the THD test:
   - `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.EqualizerThdTest"`
   - **Expected: PASS** on all four tests (THD now < 2 % / < 5 %, peak bounded,
     flat exact). Paste the new THD-DIAG numbers (should drop from ~11 % to low
     single digits).

6. Run the existing audio suite to confirm no regression:
   - `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.*"`
   - **Expected: PASS** (Reverb pass-through + tail tests unaffected — EQ change
     is isolated to its own processor).

7. **Commit**: `fix(eq): input headroom + tanh soft-limiter kills transient-peak
   aliasing (#7)`.

## Task A3 — Oversample the limiter ONLY if A2 doesn't clear thresholds (conditional)

> CONDITIONAL on A2's measured THD. If A2 passes the A1 thresholds, A3 is
> not-applicable and is recorded as such (not deferred — it is genuinely
> unnecessary; the predicate is the THD number). If A2 leaves THD above
> threshold for the +12 dB stress case, add 2× oversampling around the
> non-linearity to push residual harmonics above Nyquist before downsampling.

**Files**
- EDIT `app/src/main/java/com/powermediaplayer/audio/EqualizerAudioProcessor.kt`
  (only the per-sample limiter region; add a tiny 2× polyphase up/down inside
  the limiter, NOT around the biquads).

**Steps** (each 2–5 min)

1. Decision gate: read A2's `EqualizerThdTest` output. If
   `allBandsBoostStaysClean` and a new `+12dB` stress assertion both pass, STOP —
   record A3 = not-applicable with the THD evidence line. Otherwise continue.

2. Add a 2× zero-stuffed upsample → tanh → halfband-FIR decimate around ONLY the
   `tanh` non-linearity (the biquads are linear, so they don't alias and don't
   need oversampling — only the waveshaper does). Use a short symmetric FIR
   half-band (e.g. 11-tap) precomputed in the companion. Keep per-channel state
   for the decimator.

3. Add a stress assertion to `EqualizerThdTest` first (TDD): `plus12dbStaysClean`
   → all bands +1200 mB → `thd < 0.08`. Run:
   `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.EqualizerThdTest"`
   — expected FAIL before A3, PASS after.

4. Run `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.*"`
   → expected PASS.

5. **Commit** (only if A3 was needed): `perf(eq): 2x oversample the soft-limiter
   to suppress residual aliasing at extreme boosts (#7)`.

## Task A4 — Build gate for #7

**Steps**
1. `./gradlew.bat :app:assembleDebug` → **expected `BUILD SUCCESSFUL`**.
2. Paste the `EqualizerThdTest` PASS table + assembleDebug result into the
   TASKS.md evidence line for #7.

---

# PART B — #9 EQ live-state clobber on Audio-settings expand

## Design seam (why the fix lives in the controller, not only the VM)

- The live state is `EqualizerEffectController.bandLevels` (`@Singleton`,
  process-wide). Three writers exist: `EqualizerViewModel` (restore + manual +
  auto-swap), `EqualizerOverrideRouter` (per-track override). The *bug* is that
  VM construction unconditionally pushes the restored preset over whatever is
  currently live (which may be a per-track override applied by
  `PlayerViewModel`).
- The robust, race-free fix: make the controller record whether its live value
  has been **deliberately set by a real audio-affecting source** (override or an
  explicit user action), and have the VM's *construction-time* restore push the
  preset only when the live state is still the untouched default (or when no
  conflicting live value exists). This is unit-testable at the controller seam
  WITHOUT spinning a full Hilt VM (avoids adding `mockk` /
  `kotlinx-coroutines-test`). The VM edit then routes its `init`-time restore
  through the guarded path; the EQ-screen's own user actions still push
  unconditionally (they ARE the user choosing).

## Task B1 — Failing test: constructing the restore-push must NOT clobber a live override

**Files**
- NEW `app/src/test/java/com/powermediaplayer/audio/EqualizerLiveStateTest.kt`

**Goal**: prove that when the live `bandLevels` already holds a non-default
value (simulating an active per-track override), a *construction-time*
restore-push leaves it intact; and that a genuine user selection still applies.
Tested against `EqualizerEffectController`'s new guarded API (B2), pure JVM (no
Robolectric, no Hilt).

**Steps** (each 2–5 min)

1. Create the file in package `com.powermediaplayer.audio`. Build a fresh
   `EqualizerEffectController()` (its only state is the `MutableStateFlow`; the
   `@Inject` ctor is a no-arg ctor → directly constructible in a JVM test).

2. Add the FAILING assertions encoding the intended contract (B2 provides the
   API these call):
   - `overrideThenConstructRestore_keepsOverride`:
     1) simulate a live per-track override: `controller.setLive(listOf(0,0,0,0,0,600,0,0,0,0), source = LIVE_OVERRIDE)`;
     2) simulate the VM's construction-time restore: `controller.restoreIfUntouched(listOf(300,0,0,0,0,0,0,0,0,0))`;
     3) `assertEquals(listOf(0,0,0,0,0,600,0,0,0,0), controller.bandLevels.value)`
        — the override must survive (restore must be a no-op because the state
        was already deliberately set). **FAILS now** (no such API; current code
        would overwrite).
   - `freshProcess_constructRestoreApplies`:
     1) fresh controller (live == default `List(10){0}`, untouched);
     2) `controller.restoreIfUntouched(listOf(300,0,…))`;
     3) `assertEquals(listOf(300,0,…), controller.bandLevels.value)` — on a clean
        start the restore SHOULD apply.
   - `userSelectAlwaysApplies`:
     1) live override present;
     2) `controller.setLive(flatPreset, source = USER)` →
        `assertEquals(flatPreset, value)` — a deliberate user choice always wins.

3. Run:
   - `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.EqualizerLiveStateTest"`
   - **Expected: FAIL / does-not-compile** (API absent) — that is the red state.

4. **Commit** (failing test): `test(eq): failing guard test — VM construction
   must not clobber live EQ override (#9)`.

## Task B2 — Add the live-ownership guard to EqualizerEffectController

**Files**
- EDIT `app/src/main/java/com/powermediaplayer/audio/EqualizerEffectController.kt`

**Steps** (each 2–5 min)

1. Add an ownership marker + guarded setters. Keep `bandLevels` as the public
   read-`StateFlow` (the processor + UI still read it unchanged):
   ```kotlin
   enum class EqSource { DEFAULT, USER, LIVE_OVERRIDE }

   /** What last deliberately set the live value. DEFAULT = untouched. */
   @Volatile var source: EqSource = EqSource.DEFAULT
       private set

   /** Deliberate write (user action or per-track override). Always applies. */
   fun setLive(levels: List<Int>, source: EqSource) {
       this.source = source
       bandLevels.value = levels
   }

   /**
    * Construction-time restore. Applies ONLY if the live value is still the
    * untouched default — so lazily constructing EqualizerViewModel (e.g. when
    * the Audio settings group is expanded) can't overwrite an active per-track
    * override or a value the user already set this session.
    */
   fun restoreIfUntouched(levels: List<Int>) {
       if (source == EqSource.DEFAULT) {
           source = EqSource.USER
           bandLevels.value = levels
       }
   }
   ```
   Keep the existing `val bandLevels = MutableStateFlow(...)` field. (Direct
   `.value =` writes elsewhere still compile; B3 routes the VM + B4 routes the
   override through the new API so `source` stays accurate.)

2. Run the B1 test:
   - `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.EqualizerLiveStateTest"`
   - **Expected: PASS** (all three).

3. **Commit**: `fix(eq): live-state ownership guard on EqualizerEffectController
   (#9)`.

## Task B3 — Route EqualizerViewModel construction-time restore through the guard

**Files**
- EDIT `app/src/main/java/com/powermediaplayer/ui/equalizer/EqualizerViewModel.kt`
  - `restoreLastPreset()` (251–259) and the `pushLevels`/`selectPreset` call it
    makes — the construction path must use `restoreIfUntouched`, while genuine
    user actions (`setBandLevel`, the EQ-screen `selectPreset`) must use
    `setLive(..., USER)`.
  - `applyPresetSilently` (127–136, the headphone auto-swap) — must NOT clobber
    on first construction either: only apply when triggered by a *real*
    connect/disconnect transition, not by the collector's initial emission.

**Steps** (each 2–5 min)

1. Change `pushLevels` to take an explicit source, defaulting to USER for the
   interactive callers:
   ```kotlin
   private fun pushLevels(levels: List<Int>, source: EqSource = EqSource.USER) {
       eqEffect.setLive(levels, source)
   }
   ```
   Update `import` to `com.powermediaplayer.audio.EqualizerEffectController.EqSource`.

2. In `restoreLastPreset()`, do NOT call `selectPreset` (which pushes
   unconditionally + writes `manuallySelectedPresetId` + persists). Instead
   update `_uiState` for the UI and route the live push through the guard:
   ```kotlin
   private suspend fun restoreLastPreset() {
       val lastId = settingsDataStore.lastEqPresetId.first()
       if (lastId > 0) {
           val preset = presetDao.getPresetById(lastId) ?: return
           val levels = parseBandLevels(preset.bandLevels)
           _uiState.value = _uiState.value.copy(
               bandLevels = levels,
               selectedPresetId = preset.id,
               selectedPresetName = preset.name,
               isCustomModified = false
           )
           manuallySelectedPresetId = preset.id      // restore target only
           // Guarded: applies to the live engine ONLY if nothing deliberate
           // (per-track override / a value the user already set) is live.
           eqEffect.restoreIfUntouched(levels)
       }
   }
   ```
   The UI state still reflects the restored preset (so the EQ screen shows it);
   the *audio engine* is only touched if untouched — fixing the audible shift.

3. Keep `selectPreset` (173–189), `setBandLevel` (159–168), `resetToFlat`
   (235–243) as `setLive(levels, USER)` (these are real user actions, applied
   unconditionally). `applyPresetSilently` (auto-swap) → `setLive(levels,
   USER)` BUT gate the collector so it does not fire on the initial/stale
   emission: in the `init` collector (89–118), drop the first combined emission
   (it represents current state at construction, not a transition):
   ```kotlin
   audioOutputDetector.isHeadphonesConnected
       .combine(settingsDataStore.headphoneEqPresetId) { hp, id -> hp to id }
       .distinctUntilChanged()
       .drop(1)                 // first value = state at construction, not a swap
       .collect { (connected, headphonePresetId) -> … }
   ```
   This stops the headphone collector from also clobbering live state the instant
   the VM is constructed (the second half of #9's root cause on a BT-headphone
   device with a `headphoneEqPresetId` set).

4. Run the whole EQ-related suite:
   - `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.EqualizerLiveStateTest"`
   - `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.EqualizerThdTest"`
   - **Expected: PASS** (B1 still green; #7 unaffected).

5. **Commit**: `fix(eq): route VM construction restore + auto-swap through the
   live-state guard (#9)`.

## Task B4 — Keep per-track overrides accurate against the guard

**Files**
- EDIT `app/src/main/java/com/powermediaplayer/audio/EqualizerOverrideRouter.kt`
  (`applyPresetForUri`, line 40: `deps.eq().bandLevels.value = levels`).

**Steps** (each 2–5 min)

1. Change the override write to mark ownership so a later VM construction sees a
   non-DEFAULT source and leaves it alone:
   `deps.eq().setLive(levels, EqSource.LIVE_OVERRIDE)` (import the enum).
   Rationale: the per-track override IS the deliberate, audio-affecting live
   value; it must outrank a lazily-constructed VM's restore — which is exactly
   the bug scenario from finding #9 ("only audible when the live bandLevels
   differ from what `init` re-applies — e.g. a per-track override was active").

2. Add a 4th assertion to `EqualizerLiveStateTest` (TDD, write first then run):
   `overrideMarksSource_blocksRestore` — call a helper mirroring the router's
   write (`controller.setLive(levels, LIVE_OVERRIDE)`) then
   `controller.restoreIfUntouched(other)` and assert the override survives.
   (Already covered structurally by B1's first test; this pins the router's
   exact `source` choice.) Run:
   `./gradlew.bat :app:testDebugUnitTest --tests "com.powermediaplayer.audio.EqualizerLiveStateTest"`
   → expected PASS.

3. **Commit**: `fix(eq): per-track EQ override marks live-state ownership (#9)`.

## Task B5 — Build gate for #9

**Steps**
1. `./gradlew.bat :app:assembleDebug` → **expected `BUILD SUCCESSFUL`**
   (Hilt graph unchanged — no new bindings; only method bodies + one enum added).
2. Sanity: grep for any remaining raw writes that should be guarded —
   `Grep "eqEffect.bandLevels.value" + "bandLevels.value ="` across
   `audio/` and `ui/equalizer/` → expect the only direct `.value =` writes to be
   inside the controller itself; all external writers go through `setLive` /
   `restoreIfUntouched`. Paste the grep result as evidence.

---

# PART C — Full-suite gate + close-out

## Task C1 — Run the entire unit-test suite

**Steps**
1. `./gradlew.bat :app:testDebugUnitTest`
   - **Expected: PASS** for every suite (the two new suites + all pre-existing:
     Reverb, ChapterCache, ResumeGate, SpotifyBannerGrace, SettingsSearch, etc.).
2. Paste the suite count / 0-failures line into the TASKS.md evidence rows for
   #7 and #9.

## Task C2 — Device-verification handoff (cannot be automated here)

Per the project auto-install rule, after the commits land:
- push to origin; install the debug APK on the connected phone.
- **#7 manual check (AWAITING-USER):** play loud music, dial a heavy preset
  (Bass Boost / Rock at full) → the "robotic/low-bitrate" metallic edge is gone;
  flat preset is bit-identical (no colouration).
- **#9 manual check (AWAITING-USER):** start playback, then open Settings →
  expand the **Audio** group → confirm there is NO audible EQ shift at the
  moment of expansion (especially with a per-track EQ override active, and on the
  Bose QC Ultra BT path with a `headphoneEqPresetId` set).

These are recorded as `AWAITING-USER(device A/B listen)` in TASKS.md, not as
done — the JVM gates above prove the mechanism; the ear confirms the percept.

---

## Design decisions (confirm before execution)

### D-7 — #7 DSP approach: **RECOMMEND (a)+(b) — input headroom + tanh soft-limiter on the 16-bit path. Add (c) 2× oversampling only if THD doesn't clear.**

Options evaluated (the core design decision the prompt asked for):

- **(a) Input headroom / makeup-gain management** — pre-attenuate into the
  cascade by the worst-case boost, restore after. *Pro:* directly removes the
  mechanism (peaks no longer cross a low threshold); cheap; no version risk.
  *Con:* alone it can make heavy presets quieter; needs a level-restore + a
  catch for the residual peak. → Use it, but capped (12 dB) and paired with (b).
- **(b) Replace hard soft-knee+clip with a smooth tanh saturator at a higher
  threshold** — `tanh` is C-infinity smooth, so its harmonic spill rolls off
  fast (vs the hard knee's near-flat high-order spectrum that aliased). *Pro:*
  removes the dominant aliasing source; trivial; keeps loudness. *Con:* a
  per-sample non-linearity can still alias *a little* at extreme boosts (handled
  by (c) if measured). → **Primary fix, combined with (a).**
- **(c) Oversample the non-linearity 2–4×** — push harmonics above Nyquist
  before decimating. *Pro:* the textbook anti-alias cure. *Con:* CPU + code
  weight per buffer; only the waveshaper needs it (biquads are linear). →
  **Conditional**: apply only if A2's measured THD at +12 dB stays above
  threshold. Don't pay the cost pre-emptively.
- **(d) Operate the cascade in float (Media3 float output)** — remove the 16-bit
  clip entirely. *Pro:* cleanest in theory. *Con:* **CONVICTED out-of-budget on
  the pinned Media3 1.6.0** (Context7: full float-PCM processor support is
  1.8.0+; injectable custom output is 1.9.0; our chain's `stereoTransform` +
  `reverb` + the format gate all assume `ENCODING_PCM_16BIT`, and
  `setEnableFloatOutput` governs only sink-to-device). Adopting it means
  rewriting every processor's format contract and risking the whole audio chain
  — disproportionate to the fix. → **Rejected for this task.** (If a future
  Media3 bump to ≥1.8.0 happens, revisit as a separate, larger task.)

**Recommendation:** ship **(a)+(b)** (Task A2); keep **(c)** ready behind the THD
predicate (Task A3); do **not** do (d) on 1.6.0. The THD test (A1) is the
arbiter — the approach is "fixed" iff the thresholds pass, not by opinion.

### D-9 — #9 fix shape: **RECOMMEND the live-state ownership guard on EqualizerEffectController, with the VM's construction-time restore going through `restoreIfUntouched` and the headphone collector dropping its initial emission.**

Alternatives considered:

- **Guard the restore (chosen)** — controller records whether the live value was
  deliberately set; construction-time restore is a no-op unless still default.
  *Pro:* race-free against per-track overrides AND the headphone auto-swap;
  unit-testable at a pure-JVM seam (no `mockk`/`coroutines-test`/Hilt-test deps
  needed); minimal surface. *Con:* adds a small enum + two methods to the
  controller. → **Chosen.**
- **Make EqualizerViewModel non-eager / don't construct on expand** — e.g. make
  `HeadphoneEqSection` not call `hiltViewModel()` until interacted, or scope one
  shared VM. *Pro:* removes the trigger. *Con:* fragile (any future composable
  that touches the VM re-triggers it); a shared-scope refactor crosses nav
  destinations and is riskier; doesn't protect against the override race in
  general. → Rejected as the primary fix (the guard is the real invariant).
- **Don't mutate shared live state in VM init at all** — drop the
  `restoreLastPreset → pushLevels` from `init`. *Pro:* simplest. *Con:* breaks
  the legitimate cold-start behaviour where the EQ screen restoring the last
  preset SHOULD set the engine on a clean start. The guard preserves that (clean
  start → applies; live value present → no-op), so it's strictly better. →
  Rejected in favour of the guard.

**Recommendation:** the guard (Tasks B1–B4). It fixes both halves of #9's root
cause (the `restoreLastPreset` clobber AND the headphone-collector initial-emit
clobber) and is the only option that also defends the per-track-override race
the investigation flagged as the audible trigger.

---

## Self-review (anti-skip)

- [ ] #7: `EqualizerThdTest` written FIRST, FAILS on current code (THD ~11 %),
      PASSES after A2 (and A3 iff needed). Flat pass-through pinned byte-exact.
- [ ] #7: `assembleDebug` green; existing `audio.*` suite still green.
- [ ] #9: `EqualizerLiveStateTest` written FIRST, FAILS (API absent), PASSES
      after B2; VM (B3) + override (B4) routed through the guard; headphone
      collector drops initial emission.
- [ ] #9: grep confirms no un-guarded external `bandLevels.value =` writes.
- [ ] Full `:app:testDebugUnitTest` green; TASKS.md #7/#9 rows carry the PASS
      tables; device A/B listen recorded as `AWAITING-USER`, not as DONE.
- [ ] No new Gradle deps, no Room migration, no Hilt binding changes.
- [ ] Option (d) recorded as rejected-with-reason (version budget), NOT deferred.
