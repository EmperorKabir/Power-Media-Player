package com.powermediaplayer.util

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * Process-wide OkHttp base. Twelve separate clients each ran their own
 * connection pool + dispatcher, none had an HTTP cache, and two had no
 * call timeout at all (audit 5.3) — identical Drive listings were
 * re-fetched on every navigation and a stalled body read could hang a
 * play tap indefinitely. Derive per-concern variants via
 * `base.newBuilder()`: derived clients share the pool, dispatcher and
 * cache. The Hue bridge clients stay separate by design — LAN-only with
 * trust-all TLS, nothing to share with WAN traffic.
 */
object SharedHttp {
    @Volatile private var cacheDir: File? = null

    /** Call once from Application.onCreate, before the first [base] use. */
    fun installCacheDir(dir: File) {
        if (cacheDir == null) cacheDir = dir
    }

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Bounded by default; long media transfers override with 0.
            .callTimeout(30, TimeUnit.SECONDS)
            .apply {
                cacheDir?.let { cache(Cache(File(it, "http_cache"), 10L * 1024 * 1024)) }
            }
            .build()
    }
}
