package org.bergmayer.albumshuffler

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow

class ShufflerApplication : Application() {
    val credentials by lazy { Credentials(this) }
    val platform by lazy { createMusicPlatform(this, credentials) }
    val repository by lazy { createMusicRepository(credentials) }
    val playerState = MutableStateFlow(PlayerState())
    // A single pending queue crosses the Activity/Service boundary without large Intent payloads.
    var pendingQueue: PendingQueue? = null
}

data class PendingQueue(val requestId: String, val tracks: List<Track>)
