package com.powermediaplayer.playlist

import com.powermediaplayer.ui.library.MediaFileInfo
import org.json.JSONObject

/**
 * §C6 — converts a [SmartPlaylistEntity.rulesJson] string into a
 * filter+sort+limit applied over an in-memory `MediaFileInfo` list.
 *
 * Deliberately accepts the library list as a parameter rather than
 * pulling it itself — the caller already has it, no IPC round-trip
 * needed. Heavier rules that depend on `playback_history` or
 * `bookmarks` are evaluated against the optional history map below.
 */
object SmartPlaylistResolver {

    data class HistorySnapshot(
        val playCount: Map<String, Int> = emptyMap(),
        val lastPlayedAt: Map<String, Long> = emptyMap(),
        val bookmarkUris: Set<String> = emptySet(),
        val favouriteUris: Set<String> = emptySet()
    )

    fun resolve(
        files: List<MediaFileInfo>,
        rulesJson: String,
        history: HistorySnapshot = HistorySnapshot()
    ): List<MediaFileInfo> {
        val root = runCatching { JSONObject(rulesJson) }.getOrNull() ?: return emptyList()
        val rules = root.optJSONArray("rules") ?: return files
        val sort = root.optString("sort", "name")
        val limit = root.optInt("limit", 0)

        val now = System.currentTimeMillis()
        var out = files.filter { f ->
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                if (!matches(f, rule, history, now)) return@filter false
            }
            true
        }
        out = when (sort) {
            "name" -> out.sortedBy { it.title.lowercase() }
            "dateAdded" -> out.sortedByDescending { it.dateModified }
            "lastPlayed" -> out.sortedByDescending { history.lastPlayedAt[it.uri.toString()] ?: 0L }
            "playCount" -> out.sortedByDescending { history.playCount[it.uri.toString()] ?: 0 }
            "duration" -> out.sortedByDescending { it.duration }
            "random" -> out.shuffled()
            else -> out
        }
        if (limit in 1..Int.MAX_VALUE) out = out.take(limit)
        return out
    }

    private fun matches(
        f: MediaFileInfo,
        rule: JSONObject,
        history: HistorySnapshot,
        now: Long
    ): Boolean {
        val field = rule.optString("field")
        val op = rule.optString("op")
        val value = rule.opt("value")
        val uri = f.uri.toString()
        return when (field) {
            "title" -> stringRule(f.title, op, value)
            "artist" -> stringRule(f.artist, op, value)
            "album" -> stringRule(f.album, op, value)
            "duration" -> longRule(f.duration, op, value)
            "isFavourite" -> {
                val expected = (value as? Boolean) ?: false
                (uri in history.favouriteUris) == expected
            }
            "hasBookmark" -> {
                val expected = (value as? Boolean) ?: false
                (uri in history.bookmarkUris) == expected
            }
            "playCount" -> longRule((history.playCount[uri] ?: 0).toLong(), op, value)
            "lastPlayedDays" -> {
                val ts = history.lastPlayedAt[uri] ?: 0L
                val days = if (ts == 0L) Long.MAX_VALUE else ((now - ts) / (24L * 60 * 60 * 1000))
                longRule(days, op, value)
            }
            else -> true
        }
    }

    private fun stringRule(actual: String, op: String, value: Any?): Boolean {
        val v = (value as? String).orEmpty()
        return when (op) {
            "equals" -> actual.equals(v, ignoreCase = true)
            "not_equals" -> !actual.equals(v, ignoreCase = true)
            "contains" -> actual.contains(v, ignoreCase = true)
            "not_contains" -> !actual.contains(v, ignoreCase = true)
            else -> true
        }
    }

    private fun longRule(actual: Long, op: String, value: Any?): Boolean {
        val v = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: return true
            else -> return true
        }
        return when (op) {
            "eq" -> actual == v
            "lt" -> actual < v
            "lte" -> actual <= v
            "gt" -> actual > v
            "gte" -> actual >= v
            else -> true
        }
    }
}
