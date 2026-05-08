package com.powermediaplayer.data.repository

import com.powermediaplayer.data.db.dao.MediaOverrideDao
import com.powermediaplayer.data.db.entity.MediaOverrideEntity
import com.powermediaplayer.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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
     * Current track's `mediaId`, polled every 750 ms on Main. We poll
     * instead of subscribing to the player's listener because the
     * listener lives on the service side and is shared with several
     * existing subsystems; an extra poll keeps this repo decoupled.
     */
    private val currentUri: Flow<String> = flow {
        var last = ""
        while (currentCoroutineContextActive()) {
            val uri = PlaybackService.getExoPlayer()
                ?.currentMediaItem?.mediaId.orEmpty()
            if (uri != last) {
                last = uri
                emit(uri)
            }
            delay(750)
        }
    }
        .flowOn(Dispatchers.Main.immediate)
        .distinctUntilChanged()

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

    private fun currentCoroutineContextActive(): Boolean {
        // CoroutineScope.isActive isn't directly callable from inside a
        // flow {} builder without bringing in extra context; using the
        // backing scope here is sufficient because we cancel this scope
        // never (singleton lives for the app's lifetime). The poll
        // self-terminates if the SharingStarted policy stops sharing.
        return scope.isActive
    }
}
