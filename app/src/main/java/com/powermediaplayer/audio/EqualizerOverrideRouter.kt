package com.powermediaplayer.audio

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.powermediaplayer.data.db.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * §C7 / A10.2 — applies a per-file EQ override by resolving the
 * preset row + writing its band levels into the live
 * [EqualizerEffectController]. Routed through this object to keep
 * the wiring out of PlayerViewModel — Hilt @EntryPoint pulls the
 * dao + controller from the singleton component without us injecting
 * them into every ViewModel that needs the override.
 */
object EqualizerOverrideRouter {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): AppDatabase
        fun eq(): EqualizerEffectController
    }

    private val gson = Gson()
    private val type = object : TypeToken<List<Int>>() {}.type

    suspend fun applyPresetForUri(context: Context, presetId: Long) {
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext, Deps::class.java
        )
        val preset = deps.database().equalizerPresetDao().getPresetById(presetId) ?: return
        val levels: List<Int> = runCatching { gson.fromJson<List<Int>>(preset.bandLevels, type) }
            .getOrDefault(emptyList())
        if (levels.isEmpty()) return
        // #9 — the per-track override IS the deliberate, audio-affecting live
        // value; mark ownership so a later VM construction (settings-expand)
        // sees a non-DEFAULT source and leaves it alone.
        deps.eq().setLive(levels, EqualizerEffectController.EqSource.LIVE_OVERRIDE)
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "C7 EQ override applied preset=${preset.name} (id=$presetId)"
        )
    }
}
