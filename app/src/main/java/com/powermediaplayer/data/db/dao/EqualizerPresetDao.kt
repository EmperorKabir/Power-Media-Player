package com.powermediaplayer.data.db.dao

import androidx.room.*
import com.powermediaplayer.data.db.entity.EqualizerPresetEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for CRUD operations on equalizer presets.
 * Uses Flow for reactive UI updates.
 */
@Dao
interface EqualizerPresetDao {

    @Query("SELECT * FROM equalizer_presets ORDER BY isDefault DESC, name ASC")
    fun getAllPresets(): Flow<List<EqualizerPresetEntity>>

    @Query("SELECT * FROM equalizer_presets WHERE id = :id")
    suspend fun getPresetById(id: Long): EqualizerPresetEntity?

    @Query("SELECT * FROM equalizer_presets WHERE isDefault = 1")
    fun getDefaultPresets(): Flow<List<EqualizerPresetEntity>>

    @Query("SELECT * FROM equalizer_presets WHERE isDefault = 0")
    fun getUserPresets(): Flow<List<EqualizerPresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: EqualizerPresetEntity): Long

    @Update
    suspend fun updatePreset(preset: EqualizerPresetEntity)

    @Delete
    suspend fun deletePreset(preset: EqualizerPresetEntity)

    @Query("DELETE FROM equalizer_presets WHERE id = :id AND isDefault = 0")
    suspend fun deleteUserPresetById(id: Long)

    @Query("SELECT COUNT(*) FROM equalizer_presets")
    suspend fun getPresetCount(): Int
}
