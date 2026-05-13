package com.powermediaplayer.diag

import android.content.Context
import android.os.Build
import com.powermediaplayer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opt-in persistent file logger for evidence-gathering during dev/testing.
 *
 * Design:
 *  - Disabled by default. Enabled via Settings → Diagnostic logging.
 *  - When enabled, writes time-stamped lines to
 *    {externalFilesDir}/diag/log-current.txt — rotated to log-prev.txt
 *    when it exceeds [MAX_FILE_BYTES]. Keeps exactly 2 files (~10 MB
 *    total worst case).
 *  - File location is app-private external storage:
 *    /storage/emulated/0/Android/data/com.powermediaplayer/files/diag/
 *    Visible to the user via the system Files app + `adb pull` without
 *    requiring runtime permissions on Android 10+.
 *  - Writes happen on a single IO coroutine fed by a Channel, so call
 *    sites never block the main thread.
 *  - Never logs media titles / file paths / user-identifying content.
 *    Log opcodes, timings, session IDs, build info only.
 */
object DiagLog {

    private const val MAX_FILE_BYTES = 5L * 1024L * 1024L // 5 MB
    private const val DIR_NAME = "diag"
    private const val CURRENT = "log-current.txt"
    private const val PREV = "log-prev.txt"

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var dir: File? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<String>(capacity = Channel.UNLIMITED)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)

    init {
        scope.launch {
            for (line in channel) {
                runCatching { writeLine(line) }
            }
        }
    }

    /**
     * Initialise the logger with an app context. Safe to call multiple
     * times. The [initiallyEnabled] flag is the user's persisted choice
     * (read from DataStore by the caller). Subsequent toggles must be
     * routed via [setEnabled].
     */
    fun init(context: Context, initiallyEnabled: Boolean) {
        if (dir == null) {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            dir = File(base, DIR_NAME).apply { mkdirs() }
        }
        enabled = initiallyEnabled
        if (initiallyEnabled) {
            event("DiagLog", "init enabled=true buildVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) device=${Build.MANUFACTURER}/${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        }
    }

    fun setEnabled(v: Boolean) {
        val was = enabled
        enabled = v
        if (v && !was) {
            event("DiagLog", "enabled at runtime")
        } else if (!v && was) {
            // Final breadcrumb before going quiet.
            event("DiagLog", "disabled at runtime")
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Record a single event line. Cheap when disabled — no string
     * formatting, no file IO, no allocation beyond the receiver fields.
     */
    fun event(tag: String, msg: String) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val line = "${dateFmt.format(Date(now))} ${timeFmt.format(Date(now))} $tag: $msg"
        runCatching { channel.trySend(line) }
    }

    /** Erase both log files. */
    fun clear() {
        runCatching {
            File(dir, CURRENT).delete()
            File(dir, PREV).delete()
        }
        if (enabled) event("DiagLog", "cleared")
    }

    /** Total bytes currently held across both files. */
    fun totalBytes(): Long {
        val d = dir ?: return 0
        return (File(d, CURRENT).takeIf { it.exists() }?.length() ?: 0L) +
            (File(d, PREV).takeIf { it.exists() }?.length() ?: 0L)
    }

    /** Path the user should be told to retrieve. */
    fun directoryPath(): String = dir?.absolutePath ?: "(not initialised)"

    private fun writeLine(line: String) {
        val d = dir ?: return
        val cur = File(d, CURRENT)
        if (cur.exists() && cur.length() >= MAX_FILE_BYTES) {
            // Rotate.
            val prev = File(d, PREV)
            if (prev.exists()) prev.delete()
            cur.renameTo(prev)
        }
        FileWriter(cur, true).use { w ->
            w.append(line).append('\n')
        }
    }
}
