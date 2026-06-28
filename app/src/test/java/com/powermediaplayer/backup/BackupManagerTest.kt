package com.powermediaplayer.backup

import android.content.Context
import androidx.room.Room
import com.powermediaplayer.data.db.AppDatabase
import com.powermediaplayer.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** M3 — backup/restore round-trip (data correctness is the whole point). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsDataStore
    private lateinit var mgr: BackupManager

    @Before fun setup() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        settings = SettingsDataStore(ctx)
        mgr = BackupManager(db, settings)
    }

    @After fun teardown() { db.close() }

    @Test fun dbRowSurvivesBackupRestore() = runBlocking {
        val w = db.openHelper.writableDatabase
        w.execSQL(
            "INSERT OR REPLACE INTO media_overrides (mediaUri, volumeBoostMb, updatedAt) " +
                "VALUES ('test://x', 9, 123)"
        )
        val json = mgr.buildBackupJson("2026-06-27T00:00:00")
        w.execSQL("DELETE FROM media_overrides")
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_overrides").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
        val n = mgr.restoreFromJson(json).getOrThrow()
        assertTrue("at least the overrides row restored", n >= 1)
        db.openHelper.readableDatabase
            .query("SELECT volumeBoostMb FROM media_overrides WHERE mediaUri='test://x'").use {
                assertTrue("row is back", it.moveToFirst())
                assertEquals(9, it.getInt(0))
            }
    }

    @Test fun settingsSurviveBackupRestore() = runBlocking {
        settings.setReplayGainEnabled(true)
        val json = mgr.buildBackupJson("2026-06-27T00:00:00")
        settings.setReplayGainEnabled(false)
        assertFalse(settings.replayGainEnabled.first())
        mgr.restoreFromJson(json).getOrThrow()
        assertTrue(settings.replayGainEnabled.first())
    }

    @Test fun rejectsForeignJson() = runBlocking {
        assertTrue(mgr.restoreFromJson("{\"format\":\"something-else\"}").isFailure)
    }
}
