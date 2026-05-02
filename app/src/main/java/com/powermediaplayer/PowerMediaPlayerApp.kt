package com.powermediaplayer

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class annotated for Hilt dependency injection.
 * This is the entry point for the Hilt component hierarchy.
 *
 * Also installs an uncaught exception handler that LOGS the full stack
 * trace before delegating to the platform default — without this, "app
 * keeps crashing" / "Clear cache?" dialogs leave us nothing to debug.
 * Filter logcat: `adb logcat -s PowerMediaPlayer:V *:S`.
 */
@HiltAndroidApp
class PowerMediaPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("PowerMediaPlayer",
                "FATAL on thread ${thread.name}: ${throwable.javaClass.simpleName}: " +
                    "${throwable.message}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
