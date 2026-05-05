package com.powermediaplayer.util

import com.powermediaplayer.BuildConfig

/**
 * Logging gate. All app code routes through these inline wrappers
 * instead of `android.util.Log.*` directly so release builds emit
 * nothing to logcat. The functions are `inline` and the
 * `BuildConfig.DEBUG` check is a compile-time constant — R8 strips
 * each call entirely in release builds (no string allocation, no
 * IPC into logd).
 *
 * Why a wrapper instead of letting R8 strip Log calls via
 * `-assumenosideeffects`: assumenosideeffects is a global rule that
 * affects every Log.* in every dependency too, including some that
 * legitimately use Log to surface errors users care about. The
 * targeted wrapper only strips OUR app code.
 */
@Suppress("NOTHING_TO_INLINE")
object Diag {
    inline fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.i(tag, msg)
    }

    inline fun w(tag: String, msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.w(tag, msg)
    }

    inline fun w(tag: String, msg: String, t: Throwable) {
        if (BuildConfig.DEBUG) android.util.Log.w(tag, msg, t)
    }

    inline fun e(tag: String, msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.e(tag, msg)
    }

    inline fun e(tag: String, msg: String, t: Throwable) {
        if (BuildConfig.DEBUG) android.util.Log.e(tag, msg, t)
    }

    inline fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(tag, msg)
    }
}
