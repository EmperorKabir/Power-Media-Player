// Top-level build file for Power Media Player
plugins {
    id("com.android.application") version "8.10.1" apply false
    // Kotlin 2.1.20 + KSP 2.1.20-1.0.32 is a verified stable pairing.
    // KSP version format: {kotlin-version}-{ksp-release}
    // kotlin-android is required because AGP 8.x does NOT have the
    // "built-in Kotlin support" of AGP 9.x — without this plugin the
    // Kotlin classes land in built_in_kotlinc/debug/... which AGP 8.x
    // does not include in the DEX, producing a silent "compile-success
    // / runtime ClassNotFoundException" failure.
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.dagger.hilt.android") version "2.54" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
    // §8.6 (G1) — baseline profile generation. 1.3.4 pairs with
    // benchmark 1.3.4 + AGP 8.7.x (verified family).
    id("androidx.baselineprofile") version "1.3.4" apply false
}
