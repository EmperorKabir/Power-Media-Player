package com.powermediaplayer.cloud

/** #16 — pure, JVM-testable helpers for the enriched-metadata Drive search. */
object DriveMetadataSearch {
    /** Filename hits first, metadata-only hits appended, de-duped by id
     *  (downloadUrl fallback). Filename results win on collision. */
    fun mergeDriveResults(
        filenameHits: List<CloudMediaItem>,
        metadataHits: List<CloudMediaItem>
    ): List<CloudMediaItem> {
        val seen = HashSet<String>()
        val out = ArrayList<CloudMediaItem>(filenameHits.size + metadataHits.size)
        for (i in filenameHits) if (seen.add(dedupKey(i))) out.add(i)
        for (i in metadataHits) if (seen.add(dedupKey(i))) out.add(i)
        return out
    }

    private fun dedupKey(i: CloudMediaItem): String = i.id.ifBlank { i.downloadUrl }
}
