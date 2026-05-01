package com.powermediaplayer.cloud

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.google.common.collect.ImmutableMap

/**
 * Media3 [DataSource.Factory] that prepends a fresh `Authorization: Bearer …`
 * header to every request, so ExoPlayer can stream from Drive's
 * `?alt=media` endpoint with range-request support intact.
 *
 * The token is refetched per-request via [GoogleDriveProvider]; if no token
 * is available (signed out / refresh failed) the factory still produces a
 * source — the resulting 401 will surface as an ExoPlayer error.
 */
@OptIn(UnstableApi::class)
class GoogleDriveDataSourceFactory(
    private val driveProvider: GoogleDriveProvider
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val token = driveProvider.fetchAccessTokenBlocking()
        val headers = if (token != null) {
            ImmutableMap.of("Authorization", "Bearer $token")
        } else {
            ImmutableMap.of<String, String>()
        }
        return DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
    }
}
