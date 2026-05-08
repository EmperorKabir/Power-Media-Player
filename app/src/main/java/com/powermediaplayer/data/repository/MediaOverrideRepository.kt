package com.powermediaplayer.data.repository

import com.powermediaplayer.data.db.dao.MediaOverrideDao
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
    private val dao: MediaOverrideDao
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
            if (uri.isBlank()) flowOf(null) else dao.getByUri(uri)
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
