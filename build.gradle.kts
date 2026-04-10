// Top-level build file for Power Media Player
plugins {
    id("com.android.application") version "9.1.0" apply false
    // Kotlin 2.1.20 + KSP 2.1.20-1.0.32 is a verified stable pairing.
    // KSP version format: {kotlin-version}-{ksp-release}
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
}
