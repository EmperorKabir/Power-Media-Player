package com.powermediaplayer.data.repository

import com.powermediaplayer.data.db.dao.MediaOverrideDao
import com.powermediaplayer.data.db.dao.PodcastDao
import com.powermediaplayer.data.db.entity.MediaOverrideEntity
import com.powermediaplayer.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §C7 — single source of truth for the **active per-file override row**,
 * shared between [PlaybackService] (audio chain) and PlayerViewModel
 * (video/UI chain). Polls [PlaybackService.getExoPlayer]'s current
 * `mediaId` and emits the matching [MediaOverrideEntity] (or null).
 *
 * Both consumers compose this with their existing global setting flows
 * via the helper [withOverride] functions: when the override row has
 * a non-null axis, that wins; otherwise the global setting wins. The
 * user's global preferences are never touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MediaOverrideRepository @Inject constructor(
    private val dao: MediaOverrideDao,
    // Podcasts resolve episode → show → global. The repo maps the current
    // episode (mediaId == audioUrl) to its feedUrl so it can merge the
    // per-episode row over the per-show row (both live in media_overrides,
    // the show row keyed by feedUrl).
    private val podcastDao: PodcastDao
) {
    // ExoPlayer enforces "Main thread only" — polling currentMediaItem
    // on Dispatchers.Default raised IllegalStateException at cold start.
    // Repo's collector therefore lives on Main; downstream consumers
    // already run on Main (PlaybackService.serviceScope, viewModelScope).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * D10 fix — subscribe to PlaybackService's listener-driven
     * StateFlow instead of polling currentMediaItem on Main every
     * 750 ms. Zero work when no track is playing and zero work when
     * the user is idle on a track.
     */
    private val currentUri: Flow<String> =
        PlaybackService.currentMediaIdFlow

    val activeOverride: StateFlow<MediaOverrideEntity?> = currentUri
        .flatMapLatest { uri ->
            if (uri.isBlank()) {
                flowOf(null)
            } else {
                // When the current media is a podcast episode, merge its
                // per-episode override OVER the per-show override (episode wins
                // per-axis; a NULL axis falls through to the show, then to the
                // global setting downstream). Non-podcast media keeps the plain
                // per-uri lookup. The map miss (non-podcast / unknown) costs one
                // indexed suspend query per track change only.
                val feedUrl = runCatching {
                    podcastDao.episodeByAudioUrl(uri)?.feedUrl
                }.getOrNull()
                if (feedUrl.isNullOrBlank()) {
                    dao.getByUri(uri)
                } else {
                    combine(dao.getByUri(uri), dao.getByUri(feedUrl)) { ep, show ->
                        mergeEpisodeOverShow(uri, ep, show)
                    }
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Effective Int flow: override wins when non-null, else global. */
    fun withOverrideInt(
        global: Flow<Int>,
        pick: (MediaOverrideEntity) -> Int?
    ): Flow<Int> = combine(global, activeOverride) { g, o ->
        o?.let(pick) ?: g
    }.distinctUntilChanged()

    fun withOverrideBool(
        global: Flow<Boolean>,
        pick: (MediaOverrideEntity) -> Boolean?
    ): Flow<Boolean> = combine(global, activeOverride) { g, o ->
        o?.let(pick) ?: g
    }.distinctUntilChanged()

    fun withOverrideFloat(
        global: Flow<Float>,
        pick: (MediaOverrideEntity) -> Float?
    ): Flow<Float> = combine(global, activeOverride) { g, o ->
        o?.let(pick) ?: g
    }.distinctUntilChanged()

}

/**
 * Per-axis merge for the podcast episode → show → global chain. The episode
 * value wins; otherwise the show value; otherwise NULL so the downstream
 * `withOverride*` combine falls through to the user's global setting. Returns
 * null when neither row sets anything (no override at all). Pure → unit-tested
 * (MediaOverrideMergeTest) and load-bearing in [MediaOverrideRepository].
 */
internal fun mergeEpisodeOverShow(
    uri: String,
    ep: MediaOverrideEntity?,
    show: MediaOverrideEntity?
): MediaOverrideEntity? {
    if (ep == null && show == null) return null
    val merged = MediaOverrideEntity(
        mediaUri = uri,
        reverbPreset = ep?.reverbPreset ?: show?.reverbPreset,
        stereoFlip = ep?.stereoFlip ?: show?.stereoFlip,
        monoMix = ep?.monoMix ?: show?.monoMix,
        eqPresetId = ep?.eqPresetId ?: show?.eqPresetId,
        replayGainMode = ep?.replayGainMode ?: show?.replayGainMode,
        volumeBoostMb = ep?.volumeBoostMb ?: show?.volumeBoostMb,
        videoFlipH = ep?.videoFlipH ?: show?.videoFlipH,
        videoFlipV = ep?.videoFlipV ?: show?.videoFlipV,
        videoBw = ep?.videoBw ?: show?.videoBw,
        videoSepia = ep?.videoSepia ?: show?.videoSepia,
        videoInvert = ep?.videoInvert ?: show?.videoInvert,
        videoRotation = ep?.videoRotation ?: show?.videoRotation,
        playbackSpeed = ep?.playbackSpeed ?: show?.playbackSpeed,
        pitch = ep?.pitch ?: show?.pitch
    )
    return if (merged.isEmpty()) null else merged
}
