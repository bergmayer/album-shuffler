package org.bergmayer.albumshuffler

import android.content.Context
import android.content.Intent

data class PlayerState(
    val track: Track? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val error: String? = null,
    val startedRequest: String? = null,
    val stopped: Boolean = false,
    val externalAlbum: Album? = null,
)

interface MusicPlatform {
    fun authorizationIntent(developerToken: String): Intent
    fun authorizationResult(resultCode: Int, data: Intent?): String
    fun createEngine(context: Context, onState: (PlayerState) -> Unit): PlaybackEngine
}
interface PlaybackEngine {
    fun prepare(tracks: List<Track>, requestId: String)
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun release()
}
