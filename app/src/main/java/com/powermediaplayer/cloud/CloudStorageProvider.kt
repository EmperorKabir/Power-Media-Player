package com.powermediaplayer.cloud

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

interface CloudStorageProvider {
    val providerType: CloudProviderType
    
    /**
     * Emits true if the user is currently authenticated with this provider.
     */
    val isLoggedIn: StateFlow<Boolean>
    
    /**
     * Initiates the authentication flow. 
     * Implementations might use Activity Result APIs or Intents, thus Context is provided.
     */
    suspend fun authenticate(context: Context): Result<Unit>
    
    /**
     * Signs the user out locally and/or remotely.
     */
    suspend fun signOut(): Result<Unit>
    
    /**
     * Retrieves media files from the specified folder. 
     * If folderId is null, fetches the root directory.
     */
    suspend fun listFiles(folderId: String? = null): Result<List<CloudMediaItem>>
    
    /**
     * Retrieves a playable URI for the cloud media item.
     * This might return an API endpoint with an auth token appended, 
     * or a direct download link suitable for Media3 ExoPlayer.
     */
    suspend fun getMediaStreamUri(item: CloudMediaItem): Result<Uri>
}
