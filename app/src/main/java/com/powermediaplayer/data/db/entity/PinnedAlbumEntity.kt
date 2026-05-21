package com.powermediaplayer.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A pinned album snapshot. Lives in the same unified 10-pin cap as
 * [HistoryFavouriteEntity] track pins (enforced in the repository
 * layer). Pin time captures album metadata + the member-track list so
 * later track-library churn doesn't break the pin.
 *
 * The user-visible behaviour (locked by design decision earlier):
 * - Tap album row → expand member tracks (does NOT auto-play)
 * - Tap a member track → play that one
 * - Long-press album row → unpin
 *
 * Member tracks live in [PinnedAlbumTrackEntity] with FK CASCADE so
 * deleting an album drops its track snapshot.
 */
@Entity(
    tableName = "pinned_albums",
    indices = [Index("pinOrder"), Index("albumKey")]
)
data class PinnedAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Library-side album key — `artist|||album` lowercased. Used to
     *  disambiguate same-title albums from different artists. */
    val albumKey: String,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val pinOrder: Int,
    val pinnedAtMs: Long
)

@Entity(
    tableName = "pinned_album_tracks",
    foreignKeys = [
        ForeignKey(
            entity = PinnedAlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("albumId")]
)
data class PinnedAlbumTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumId: Long,
    val mediaUri: String,
    val title: String,
    val durationMs: Long,
    val trackIndex: Int
)
