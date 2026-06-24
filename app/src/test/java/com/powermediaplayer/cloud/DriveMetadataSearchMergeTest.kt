package com.powermediaplayer.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** #16 — merge/dedup of filename + enriched-metadata Drive search results. */
class DriveMetadataSearchMergeTest {
    private fun item(id: String, name: String) = CloudMediaItem(
        id = id, name = name, mimeType = "audio/*", size = 0L,
        downloadUrl = id, sourceProvider = CloudProviderType.GOOGLE_DRIVE
    )

    @Test fun filenameFirst_thenMetadataOnly_deduped() {
        val fn = listOf(item("id1", "A.m4b"), item("id2", "B.m4b"))
        val meta = listOf(item("id2", "B.m4b"), item("id3", "Matt book.m4b"))
        val out = DriveMetadataSearch.mergeDriveResults(fn, meta)
        assertEquals(listOf("id1", "id2", "id3"), out.map { it.id }) // id2 not duplicated
    }

    @Test fun emptyMetadata_returnsFilenameUnchanged() {
        val fn = listOf(item("id1", "A.m4b"))
        assertEquals(fn, DriveMetadataSearch.mergeDriveResults(fn, emptyList()))
    }
}
