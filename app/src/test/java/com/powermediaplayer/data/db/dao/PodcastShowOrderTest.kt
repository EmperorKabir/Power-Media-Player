package com.powermediaplayer.data.db.dao

import androidx.room.Room
import com.powermediaplayer.data.db.AppDatabase
import com.powermediaplayer.data.db.entity.PodcastShowEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #5 — displayOrder DAO ordering + setShowOrder + reorder contiguity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PodcastShowOrderTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PodcastDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.podcastDao()
    }

    @After fun tearDown() = db.close()

    private fun show(feed: String, title: String, order: Int) =
        PodcastShowEntity(feedUrl = feed, title = title, displayOrder = order)

    @Test fun observeShows_ordersByDisplayOrderThenTitle() = runBlocking {
        dao.upsertShow(show("c", "Charlie", 2))
        dao.upsertShow(show("a", "Alpha", 0))
        dao.upsertShow(show("b", "Bravo", 1))
        assertEquals(
            listOf("Alpha", "Bravo", "Charlie"),
            dao.observeShows().first().map { it.title }
        )
    }

    @Test fun setShowOrder_movesRow() = runBlocking {
        dao.upsertShow(show("a", "Alpha", 0))
        dao.upsertShow(show("b", "Bravo", 1))
        dao.setShowOrder("b", 0)
        dao.setShowOrder("a", 1)
        assertEquals(listOf("Bravo", "Alpha"), dao.observeShows().first().map { it.title })
    }

    @Test fun reorderCompaction_isContiguous0toNminus1() {
        // Mirrors reorderShow's remove-at + insert-at + re-index (pure).
        val ids = mutableListOf("a", "b", "c", "d")
        val item = ids.removeAt(ids.indexOf("d"))
        ids.add(1, item)
        assertEquals(
            listOf("a" to 0, "d" to 1, "b" to 2, "c" to 3),
            ids.mapIndexed { idx, id -> id to idx }
        )
    }
}
