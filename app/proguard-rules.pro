# ProGuard rules for Power Media Player

# Media3 — extensive reflection in extractors, renderers, sessions
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# FFmpegMediaMetadataRetriever — JNI bindings

# Hilt — generated injection code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room — entities, DAOs, generated _Impl classes
-keep class com.powermediaplayer.data.db.entity.** { *; }
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Coil 3 — reflection-driven image loaders + decoders
-keep class coil3.** { *; }
-dontwarn coil3.**
# (coil3 also exposes io.coil_kt.* paths in some artefacts)
-keep class io.coil_kt.** { *; }
-dontwarn io.coil_kt.**

# OkHttp — internal reflection on platform classes
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# AppAuth — OAuth library uses reflection on response parsers
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# Gson — preserve generic signatures + annotations
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Cast — Google Play Services Cast framework reflection
-keep class com.google.android.gms.cast.** { *; }
-dontwarn com.google.android.gms.cast.**

# Spotify App Remote + Auth SDK (I4d precise stop-at-end). The protocol layer
# reflects over its gson/jackson mappers + connection binder; keep the SDK classes.
# The App Remote AAR references an OPTIONAL Jackson mapper path (we ship gson, not
# jackson) + a compile-time Spotify annotation — both absent at runtime, so dontwarn
# them. Without these R8 fails minifyRelease (device-proven 2026-08-25: missing
# StdDeserializer/StdSerializer/NotNull) and the Play upload build breaks.
-keep class com.spotify.android.appremote.** { *; }
-keep class com.spotify.protocol.** { *; }
-keep class com.spotify.sdk.android.auth.** { *; }
-dontwarn com.spotify.**
-dontwarn com.fasterxml.jackson.**

# Media3 FFmpeg extension (fallback-only, EXTENSION_RENDERER_MODE_ON).
# DefaultRenderersFactory loads FfmpegAudioRenderer by REFLECTION, so its
# constructor must survive minification. Harmless while the extension is not
# bundled (R8 ignores a keep for an absent class); active once the native
# media3-decoder-ffmpeg .so libraries are added via a local NDK build.
-dontnote androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
-keepclassmembers class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}
-dontwarn androidx.media3.decoder.ffmpeg.**
