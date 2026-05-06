// Top-level build file for Power Media Player
plugins {
    id("com.android.application") version "8.7.3" apply false
    // Kotlin 2.1.20 + KSP 2.1.20-1.0.32 is a verified stable pairing.
    // KSP version format: {kotlin-version}-{ksp-release}
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
}
