package com.powermediaplayer.service

import android.content.Context
import android.net.Uri
import com.powermediaplayer.cloud.DriveOAuthProvider
import com.powermediaplayer.util.Diag
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded HTTP relay that exposes locally-resolvable media (`file://`,
 * `content://`) and OAuth-protected Drive HTTPS URLs to a Cast receiver
 * via plain `http://<phone-LAN-IP>:<port>/<token>` URLs.
 *
 * Why: the default Cast Media Receiver `CC1AD845` only fetches public
 * HTTPS URLs without auth. It cannot resolve `content://` (no
 * ContentResolver outside our process), `file://` (no LAN access to the
 * phone's filesystem), or googleapis.com URLs that require an
 * `Authorization: Bearer` header (the receiver can't add headers).
 *
 * This server lives only while a Cast session is active. PlaybackService
 * starts it on `onCastSessionAvailable` and stops it on
 * `onCastSessionUnavailable`.
 *
 * Range requests are forwarded:
 *   - Local / SAF: open the InputStream, skip(rangeStart), serve.
 *   - Drive: rebuild the upstream Range header verbatim and forward to
 *     `googleapis.com/drive/v3/files/{id}?alt=media` with a fresh Bearer
 *     token. Token is refreshed on every request via
 *     [DriveOAuthProvider.fetchAccessTokenBlocking] so a long playback
 *     session that crosses the 1h token boundary just works.
 */
open class CastRelayServer(
    private val context: Context,
    private val driveOAuthProvider: DriveOAuthProvider,
    port: Int = 0  // 0 = ephemeral; query .listeningPort after start()
    // Single-arg ctor binds wildcard (all interfaces) so the cast
    // receiver on the LAN can reach us via the phone's Wi-Fi IP.
) : NanoHTTPD(port) {

    private val tokenCounter = AtomicLong(0)
    // ConcurrentHashMap: serve() reads from NanoHTTPD worker threads while
    // register() writes from the cast-switch path — a plain map has no
    // visibility guarantee across those threads, so a fresh token could
    // read as absent (spurious 404 → receiver error → cast flakiness).
    private val items: java.util.concurrent.ConcurrentHashMap<String, RelayItem> =
        java.util.concurrent.ConcurrentHashMap()
    private val http: OkHttpClient = com.powermediaplayer.util.SharedHttp.base.newBuilder()  // shared pool (audit 5.3)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)  // media streams run as long as playback
        .cache(null)                       // ranged media must not enter the HTTP cache
        .build()

    sealed class RelayItem(val mimeType: String) {
        class Local(val uri: Uri, mimeType: String) : RelayItem(mimeType)
        class DriveOAuth(val fileId: String, mimeType: String) : RelayItem(mimeType)
    }

    /**
     * Register an item for relay. Returns an opaque token; build the URL
     * as `http://<lanIp>:<listeningPort>/<token>` and pass it to
     * `CastPlayer.setMediaItems`.
     */
    @Synchronized
    fun register(item: RelayItem): String {
        val token = tokenCounter.incrementAndGet().toString(16)
        items[token] = item
        return token
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val token = session.uri.trimStart('/').substringBefore('/')
        val item = items[token]
        if (item == null) {
            Diag.w("PMP_DIAG", "CastRelay 404: unknown token=$token")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "unknown token")
        }
        val rangeHeader = session.headers["range"]
        Diag.i("PMP_DIAG", "CastRelay GET /$token range=$rangeHeader item=${item::class.simpleName}")
        return try {
            when (item) {
                is RelayItem.Local -> serveLocal(item, rangeHeader)
                is RelayItem.DriveOAuth -> serveDrive(item, rangeHeader)
            }
        } catch (t: Throwable) {
            Diag.w("PMP_DIAG", "CastRelay serve failed token=$token", t)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "relay error")
        }
    }

    private fun serveLocal(item: RelayItem.Local, rangeHeader: String?): Response {
        val resolver = context.contentResolver
        // Open a SEEKABLE file descriptor. A Range request to a large offset
        // (e.g. the moov atom at the END of a multi-GB MP4 — the receiver asks
        // for it first) is then served by an INSTANT lseek via the file
        // channel, instead of read-and-discarding gigabytes through skip()
        // (which caused the 20-30s cast start + starved the receiver into
        // constant buffering on big files).
        // Some content providers only support stream access and either return
        // null or THROW on openFileDescriptor — the seekable-fd path then 404s
        // (or 500s on the throw) content that the old openInputStream path
        // served. Fall back to a plain stream + skip for those.
        val pfd = try {
            resolver.openFileDescriptor(item.uri, "r")
        } catch (t: Throwable) {
            Diag.w("PMP_DIAG", "CastRelay Local: openFileDescriptor threw (${t.message}) → stream fallback")
            null
        } ?: return serveLocalStream(item, rangeHeader)
        val totalLength: Long = runCatching { pfd.statSize }.getOrDefault(-1L)
        val (start, end) = parseRange(rangeHeader, totalLength)
        val fis = java.io.FileInputStream(pfd.fileDescriptor)
        val contentLength = if (end >= 0 && totalLength > 0) (end - start + 1) else -1L
        val status = if (rangeHeader != null && totalLength > 0)
            Response.Status.PARTIAL_CONTENT else Response.Status.OK
        // NanoHTTPD owns the stream only once it's wrapped in a Response;
        // a throw before that must close the fd here or it leaks per request.
        val resp = try {
            if (start > 0) {
                // True random-access seek (instant). Fall back to read-skip
                // only if the fd somehow isn't seekable (non-file provider).
                runCatching { fis.channel.position(start) }.getOrElse { fis.skipFully(start) }
            }
            // Close the underlying ParcelFileDescriptor when NanoHTTPD closes
            // the served stream — otherwise the fd leaks per request.
            val served = object : java.io.FilterInputStream(fis) {
                override fun close() {
                    runCatching { super.close() }
                    runCatching { pfd.close() }
                }
            }
            if (contentLength > 0) {
                newFixedLengthResponse(status, item.mimeType, served, contentLength)
            } else {
                newChunkedResponse(status, item.mimeType, served)
            }
        } catch (t: Throwable) {
            runCatching { fis.close() }
            runCatching { pfd.close() }
            throw t
        }
        if (rangeHeader != null && totalLength > 0) {
            resp.addHeader("Content-Range", "bytes $start-${if (end >= 0) end else totalLength - 1}/$totalLength")
            resp.addHeader("Accept-Ranges", "bytes")
        }
        Diag.d(
            "PMP_DIAG",
            "CastRelay Local response status=$status mime=${item.mimeType} " +
                "contentLength=$contentLength range=${start}-${end} total=$totalLength seek=lseek"
        )
        return resp
    }

    /**
     * Stream fallback for content providers without a seekable fd: open the
     * InputStream, skip to the range start, serve chunked. No Content-Range
     * (total length unknown for a pure stream), but Accept-Ranges advertised
     * and the bytes from [start] onward delivered — restores the pre-lseek
     * behaviour for those providers instead of failing the cast.
     */
    private fun serveLocalStream(item: RelayItem.Local, rangeHeader: String?): Response {
        val input = try {
            context.contentResolver.openInputStream(item.uri)
        } catch (t: Throwable) {
            Diag.w("PMP_DIAG", "CastRelay Local 404: openInputStream failed (${t.message})")
            null
        } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no stream")
        val (start, _) = parseRange(rangeHeader, -1L)
        if (start > 0) runCatching { input.skipFully(start) }
        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val resp = newChunkedResponse(status, item.mimeType, input)
        resp.addHeader("Accept-Ranges", "bytes")
        Diag.d("PMP_DIAG", "CastRelay Local response status=$status mime=${item.mimeType} seek=stream-skip start=$start")
        return resp
    }

    private fun serveDrive(item: RelayItem.DriveOAuth, rangeHeader: String?): Response {
        val token = driveOAuthProvider.fetchAccessTokenBlocking()
            ?: return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "text/plain", "drive token unavailable"
            )
        val url = "https://www.googleapis.com/drive/v3/files/${item.fileId}?alt=media"
        val reqBuilder = Request.Builder().url(url).header("Authorization", "Bearer $token")
        if (rangeHeader != null) reqBuilder.header("Range", rangeHeader)
        val resp = http.newCall(reqBuilder.build()).execute()
        val body = resp.body
        if (!resp.isSuccessful || body == null) {
            Diag.w("PMP_DIAG", "CastRelay drive upstream=${resp.code}")
            resp.close()
            return newFixedLengthResponse(
                Response.Status.lookup(resp.code) ?: Response.Status.INTERNAL_ERROR,
                "text/plain",
                "drive upstream ${resp.code}"
            )
        }
        val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
        val mimeType = resp.header("Content-Type") ?: item.mimeType
        val status = if (resp.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val out = if (contentLength > 0) {
            newFixedLengthResponse(status, mimeType, body.byteStream(), contentLength)
        } else {
            newChunkedResponse(status, mimeType, body.byteStream())
        }
        resp.header("Content-Range")?.let { out.addHeader("Content-Range", it) }
        out.addHeader("Accept-Ranges", "bytes")
        // §Phase 11 Task 11.1: log every Drive response so the cast bug
        // bisect can correlate Drive 401/403/206/200 with receiver
        // loadMedia outcomes.
        Diag.d(
            "PMP_DIAG",
            "CastRelay Drive upstream=${resp.code} mime=$mimeType contentLength=$contentLength " +
                "range=${resp.header("Content-Range")} fileId=${item.fileId}"
        )
        return out
    }

    private fun InputStream.skipFully(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun parseRange(header: String?, totalLength: Long): Pair<Long, Long> {
        if (header == null || !header.startsWith("bytes=")) return 0L to -1L
        val spec = header.removePrefix("bytes=")
        val parts = spec.split("-")
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.toLongOrNull()
            ?: (if (totalLength > 0) totalLength - 1 else -1L)
        return start to end
    }
}
