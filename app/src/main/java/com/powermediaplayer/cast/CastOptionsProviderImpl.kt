package com.powermediaplayer.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Cast SDK configuration. Registered via meta-data in AndroidManifest so the
 * Cast framework can locate it on first MediaRouteButton interaction.
 *
 * Uses the default Styled Media Receiver (no custom receiver app required).
 */
class CastOptionsProviderImpl : OptionsProvider {

    override fun getCastOptions(appContext: Context): CastOptions {
        val notificationOptions = NotificationOptions.Builder().build()
        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .build()
        return CastOptions.Builder()
            // Default Styled Media Receiver — works for audio + video without
            // needing a Cast Developer Console registration.
            .setReceiverApplicationId("CC1AD845")
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
