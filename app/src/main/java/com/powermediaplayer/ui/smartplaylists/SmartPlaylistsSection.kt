package com.powermediaplayer.ui.smartplaylists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.powermediaplayer.data.db.dao.SmartPlaylistDao
import com.powermediaplayer.data.db.entity.SmartPlaylistEntity
import com.powermediaplayer.ui.theme.DisabledGrey
import com.powermediaplayer.ui.theme.ErrorRed
import com.powermediaplayer.ui.theme.OledBlack
import com.powermediaplayer.ui.theme.TealAccent
import com.powermediaplayer.ui.theme.TextPrimary
import com.powermediaplayer.ui.theme.TextSecondary
import com.powermediaplayer.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * §C6 — settings entry for managing smart playlists. Editor is a
 * minimal name + raw-JSON-rules input; the resolver
 * (`SmartPlaylistResolver`) accepts any rule shape so power users
 * can hand-craft. A guided form-based editor is the next iteration.
 */
@HiltViewModel
class SmartPlaylistsViewModel @Inject constructor(
    private val dao: SmartPlaylistDao
) : ViewModel() {
    val playlists: StateFlow<List<SmartPlaylistEntity>> =
        dao.getAll().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )

    fun upsert(name: String, rulesJson: String) {
        if (name.isBlank() || rulesJson.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            // Cap of 20 per locked spec.
            if (dao.count() >= 20) return@launch
            dao.insert(
                SmartPlaylistEntity(
                    name = name.trim(),
                    rulesJson = rulesJson.trim()
                )
            )
        }
    }

    fun update(id: Long, name: String, rulesJson: String) {
        if (name.isBlank() || rulesJson.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.getById(id) ?: return@launch
            dao.update(
                existing.copy(
                    name = name.trim(),
                    rulesJson = rulesJson.trim(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun delete(playlist: SmartPlaylistEntity) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(playlist) }
    }
}

@Composable
fun SmartPlaylistsSection(
    vm: SmartPlaylistsViewModel = hiltViewModel()
) {
    val playlists by vm.playlists.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    // Non-null while editing an existing playlist; null = creating a new one.
    var editingPlaylist by remember { mutableStateOf<SmartPlaylistEntity?>(null) }
    // Which row's overflow menu is open (by id), null = none.
    var openMenuId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = TealAccent)
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier
                    .weight(1f)
                    // Tapping the header (not just the "+") opens the editor —
                    // with no playlists the rail is hidden, so the label was
                    // the obvious thing to tap yet did nothing.
                    .clickable { editingPlaylist = null; showEditor = true }
            ) {
                Text(
                    "Smart playlists (${playlists.size}/20)",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    if (playlists.isEmpty())
                        "Auto-playlists built from rules (e.g. favourites, " +
                            "most-played, recently added). Tap to create one."
                    else
                        "Tap a playlist above the library list to play it. " +
                            "Tap here or + to add another.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            IconButton(onClick = { editingPlaylist = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = TealAccent)
            }
        }
        if (playlists.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            playlists.forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = TextPrimary,
                            style = MaterialTheme.typography.titleSmall)
                        Text(
                            summariseRules(p.rulesJson),
                            color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2
                        )
                    }
                    // Overflow menu — Edit + Delete (replaces the bare × so a
                    // saved playlist can be edited, not only deleted).
                    Box {
                        IconButton(onClick = { openMenuId = p.id }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "More",
                                tint = TextSecondary
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = openMenuId == p.id,
                            onDismissRequest = { openMenuId = null }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Edit", color = TextPrimary) },
                                onClick = {
                                    editingPlaylist = p
                                    showEditor = true
                                    openMenuId = null
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Delete", color = ErrorRed) },
                                onClick = {
                                    vm.delete(p)
                                    openMenuId = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        SmartPlaylistEditor(
            existing = editingPlaylist,
            onCancel = { showEditor = false },
            onSave = { name, json ->
                val editing = editingPlaylist
                if (editing != null) vm.update(editing.id, name, json)
                else vm.upsert(name, json)
                showEditor = false
            }
        )
    }
}

/**
 * §C6 — form-based smart-playlist editor. Users add an arbitrary
 * number of rule rows (field × op × value), pick a sort + limit, and
 * we serialise to JSON for [SmartPlaylistResolver]. C8 fix: the
 * raw-JSON escape hatch was never asked for and has been removed —
 * form-only.
 */
@Composable
private fun SmartPlaylistEditor(
    onCancel: () -> Unit,
    onSave: (String, String) -> Unit,
    existing: SmartPlaylistEntity? = null
) {
    // Pre-fill from the existing playlist when editing; otherwise start with
    // a single favourites rule. `remember(existing)` re-seeds if the dialog
    // is reused for a different playlist.
    val seed = remember(existing) { existing?.let { parseRules(it.rulesJson) } }
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    val rules = remember(existing) {
        mutableStateListOf<RuleRow>().apply {
            val seededRules = seed?.first
            if (!seededRules.isNullOrEmpty()) addAll(seededRules)
            else add(RuleRow(field = "isFavourite", op = "eq", value = "true"))
        }
    }
    var sort by remember(existing) { mutableStateOf(seed?.second ?: "lastPlayed") }
    var limit by remember(existing) { mutableStateOf(seed?.third ?: "50") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (existing != null) "Edit smart playlist" else "New smart playlist",
                color = TealAccent,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true
                )
                rules.forEachIndexed { idx, r ->
                    RuleRowEditor(
                        row = r,
                        onChange = { rules[idx] = it },
                        onDelete = { rules.removeAt(idx) }
                    )
                }
                androidx.compose.material3.TextButton(onClick = {
                    rules.add(RuleRow(field = "artist", op = "contains", value = ""))
                }) {
                    Text("+ Add rule", color = TealAccent)
                }
                SortAndLimit(
                    sort = sort, onSort = { sort = it },
                    limit = limit, onLimit = { limit = it }
                )
                Text(
                    "Rules combine with AND. Saved playlists appear at the top of the Library tab.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        },
        confirmButton = {
            // Disabled until a name is entered — saving nameless silently
            // discarded the playlist (upsert/update bail on a blank name) yet
            // the dialog still closed, which read as a broken Save. Cancel
            // still lets you back out without creating anything.
            val canSave = name.isNotBlank()
            TextButton(
                onClick = {
                    val json = buildJson(rules, sort, limit)
                    onSave(name, json)
                },
                enabled = canSave
            ) {
                Text("Save", color = if (canSave) TealAccent else DisabledGrey)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = OledBlack
    )
}

private data class RuleRow(val field: String, val op: String, val value: String)

private val FIELDS = listOf(
    "title", "artist", "album", "duration",
    "playCount", "lastPlayedDays",
    "isFavourite", "hasBookmark"
)
private val OPS_STRING = listOf("contains", "not_contains", "equals", "not_equals")
private val OPS_NUMBER = listOf("eq", "lt", "lte", "gt", "gte")
private val OPS_BOOL = listOf("eq")
private val SORTS = listOf(
    "name", "dateAdded", "lastPlayed", "playCount", "duration", "random"
)

private fun opsFor(field: String) = when (field) {
    "title", "artist", "album" -> OPS_STRING
    "duration", "playCount", "lastPlayedDays" -> OPS_NUMBER
    "isFavourite", "hasBookmark" -> OPS_BOOL
    else -> OPS_STRING
}

// ── End-user-facing labels ───────────────────────────────────────────
// The rule engine speaks in field/operator CODES; the editor and the
// saved-playlist summary show these plain-English labels instead.
private val FIELD_LABELS = mapOf(
    "title" to "Title", "artist" to "Artist", "album" to "Album",
    "duration" to "Length", "playCount" to "Play count",
    "lastPlayedDays" to "Last played", "isFavourite" to "Favourite",
    "hasBookmark" to "Bookmarked"
)
private val OP_LABELS = mapOf(
    "contains" to "contains", "not_contains" to "doesn't contain",
    "equals" to "is", "not_equals" to "is not",
    "eq" to "is", "lt" to "under", "lte" to "at most",
    "gt" to "over", "gte" to "at least"
)
private val SORT_LABELS = mapOf(
    "name" to "Name", "dateAdded" to "Date added", "lastPlayed" to "Last played",
    "playCount" to "Play count", "duration" to "Length", "random" to "Shuffle"
)

private fun fieldLabel(code: String) = FIELD_LABELS[code] ?: code
private fun opLabel(code: String) = OP_LABELS[code] ?: code
private fun sortLabel(code: String) = SORT_LABELS[code] ?: code
private fun isBoolField(field: String) = field == "isFavourite" || field == "hasBookmark"
private fun unitFor(field: String) = when (field) {
    "lastPlayedDays" -> "days"
    "duration" -> "seconds"
    else -> ""
}

/**
 * Turn a rules-JSON blob into a one-line, plain-English summary for the
 * saved-playlist list — e.g. "Favourite: Yes · Play count at least 3 ·
 * sorted by Last played · max 50". Falls back gracefully on odd JSON.
 */
private fun summariseRules(rulesJson: String): String {
    val root = runCatching { org.json.JSONObject(rulesJson) }.getOrNull()
        ?: return "Custom rules"
    val arr = root.optJSONArray("rules")
    val parts = ArrayList<String>()
    if (arr != null) for (i in 0 until arr.length()) {
        val r = arr.optJSONObject(i) ?: continue
        val field = r.optString("field")
        val op = r.optString("op")
        val value = r.opt("value")
        val fl = fieldLabel(field)
        parts += when {
            isBoolField(field) -> {
                val yes = value == true ||
                    value?.toString().equals("true", ignoreCase = true)
                "$fl: ${if (yes) "Yes" else "No"}"
            }
            field == "duration" -> {
                val secs = (value?.toString()?.toLongOrNull() ?: 0L) / 1000
                "$fl ${opLabel(op)} ${secs}s"
            }
            else -> {
                val unit = unitFor(field).let { if (it.isNotEmpty()) " $it" else "" }
                val v = if (value is String) "“$value”" else value.toString()
                "$fl ${opLabel(op)} $v$unit"
            }
        }
    }
    val body = if (parts.isEmpty()) "All tracks" else parts.joinToString("  ·  ")
    val sort = sortLabel(root.optString("sort", "name"))
    val limit = root.optInt("limit", 0)
    return body + "  ·  sorted by " + sort + if (limit > 0) "  ·  max $limit" else ""
}

/**
 * Inverse of [buildJson] for the editor: parse a stored rules-JSON blob back
 * into editable rows + sort + limit. Length is stored in ms but edited in
 * seconds, so it is divided back down here.
 */
private fun parseRules(rulesJson: String): Triple<List<RuleRow>, String, String> {
    val root = runCatching { org.json.JSONObject(rulesJson) }.getOrNull()
        ?: return Triple(emptyList(), "lastPlayed", "50")
    val arr = root.optJSONArray("rules")
    val rows = ArrayList<RuleRow>()
    if (arr != null) for (i in 0 until arr.length()) {
        val r = arr.optJSONObject(i) ?: continue
        val field = r.optString("field")
        val op = r.optString("op")
        val raw = r.opt("value")
        val value = when {
            raw is Boolean -> raw.toString()
            field == "duration" ->
                ((raw?.toString()?.toLongOrNull() ?: 0L) / 1000L).toString()
            else -> raw?.toString().orEmpty()
        }
        rows.add(RuleRow(field = field, op = op, value = value))
    }
    val sort = root.optString("sort", "lastPlayed")
    val limit = root.optInt("limit", 50).toString()
    return Triple(rows, sort, limit)
}

@Composable
private fun RuleRowEditor(
    row: RuleRow,
    onChange: (RuleRow) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        DropdownPill(
            value = row.field,
            options = FIELDS,
            display = ::fieldLabel,
            onPick = { f ->
                onChange(
                    row.copy(
                        field = f,
                        op = opsFor(f).first(),
                        value = if (isBoolField(f)) "true" else ""
                    )
                )
            },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        DropdownPill(
            value = row.op,
            options = opsFor(row.field),
            display = ::opLabel,
            onPick = { onChange(row.copy(op = it)) },
            modifier = Modifier.weight(0.8f)
        )
        Spacer(Modifier.width(4.dp))
        if (isBoolField(row.field)) {
            // Favourite / Bookmarked are yes/no — a free-text "true/false"
            // box is the kind of code-y UX end users trip over.
            DropdownPill(
                value = row.value.ifBlank { "true" },
                options = listOf("true", "false"),
                display = { if (it == "true") "Yes" else "No" },
                onPick = { onChange(row.copy(value = it)) },
                modifier = Modifier.weight(1f)
            )
        } else {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { onChange(row.copy(value = it)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                val unit = unitFor(row.field)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = ErrorRed
            )
        }
    }
}

@Composable
private fun SortAndLimit(
    sort: String, onSort: (String) -> Unit,
    limit: String, onLimit: (String) -> Unit
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Sort:", color = TextSecondary)
        Spacer(Modifier.width(4.dp))
        DropdownPill(
            value = sort, options = SORTS, onPick = onSort,
            display = ::sortLabel,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text("Limit:", color = TextSecondary)
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = limit,
            onValueChange = { onLimit(it.filter(Char::isDigit)) },
            singleLine = true,
            modifier = Modifier.width(80.dp)
        )
    }
}

@Composable
private fun DropdownPill(
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    display: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        androidx.compose.material3.OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(display(value), style = MaterialTheme.typography.labelMedium)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(display(opt)) },
                    onClick = { onPick(opt); expanded = false }
                )
            }
        }
    }
}

private fun buildJson(rules: List<RuleRow>, sort: String, limit: String): String {
    val rulesJsonArr = rules.joinToString(",") { r ->
        val v = when {
            r.value.equals("true", ignoreCase = true) -> "true"
            r.value.equals("false", ignoreCase = true) -> "false"
            r.value.toIntOrNull() != null -> {
                val n = r.value.toInt()
                // Length is entered in seconds but the engine compares the
                // track duration in milliseconds.
                if (r.field == "duration") (n * 1000).toString() else n.toString()
            }
            else -> "\"${r.value.replace("\"", "\\\"")}\""
        }
        "{\"field\":\"${r.field}\",\"op\":\"${r.op}\",\"value\":$v}"
    }
    return "{\"rules\":[$rulesJsonArr],\"sort\":\"$sort\"," +
        "\"limit\":${limit.toIntOrNull() ?: 0}}"
}
