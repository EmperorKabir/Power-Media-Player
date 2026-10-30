package com.powermediaplayer.ui.library

import android.Manifest
import android.os.Build

/**
 * LIB-PERM (2026-10-30, user: "surely that should be a popup when first launching in relevant
 * tabs"). The Library used ONE flag (READ_MEDIA_AUDIO) for both tabs and treated any single grant
 * as "granted", so a denied video permission showed "No video files found" with no way to ask.
 * Each tab now gates on its own permission and prompts for exactly what it is missing; the very
 * first open still asks for both at once (the original one-shot prompt).
 */
object LibraryPermissions {
    const val TAB_AUDIO = 0
    const val TAB_VIDEO = 1

    /** The runtime permission a tab reads with (API 33+ split; one storage permission before). */
    fun permissionFor(tab: Int, sdk: Int = Build.VERSION.SDK_INT): String = when {
        sdk < 33 -> Manifest.permission.READ_EXTERNAL_STORAGE
        tab == TAB_VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
        else -> Manifest.permission.READ_MEDIA_AUDIO
    }

    /**
     * What to request when [tab] is shown: nothing if that tab's permission is granted; both
     * media permissions on a fresh install (neither granted) so the user sees one prompt; else
     * only the one this tab lacks.
     */
    fun toRequest(tab: Int, hasAudio: Boolean, hasVideo: Boolean, sdk: Int = Build.VERSION.SDK_INT): Array<String> {
        if (sdk < 33) return if (hasAudio) emptyArray() else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val need = if (tab == TAB_VIDEO) !hasVideo else !hasAudio
        if (!need) return emptyArray()
        return if (!hasAudio && !hasVideo) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(permissionFor(tab, sdk))
        }
    }

    fun granted(tab: Int, hasAudio: Boolean, hasVideo: Boolean, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        if (sdk < 33 || tab != TAB_VIDEO) hasAudio else hasVideo

    fun title(tab: Int, sdk: Int = Build.VERSION.SDK_INT): String = when {
        sdk < 33 -> "Storage permission required"
        tab == TAB_VIDEO -> "Photos and videos permission required"
        else -> "Music and audio permission required"
    }
}
