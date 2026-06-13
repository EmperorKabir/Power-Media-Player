package com.powermediaplayer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §8.6 (G1) — startup baseline profile. Exercises cold launch + the two
 * most-trafficked tab switches (Library, Settings) so ART can AOT-compile
 * the hot startup + navigation paths, cutting first-run jank.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect("com.powermediaplayer") {
        // Grant media + notification permissions up front so the cold
        // launch goes straight to content. Without this the first-run
        // runtime-permission dialog blocks the activity from producing
        // frames, and the macrobenchmark's amStartAndWait can't confirm
        // launch (empty gfxinfo framestats).
        listOf(
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach { perm ->
            runCatching {
                device.executeShellCommand("pm grant com.powermediaplayer $perm")
            }
        }
        pressHome()
        startActivityAndWait()
        device.findObject(By.text("Library"))?.click()
        device.waitForIdle()
        device.findObject(By.text("Settings"))?.click()
        device.waitForIdle()
    }
}
