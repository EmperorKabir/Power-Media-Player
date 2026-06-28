package com.powermediaplayer.backup

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.powermediaplayer.data.db.AppDatabase
import com.powermediaplayer.data.preferences.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M3 — settings/metadata backup & restore. Backs up ABSOLUTELY EVERYTHING
 * except the actual audio/video files (their METADATA is included): all
 * DataStore settings + every Room table (overrides, EQ presets, favourites,
 * history, bookmarks, smart playlists, podcast subs + episode metadata,
 * enrichment cache, the offline-copy REGISTRY — paths/sizes, not the bytes).
 *
 * Implementation is GENERIC on purpose: settings via [Preferences.asMap], DB
 * via a raw dump of every user table. New columns/tables are captured
 * automatically, and restore only writes columns that still exist in the
 * current schema — so a backup survives additive migrations.
 */
@Singleton
class BackupManager @Inject constructor(
    private val appDatabase: AppDatabase,
    private val settings: SettingsDataStore
) {
    /** Build the full backup as a JSON string. [stampIso] is supplied by the
     *  caller (the worker can't read the clock) and recorded for display. */
    suspend fun buildBackupJson(stampIso: String): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("schemaVersion", currentSchemaVersion())
        root.put("createdAt", stampIso)
        root.put("settings", dumpSettings())
        root.put("tables", dumpTables())
        root.toString()
    }

    /** Restore from a JSON string produced by [buildBackupJson]. Returns the
     *  number of rows written. Validates the format header; restores only
     *  columns/tables that still exist, inside a single transaction. */
    suspend fun restoreFromJson(json: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(json)
            require(root.optString("format") == FORMAT) { "Not a Power Media Player backup" }
            // Settings first (cheap, no transaction needed).
            root.optJSONObject("settings")?.let { restoreSettings(it) }
            // Tables in one transaction so a failure rolls back cleanly.
            var rows = 0
            val tables = root.optJSONObject("tables")
            if (tables != null) {
                val existing = currentTables()
                appDatabase.runInTransaction {
                    val db = appDatabase.openHelper.writableDatabase
                    val names = tables.keys()
                    while (names.hasNext()) {
                        val table = names.next()
                        if (table !in existing) continue
                        val cols = tableColumns(table)
                        val arr = tables.optJSONArray(table) ?: continue
                        db.execSQL("DELETE FROM `$table`")
                        for (i in 0 until arr.length()) {
                            if (insertRow(table, cols, arr.getJSONObject(i))) rows++
                        }
                    }
                }
            }
            rows
        }
    }

    // ── settings ─────────────────────────────────────────────────────────
    private suspend fun dumpSettings(): JSONObject {
        val out = JSONObject()
        val prefs = settings.dataStoreData().first()
        for ((key, value) in prefs.asMap()) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                is Int -> { entry.put("t", "i"); entry.put("v", value) }
                is Long -> { entry.put("t", "l"); entry.put("v", value) }
                is Float -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                is Double -> { entry.put("t", "d"); entry.put("v", value) }
                is String -> { entry.put("t", "s"); entry.put("v", value) }
                is Set<*> -> {
                    entry.put("t", "ss")
                    entry.put("v", JSONArray().apply { value.forEach { put(it.toString()) } })
                }
                else -> continue
            }
            out.put(key.name, entry)
        }
        return out
    }

    private suspend fun restoreSettings(obj: JSONObject) {
        settings.editPreferences { prefs ->
            val names = obj.keys()
            while (names.hasNext()) {
                val name = names.next()
                val e = obj.getJSONObject(name)
                when (e.optString("t")) {
                    "b" -> prefs[booleanPreferencesKey(name)] = e.getBoolean("v")
                    "i" -> prefs[intPreferencesKey(name)] = e.getInt("v")
                    "l" -> prefs[longPreferencesKey(name)] = e.getLong("v")
                    "f" -> prefs[floatPreferencesKey(name)] = e.getDouble("v").toFloat()
                    "d" -> prefs[doublePreferencesKey(name)] = e.getDouble("v")
                    "s" -> prefs[stringPreferencesKey(name)] = e.getString("v")
                    "ss" -> {
                        val set = LinkedHashSet<String>()
                        val a = e.getJSONArray("v")
                        for (i in 0 until a.length()) set.add(a.getString(i))
                        prefs[stringSetPreferencesKey(name)] = set
                    }
                }
            }
        }
    }

    // ── database (generic) ────────────────────────────────────────────────
    private fun currentSchemaVersion(): Int =
        runCatching { appDatabase.openHelper.readableDatabase.version }.getOrDefault(0)

    private fun currentTables(): Set<String> {
        val out = LinkedHashSet<String>()
        val db = appDatabase.openHelper.readableDatabase
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'android_metadata' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'room_master_table'"
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    private fun tableColumns(table: String): List<String> {
        val out = ArrayList<String>()
        val db = appDatabase.openHelper.readableDatabase
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) out.add(c.getString(nameIdx))
        }
        return out
    }

    private fun dumpTables(): JSONObject {
        val out = JSONObject()
        val db = appDatabase.openHelper.readableDatabase
        for (table in currentTables()) {
            val arr = JSONArray()
            db.query("SELECT * FROM `$table`").use { c ->
                val n = c.columnCount
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in 0 until n) {
                        val col = c.getColumnName(i)
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> row.put(col, JSONObject.NULL)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(col, c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(col, c.getDouble(i))
                            android.database.Cursor.FIELD_TYPE_STRING -> row.put(col, c.getString(i))
                            android.database.Cursor.FIELD_TYPE_BLOB ->
                                row.put(col, JSONObject().put(
                                    "__blob64",
                                    android.util.Base64.encodeToString(c.getBlob(i), android.util.Base64.NO_WRAP)
                                ))
                        }
                    }
                    arr.put(row)
                }
            }
            out.put(table, arr)
        }
        return out
    }

    /** Insert one row, binding only columns that still exist in the schema. */
    private fun insertRow(table: String, cols: List<String>, row: JSONObject): Boolean {
        val present = cols.filter { row.has(it) }
        if (present.isEmpty()) return false
        val placeholders = present.joinToString(",") { "?" }
        val colList = present.joinToString(",") { "`$it`" }
        val args = arrayOfNulls<Any?>(present.size)
        present.forEachIndexed { i, col ->
            val v = row.get(col)
            args[i] = when {
                v == JSONObject.NULL -> null
                v is JSONObject && v.has("__blob64") ->
                    android.util.Base64.decode(v.getString("__blob64"), android.util.Base64.NO_WRAP)
                else -> v
            }
        }
        appDatabase.openHelper.writableDatabase
            .execSQL("INSERT OR REPLACE INTO `$table` ($colList) VALUES ($placeholders)", args)
        return true
    }

    companion object { const val FORMAT = "pmp-backup-v1" }
}
