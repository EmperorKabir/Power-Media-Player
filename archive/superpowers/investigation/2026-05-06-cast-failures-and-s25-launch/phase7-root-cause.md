# Root Cause — F1 + F2 + F3

**Status:** all three failure modes evidence-locked.
**Date:** 2026-05-07.

## F1 — S25 Ultra force-closes on launch

**Two stacked crashes** along the same code path (whichever fires first kills the process). Both reproduced on a clean Android 16 emulator (`Medium_Phone_API_36.0`, `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.D1`). The Z Fold 6 doesn't reproduce because its database is already at v7 from prior testing.

### F1-a — Room migration crash

**Symptom:**
```
java.lang.IllegalStateException: A migration from 1 to 7 was required but not found.
Please provide the necessary Migration path via RoomDatabase.Builder.addMigration(...)
or allow for destructive migrations via one of the
RoomDatabase.Builder.fallbackToDestructiveMigration* functions.
  at androidx.room.BaseRoomConnectionManager.onMigrate(RoomConnectionManager.kt:221)
```

**Cause:** Master plan step 1 (commit `9d7487f`) removed `.fallbackToDestructiveMigration(true)` from `AppModule.provideAppDatabase`. The intent was correct: "future schema bumps must ship Migration objects, not silently wipe data". But the implementation didn't account for users *upgrading from beta versions* — anyone who installed any pre-v1.0 build (versions v1.x through v6.x in `AppDatabase.DATABASE_VERSION`) and then receives the v1.0 APK has an existing v1-v6 database and a v7 schema in the new code; with no migration and no fallback, Room throws on first launch. The user's friend installed a previous-version APK on his S25 Ultra, then the v1.0 APK, and triggered exactly this.

**Fix:** `AppModule.kt` now uses `.fallbackToDestructiveMigrationFrom(false, 1, 2, 3, 4, 5, 6)` — explicit one-time destructive authorisation for those specific old versions only. From v7 forward every schema bump still requires a `Migration` (per `docs/MIGRATION_INSTRUCTIONS.md`).

### F1-b — `PlayerViewModel` field-init-order NPE

**Symptom (after F1-a fix):**
```
java.lang.NullPointerException: Attempt to invoke interface method
  'void kotlinx.coroutines.flow.MutableStateFlow.setValue(java.lang.Object)'
  on a null object reference
  at com.powermediaplayer.ui.player.PlayerViewModel.setVolumeBoost(PlayerViewModel.kt:775)
  at com.powermediaplayer.ui.player.PlayerViewModel$2$1.emit(PlayerViewModel.kt:150)
  at com.powermediaplayer.data.preferences.SettingsDataStore$special$$inlined$map$30$2.emit
```

**Cause:** `PlayerViewModel.init {}` (line 143) launches `viewModelScope.launch { settingsDataStore.volumeBoostMb.collect { mb -> setVolumeBoost(mb) } }`. The MutableStateFlow `_volumeBoostMb` was declared at line 769 — *after* the init block. On `Dispatchers.Main.immediate`, the coroutine can run far enough into its first emission *before* line 769's field initialiser runs, dereferencing a null `_volumeBoostMb`. Same race for `_pitch` (was at line 604, init coroutine at line 147).

**Why the Z Fold 6 didn't show this:** the emulator caught it on a fresh post-uninstall install where DataStore's first emission is faster (no warm-state delay). On the Z Fold 6 with a long-lived install + warm DataStore, the timing happens to favour constructor completion before the first emit. Race, not deterministic.

**Fix:** moved `private val _pitch = MutableStateFlow(1.0f)` and `private val _volumeBoostMb = MutableStateFlow(0)` to BEFORE the init block (just above line 143). The public `val pitch: StateFlow<Float>` / `val volumeBoostMb: StateFlow<Int>` façades stay where they were. This guarantees the backing fields are non-null before any init-block coroutine can reference them.

## F2 — Cast button tap force-closes (Z Fold 6)

**Symptom:**
```
java.lang.IllegalArgumentException: background can not be translucent: #0
  at androidx.core.graphics.ColorUtils.calculateContrast(ColorUtils.java:175)
  at androidx.mediarouter.app.MediaRouterThemeHelper.getControllerColor(MediaRouterThemeHelper.java:179)
  at androidx.mediarouter.app.MediaRouterThemeHelper.getRouterThemeId(MediaRouterThemeHelper.java:314)
  at androidx.mediarouter.app.MediaRouterThemeHelper.createThemedDialogStyle(MediaRouterThemeHelper.java:158)
  at androidx.mediarouter.app.MediaRouteChooserDialog.<init>(MediaRouteChooserDialog.java:142)
```

**Cause:** the prior fix (`d086c70`) wrapped the *button* construction with a non-translucent `Theme.AppCompat.NoActionBar` ContextThemeWrapper. That handled `MediaRouteButton.<init>`. But when the user taps the button, it constructs `MediaRouteChooserDialog` using the **Activity's** theme — `Theme.PowerMediaPlayer`, which inherits `android:Theme.Material.NoActionBar`. `MediaRouterThemeHelper.getControllerColor` resolves AppCompat's non-namespaced `colorPrimary` attribute; in a pure `android:Theme.Material` parent that attribute is undefined → TypedValue.data = 0 (`#0`) → `ColorUtils.calculateContrast(0, ...)` rejects with `background can not be translucent`.

**Fix:** changed `Theme.PowerMediaPlayer` parent from `android:Theme.Material.NoActionBar` to `Theme.AppCompat.NoActionBar` and added the AppCompat-namespaced `colorPrimary` / `colorPrimaryDark` / `colorAccent` + an explicit `android:colorBackground` / `android:windowBackground` of `oled_black`. MediaRouter's theme helper now resolves to a solid black background and the contrast check passes.

## F3 — Cast device discovery zero-result

**Cause:** **F3 was a SYMPTOM of F2, not a separate bug.** Discovery was working all along; the chooser dialog was crashing at construction before it could render the route list. With F2 fixed, the dialog opens cleanly and the user's two Cast devices ("Bedroom speaker", "Kabir's Kitchen Stereo") appear immediately on first tap. Verified visually via `phase2-step05-cast-chooser-zfold6.png`.

**This invalidates substrate D1-D6 from Phase 5** — they were all premature speculation about discovery filters / multicast locks / permissions. None applied because discovery never failed. The user's "no devices showing" report was an accurate observation of the chooser-dialog visual; the underlying cause was that the dialog never rendered.

## Substrates rejected with citations

| # | Substrate | Verdict | Why |
|---|---|---|---|
| L1 | `MainActivity` AppCompat theme requirement | **PARTIAL_TRUE for F2** | Required theme parent change; not a launch-crash issue. |
| L2 | EdgeToEdge / Android 15 API breakage | **REJECTED** | Crash trace doesn't cite it. |
| L3 | Hilt component-tree / DEX layout | **REJECTED** | Crash trace cites Room, not Hilt. |
| L4 | Media3 Cast init Android 15 contract | **REJECTED** | Crash trace doesn't reach Cast init. |
| L5 | Foreground service stricter policy | **REJECTED** | No FGS class in trace. |
| L6 | Notification channel One UI 7 | **REJECTED** | Crash precedes notification creation. |
| L7 | mediarouter manifest merger | **REJECTED** | App reaches Application.onCreate cleanly. |
| L8 | Data extraction rules XML | **REJECTED** | No BackupAgent in trace. |
| L9 | Compose JVM 21 bytecode | **REJECTED** | Crash is at runtime in our code, not VerifyError. |
| L10 | Play services Cast crash | **REJECTED** | Trace is in app code (Room + PlayerViewModel). |
| Lnull | Wrong APK on friend's device | **REJECTED** | The crash signature on the emulator with a fresh upgrade exactly matches user's report. |
| C1 | d086c70 fix didn't ship | **REJECTED** | Phone install timestamp matches commit time. |
| C2 | ContextThemeWrapper-Activity chain issue | **CONFIRMED for F2** | Dialog uses Activity theme directly, not button's wrapped theme. |
| C3-C6 | various other dialog-side issues | **REJECTED** | The single root C2 explains the trace. |
| D1-D6, Dnull | F3 discovery filtering / permissions / multicast | **NOT_APPLICABLE** | F3 was a UI symptom of F2, not a discovery problem. |

## Outstanding unknowns

- **Field-init-order timing on the Z Fold 6 vs the emulator.** The same code is in both APKs, but only the emulator reproduced F1-b. Most likely: `Dispatchers.Main.immediate` schedules the coroutine continuation differently when DataStore's underlying Preferences flow has a warm cached value (Z Fold 6) vs cold (emulator post-uninstall). Doesn't change the fix — declaring fields before init is correct regardless.
- **Friend's S25 Ultra hasn't received the new APK yet.** The fix is verified on the Android 16 emulator + Z Fold 6 (regression). The S25 Ultra should be the final confirmation; once the user shares the new APK we'll know if any Samsung-specific extras remain. None expected based on the crash signatures captured.
