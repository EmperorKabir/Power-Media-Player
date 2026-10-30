package com.powermediaplayer.playback

/** COLD-BOUNCE: a cold-start restore of a Spotify track sends a play command only when autoplay is on. */
object ColdStartSpotifyRule {
    fun sendsPlayCommand(autoplay: Boolean): Boolean = autoplay
}
