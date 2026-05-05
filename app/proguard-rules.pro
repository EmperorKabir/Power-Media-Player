# ProGuard rules for Power Media Player

# Media3 — extensive reflection in extractors, renderers, sessions
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# FFmpegMediaMetadataRetriever — JNI bindings
-keep class wseemann.media.** { *; }
-dontwarn wseemann.media.**

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
