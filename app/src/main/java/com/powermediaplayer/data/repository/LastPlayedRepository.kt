package com.powermediaplayer.data.repository

import com.powermediaplayer.data.db.dao.HistoryFavouriteDao
import com.powermediaplayer.data.db.dao.PlaybackHistoryDao
import com.powermediaplayer.data.db.entity.HistoryFavouriteEntity
import com.powermediaplayer.data.db.entity.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One source of truth for the Last Played tab. Combines
 * [PlaybackHistoryDao] (the dynamic list of recently played items)
 * with [HistoryFavouriteDao] (user-pinned items, max 10).
 *
 * Caps the dynamic list at the most recent 10 NON-pinned items;
 * pinned items are never auto-evicted.
 */
@Singleton
class LastPlayedRepository @Inject constructor(
    private val historyDao: PlaybackHistoryDao,
    private val favDao: HistoryFavouriteDao
) {

    data class HistoryItem(
        val mediaUri: String,
        val title: String,
        val subtitle: String,
        val artworkUri: String?,
        val source: Source,
        val mediaKindOrdinal: Int,
        val lastPositionMs: Long,
        val durationMs: Long,
        val lastPlayedAt: Long,
        val isPinned: Boolean,
        val pinOrder: Int
    )

    enum class Source { LOCAL, DRIVE, SPOTIFY }

    suspend fun recordPlay(row: PlaybackHistoryEntity) {
        historyDao.upsert(row)
        trimToCap()
    }

    suspend fun updatePosition(uri: String, posMs: Long) {
        historyDao.updatePosition(uri, posMs)
    }

    suspend fun delete(uri: String) {
        historyDao.delete(uri)
        favDao.unpin(uri)
    }

    suspend fun mostRecent(): PlaybackHistoryEntity? = historyDao.mostRecent()

    /** Recent + pinned, joined into the read model. */
    fun observeAll(): Flow<List<HistoryItem>> =
        historyDao.observeAll().combine(favDao.observeAll()) { hist, favs ->
            val favMap = favs.associateBy { it.mediaUri }
            hist.map { e ->
                val f = favMap[e.mediaUri]
                HistoryItem(
                    mediaUri = e.mediaUri,
                    title = e.title,
                    subtitle = e.subtitle,
                    artworkUri = e.artworkUri,
                    source = sourceOf(e.source),
                    mediaKindOrdinal = e.mediaKindOrdinal,
                    lastPositionMs = e.lastPositionMs,
                    durationMs = e.durationMs,
                    lastPlayedAt = e.lastPlayedAt,
                    isPinned = f != null,
                    pinOrder = f?.pinOrder ?: Int.MAX_VALUE
                )
            }
        }

    /** Top 10 most recent NON-pinned items. */
    fun observeDynamic(): Flow<List<HistoryItem>> =
        observeAll().map { all ->
            all.filterNot { it.isPinned }.take(10)
        }

    /** Pinned items in user-defined order. */
    fun observePinned(): Flow<List<HistoryItem>> =
        observeAll().map { all ->
            all.filter { it.isPinned }.sortedBy { it.pinOrder }
        }

    /**
     * Add [uri] to the favourites pin list.
     * Returns Failure when 10 pins already exist (per locked spec).
     */
    suspend fun pin(uri: String): Result<Unit> {
        val n = favDao.count()
        if (n >= 10) return Result.failure(IllegalStateException("Favourites full (10/10)"))
        favDao.upsert(HistoryFavouriteEntity(mediaUri = uri, pinOrder = n))
        return Result.success(Unit)
    }

    suspend fun unpin(uri: String) {
        favDao.unpin(uri)
        compactPinOrders()
    }

    /**
     * Reorder a pin to position [newOrder] (0-based). Other pins are
     * shifted to maintain a contiguous 0..n-1 ordering.
     */
    suspend fun reorderPinned(uri: String, newOrder: Int) {
        val pins = favDao.snapshot().toMutableList()
        val cur = pins.indexOfFirst { it.mediaUri == uri }
        if (cur == -1) return
        val target = newOrder.coerceIn(0, pins.size - 1)
        if (target == cur) return
        val item = pins.removeAt(cur)
        pins.add(target, item)
        pins.forEachIndexed { idx, p ->
            if (p.pinOrder != idx) favDao.setOrder(p.mediaUri, idx)
        }
    }

    private suspend fun compactPinOrders() {
        val pins = favDao.snapshot()
        pins.forEachIndexed { idx, p ->
            if (p.pinOrder != idx) favDao.setOrder(p.mediaUri, idx)
        }
    }

    private suspend fun trimToCap() {
        val pinnedUris = favDao.snapshot().map { it.mediaUri }.toSet()
        // Use the one-shot DAO snapshot — observeAll().first() would
        // subscribe + unsubscribe the whole table on every recordPlay.
        val rows = historyDao.snapshot().filterNot { pinnedUris.contains(it.mediaUri) }
        if (rows.size > 10) {
            val toDelete = rows.drop(10).map { it.mediaUri }
            historyDao.deleteMany(toDelete)
        }
    }

    private fun sourceOf(s: String): Source = when (s) {
        "DRIVE" -> Source.DRIVE
        "SPOTIFY" -> Source.SPOTIFY
        else -> Source.LOCAL
    }
}
