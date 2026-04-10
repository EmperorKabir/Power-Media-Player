package com.powermediaplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class annotated for Hilt dependency injection.
 * This is the entry point for the Hilt component hierarchy.
 */
@HiltAndroidApp
class PowerMediaPlayerApp : Application()
