// §8.6 (G1) — baseline profile generator module.
// A com.android.test module that drives the app on a managed device and
// records a startup baseline profile, embedded into the release build.
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.powermediaplayer.baselineprofile"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        minSdk = 30
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // AOSP managed device — baseline-profile capture needs a profileable
    // (non-Play-store) image. Gradle downloads it on first run.
    testOptions.managedDevices.devices {
        create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

baselineProfile {
    // Local generation runs on the connected emulator only. The AOSP
    // managed device (declared above for CI / hardware-GPU hosts) returns
    // empty gfxinfo framestats on this headless software-GPU host, which
    // breaks the macrobenchmark's amStartAndWait launch confirmation. The
    // generator grants runtime permissions before launch so the activity
    // draws frames immediately (no blocking permission dialog).
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
