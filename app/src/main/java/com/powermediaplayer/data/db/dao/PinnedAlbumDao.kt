package com.powermediaplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermediaplayer.data.db.entity.PinnedAlbumEntity
import com.powermediaplayer.data.db.entity.PinnedAlbumTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PinnedAlbumDao {

    @Query("SELECT * FROM pinned_albums ORDER BY pinOrder ASC")
    fun observeAll(): Flow<List<PinnedAlbumEntity>>

    @Query("SELECT * FROM pinned_albums ORDER BY pinOrder ASC")
    suspend fun snapshot(): List<PinnedAlbumEntity>

    @Query("SELECT COUNT(*) FROM pinned_albums")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: PinnedAlbumEntity): Long

    @Query("DELETE FROM pinned_albums WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pinned_albums SET pinOrder = :order WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrack(row: PinnedAlbumTrackEntity): Long

    @Query("SELECT * FROM pinned_album_tracks WHERE albumId = :albumId ORDER BY trackIndex ASC")
    fun observeTracks(albumId: Long): Flow<List<PinnedAlbumTrackEntity>>

    @Query("SELECT * FROM pinned_album_tracks WHERE albumId = :albumId ORDER BY trackIndex ASC")
    suspend fun snapshotTracks(albumId: Long): List<PinnedAlbumTrackEntity>
}
