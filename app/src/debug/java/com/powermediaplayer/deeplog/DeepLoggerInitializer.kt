/*
 * DeepLoggerInitializer.kt — DEBUG source-set only. App Startup auto-install.
 *
 * Installed via the android-deep-logger skill. androidx.startup runs this at
 * process start (declared in src/debug/AndroidManifest.xml) with NO edit to
 * PowerMediaPlayerApp — LeakCanary-style auto-install. Debug builds only;
 * release links the no-op DeepLogger and this file is not compiled.
 *
 * Requires (debug only):
 *   debugImplementation("androidx.startup:startup-runtime:1.1.1")
 */
package com.powermediaplayer.deeplog

import android.app.Application
import android.content.Context
import androidx.startup.Initializer
import com.powermediaplayer.BuildConfig

class DeepLoggerInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext as? Application ?: return
        // Feed build identity into every NDJSON entry's `ctx` field so a
        // pulled session is self-identifying (which vc produced it).
        DeepLogger.setContextSupplier {
            mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "buildType" to BuildConfig.BUILD_TYPE
            )
        }
        DeepLogger.install(app)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
