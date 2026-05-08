package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * §C28 LOCKED — Drive offline copies. Replaces the earlier DataStore
 * Set<String> path so we can track byteSize for LRU eviction at the
 * user-configured storage limit.
 */
@Entity(tableName = "offline_copy")
data class OfflineCopyEntity(
    @PrimaryKey
    val driveFileId: String,
    val localPath: String,
    val byteSize: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = System.currentTimeMillis(),
    val isStarred: Boolean = false
)
