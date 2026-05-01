package com.powermediaplayer.cloud

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.spotifyDataStore by preferencesDataStore(name = "spotify_auth")

/**
 * Persists the Spotify AuthState (PKCE access + refresh tokens) across app
 * restarts. Stored as the JSON serialization that AppAuth provides — we never
 * have to handcraft the schema.
 */
@Singleton
class SpotifyTokenStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val key = stringPreferencesKey("auth_state_json")

    fun observe(): Flow<String?> = context.spotifyDataStore.data.map { it[key] }

    suspend fun read(): String? = observe().first()

    suspend fun write(json: String?) {
        context.spotifyDataStore.edit { prefs ->
            if (json == null) prefs.remove(key) else prefs[key] = json
        }
    }
}
