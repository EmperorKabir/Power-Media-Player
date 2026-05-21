import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.powermediaplayer"
        minSdk = 30
        targetSdk = 35
        versionCode = 24
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OAuth credentials surfaced to code via BuildConfig.* — never committed.
        buildConfigField("String", "GDRIVE_ANDROID_CLIENT_ID", "\"${localProp("GDRIVE_ANDROID_CLIENT_ID")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProp("SPOTIFY_CLIENT_ID")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"${localProp("SPOTIFY_REDIRECT_URI")}\"")
        // Drive Picker — JS Picker requires (a) the GCP project number
        // ("App ID") and (b) a Google API Key restricted to Picker API.
        // Both are public-by-design (the API key is restricted by API
        // and origin, not by secrecy).
        buildConfigField("String", "DRIVE_PICKER_APP_ID", "\"${localProp("DRIVE_PICKER_APP_ID")}\"")
        buildConfigField("String", "DRIVE_PICKER_API_KEY", "\"${localProp("DRIVE_PICKER_API_KEY")}\"")
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
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3-window-size-class")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ── Reorderable lists (Last Played favourites drag-to-reorder) ──
    implementation("sh.calvin.reorderable:reorderable:2.5.0")

    // ── Core AndroidX ────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
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

    // ── Palette API ──────────────────────────────────────────────
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ── Coroutines ───────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")

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
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
