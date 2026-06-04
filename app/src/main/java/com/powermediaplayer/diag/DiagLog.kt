package com.powermediaplayer.diag

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.powermediaplayer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Opt-in persistent file logger for evidence-gathering during dev/testing.
 *
 * Design:
 *  - Disabled by default. Enabled via Settings → Diagnostic logging.
 *  - When enabled, writes time-stamped lines to
 *    {externalFilesDir}/diag/log-current.txt — rotated through
 *    log-prev-1.txt … log-prev-3.txt when it exceeds [MAX_FILE_BYTES].
 *    Total worst-case footprint ~200 MB across 4 files.
 *  - File location is app-private external storage:
 *    /storage/emulated/0/Android/data/com.powermediaplayer/files/diag/
 *    Visible to the user via the system Files app + `adb pull` without
 *    requiring runtime permissions on Android 10+.
 *  - Writes happen on a single IO coroutine fed by a Channel, so call
 *    sites never block the main thread.
 *  - Privacy: titles, mediaUris and other potentially identifying
 *    strings should be passed through [hash] before logging — hash
 *    keeps cross-event correlation possible without leaking content.
 *
 * Format of each line:
 *   YYYY-MM-DD HH:MM:SS.sss [sess=ABC123 +uptimeMs] TAG: msg
 *
 * - `sess`     — short hex token assigned on first init(). Lets a log
 *                reader trivially group lines from the same process,
 *                vital after a process kill / restart mid-session.
 * - `uptimeMs` — millis since process start (NOT since boot). Useful
 *                when correlating closely-spaced events where the
 *                wall-clock has 1 ms quantisation.
 *
 * Backward-compatible helper [event] preserves the original
 * "TAG: msg" body shape so existing call sites need no edits; new
 * structured helpers ([bt], [route], [player], [lifecycle], [ui],
 * [settings], [dec]) standardise the body as key=value pairs for
 * reliable parsing on the consumer side.
 */
object DiagLog {

    private const val MAX_FILE_BYTES = 50L * 1024L * 1024L // 50 MB per file
    private const val ROTATION_DEPTH = 3 // keep prev-1, prev-2, prev-3
    private const val DIR_NAME = "diag"
    private const val CURRENT = "log-current.txt"

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var dir: File? = null

    /** Short token identifying this process start. Reset on each [init]. */
    @Volatile
    private var sessionToken: String = "boot"

    /** Process start time (uptime). All "+ms" deltas reference this. */
    @Volatile
    private var startUptimeMs: Long = 0L

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
        sessionToken = UUID.randomUUID().toString().take(8)
        startUptimeMs = SystemClock.uptimeMillis()
        enabled = initiallyEnabled
        if (initiallyEnabled) {
            // Header line distinguishes a new process start from a
            // continuation of the previous log on the same file.
            event(
                "SESSION",
                "── start sess=$sessionToken build=${BuildConfig.VERSION_NAME}" +
                    " (vc${BuildConfig.VERSION_CODE}) device=${Build.MANUFACTURER}/${Build.MODEL}" +
                    " android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}" +
                    " abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} ──"
            )
        }
    }

    fun setEnabled(v: Boolean) {
        val was = enabled
        enabled = v
        if (v && !was) {
            event("SESSION", "logger enabled at runtime")
        } else if (!v && was) {
            event("SESSION", "logger disabled at runtime")
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Record a single event line. Cheap when disabled — no string
     * formatting, no file IO, no allocation beyond the receiver fields.
     */
    fun event(tag: String, msg: String) {
        // Debug-only logcat mirror — makes DiagLog visible to
        // `adb logcat -s PMP_DIAG_FILE` during dev / emulator runs
        // without needing the user to first toggle the file logger
        // on. R8 strips the entire block in release builds because
        // BuildConfig.DEBUG resolves to a compile-time `false`.
        if (BuildConfig.DEBUG) {
            android.util.Log.i("PMP_DIAG_FILE", "[$tag] $msg")
        }
        if (!enabled) return
        val now = System.currentTimeMillis()
        val upMs = SystemClock.uptimeMillis() - startUptimeMs
        val line = "${dateFmt.format(Date(now))} ${timeFmt.format(Date(now))} " +
            "[sess=$sessionToken +${upMs}ms] $tag: $msg"
        runCatching { channel.trySend(line) }
    }

    // ── Structured event helpers ─────────────────────────────────────
    //
    // Each helper renders the body as `key1=value1 key2=value2 …` so
    // a log consumer (you, future me, or a grep) can parse without
    // ambiguity. Keep the same tag prefix family across the codebase
    // so filtering on a single token (e.g. BT) catches the lot.

    /** Bluetooth / AVRCP / media-button events. */
    fun bt(msg: String) = event("BT", msg)

    /** Audio routing changes (BT, headphones, wired, speaker). */
    fun route(msg: String) = event("ROUTE", msg)

    /** Player state / error / item-transition. */
    fun player(msg: String) = event("PLAYER", msg)

    /** App / Activity / Service lifecycle. */
    fun lifecycle(msg: String) = event("LIFECYCLE", msg)

    /** User-visible UI actions (taps, navigation). */
    fun ui(msg: String) = event("UI", msg)

    /** Settings reads at start, writes mid-session. */
    fun settings(msg: String) = event("SETTINGS", msg)

    /**
     * "Decision" — explains WHY a branch was taken or skipped.
     * The reason field is the most useful field at debug time —
     * always include it.
     */
    fun dec(branch: String, reason: String) = event("DEC", "branch=$branch reason=$reason")

    /**
     * Resume-from-last-played path timing. vc31 investigation — the
     * testers reported multi-minute resume hangs + "keeps loading over
     * and over" on repeated taps. These events carry per-phase
     * elapsed-ms so the on-device log pinpoints which phase eats the
     * time, plus an attempt/active counter pair so the re-entrancy
     * (no tap-debounce) is visible.
     */
    fun resume(msg: String) = event("RESUME", msg)

    /**
     * Generic phase-timing helper. Logs a labelled phase with its
     * elapsed wall-clock cost. Use for any "this block was slow"
     * investigation — chapter parse, Drive fetch, ExoPlayer prepare.
     * Pair with [now] to capture the start timestamp.
     */
    fun perf(label: String, elapsedMs: Long, extra: String = "") =
        event("PERF", "$label took=${elapsedMs}ms${if (extra.isNotEmpty()) " $extra" else ""}")

    /** Monotonic timestamp for measuring elapsed phases. */
    fun now(): Long = SystemClock.uptimeMillis()

    /**
     * Hash a string (URI / title / package) to a short hex token. Same
     * input always produces same token, so events referencing the
     * "same" content are correlatable without exposing the content
     * itself. Returned token is 8 hex chars (64 bits) — collision-free
     * for a session.
     */
    fun hash(s: String?): String {
        if (s.isNullOrEmpty()) return "null"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(8)
        for (i in 0 until 4) sb.append("%02x".format(bytes[i]))
        return sb.toString()
    }

    /** Erase every log file in the rotation. */
    fun clear() {
        runCatching {
            val d = dir ?: return@runCatching
            File(d, CURRENT).delete()
            for (i in 1..ROTATION_DEPTH) File(d, "log-prev-$i.txt").delete()
        }
        if (enabled) event("SESSION", "cleared")
    }

    /** Total bytes currently held across every file in the rotation. */
    fun totalBytes(): Long {
        val d = dir ?: return 0L
        var total = (File(d, CURRENT).takeIf { it.exists() }?.length() ?: 0L)
        for (i in 1..ROTATION_DEPTH) {
            total += File(d, "log-prev-$i.txt").takeIf { it.exists() }?.length() ?: 0L
        }
        return total
    }

    /** Path the user should be told to retrieve. */
    fun directoryPath(): String = dir?.absolutePath ?: "(not initialised)"

    private fun writeLine(line: String) {
        val d = dir ?: return
        val cur = File(d, CURRENT)
        if (cur.exists() && cur.length() >= MAX_FILE_BYTES) {
            // Cascade rotation: prev-2 → prev-3, prev-1 → prev-2, cur → prev-1.
            // Oldest file (prev-ROTATION_DEPTH before promotion) is dropped.
            val oldest = File(d, "log-prev-$ROTATION_DEPTH.txt")
            if (oldest.exists()) oldest.delete()
            for (i in ROTATION_DEPTH downTo 2) {
                val from = File(d, "log-prev-${i - 1}.txt")
                val to = File(d, "log-prev-$i.txt")
                if (from.exists()) from.renameTo(to)
            }
            cur.renameTo(File(d, "log-prev-1.txt"))
        }
        FileWriter(cur, true).use { w ->
            w.append(line).append('\n')
        }
    }
}
