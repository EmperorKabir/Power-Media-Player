import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")   // §8.6 (G1)
}

// Read OAuth credentials from local.properties (gitignored).
// Falling back to empty strings keeps the build green when keys are missing —
// runtime auth will fail with a clear error instead of leaking placeholders
// into source control.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}
fun localProp(key: String): String = localProps.getProperty(key) ?: ""

android {
    namespace = "com.powermediaplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.powermediaplayer"
        minSdk = 30
        // 2026-07-22: Play policy — target API 36 by 31 Aug 2026 or updates
        // are rejected. Behaviour-change review for 36: edge-to-edge opt-out
        // removed (we already run enableEdgeToEdge + Compose insets), no
        // orientation locks to trip the large-screen rule, no native .so
        // (16 KB page rule moot), WorkManager unaffected.
        targetSdk = 36
        versionCode = 71
        versionName = "1.5.16"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OAuth credentials surfaced to code via BuildConfig.* — never committed.
        buildConfigField("String", "GDRIVE_ANDROID_CLIENT_ID", "\"${localProp("GDRIVE_ANDROID_CLIENT_ID")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProp("SPOTIFY_CLIENT_ID")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"${localProp("SPOTIFY_REDIRECT_URI")}\"")
        // (DRIVE_PICKER_APP_ID / DRIVE_PICKER_API_KEY removed 2026-07-25 —
        //  the WebView JS Picker they fed was deleted for a native folder
        //  browser; no BuildConfig.DRIVE_PICKER_* consumer remains.)
        // §C9 LOCKED — OpenSubtitles app-identifying API key. The key
        // identifies our APP, not the user; per-user login uses a
        // separate token. Empty default means the auto-fetcher
        // disables itself silently when the dev hasn't set the key
        // in local.properties.
        buildConfigField("String", "OPENSUBS_API_KEY", "\"${localProp("OPENSUBS_API_KEY")}\"")

        // AppAuth needs the redirect URI scheme registered as a manifest
        // placeholder so its RedirectUriReceiverActivity can be auto-merged.
        manifestPlaceholders["appAuthRedirectScheme"] = "powermediaplayer"
    }

    // Release signing configured from local.properties (gitignored). When
    // RELEASE_STORE_FILE is missing the release signingConfig is omitted, so
    // `bundleRelease` builds an unsigned AAB instead of failing — useful in CI
    // and for contributors who don't have the keystore.
    signingConfigs {
        val storeFilePath = localProp("RELEASE_STORE_FILE")
        if (storeFilePath.isNotBlank()) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = localProp("RELEASE_STORE_PASSWORD")
                keyAlias = localProp("RELEASE_KEY_ALIAS")
                keyPassword = localProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Attach the release signingConfig only if it was created above.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // §91 — bundle native debug symbols (Media3 ExoPlayer
            // extensions, Cast SDK, NanoHTTPD JNI) into the AAB so
            // Play Console can symbolicate native crash / ANR stack
            // traces. SYMBOL_TABLE keeps the AAB small (vs FULL which
            // ships entire .debug sections); sufficient for symbol
            // resolution.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        debug {
            isMinifyEnabled = false
            // 2026-07-25: BASE-PACKAGE debug build (NO .test suffix) signed with the
            // DEFAULT debug key (SHA-1 BD:78…). This gives it the SAME app identity
            // as the OAuth client "Power Media Player" (com.powermediaplayer + debug
            // SHA-1), so the embedded Drive Picker accepts its token and grants file
            // access. The .test package could NOT: the Picker binds file grants to
            // the project's base package, so a different-package build's picks never
            // grant (device-proven — token aud = the .test client, files.get 404 even
            // after a dedicated unique signing key). This build REPLACES the Play app
            // on the device (same package); per user request it is the standard test
            // build from now on. Fresh, separate data on install.
            versionNameSuffix = "-test"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // kotlin-android plugin (required by AGP 8.x) defaults Kotlin's
    // jvmTarget to whatever JDK runs Gradle (here: 21). AGP's Java
    // compile task is locked to 17 above; mismatching them makes
    // kspDebugKotlin fail with "Inconsistent JVM-target compatibility".
    // Pinning to 17 here keeps both halves in lockstep.
    kotlinOptions {
        jvmTarget = "17"
    }



    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Audit §2.1: export Room schemas so the 22-version hand-written migration
    // chain becomes testable (MigrationTestHelper) and future bumps are diffable.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    // Robolectric: enable Android-stub default values so unit tests
    // can construct things like android.net.Uri without explicit
    // Robolectric setup. Tests that genuinely need Robolectric (e.g.
    // Uri.parse) annotate with @RunWith(RobolectricTestRunner).
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    // §A17 — release build verification. AGP 8.7.x bundles a Lint
    // detector (NonNullableMutableLiveDataDetector) that throws
    // IncompatibleClassChangeError when run against this project
    // because of a Kotlin-bytecode visitor incompatibility. The
    // compiled APK is unaffected; only Lint's UAST analyzer crashes.
    // Per-PR lint runs still happen via lintDebug.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                // BouncyCastle 1.78.1 ships duplicate OSGi manifests
                // across its jdk18on artefacts (bctls + bcprov +
                // bcutil). We don't ship as an OSGi bundle so the
                // manifests are noise; excluding them lets the merge
                // step pass cleanly.
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/OSGI-INF/**"
            )
        }
    }
}

dependencies {
    // ── android-deep-logger (debug-only forensic NDJSON logger) ──
    // App Startup auto-installs DeepLogger at process start via the
    // debug manifest. Release builds link the src/release no-op and
    // never pull this in.
    debugImplementation("androidx.startup:startup-runtime:1.1.1")

    // ── Compose BOM ──────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Specific icon groups from material-icons — avoids the full 5MB extended bundle.
    // icons-core = base icons (Play, Pause, Settings, etc.)
    // Extended icons needed for FastForward/FastRewind/FileOpen etc.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3-window-size-class")
    // ── Adaptive layouts (8.1/8.2) ──────────────────────────────
    // Navigation suite (bar↔rail by width) is BOM-managed; the pane/
    // posture artifacts live in their own group. Posture (isTabletop,
    // hinge bounds) wraps androidx.window — no hand-rolled
    // WindowInfoTracker needed.
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material3.adaptive:adaptive:1.1.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.1.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.1.0")
    // Direct WindowInfoTracker access for the fold-posture diagnostic
    // logger (same source material3-adaptive reads transitively).
    implementation("androidx.window:window:1.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ── Reorderable lists (Last Played favourites drag-to-reorder) ──
    implementation("sh.calvin.reorderable:reorderable:2.5.0")

    // ── Baseline profile (§8.6 / G1) ─────────────────────────────
    // profileinstaller embeds + installs the generated profile at
    // first run; the :baselineprofile module generates it.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))

    // ── Core AndroidX ────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    // ProcessLifecycleOwner — pauses the Spotify mirror poll 30s after
    // the app backgrounds (audit 3.8/8.7).
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // ── Media3 ExoPlayer ─────────────────────────────────────────
    val media3Version = "1.6.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-cast:$media3Version")
    // Chromecast: Cast SDK + MediaRouter (button in player UI)
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    // FFmpeg extension - requires local build from androidx/media repo
    // implementation("androidx.media3:media3-decoder-ffmpeg:$media3Version")

    // ── FFmpeg Metadata Retriever ────────────────────────────────
    // NOTE: FFmpegMediaMetadataRetriever has namespace conflicts with AGP 9.
    // Deep Scan mode falls back to Android's built-in MediaMetadataRetriever.
    // To enable: resolve namespace conflict or use local AAR build.


    // ── Hilt Dependency Injection ────────────────────────────────
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-android-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // ── Room Database ────────────────────────────────────────────
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ── DataStore Preferences ────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // ── Coil Image Loading ───────────────────────────────────────
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    // Network fetcher — required for Coil 3 to load HTTP/HTTPS URLs
    // (e.g. Spotify CDN album art at https://i.scdn.co/image/...).
    // Without this, AsyncImage fails with
    // "Unable to create a fetcher that supports: https://..."
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")
    // Video-frame thumbnails for LOCAL library video rows (#12). Pin to the same
    // 3.1.0 as coil-compose/coil-network-okhttp above — do NOT bump.
    implementation("io.coil-kt.coil3:coil-video:3.1.0")

    // ── Palette API ──────────────────────────────────────────────
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ── Coroutines ───────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // ── Google Guava (for MediaController ListenableFuture) ─────
    implementation("com.google.guava:guava:33.4.0-android")

    // ── Gson for JSON serialization ──────────────────────────────
    implementation("com.google.code.gson:gson:2.12.1")

    // ── Cloud / OAuth ────────────────────────────────────────────
    // AppAuth: PKCE OAuth flows with Custom Tabs (Spotify).
    implementation("net.openid:appauth:0.11.1")
    // OkHttp: Spotify + Drive REST HTTP client.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // BouncyCastle TLS — DTLS-PSK for the Philips Hue Entertainment API
    // (audio-reactive lighting). The bridge's UDP entertainment endpoint
    // requires DTLS 1.2 with pre-shared key, which Android's stock JSSE
    // does not expose for UDP. bctls bundles a UDP-capable DTLS stack.
    implementation("org.bouncycastle:bctls-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // NanoHTTPD: tiny embedded HTTP server (~50KB) used to relay
    // local / SAF / Drive content to Cast receivers. Receiver fetches
    // http://<phone-LAN-IP>:<port>/<token> from the local network; the
    // relay forwards Range requests with the appropriate auth back to
    // the original source on the phone side.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Storage Access Framework — kept for OneDrive / Dropbox / USB /
    // local-storage flow on devices whose SAF picker exposes those
    // sources. (Drive uses its own JS Picker via WebView since the
    // SAF picker hides Drive on some Samsung / fold builds.)
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Google Sign-In + drive.file OAuth — Google's official SDK
    // returns OAuth tokens scoped to specific files/folders the user
    // picks via the Drive Picker. No Google verification required
    // because drive.file is non-sensitive.
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // ── §C10 Podcast auto-sync via WorkManager + Hilt ────────────
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // ── §C9 LOCKED — EncryptedSharedPreferences for OpenSubs creds.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── Testing ──────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
