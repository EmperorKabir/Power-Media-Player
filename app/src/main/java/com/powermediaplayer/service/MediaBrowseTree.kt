package com.powermediaplayer.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.repository.LastPlayedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Android Auto / Android Automotive browse tree served by
 * [PlaybackService]'s [androidx.media3.session.MediaLibraryService.MediaLibrarySession].
 *
 * The car (a MediaBrowserCompat client) walks this tree via onGetLibraryRoot →
 * onGetChildren(parentId); tapping a PLAYABLE leaf sends it back to the session, where
 * [PlaybackService]'s onAddMediaItems resolves a URI from `requestMetadata.mediaUri` (or
 * `Uri.parse(mediaId)`). So every leaf carries BOTH:
 *   - `mediaId` = the app's stable per-item key (unifies Recents/resume with the phone UI), and
 *   - `requestMetadata.mediaUri` = the actually-playable URI (a local copy when one exists).
 *
 * Sources are offline-safe (Room + MediaStore) so onGetChildren never blocks on the network:
 * Recently Played + Favourites ([LastPlayedRepository]), Songs/Albums/Artists (MediaStore),
 * and Podcasts ([PodcastDao]). Spotify (not ExoPlayer-playable) and Drive streaming are
 * intentionally out of this first tree.
 */
@Singleton
class MediaBrowseTree @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val lastPlayed: LastPlayedRepository,
    private val podcastDao: PodcastDao,
) {
    companion object {
        const val ROOT_ID = "pmp_root"
        private const val CAT_RECENT = "cat/recent"
        private const val CAT_FAVOURITES = "cat/favourites"
        private const val CAT_SONGS = "cat/songs"
        private const val CAT_ALBUMS = "cat/albums"
        private const val CAT_ARTISTS = "cat/artists"
        private const val CAT_PODCASTS = "cat/podcasts"
        private const val PREFIX_ALBUM = "album/"
        private const val PREFIX_ARTIST = "artist/"
        private const val PREFIX_SHOW = "show/"

        private const val ALBUM_ART_BASE = "content://media/external/audio/albumart"
        private const val MAX_RECENTS = 50
    }

    private data class BrowseTrack(
        val uri: Uri,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: Long,
        val durationMs: Long,
        val artUri: Uri?,
    )

    // Short-lived cache so a single car browse session (songs → albums → album → …)
    // doesn't re-run the MediaStore query for every node. Invalidated after TTL.
    @Volatile private var audioCache: List<BrowseTrack>? = null
    @Volatile private var audioCacheAtMs: Long = 0L
    private val cacheTtlMs = 15_000L

    /** The browsable root item. */
    fun root(): MediaItem = browsable(
        ROOT_ID, "Power Media Player", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    )

    /**
     * Children of [parentId]. Honours [page]/[pageSize] for the large leaf lists so a huge
     * library can't overflow the browser IPC. Returns an empty list for an unknown id.
     */
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val result = childrenInternal(parentId, page, pageSize)
        com.powermediaplayer.diag.DiagLog.event(
            "AABROWSE", "children parent=$parentId page=$page size=$pageSize → ${result.size} items"
        )
        return result
    }

    private suspend fun childrenInternal(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        return when {
            parentId == ROOT_ID -> rootCategories()
            parentId == CAT_RECENT -> recents()
            parentId == CAT_FAVOURITES -> favourites()
            parentId == CAT_SONGS -> paged(songs(), page, pageSize)
            parentId == CAT_ALBUMS -> paged(albums(), page, pageSize)
            parentId == CAT_ARTISTS -> paged(artists(), page, pageSize)
            parentId == CAT_PODCASTS -> podcastShows()
            parentId.startsWith(PREFIX_ALBUM) ->
                paged(albumTracks(parentId.removePrefix(PREFIX_ALBUM)), page, pageSize)
            parentId.startsWith(PREFIX_ARTIST) ->
                paged(artistTracks(parentId.removePrefix(PREFIX_ARTIST)), page, pageSize)
            parentId.startsWith(PREFIX_SHOW) ->
                paged(showEpisodes(parentId.removePrefix(PREFIX_SHOW)), page, pageSize)
            else -> emptyList()
        }
    }

    /** Best-effort single-item lookup (used for playback resumption / metadata by id). */
    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID -> root()
        mediaId.startsWith("cat/") || mediaId.startsWith(PREFIX_ALBUM) ||
            mediaId.startsWith(PREFIX_ARTIST) || mediaId.startsWith(PREFIX_SHOW) ->
            browsable(mediaId, mediaId.substringAfterLast('/'), null,
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        // A playable leaf: its mediaId is already its stable/playable key.
        runCatching { Uri.parse(mediaId) }.getOrNull() != null ->
            playable(mediaId, Uri.parse(mediaId), mediaId.substringAfterLast('/'), "", "", null)
        else -> null
    }

    // ---- categories ---------------------------------------------------------

    private fun rootCategories(): List<MediaItem> = listOf(
        browsable(CAT_RECENT, "Recently played", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browsable(CAT_FAVOURITES, "Favourites", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browsable(CAT_SONGS, "Songs", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browsable(CAT_ALBUMS, "Albums", null, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browsable(CAT_ARTISTS, "Artists", null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        browsable(CAT_PODCASTS, "Podcasts", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
    )

    private suspend fun recents(): List<MediaItem> =
        runCatching { lastPlayed.observeDynamic().first() }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.source != LastPlayedRepository.Source.SPOTIFY }
            .filter { it.mediaUri.isNotBlank() }
            .take(MAX_RECENTS)
            .map { historyToItem(it) }
            .toList()

    private suspend fun favourites(): List<MediaItem> =
        runCatching { lastPlayed.observePinned().first() }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.source != LastPlayedRepository.Source.SPOTIFY }
            .filter { it.mediaUri.isNotBlank() }
            .map { historyToItem(it) }
            .toList()

    private fun historyToItem(h: LastPlayedRepository.HistoryItem): MediaItem {
        val play = runCatching { Uri.parse(h.mediaUri) }.getOrNull() ?: Uri.EMPTY
        return playable(
            mediaId = h.mediaUri,
            playUri = play,
            title = h.title,
            artist = h.subtitle,
            album = "",
            artUri = h.artworkUri?.let { runCatching { Uri.parse(it) }.getOrNull() },
        )
    }

    private fun songs(): List<MediaItem> = localAudio().map { trackToItem(it) }

    private fun albums(): List<MediaItem> =
        localAudio()
            .groupBy { albumKey(it.artist, it.album) }
            .map { (key, tracks) ->
                val first = tracks.first()
                browsable(
                    PREFIX_ALBUM + key,
                    first.album.ifBlank { "Unknown album" },
                    first.artUri,
                    MediaMetadata.MEDIA_TYPE_ALBUM,
                    subtitle = first.artist,
                )
            }
            .sortedBy { it.mediaMetadata.title?.toString()?.lowercase() }

    private fun albumTracks(key: String): List<MediaItem> =
        localAudio().filter { albumKey(it.artist, it.album) == key }.map { trackToItem(it) }

    private fun artists(): List<MediaItem> =
        localAudio()
            .groupBy { it.artist.ifBlank { "Unknown artist" } }
            .map { (artist, _) ->
                browsable(PREFIX_ARTIST + artist, artist, null, MediaMetadata.MEDIA_TYPE_ARTIST)
            }
            .sortedBy { it.mediaMetadata.title?.toString()?.lowercase() }

    private fun artistTracks(artist: String): List<MediaItem> =
        localAudio().filter { it.artist.ifBlank { "Unknown artist" } == artist }
            .map { trackToItem(it) }

    private suspend fun podcastShows(): List<MediaItem> =
        runCatching { podcastDao.observeShows().first() }.getOrDefault(emptyList())
            .map { show ->
                browsable(
                    PREFIX_SHOW + show.feedUrl,
                    show.title.ifBlank { "Podcast" },
                    show.artworkUrl?.let { runCatching { Uri.parse(it) }.getOrNull() },
                    MediaMetadata.MEDIA_TYPE_PODCAST,
                )
            }

    private suspend fun showEpisodes(feedUrl: String): List<MediaItem> {
        val show = runCatching { podcastDao.observeShows().first() }.getOrDefault(emptyList())
            .firstOrNull { it.feedUrl == feedUrl }
        val art = show?.artworkUrl
        return runCatching { podcastDao.observeEpisodes(feedUrl).first() }
            .getOrDefault(emptyList())
            .map { ep ->
                val keyUri = runCatching { Uri.parse(ep.audioUrl) }.getOrNull() ?: Uri.EMPTY
                val playUri = ep.localPath?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: keyUri
                MediaItem.Builder()
                    .setMediaId(ep.audioUrl)
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder().setMediaUri(playUri).build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(ep.title)
                            .setArtist(show?.title ?: "")
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                            .apply {
                                art?.let { setArtworkUri(runCatching { Uri.parse(it) }.getOrNull()) }
                            }
                            .setExtras(Bundle().apply { putBoolean("is_podcast", true) })
                            .build()
                    )
                    .build()
            }
    }

    // ---- MediaStore ---------------------------------------------------------

    private fun localAudio(): List<BrowseTrack> {
        val cached = audioCache
        if (cached != null && System.currentTimeMillis() - audioCacheAtMs < cacheTtlMs) {
            return cached
        }
        val out = ArrayList<BrowseTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        runCatching {
            // selection = null to match the app's own Library (LibraryViewModel.scanAudioFiles
            // queries with no selection) — an is_music filter would hide untagged local files
            // the user sees in the Library tab.
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idC)
                    val albumId = c.getLong(albumIdC)
                    out.add(
                        BrowseTrack(
                            uri = ContentUris.withAppendedId(collection, id),
                            title = c.getString(titleC) ?: "",
                            artist = c.getString(artistC)?.takeIf { it != "<unknown>" } ?: "",
                            album = c.getString(albumC) ?: "",
                            albumId = albumId,
                            durationMs = c.getLong(durC),
                            artUri = ContentUris.withAppendedId(
                                Uri.parse(ALBUM_ART_BASE), albumId
                            ),
                        )
                    )
                }
            }
        }
        audioCache = out
        audioCacheAtMs = System.currentTimeMillis()
        return out
    }

    private fun albumKey(artist: String, album: String): String =
        "${artist.lowercase()}|||${album.lowercase()}"

    private fun trackToItem(t: BrowseTrack): MediaItem =
        playable(t.uri.toString(), t.uri, t.title, t.artist, t.album, t.artUri, t.durationMs)

    // ---- builders -----------------------------------------------------------

    private fun browsable(
        id: String,
        title: String,
        artUri: Uri?,
        mediaType: Int,
        subtitle: String? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .apply {
                    subtitle?.let { setSubtitle(it); setArtist(it) }
                    artUri?.let { setArtworkUri(it) }
                }
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build()
        )
        .build()

    private fun playable(
        mediaId: String,
        playUri: Uri,
        title: String,
        artist: String,
        album: String,
        artUri: Uri?,
        durationMs: Long? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setMediaUri(playUri).build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .apply {
                    if (album.isNotBlank()) setAlbumTitle(album)
                    artUri?.let { setArtworkUri(it) }
                    durationMs?.takeIf { it > 0 }?.let { setDurationMs(it) }
                }
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    private fun paged(all: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0) return all
        val from = page * pageSize
        if (from >= all.size) return emptyList()
        return all.subList(from, minOf(from + pageSize, all.size))
    }
}
