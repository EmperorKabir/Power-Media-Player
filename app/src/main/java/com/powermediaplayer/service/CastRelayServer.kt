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

    // Audit §9.2: tokens are URL path segments on a wildcard-bound LAN server.
    // The old AtomicLong counter yielded guessable "1","2","3" — any LAN device
    // could enumerate and fetch the user's media during a cast session. Opaque
    // end-to-end (generated here, embedded in the receiver URL, looked up in the
    // map), so widening to 128-bit random hex changes nothing but guessability.
    private val tokenRandom = java.security.SecureRandom()
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

    // moov larger than this → fall back (the head must fit comfortably in RAM)
    private val MAX_FASTSTART_MOOV = 48L * 1024 * 1024

    // head-prep (ftyp + moov fetch + patch) runs off the NanoHTTPD worker threads
    private val prepExecutor = java.util.concurrent.Executors.newCachedThreadPool()

    sealed class RelayItem(val mimeType: String) {
        class Local(val uri: Uri, mimeType: String) : RelayItem(mimeType)
        class DriveOAuth(val fileId: String, mimeType: String) : RelayItem(mimeType)

        /**
         * Streaming faststart proxy for a moov-at-end Drive audiobook (T302). On
         * [register] the server fetches only the `ftyp` head + the `moov` tail (~19 MB)
         * once, patches the chunk offsets in memory, and thereafter serves a synthesised
         * `[ftyp][moov'][mdat]` stream (mdat proxied live from Drive). The receiver reads
         * the sample tables at the FRONT, seeks, and streams from the seek point — no full
         * download, and the cast item never has to be swapped.
         *
         * [head] is null until [ready] counts down; null AFTER ready means head-prep
         * decided to fall back (already-faststart / oversize / parse anomaly / fetch fail)
         * → serve as a plain Drive passthrough (no regression).
         */
        class FaststartDrive(
            val fileId: String,
            mimeType: String,
            val onOversize: (() -> Unit)? = null
        ) : RelayItem(mimeType) {
            val ready = java.util.concurrent.CountDownLatch(1)
            @Volatile var head: FaststartHead? = null
        }
    }

    /** The in-memory `ftyp + patched moov'` plus the synth layout for one FaststartDrive. */
    class FaststartHead(val bytes: ByteArray, val layout: com.powermediaplayer.util.FaststartStream.Layout)

    /**
     * Register an item for relay. Returns an opaque token; build the URL
     * as `http://<lanIp>:<listeningPort>/<token>` and pass it to
     * `CastPlayer.setMediaItems`.
     */
    @Synchronized
    fun register(item: RelayItem): String {
        val token = ByteArray(16).let { b ->
            tokenRandom.nextBytes(b)
            b.joinToString("") { "%02x".format(it) }
        }
        items[token] = item
        if (item is RelayItem.FaststartDrive) {
            runCatching { prepExecutor.submit { prepareFaststart(item) } }
        }
        return token
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    override fun stop() {
        super.stop()
        runCatching { prepExecutor.shutdownNow() }
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
                is RelayItem.FaststartDrive -> serveFaststart(item, rangeHeader)
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
        val url = "https://www.googleapis.com/drive/v3/files/${item.fileId}?alt=media&supportsAllDrives=true"
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

    /**
     * Serve the synthesised faststart stream `[ftyp][moov'][mdat]` (T302). Waits for
     * head-prep, then maps the receiver's Range to head bytes (memory) + mdat bytes
     * (proxied live from Drive) via [com.powermediaplayer.util.FaststartStream]. If
     * head-prep fell back (head still null after ready) it serves a plain Drive
     * passthrough — never worse than before.
     */
    private fun serveFaststart(item: RelayItem.FaststartDrive, rangeHeader: String?): Response {
        // Only the FIRST request waits (~moov fetch); later seeks hit the cached head.
        val ready = runCatching { item.ready.await(25, TimeUnit.SECONDS) }.getOrDefault(false)
        val head = item.head
        if (!ready || head == null) {
            Diag.w("PMP_DIAG", "CastRelay faststart → passthrough (ready=$ready head=${head != null}) fileId=${item.fileId}")
            return serveDrive(RelayItem.DriveOAuth(item.fileId, item.mimeType), rangeHeader)
        }
        val total = head.layout.total
        val (start, endRaw) = parseRange(rangeHeader, total)
        val end = if (endRaw >= 0) endRaw else total - 1
        if (start >= total || start > end) {
            val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "range not satisfiable")
            r.addHeader("Content-Range", "bytes */$total")
            return r
        }
        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val body = com.powermediaplayer.util.FaststartStream.open(
            head.bytes, head.layout, start, end
        ) { origStart, len -> driveRangeStream(item.fileId, origStart, origStart + len - 1) }
        val resp = newFixedLengthResponse(status, item.mimeType, body, end - start + 1)
        resp.addHeader("Content-Range", "bytes $start-$end/$total")
        resp.addHeader("Accept-Ranges", "bytes")
        Diag.d(
            "PMP_DIAG",
            "CastRelay faststart status=$status range=$start-$end/$total " +
                "ftyp=${head.layout.ftypSize} moov=${head.layout.moovSize} fileId=${item.fileId}"
        )
        return resp
    }

    /**
     * Walk the moov-at-end file's top-level atoms via tiny Drive Range reads, fetch the
     * ftyp + moov, patch the chunk offsets in memory, and publish [RelayItem.FaststartDrive.head].
     * Any anomaly / oversize / already-faststart leaves head null (→ serveDrive passthrough).
     */
    private fun prepareFaststart(item: RelayItem.FaststartDrive) {
        val fileId = item.fileId
        try {
            val (h0, total) = driveRangeBytes(fileId, 0, 15) ?: run { item.ready.countDown(); return }
            val ftypHdr = com.powermediaplayer.util.Mp4Faststart.parseBoxHeader(h0, 0)
            if (ftypHdr == null || ftypHdr.type != "ftyp" || total <= 0) {
                Diag.w("PMP_DIAG", "Faststart prep: not ftyp / no total fileId=$fileId")
                item.ready.countDown(); return
            }
            val ftypSize = ftypHdr.size
            var pos = ftypSize
            var moovOffset = -1L
            var moovSize = -1L
            var sawMdat = false
            var guard = 0
            while (pos + 16 <= total && guard++ < 16) {
                val hb = driveRangeBytes(fileId, pos, pos + 15)?.first ?: break
                val bh = com.powermediaplayer.util.Mp4Faststart.parseBoxHeader(hb, 0) ?: break
                val size = if (bh.size == 0L) total - pos else bh.size
                if (size < bh.headerLen) break
                when (bh.type) {
                    "moov" -> { moovOffset = pos; moovSize = size }
                    "mdat" -> sawMdat = true
                }
                if (bh.type == "moov") break
                pos += size
            }
            // no moov, or moov already BEFORE mdat (already faststart) → passthrough
            if (moovOffset < 0 || moovSize <= 0 || !sawMdat) {
                Diag.i("PMP_DIAG", "Faststart prep: passthrough (moovOffset=$moovOffset sawMdat=$sawMdat) fileId=$fileId")
                item.ready.countDown(); return
            }
            // The streaming synth model shifts EVERYTHING after ftyp by a uniform +moovSize,
            // which is only valid if moov is the FINAL atom. A trailing atom after moov
            // ([ftyp][mdat][moov][free/udta/…]) would make the drive region overlap real moov
            // bytes → corrupt. Fall back to passthrough (the download+remux path handles it).
            if (moovOffset + moovSize != total) {
                Diag.i("PMP_DIAG", "Faststart prep: moov not last (end=${moovOffset + moovSize} total=$total) → passthrough fileId=$fileId")
                item.ready.countDown(); return
            }
            if (moovSize > MAX_FASTSTART_MOOV) {
                Diag.w("PMP_DIAG", "Faststart prep: moov $moovSize > cap → passthrough+fallback fileId=$fileId")
                runCatching { item.onOversize?.invoke() }
                item.ready.countDown(); return
            }
            val ftyp = driveRangeBytes(fileId, 0, ftypSize - 1)?.first ?: run { item.ready.countDown(); return }
            val moov = driveRangeBytes(fileId, moovOffset, moovOffset + moovSize - 1)?.first
                ?: run { item.ready.countDown(); return }
            val patched = com.powermediaplayer.util.Mp4Faststart.patchMoovBox(moov, moovSize)
                ?: run {
                    Diag.w("PMP_DIAG", "Faststart prep: patchMoovBox null → passthrough fileId=$fileId")
                    item.ready.countDown(); return
                }
            item.head = FaststartHead(
                ftyp + patched,
                com.powermediaplayer.util.FaststartStream.Layout(ftypSize, moovSize, total)
            )
            item.ready.countDown()
            Diag.i(
                "PMP_DIAG",
                "Faststart head ready fileId=$fileId ftyp=$ftypSize moov=$moovSize total=$total"
            )
        } catch (t: Throwable) {
            Diag.w("PMP_DIAG", "Faststart prep failed fileId=$fileId", t)
            item.ready.countDown()
        }
    }

    /** Blocking Drive Range GET fully into memory; returns the bytes + the file total
     *  (parsed from Content-Range). For the small head-prep reads only. */
    private fun driveRangeBytes(fileId: String, start: Long, end: Long): Pair<ByteArray, Long>? {
        val token = driveOAuthProvider.fetchAccessTokenBlocking() ?: return null
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true"
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Range", "bytes=$start-$end")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body ?: return null
            // Require 206: a 200 means the server ignored Range and is sending the WHOLE
            // (multi-GB) file → body.bytes() would OOM the prep thread. Treat as failure → passthrough.
            if (resp.code != 206) return null
            val bytes = body.bytes()
            val total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
            return bytes to total
        }
    }

    /** Blocking Drive Range GET returning the live stream; closing it closes the upstream
     *  OkHttp response. Used to proxy the mdat segment of the faststart stream. */
    private fun driveRangeStream(fileId: String, start: Long, end: Long): InputStream {
        val token = driveOAuthProvider.fetchAccessTokenBlocking()
            ?: throw java.io.IOException("drive token unavailable")
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true"
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Range", "bytes=$start-$end")
            .build()
        val resp = http.newCall(req).execute()
        val body = resp.body
        if (!resp.isSuccessful || body == null) {
            resp.close()
            throw java.io.IOException("drive upstream ${resp.code}")
        }
        return object : java.io.FilterInputStream(body.byteStream()) {
            override fun close() {
                runCatching { super.close() }
                runCatching { resp.close() }
            }
        }
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
        val startStr = parts.getOrNull(0)
        val endStr = parts.getOrNull(1)
        // Suffix range "bytes=-N" (RFC 7233 = the final N bytes). split → ["","N"].
        if (startStr.isNullOrBlank()) {
            val suffix = endStr?.toLongOrNull()
            return if (suffix != null && totalLength > 0)
                (totalLength - suffix).coerceAtLeast(0L) to (totalLength - 1)
            else 0L to -1L
        }
        val start = startStr.toLongOrNull() ?: 0L
        val end = endStr?.toLongOrNull()
            ?: (if (totalLength > 0) totalLength - 1 else -1L)
        return start to end
    }
}
