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
import kotlinx.coroutines.launch
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
    private val favoriteDao: com.powermediaplayer.data.db.dao.FavoriteDao,
    @param:com.powermediaplayer.di.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope,
) {
    companion object {
        const val ROOT_ID = "pmp_root"
        const val CAT_RECENT = "cat/recent"
        const val CAT_FAVOURITES = "cat/favourites"
        private const val CAT_LIBRARY = "cat/library"
        private const val CAT_SONGS = "cat/songs"
        private const val CAT_ALBUMS = "cat/albums"
        private const val CAT_ARTISTS = "cat/artists"
        private const val CAT_PODCASTS = "cat/podcasts"
        private const val CAT_BOOKS = "cat/books"
        private const val PREFIX_ALBUM = "album/"
        private const val PREFIX_ARTIST = "artist/"
        private const val PREFIX_SHOW = "show/"
        // Phase 2 — "Playback options" settings menus (speed / sleep / effects). The leaves
        // are "action" items: tapping one applies a setting instead of playing (intercepted
        // by PlaybackService.onSetMediaItems via ACTION_PREFIX, which must stay in sync with
        // PlaybackService.AUTO_ACTION_PREFIX).
        const val CAT_PLAYBACK = "cat/playback"
        const val MENU_CHAPTERS = "menu/chapters"
        private const val MENU_SPEED = "menu/speed"
        private const val MENU_SLEEP = "menu/sleep"
        private const val MENU_EFFECTS = "menu/effects"
        // Single source of truth — PlaybackService.onSetMediaItems intercepts this exact prefix.
        private val ACTION_PREFIX = PlaybackService.AUTO_ACTION_PREFIX

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
        /** File path + MIME — used to classify audiobooks (.m4b / long-form). */
        val path: String,
        val mime: String,
    )

    // Short-lived cache so a single car browse session (songs → albums → album → …)
    // doesn't re-run the MediaStore query for every node. Invalidated after TTL.
    @Volatile private var audioCache: List<BrowseTrack>? = null
    @Volatile private var audioCacheAtMs: Long = 0L
    private val cacheTtlMs = 15_000L

    // Every playable leaf a browser actually RECEIVED (registered in [children] after paging,
    // so page 0 of a huge Songs list is never evicted by pages the car never saw), by
    // mediaId. Car / legacy controllers play a leaf by sending ONLY its mediaId back
    // (playFromMediaId) — title, artwork and the chapter extras do not survive that
    // round-trip — so PlaybackService.onAddMediaItems re-hydrates the bare request from here
    // (device-proven: the book played with the MUSIC button layout until this existed).
    private val servedLeaves = android.util.LruCache<String, MediaItem>(4096)

    /** The full leaf last served under [mediaId], or null if never browsed. */
    fun servedLeaf(mediaId: String): MediaItem? = servedLeaves.get(mediaId)

    /**
     * Fallback for a play request whose leaf was evicted (library > cache): rebuild it from
     * the still-warm MediaStore snapshot without querying (main-thread safe). Null when the
     * snapshot expired or the id is not a local track.
     */
    fun cachedLocalLeaf(mediaId: String): MediaItem? =
        audioCache?.firstOrNull { it.uri.toString() == mediaId }?.let { trackToItem(it) }

    /** Set by PlaybackService: (parentId, itemCount) → MediaLibrarySession.notifyChildrenChanged
     *  on the session thread. Fired when a background chapter warm-up finishes. */
    @Volatile var onChildrenChanged: ((String, Int) -> Unit)? = null
    @Volatile private var booksWarmJob: kotlinx.coroutines.Job? = null

    // The CURRENT item's chapter table (chapter_count / chapter_title_$i / chapter_start_$i),
    // mirrored here by PlaybackService on every item/metadata change so the Chapters menu is
    // built on the IO browse dispatch without touching the player (main-thread only).
    @Volatile var currentChapters: Bundle? = null

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
        // Register exactly what the browser receives (post-paging) — see servedLeaves.
        result.forEach { if (it.mediaMetadata.isPlayable == true) servedLeaves.put(it.mediaId, it) }
        com.powermediaplayer.diag.DiagLog.event(
            "AABROWSE", "children parent=$parentId page=$page size=$pageSize → ${result.size} items"
        )
        return result
    }

    private suspend fun childrenInternal(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        return when {
            parentId == ROOT_ID -> rootCategories()
            parentId == CAT_LIBRARY -> libraryCategories()
            parentId == CAT_RECENT -> recents()
            parentId == CAT_FAVOURITES -> favourites()
            parentId == CAT_SONGS -> paged(songs(), page, pageSize)
            parentId == CAT_ALBUMS -> paged(albums(), page, pageSize)
            parentId == CAT_ARTISTS -> paged(artists(), page, pageSize)
            parentId == CAT_PODCASTS -> podcastShows()
            parentId == CAT_BOOKS -> books()
            parentId == CAT_PLAYBACK -> playbackMenus()
            parentId == MENU_CHAPTERS -> chapterItems()
            parentId == MENU_SPEED -> speedItems()
            parentId == MENU_SLEEP -> sleepItems()
            parentId == MENU_EFFECTS -> effectItems()
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
        // A leaf the browser has seen — full metadata. (The bare fallback below is NOT
        // registered, so it can never override a file's own tags on play.)
        servedLeaf(mediaId) != null -> servedLeaf(mediaId)
        mediaId.startsWith("cat/") || mediaId.startsWith("menu/") ||
            mediaId.startsWith(PREFIX_ALBUM) ||
            mediaId.startsWith(PREFIX_ARTIST) || mediaId.startsWith(PREFIX_SHOW) ->
            browsable(mediaId, mediaId.substringAfterLast('/'), null,
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        // A playable leaf: its mediaId is already its stable/playable key.
        runCatching { Uri.parse(mediaId) }.getOrNull() != null ->
            playable(mediaId, Uri.parse(mediaId), mediaId.substringAfterLast('/'), "", "", null)
        else -> null
    }

    // ---- categories ---------------------------------------------------------

    // Android Auto shows at most ~4 root tabs, so the top level is kept to 4: the two
    // highest-value lists (Recently played, Favourites), a Library tab that nests the full
    // browse (Songs/Albums/Artists/Podcasts), and the Playback-options settings tab.
    // Short tab titles: the car's tab strip truncates longer ones ("Recently pla…",
    // "Playback opt…" on the 1080×600 AAOS emulator).
    private fun rootCategories(): List<MediaItem> = listOf(
        browsable(CAT_RECENT, "Recent", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browsable(CAT_FAVOURITES, "Favourites", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browsable(CAT_LIBRARY, "Library", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browsable(CAT_PLAYBACK, "Playback", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
    )

    private fun libraryCategories(): List<MediaItem> = listOf(
        browsable(CAT_SONGS, "Songs", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browsable(CAT_ALBUMS, "Albums", null, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browsable(CAT_ARTISTS, "Artists", null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        browsable(CAT_BOOKS, "Audiobooks", null, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS),
        browsable(CAT_PODCASTS, "Podcasts", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
    )

    // ---- Playback-options settings menus (Phase 2) --------------------------

    // "Chapters" leads the list only while the current item has a chapter table; the
    // service notifies CAT_PLAYBACK changed when that comes and goes.
    private fun playbackMenus(): List<MediaItem> = buildList {
        val n = currentChapters?.getInt("chapter_count", 0) ?: 0
        if (n > 0) {
            add(
                browsable(
                    MENU_CHAPTERS, "Chapters", null, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
                    subtitle = "$n chapters",
                )
            )
        }
        add(browsable(MENU_SPEED, "Speed", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
        add(browsable(MENU_SLEEP, "Sleep timer", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
        add(browsable(MENU_EFFECTS, "Audio effects", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    }

    /**
     * The current book's chapters as action leaves (`pmp_action/chapter/<i>`, title + start
     * time): tapping one seeks WITHIN the current item (PlaybackService.applyAutoAction).
     */
    private fun chapterItems(): List<MediaItem> {
        val b = currentChapters ?: return emptyList()
        return (0 until b.getInt("chapter_count", 0)).map { i ->
            val title = b.getString("chapter_title_$i")?.takeIf { it.isNotBlank() }
                ?: "Chapter ${i + 1}"
            actionItem(
                "${ACTION_PREFIX}chapter/$i", title,
                subtitle = formatClock(b.getLong("chapter_start_$i", 0L)),
            )
        }
    }

    /** 0:00 / 12:34 / 1:02:03 */
    private fun formatClock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, sec)
        else "%d:%02d".format(java.util.Locale.ROOT, m, sec)
    }

    private fun speedItems(): List<MediaItem> =
        floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.3f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).map { s ->
            actionItem("${ACTION_PREFIX}speed/$s", speedLabel(s))
        }

    private fun sleepItems(): List<MediaItem> = listOf(
        actionItem("${ACTION_PREFIX}sleep/off", "Off"),
        actionItem("${ACTION_PREFIX}sleep/15", "15 minutes"),
        actionItem("${ACTION_PREFIX}sleep/30", "30 minutes"),
        actionItem("${ACTION_PREFIX}sleep/45", "45 minutes"),
        actionItem("${ACTION_PREFIX}sleep/60", "60 minutes"),
        actionItem("${ACTION_PREFIX}sleep/90", "90 minutes"),
        actionItem("${ACTION_PREFIX}sleep/120", "120 minutes"),
        actionItem("${ACTION_PREFIX}sleep/eot", "End of current track"),
    )

    private fun effectItems(): List<MediaItem> = listOf(
        actionItem("${ACTION_PREFIX}reverb/0", "Reverb: Off"),
        actionItem("${ACTION_PREFIX}reverb/1", "Reverb: Room"),
        actionItem("${ACTION_PREFIX}reverb/2", "Reverb: Medium hall"),
        actionItem("${ACTION_PREFIX}reverb/3", "Reverb: Large hall"),
        actionItem("${ACTION_PREFIX}reverb/4", "Reverb: Plate"),
        actionItem("${ACTION_PREFIX}reverb/5", "Reverb: Cave"),
        actionItem("${ACTION_PREFIX}voiceboost/1", "Voice boost: On"),
        actionItem("${ACTION_PREFIX}voiceboost/0", "Voice boost: Off"),
        actionItem("${ACTION_PREFIX}mono/1", "Mono mix: On"),
        actionItem("${ACTION_PREFIX}mono/0", "Mono mix: Off"),
        actionItem("${ACTION_PREFIX}stereo/1", "Stereo flip: On"),
        actionItem("${ACTION_PREFIX}stereo/0", "Stereo flip: Off"),
    )

    private fun speedLabel(s: Float): String {
        val whole = s.toLong()
        val txt = if (s == whole.toFloat()) "$whole" else s.toString().trimEnd('0').trimEnd('.')
        return "${txt}×"
    }

    /** A playable "action" leaf: tapping it in the car applies a setting (intercepted by
     *  PlaybackService.onSetMediaItems via the ACTION_PREFIX) rather than playing media. */
    private fun actionItem(actionId: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(actionId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { subtitle?.let { setSubtitle(it); setArtist(it) } }
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private suspend fun recents(): List<MediaItem> =
        runCatching { lastPlayed.observeDynamic().first() }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.source != LastPlayedRepository.Source.SPOTIFY }
            .filter { it.mediaUri.isNotBlank() }
            .take(MAX_RECENTS)
            .map { historyToItem(it) }
            .toList()

    // Pinned sessions (the phone's Last Played pins) PLUS starred tracks (FavoriteDao — what
    // the car's ♥ button and the Library star write), so a ♥ tapped in the car shows up here.
    private suspend fun favourites(): List<MediaItem> {
        val pinned = runCatching { lastPlayed.observePinned().first() }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.source != LastPlayedRepository.Source.SPOTIFY }
            .filter { it.mediaUri.isNotBlank() }
            .map { historyToItem(it) }
            .toList()
        val starredUris = runCatching { favoriteDao.getAll() }.getOrDefault(emptyList())
            .mapTo(HashSet()) { it.uri }
        if (starredUris.isEmpty()) return pinned
        val pinnedIds = pinned.mapTo(HashSet()) { it.mediaId }
        val starred = localAudio()
            .filter { it.uri.toString() in starredUris && it.uri.toString() !in pinnedIds }
            .map { trackToItem(it) }
        return pinned + starred
    }

    private fun historyToItem(h: LastPlayedRepository.HistoryItem): MediaItem {
        val play = runCatching { Uri.parse(h.mediaUri) }.getOrNull() ?: Uri.EMPTY
        // Anything the phone app has played was parsed by it, so the chapter cache is warm
        // for a book here and this is a disk read, not a parse.
        val chapters = if (play != Uri.EMPTY) cachedChapters(play) else null
        return playable(
            mediaId = h.mediaUri,
            playUri = play,
            title = h.title,
            artist = h.subtitle,
            album = "",
            artUri = h.artworkUri?.let { runCatching { Uri.parse(it) }.getOrNull() },
            extras = chapters,
            mediaType = if (chapters != null) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
            else MediaMetadata.MEDIA_TYPE_MUSIC,
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
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
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
                val dataC = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val mimeC = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
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
                            path = if (dataC >= 0) c.getString(dataC) ?: "" else "",
                            mime = if (mimeC >= 0) c.getString(mimeC) ?: "" else "",
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

    // A book reached via Songs / an album / an artist still gets its chapter buttons: the
    // cache-only lookup is a disk read (no parse), so the big lists stay cheap, and the
    // Audiobooks node (full parse) warms that cache the first time it is browsed.
    private fun trackToItem(t: BrowseTrack): MediaItem {
        val book = isAudiobook(t)
        return playable(
            t.uri.toString(), t.uri, t.title, t.artist, t.album, t.artUri, t.durationMs,
            extras = if (book) cachedChapters(t.uri) else null,
            mediaType = if (book) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
            else MediaMetadata.MEDIA_TYPE_MUSIC,
        )
    }

    // ---- Audiobooks ---------------------------------------------------------

    /** .m4b, an audiobook MIME, or long-form (>20 min) local audio. */
    private fun isAudiobook(t: BrowseTrack): Boolean =
        t.path.endsWith(".m4b", ignoreCase = true) ||
            t.mime.contains("m4b", ignoreCase = true) ||
            t.mime.contains("audiobook", ignoreCase = true) ||
            t.durationMs > 20L * 60L * 1000L

    /** The parser's cache entry for [uri] as stored (null = never parsed; a parsed file with
     *  no chapters is a Bundle with chapter_count=0). Never parses. */
    private fun cachedRaw(uri: Uri): Bundle? = runCatching {
        com.powermediaplayer.util.M4bChapterParser.cachedOnly(context, uri)
    }.getOrNull()

    /** A COPY of the cached chapter table for [uri] when it has chapters. The cache hands
     *  out its stored instance and the phone UI mutates that Bundle in place
     *  (is_video_hint), so never share it across the browse (IO) / player threads. */
    private fun cachedChapters(uri: Uri): Bundle? =
        cachedRaw(uri)?.takeIf { it.getInt("chapter_count", 0) > 0 }?.let { Bundle(it) }

    /**
     * Audiobooks with their CHAPTER table attached as MediaItem extras
     * (`chapter_count` / `chapter_start_$i` / `chapter_title_$i`, via
     * [com.powermediaplayer.util.M4bChapterParser]). The now-playing layout keys the
     * chapter buttons off `chapter_count`, and `seekChapter` reads the starts, so a book
     * played from Auto gets chapter next/prev.
     *
     * Served from the parser's cache only — a cache miss (a long file never parsed) is
     * parsed in the BACKGROUND (moov walk, seconds per file, unbounded count) and the node
     * is re-announced via [onChildrenChanged] when done, so the car never sits on a
     * spinner behind N inline parses.
     */
    private fun books(): List<MediaItem> {
        val tracks = localAudio().filter { isAudiobook(it) }
        val misses = ArrayList<Uri>()
        val items = tracks.map { t ->
            val raw = cachedRaw(t.uri)
            if (raw == null) misses.add(t.uri)
            playable(
                t.uri.toString(), t.uri, t.title, t.artist, t.album, t.artUri, t.durationMs,
                extras = raw?.takeIf { it.getInt("chapter_count", 0) > 0 }?.let { Bundle(it) },
                mediaType = MediaMetadata.MEDIA_TYPE_AUDIO_BOOK,
            )
        }
        if (misses.isNotEmpty()) warmChapters(misses, tracks.size)
        return items
    }

    private fun warmChapters(uris: List<Uri>, bookCount: Int) {
        if (booksWarmJob?.isActive == true) return
        booksWarmJob = appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            uris.forEach { uri ->
                runCatching {
                    com.powermediaplayer.util.M4bChapterParser.extractChaptersAsBundle(context, uri)
                }
            }
            com.powermediaplayer.diag.DiagLog.event(
                "AABROWSE", "chapter warm-up done for ${uris.size} files → re-announce $CAT_BOOKS"
            )
            onChildrenChanged?.invoke(CAT_BOOKS, bookCount)
        }
    }

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
        extras: Bundle? = null,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
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
                    extras?.let { setExtras(it) }
                }
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(mediaType)
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
