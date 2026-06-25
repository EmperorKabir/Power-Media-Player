package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §C28 LOCKED — Drive offline copies. Replaces the earlier DataStore
 * Set<String> path so we can track byteSize for LRU eviction at the
 * user-configured storage limit.
 */
// #16 — displayName indexed to back the enriched-metadata Drive search.
@Entity(tableName = "offline_copy", indices = [Index("displayName")])
data class OfflineCopyEntity(
    @PrimaryKey
    val driveFileId: String,
    val localPath: String,
    val byteSize: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = System.currentTimeMillis(),
    val isStarred: Boolean = false,
    // The Drive file's real name, so the Downloads list shows "This Inevitable
    // Ruin.m4b" not the opaque cache filename. Blank for pre-v18 rows.
    val displayName: String = ""
)
