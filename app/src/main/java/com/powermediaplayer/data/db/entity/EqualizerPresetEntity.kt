package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for storing equalizer presets (both default and user-created).
 * Band levels are stored as a JSON array string of 10 integer values (millibel).
 */
@Entity(tableName = "equalizer_presets")
data class EqualizerPresetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false,
    val bandLevels: String, // JSON array: "[0, 0, 0, 0, 0, 0, 0, 0, 0, 0]"
    val createdAt: Long = System.currentTimeMillis()
)
