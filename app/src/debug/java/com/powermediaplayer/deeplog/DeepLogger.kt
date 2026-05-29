/*
 * DeepLogger.kt — DEBUG source-set implementation.
 *
 * Place this file in the app module's `src/debug/java/<pkg>/deeplog/DeepLogger.kt`.
 * A no-op twin with identical package + public signatures lives in
 * `src/release/java/<pkg>/deeplog/DeepLogger.kt` (see release/DeepLogger.kt in
 * this skill's assets). Android merges per-variant source sets, so release
 * builds link the no-op and the full implementation below is never compiled
 * into release.
 *
 * Public API is annotated @JvmStatic so it is fully callable from Java.
 *
 * Field list and rationale: references/data-spec.md.
 */
@file:Suppress("unused")

package com.powermediaplayer.deeplog

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.Job

/**
 * Forensic on-device logger. Asynchronous, NDJSON, fresh file per launch.
 *
 * Lifecycle: call [install] once at process start (the App Startup Initializer
 * or no-op ContentProvider does this for you). Everything else self-registers.
 */
object DeepLogger {

    // ---- Configuration ----------------------------------------------------

    private const val DIR_NAME = "deeplog"
    private const val QUEUE_CAPACITY = 8192          // bounded; drops are counted
    private const val FLUSH_EVERY = 64               // entries between flushes
    private const val MEMORY_SNAPSHOT_MS = 5_000L    // periodic memory/GC sample
    private val ISO: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    // ---- State ------------------------------------------------------------

    private val started = AtomicBoolean(false)
    private val seq = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val queueDepth = AtomicInteger(0)
    @Volatile private var queue: ArrayBlockingQueue<JSONObject>? = null
    @Volatile private var writerThread: Thread? = null
    @Volatile private var sessionFile: File? = null
    @Volatile private var appContext: Context? = null

    /** Optional supplier for build/flag context, set by the host if desired. */
    @Volatile private var contextSupplier: (() -> Map<String, Any?>)? = null

    // ---- Public API -------------------------------------------------------

    /** Install once at process start. Idempotent. Safe to call from Java. */
    @JvmStatic
    fun install(app: Application) {
        if (!started.compareAndSet(false, true)) return
        appContext = app.applicationContext
        openSessionFile(app)
        startWriter()
        writeSessionHeader(app)
        registerActivityCallbacks(app)
        registerMemorySampler()
        installCrashHandler()
        registerConnectivity(app)
        Choreographer.getInstance().postFrameCallback(FrameJankCallback)
        log("logger", mapOf("event" to "installed", "file" to sessionFile?.absolutePath))
    }

    /** Register a supplier of build/commit/feature-flag values captured per entry. */
    @JvmStatic
    fun setContextSupplier(supplier: () -> Map<String, Any?>) {
        contextSupplier = supplier
    }

    /** Core entry point. [category] is a capture-category tag; [payload] is free-form. */
    @JvmStatic
    @JvmOverloads
    fun log(category: String, payload: Map<String, Any?>, traceId: String? = null) {
        val q = queue ?: return
        val obj = baseEntry(category, traceId)
        val data = JSONObject()
        for ((k, v) in payload) data.put(k, normalize(v))
        obj.put("payload", data)
        enqueue(q, obj)
    }

    /** Convenience for a single key/value payload. */
    @JvmStatic
    @JvmOverloads
    fun log(category: String, key: String, value: Any?, traceId: String? = null) =
        log(category, mapOf(key to value), traceId)

    /** Programmatic state change — tagged DISTINCTLY from user input. */
    @JvmStatic
    @JvmOverloads
    fun stateChange(name: String, from: Any?, to: Any?, trigger: String? = null, traceId: String? = null) =
        log("state.programmatic", mapOf(
            "name" to name, "from" to from, "to" to to, "trigger" to trigger
        ), traceId)

    /** Explicit formal state-machine transition. */
    @JvmStatic
    @JvmOverloads
    fun transition(machine: String, from: String, to: String, trigger: String, guardPassed: Boolean, traceId: String? = null) =
        log("state.transition", mapOf(
            "machine" to machine, "from" to from, "to" to to,
            "trigger" to trigger, "guard" to guardPassed
        ), traceId)

    /** Flow/LiveData emission. */
    @JvmStatic
    @JvmOverloads
    fun emission(source: String, type: String, value: Any?, traceId: String? = null) =
        log("state.emission", mapOf("source" to source, "type" to type, "value" to value), traceId)

    /** Touch/input event — tagged DISTINCTLY from programmatic state. */
    @JvmStatic
    fun input(action: String, x: Float, y: Float, targetViewId: String?, traceId: String? = null) =
        log("input.touch", mapOf("action" to action, "x" to x, "y" to y, "target" to targetViewId), traceId)

    /** Full exception with complete cause chain. */
    @JvmStatic
    @JvmOverloads
    fun exception(t: Throwable, fatal: Boolean = false, traceId: String? = null) {
        log("exception", mapOf(
            "fatal" to fatal,
            "type" to t.javaClass.name,
            "message" to t.message,
            "causes" to causeChain(t),
            "stack" to stackString(t)
        ), traceId)
    }

    /** Self-diagnostics: dropped count, queue depth, last write latency. */
    @JvmStatic
    fun diagnostics(): Map<String, Any?> = mapOf(
        "dropped" to dropped.get(),
        "queueDepth" to queueDepth.get(),
        "seq" to seq.get(),
        "file" to sessionFile?.absolutePath
    )

    /** Force a drain+flush. Blocks briefly. Used by the crash handler. */
    @JvmStatic
    fun flushBlocking(timeoutMs: Long = 2_000L) {
        val q = queue ?: return
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (q.isNotEmpty() && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    // ---- Trace propagation across coroutines ------------------------------

    /**
     * A [ThreadContextElement] that carries a trace id across suspend boundaries.
     * Add to a scope: `launch(DeepLogger.trace("checkout-42")) { ... }`. Any
     * `DeepLogger.currentTrace()` inside that coroutine returns the id regardless
     * of which dispatcher thread the continuation resumed on.
     */
    class TraceContext(val traceId: String) : ThreadContextElement<String?>, CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<TraceContext>
        override val key: CoroutineContext.Key<*> get() = Key
        override fun updateThreadContext(context: CoroutineContext): String? {
            val prev = threadTrace.get()
            threadTrace.set(traceId)
            return prev
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
            if (oldState == null) threadTrace.remove() else threadTrace.set(oldState)
        }
    }

    private val threadTrace = ThreadLocal<String?>()

    /** Build a trace context element to attach to a coroutine scope. */
    @JvmStatic
    fun trace(traceId: String): CoroutineContext = TraceContext(traceId)

    /** Current trace id for this thread/coroutine, if any. */
    @JvmStatic
    fun currentTrace(): String? = threadTrace.get()

    // ---- Entry construction ----------------------------------------------

    private fun baseEntry(category: String, traceId: String?): JSONObject {
        val now = System.currentTimeMillis()
        val obj = JSONObject()
        obj.put("seq", seq.incrementAndGet())
        obj.put("nano", System.nanoTime())
        obj.put("wall", now)
        obj.put("iso", ISO.get()!!.format(now))
        obj.put("category", category)
        obj.put("trace", traceId ?: currentTrace())
        val thread = Thread.currentThread()
        obj.put("thread", thread.name)
        obj.put("tid", thread.id)
        // Coroutine context (best-effort: name/job recovered from thread name when present)
        coroutineInfo()?.let { obj.put("coroutine", it) }
        contextSupplier?.invoke()?.let { ctx ->
            val c = JSONObject(); for ((k, v) in ctx) c.put(k, normalize(v)); obj.put("ctx", c)
        }
        return obj
    }

    /**
     * Coroutine info. The robust path is to read [CoroutineName]/[Job] from the
     * active context; callers in suspend funs can pass them via [logCoroutine].
     * Here we surface the dispatcher hint embedded in the thread name.
     */
    private fun coroutineInfo(): JSONObject? {
        val name = Thread.currentThread().name
        if (!name.contains("Dispatch") && !name.contains("coroutine")) return null
        return JSONObject().put("dispatcherHint", name)
    }

    /** Log from inside a coroutine, capturing dispatcher/name/job state explicitly. */
    @JvmStatic
    @OptIn(kotlin.ExperimentalStdlibApi::class)
    fun logCoroutine(category: String, payload: Map<String, Any?>, ctx: CoroutineContext) {
        val q = queue ?: return
        val obj = baseEntry(category, (ctx[TraceContext.Key])?.traceId)
        val co = JSONObject()
        co.put("name", ctx[CoroutineName]?.name)
        val job = ctx[Job]
        co.put("job", when {
            job == null -> "none"
            job.isCancelled -> "cancelled"
            job.isCompleted -> "completed"
            job.isActive -> "active"
            else -> "new"
        })
        co.put("dispatcher", ctx[kotlinx.coroutines.CoroutineDispatcher.Key]?.toString())
        obj.put("coroutine", co)
        val data = JSONObject(); for ((k, v) in payload) data.put(k, normalize(v))
        obj.put("payload", data)
        enqueue(q, obj)
    }

    // ---- Queue + writer thread -------------------------------------------

    private fun enqueue(q: ArrayBlockingQueue<JSONObject>, obj: JSONObject) {
        if (q.offer(obj)) {
            queueDepth.set(q.size)
        } else {
            dropped.incrementAndGet()
        }
    }

    private fun startWriter() {
        val q = ArrayBlockingQueue<JSONObject>(QUEUE_CAPACITY)
        queue = q
        val file = sessionFile ?: return
        val t = Thread({
            val writer = BufferedWriter(FileWriter(file, /* append = */ true), 1 shl 16)
            var sinceFlush = 0
            try {
                while (true) {
                    val item = q.poll(1, TimeUnit.SECONDS)
                    if (item == null) { writer.flush(); continue }
                    val t0 = System.nanoTime()
                    writer.write(item.toString())
                    writer.write("\n")
                    val latency = System.nanoTime() - t0
                    queueDepth.set(q.size)
                    if (++sinceFlush >= FLUSH_EVERY) { writer.flush(); sinceFlush = 0 }
                    // Periodically emit our own write latency (cheap, low volume).
                    if (item.optString("category") == "logger.heartbeat") {
                        item.put("writeLatencyNs", latency)
                    }
                }
            } catch (_: InterruptedException) {
                // drain remaining then exit
                while (true) {
                    val item = q.poll() ?: break
                    writer.write(item.toString()); writer.write("\n")
                }
                writer.flush()
            } finally {
                try { writer.flush(); writer.close() } catch (_: Throwable) {}
            }
        }, "DeepLogger-writer")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY + 1
        t.start()
        writerThread = t
    }

    // ---- Session file -----------------------------------------------------

    private fun openSessionFile(ctx: Context) {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(System.currentTimeMillis())
        sessionFile = File(dir, "session-$stamp.ndjson")
    }

    private fun writeSessionHeader(ctx: Context) {
        val dm: DisplayMetrics = ctx.resources.displayMetrics
        val header = mapOf(
            "kind" to "session-header",
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "device" to Build.DEVICE,
            "api" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "abis" to Build.SUPPORTED_ABIS.toList(),
            "density" to dm.density,
            "densityDpi" to dm.densityDpi,
            "widthPx" to dm.widthPixels,
            "heightPx" to dm.heightPixels,
            "locale" to Locale.getDefault().toLanguageTag(),
            "pkg" to ctx.packageName,
            "startUptimeMs" to SystemClock.uptimeMillis()
        )
        log("session", header)
    }

    // ---- Lifecycle: Activities + Fragments + input ------------------------

    private fun registerActivityCallbacks(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                log("lifecycle.activity", mapOf("event" to "created", "activity" to a.javaClass.name))
                hookWindowTouch(a)
                if (a is FragmentActivity) registerFragmentCallbacks(a)
            }
            override fun onActivityStarted(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "started", "activity" to a.javaClass.name))
            override fun onActivityResumed(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "resumed", "activity" to a.javaClass.name))
            override fun onActivityPaused(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "paused", "activity" to a.javaClass.name))
            override fun onActivityStopped(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "stopped", "activity" to a.javaClass.name))
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "destroyed", "activity" to a.javaClass.name))
        })
    }

    private fun registerFragmentCallbacks(a: FragmentActivity) {
        a.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) =
                    log("lifecycle.fragment", mapOf("event" to "resumed", "fragment" to f.javaClass.name))
                override fun onFragmentPaused(fm: FragmentManager, f: Fragment) =
                    log("lifecycle.fragment", mapOf("event" to "paused", "fragment" to f.javaClass.name))
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, s: Bundle?) =
                    log("lifecycle.fragment", mapOf("event" to "viewCreated", "fragment" to f.javaClass.name))
                override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) =
                    log("lifecycle.fragment", mapOf("event" to "destroyed", "fragment" to f.javaClass.name))
            }, /* recursive = */ true
        )
    }

    /** Wrap the window callback to capture touches with coordinates + target id. */
    private fun hookWindowTouch(a: Activity) {
        val window = a.window ?: return
        val existing = window.callback
        window.callback = object : Window.Callback by existing {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val actionName = MotionEvent.actionToString(event.actionMasked)
                val targetId = try {
                    val v = a.window.decorView.findViewById<View>(
                        // hit-testing the exact view is expensive; record decor + coords
                        android.R.id.content
                    )
                    v?.let { resName(a, it.id) }
                } catch (_: Throwable) { null }
                input(actionName, event.x, event.y, targetId)
                return existing.dispatchTouchEvent(event)
            }
        }
    }

    private fun resName(ctx: Context, id: Int): String? =
        if (id == View.NO_ID) null else try { ctx.resources.getResourceEntryName(id) } catch (_: Throwable) { null }

    // ---- Frames / jank ----------------------------------------------------

    private object FrameJankCallback : Choreographer.FrameCallback {
        private var last = 0L
        private const val FRAME_BUDGET_NS = 16_666_666L // ~60fps
        override fun doFrame(frameTimeNanos: Long) {
            if (last != 0L) {
                val delta = frameTimeNanos - last
                if (delta > FRAME_BUDGET_NS * 2) {
                    log("render.frame", mapOf(
                        "deltaNs" to delta,
                        "droppedApprox" to (delta / FRAME_BUDGET_NS - 1)
                    ))
                }
            }
            last = frameTimeNanos
            try { Choreographer.getInstance().postFrameCallback(this) } catch (_: Throwable) {}
        }
    }

    // ---- Memory + GC ------------------------------------------------------

    private fun registerMemorySampler() {
        val ht = HandlerThread("DeepLogger-mem").apply { start() }
        val handler = Handler(ht.looper)
        val rt = Runtime.getRuntime()
        val task = object : Runnable {
            override fun run() {
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                log("memory", mapOf(
                    "totalPss" to mi.totalPss,
                    "dalvikPss" to mi.dalvikPss,
                    "nativePss" to mi.nativePss,
                    "heapUsed" to (rt.totalMemory() - rt.freeMemory()),
                    "heapMax" to rt.maxMemory()
                ))
                handler.postDelayed(this, MEMORY_SNAPSHOT_MS)
            }
        }
        handler.post(task)
    }

    // ---- Crash handler ----------------------------------------------------

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                exception(throwable, fatal = true)
                flushBlocking()
            } catch (_: Throwable) {
            } finally {
                prev?.uncaughtException(thread, throwable)
            }
        }
    }

    // ---- Connectivity (lightweight; full receiver wired by host if needed) -

    private fun registerConnectivity(app: Application) {
        // Hosts on API 24+ should prefer ConnectivityManager.NetworkCallback;
        // we log the initial snapshot and leave callback registration to the
        // network template to avoid duplicate permissions handling here.
        log("connectivity", mapOf("event" to "init"))
    }

    // ---- SharedPreferences hook (call from host if desired) ---------------

    /** Attach a change listener to a prefs instance to log writes. */
    @JvmStatic
    fun observePrefs(prefs: SharedPreferences, name: String) {
        prefs.registerOnSharedPreferenceChangeListener { sp, key ->
            log("prefs.write", mapOf("store" to name, "key" to key, "value" to sp.all[key]))
        }
    }

    // ---- Broadcast / service helpers (call from host receivers) -----------

    @JvmStatic
    fun broadcastReceived(receiver: BroadcastReceiver, intent: Intent) =
        log("broadcast", mapOf(
            "receiver" to receiver.javaClass.name,
            "action" to intent.action,
            "extras" to intent.extras?.keySet()?.associateWith { intent.extras?.get(it)?.toString() }
        ))

    @JvmStatic
    fun serviceBound(component: ComponentName?) =
        log("service", mapOf("event" to "bound", "component" to component?.flattenToString()))

    // ---- Value normalization ----------------------------------------------

    private fun normalize(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Number, is Boolean, is String -> v
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, vv) -> o.put(k.toString(), normalize(vv)) } }
        is Iterable<*> -> JSONArray().also { arr -> v.forEach { arr.put(normalize(it)) } }
        is Array<*> -> JSONArray().also { arr -> v.forEach { arr.put(normalize(it)) } }
        else -> v.toString()
    }

    private fun causeChain(t: Throwable): JSONArray {
        val arr = JSONArray()
        var cur: Throwable? = t.cause
        var guard = 0
        while (cur != null && guard < 20) {
            arr.put(JSONObject()
                .put("type", cur.javaClass.name)
                .put("message", cur.message))
            cur = cur.cause
            guard++
        }
        return arr
    }

    private fun stackString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
