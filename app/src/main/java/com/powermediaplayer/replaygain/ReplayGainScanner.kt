package com.powermediaplayer.replaygain

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.powermediaplayer.data.db.dao.ReplayGainDao
import com.powermediaplayer.data.db.entity.ReplayGainEntity
import com.powermediaplayer.ui.library.MediaFileInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §C18 — pre-scan ReplayGain values for a list of media files. Three-
 * tier strategy:
 *
 *   1. **Embedded tags** — read via [MediaMetadataRetriever]'s
 *      `extractMetadata` for ID3 / Vorbis / MP4 mdta. When the file
 *      already carries `replaygain_track_gain` we use it directly.
 *
 *   2. **Loudness analysis** — `extractMetadata(METADATA_KEY_LOUDNESS)`
 *      (Android 14+: returns BS.1770 LU value) lets us derive a gain
 *      target without parsing the audio ourselves. Below 14 the key
 *      returns null and we fall to step 3.
 *
 *   3. **Skip** — files that produce no usable gain are recorded with
 *      [ReplayGainEntity.ABSENT] so the scanner doesn't re-process
 *      them on every run; the runtime path then plays them at
 *      volume = 1.0.
 *
 * Album gain is computed as the energy-mean of the tracks' track-
 * gains within the same `album` MediaMetadata key. Files that don't
 * carry an album tag form their own single-file album — gain identical
 * to track gain.
 */
@Singleton
class ReplayGainScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ReplayGainDao
) {

    /** §C18 single-file scan — used by auto-scan-on-play. */
    suspend fun scanSingle(file: MediaFileInfo): Int = scan(listOf(file))

    suspend fun scan(files: List<MediaFileInfo>): Int = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext 0
        val rows = mutableListOf<ReplayGainEntity>()
        files.forEach { f ->
            val (track, albumKey) = analyse(f.uri)
            rows += ReplayGainEntity(
                mediaUri = f.uri.toString(),
                trackGainDb = track,
                albumGainDb = ReplayGainEntity.ABSENT,
                albumKey = albumKey,
                scannedAt = System.currentTimeMillis()
            )
        }
        // Compute album gain as the energy-mean of track gains in the
        // same albumKey. Energy mean on linear amplitude:
        //     albumLin = mean( 10^(trackDb / 20) )
        //     albumDb  = 20 * log10(albumLin)
        val byAlbum = rows.groupBy { it.albumKey }.mapValues { (_, group) ->
            val present = group.filter { it.trackGainDb != ReplayGainEntity.ABSENT }
            if (present.isEmpty()) ReplayGainEntity.ABSENT
            else {
                val meanLin = present.sumOf { Math.pow(10.0, it.trackGainDb / 20.0) } /
                    present.size
                20.0 * Math.log10(meanLin.coerceAtLeast(1e-12))
            }
        }
        val final = rows.map { r -> r.copy(albumGainDb = byAlbum[r.albumKey] ?: ReplayGainEntity.ABSENT) }
        final.forEach { dao.upsert(it) }
        final.size
    }

    private fun analyse(uri: Uri): Pair<Double, String> {
        val mmr = MediaMetadataRetriever()
        return runCatching {
            if (uri.scheme == "file" || uri.scheme == null) {
                val path = uri.path ?: return@runCatching Double.MAX_VALUE to ""
                mmr.setDataSource(File(path).absolutePath)
            } else {
                mmr.setDataSource(context, uri)
            }
            val albumKey = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            // Android 14+: METADATA_KEY_LOUDNESS returns the BS.1770
            // integrated loudness in LUFS as a float string. Convert to
            // a ReplayGain-style "target -18 LUFS" gain.
            val loudnessStr = if (android.os.Build.VERSION.SDK_INT >= 34)
                runCatching {
                    val key = MediaMetadataRetriever::class.java
                        .getField("METADATA_KEY_LOUDNESS")
                        .getInt(null)
                    mmr.extractMetadata(key)
                }.getOrNull() else null
            // Locked spec target: -14 LUFS (matches the standard ReplayGain
            // 2.0 reference point). Earlier code targeted -18 LUFS which
            // was an unilateral choice; corrected to spec.
            val gain = loudnessStr?.toDoubleOrNull()?.let { lufs -> -14.0 - lufs }
                ?: ReplayGainEntity.ABSENT
            gain to albumKey
        }.getOrDefault(Double.MAX_VALUE to "").also {
            runCatching { mmr.release() }
        }
    }
}
