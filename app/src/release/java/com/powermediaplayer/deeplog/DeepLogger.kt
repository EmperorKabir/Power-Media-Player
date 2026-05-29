/*
 * DeepLogger.kt — RELEASE source-set NO-OP.
 *
 * Place in `src/release/java/<pkg>/deeplog/DeepLogger.kt`. Identical package and
 * public signatures to the debug implementation; every method is empty / returns
 * a trivial value. Because Android selects the per-variant source set, release
 * builds compile THIS file and never see the real logger — nothing is written,
 * no threads start, no callbacks register, and the heavy code does not link.
 *
 * Keep these signatures in lock-step with assets/DeepLogger.kt's public API.
 */
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.powermediaplayer.deeplog

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object DeepLogger {
    @JvmStatic fun install(app: Application) {}
    @JvmStatic fun setContextSupplier(supplier: () -> Map<String, Any?>) {}
    @JvmStatic @JvmOverloads fun log(category: String, payload: Map<String, Any?>, traceId: String? = null) {}
    @JvmStatic @JvmOverloads fun log(category: String, key: String, value: Any?, traceId: String? = null) {}
    @JvmStatic @JvmOverloads fun stateChange(name: String, from: Any?, to: Any?, trigger: String? = null, traceId: String? = null) {}
    @JvmStatic @JvmOverloads fun transition(machine: String, from: String, to: String, trigger: String, guardPassed: Boolean, traceId: String? = null) {}
    @JvmStatic @JvmOverloads fun emission(source: String, type: String, value: Any?, traceId: String? = null) {}
    @JvmStatic fun input(action: String, x: Float, y: Float, targetViewId: String?, traceId: String? = null) {}
    @JvmStatic @JvmOverloads fun exception(t: Throwable, fatal: Boolean = false, traceId: String? = null) {}
    @JvmStatic fun diagnostics(): Map<String, Any?> = emptyMap()
    @JvmStatic fun flushBlocking(timeoutMs: Long = 2_000L) {}
    @JvmStatic fun trace(traceId: String): CoroutineContext = EmptyCoroutineContext
    @JvmStatic fun currentTrace(): String? = null
    @JvmStatic fun logCoroutine(category: String, payload: Map<String, Any?>, ctx: CoroutineContext) {}
    @JvmStatic fun observePrefs(prefs: SharedPreferences, name: String) {}
    @JvmStatic fun broadcastReceived(receiver: BroadcastReceiver, intent: Intent) {}
    @JvmStatic fun serviceBound(component: ComponentName?) {}
}
