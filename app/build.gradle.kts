import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
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
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OAuth credentials surfaced to code via BuildConfig.* — never committed.
        buildConfigField("String", "GDRIVE_ANDROID_CLIENT_ID", "\"${localProp("GDRIVE_ANDROID_CLIENT_ID")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProp("SPOTIFY_CLIENT_ID")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"${localProp("SPOTIFY_REDIRECT_URI")}\"")

        // AppAuth needs the redirect URI scheme registered as a manifest
        // placeholder so its RedirectUriReceiverActivity can be auto-merged.
        manifestPlaceholders["appAuthRedirectScheme"] = "powermediaplayer"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    buildFeatures {
        compose = true
        buildConfig = true
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
                "META-INF/*.kotlin_module"
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
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
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

    // ── Text Recognition (ML Kit v2) ─────────────────────────────
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // ── Google Guava (for MediaController ListenableFuture) ─────
    implementation("com.google.guava:guava:33.4.0-android")

    // ── Gson for JSON serialization ──────────────────────────────
    implementation("com.google.code.gson:gson:2.12.1")

    // ── Cloud / OAuth ────────────────────────────────────────────
    // AppAuth: PKCE OAuth flows with Custom Tabs (Spotify, Drive token refresh)
    implementation("net.openid:appauth:0.11.1")
    // OkHttp: Spotify Web API HTTP client + Drive streaming DataSource
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Google Sign-In + Drive REST v3
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.api-client:google-api-client-android:2.7.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20240914-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-gson:1.45.0") {
        exclude(group = "org.apache.httpcomponents")
    }

    // ── Testing ──────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
