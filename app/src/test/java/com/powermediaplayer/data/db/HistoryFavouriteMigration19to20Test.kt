package com.powermediaplayer.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #19 — v19→v20 adds history_favourites.followLive as an additive,
 * NOT-NULL-DEFAULT-0 column. Existing rows must survive with followLive=0.
 * Schema-free (the repo runs exportSchema=false, so MigrationTestHelper —
 * which loadSchema()s an exported JSON — cannot run here): builds the v19 row
 * shape by raw SQL and applies the production migration body directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryFavouriteMigration19to20Test {

    private fun openV19(): SupportSQLiteDatabase {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("migr-test.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name("migr-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE history_favourites (
                                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                                mediaUri TEXT NOT NULL,
                                title TEXT NOT NULL,
                                subtitle TEXT NOT NULL,
                                artworkUri TEXT,
                                source TEXT NOT NULL,
                                mediaKindOrdinal INTEGER NOT NULL,
                                lastPositionMs INTEGER NOT NULL,
                                durationMs INTEGER NOT NULL,
                                pinOrder INTEGER NOT NULL,
                                pinnedAtMs INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, o: Int, n: Int) {}
                })
                .build()
        )
        return helper.writableDatabase
    }

    @Test fun migrate19to20_addsFollowLive_preservesRows_default0() {
        val db = openV19()
        db.execSQL(
            "INSERT INTO history_favourites " +
                "(mediaUri,title,subtitle,artworkUri,source,mediaKindOrdinal," +
                "lastPositionMs,durationMs,pinOrder,pinnedAtMs) " +
                "VALUES ('u','t','s',NULL,'DRIVE',0,12345,99999,0,1)"
        )
        AppDatabase.MIGRATION_19_20.migrate(db)

        val cols = db.query("PRAGMA table_info(history_favourites)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
        assertTrue("followLive column added", cols.contains("followLive"))

        db.query("SELECT lastPositionMs, followLive FROM history_favourites WHERE mediaUri='u'")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(12345L, c.getLong(0))
                assertEquals(0, c.getInt(1)) // default 0 = fixed
            }
    }
}
