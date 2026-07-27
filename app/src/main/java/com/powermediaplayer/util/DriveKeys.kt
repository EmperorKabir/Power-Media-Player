package com.powermediaplayer.util

/**
 * Drive URL ↔ stable-key helpers.
 *
 * BACKGROUND (P1 regression, vc63 89b5994): the Drive stream/download URL
 * `https://www.googleapis.com/drive/v3/files/{id}?alt=media` is used across the app
 * as the STABLE KEY for the Last-Played `mediaUri`, the chapter cache, the cover-art
 * cache and the sender-metadata map. vc63 appended `&supportsAllDrives=true` to that
 * stored URL, which silently RE-KEYED every Drive item across the update → orphaned
 * chapters/cover + a duplicate, position-reset Recents row.
 *
 * Fix model:
 *  - The STORED url reverts to the param-free `?alt=media` form (the pre-vc63 key), so
 *    keys stay identical across app updates.
 *  - [ensureFetchParams] re-adds `supportsAllDrives=true` ONLY at the actual HTTP fetch
 *    sites (stream resolver, range download, cast relay) so Shared-Drive files still
 *    resolve on the wire.
 *  - [canonicalKey] collapses any Drive files URL (regardless of query params) to a
 *    param-independent `drive:{id}` key; the cache classes canonicalise internally so a
 *    future URL change can never orphan them again.
 */
object DriveKeys {

    private val FILE_ID = Regex("/drive/v3/files/([^?/]+)")

    /**
     * Param-independent cache key for a media uri. A Drive files URL (any query params)
     * → `drive:{id}`; a `content://` SAF uri, a `spotify:` uri, or anything else is
     * returned unchanged. Idempotent.
     */
    fun canonicalKey(uri: String?): String {
        if (uri.isNullOrEmpty()) return ""
        val m = FILE_ID.find(uri) ?: return uri
        return "drive:${m.groupValues[1]}"
    }

    /**
     * Ensure a Drive files URL carries `supportsAllDrives=true` before it is fetched
     * (Shared-Drive items 404 without it). No-op for non-Drive URLs and idempotent for
     * URLs that already carry the param.
     */
    fun ensureFetchParams(url: String?): String {
        if (url.isNullOrEmpty()) return url ?: ""
        if (!url.contains("/drive/v3/files/")) return url
        if (url.contains("supportsAllDrives=")) return url
        return url + (if (url.contains('?')) "&" else "?") + "supportsAllDrives=true"
    }
}
