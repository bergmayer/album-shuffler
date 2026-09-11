package org.bergmayer.albumshuffler

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.apple.android.sdk.authentication.AuthenticationFactory
import com.apple.android.sdk.authentication.TokenProvider
import com.apple.android.sdk.authentication.TokenError
import com.apple.android.music.playback.controller.MediaPlayerController
import com.apple.android.music.playback.controller.MediaPlayerControllerFactory
import com.apple.android.music.playback.model.MediaItemType
import com.apple.android.music.playback.model.MediaPlayerException
import com.apple.android.music.playback.model.PlaybackRepeatMode
import com.apple.android.music.playback.model.PlaybackShuffleMode
import com.apple.android.music.playback.model.PlaybackState
import com.apple.android.music.playback.model.PlayerQueueItem
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider

fun createMusicRepository(credentials: Credentials): MusicRepository = AppleMusicRepository(AppleMusicApi(credentials = {
    credentials.developerToken() to (credentials.userToken() ?: "")
}))

fun createMusicPlatform(context: Context, credentials: Credentials): MusicPlatform = object : MusicPlatform {
    private val authentication = AuthenticationFactory.createAuthenticationManager(context)
    override fun authorizationIntent(developerToken: String): Intent = authentication.createIntentBuilder(developerToken)
        .setStartScreenMessage("Connect your Apple Music library to shuffle albums.").build()
    override fun authorizationResult(resultCode: Int, data: Intent?): String {
        val result = data?.let(authentication::handleTokenResult)
        // Apple Music can report USER_CANCELLED after its own sign-in failure, or
        // after Back is used to escape its stuck 6.5.x authorization screen.
        // Preserve specific SDK errors even when Android reports RESULT_CANCELED.
        val message = when (result?.error) {
            TokenError.NO_SUBSCRIPTION -> "This Apple Account has no active Apple Music subscription. Check your account in Apple Music, then connect again."
            TokenError.SUBSCRIPTION_EXPIRED -> "Your Apple Music subscription has expired. Renew it in Apple Music, then connect again."
            TokenError.TOKEN_FETCH_ERROR -> "Apple Music could not issue an access token. Check your connection and try again."
            else -> null
        }
        if (message != null) throw AuthenticationFailure(message)
        if (resultCode != Activity.RESULT_OK || result == null || result.isError || result.musicUserToken.isNullOrBlank()) {
            throw AuthenticationFailure("Apple Music did not finish connecting. You can try again. If it stayed on a spinner, choose ‘Sign-in stuck?’ for recovery steps.")
        }
        return result.musicUserToken
    }
    override fun createEngine(context: Context, onState: (PlayerState) -> Unit): PlaybackEngine = ApplePlaybackEngine(context, credentials, onState)
}

private class ApplePlaybackEngine(context: Context, credentials: Credentials, private val onState: (PlayerState) -> Unit) :
    PlaybackEngine, MediaPlayerController.Listener {
    // The distributed SDK declares JNI methods but does not load its native library.
    init { NativeMusicKit.load() }
    private val controller = MediaPlayerControllerFactory.createLocalController(context, object : TokenProvider {
        override fun getDeveloperToken() = credentials.currentDeveloperToken()
        override fun getUserToken() = credentials.userToken() ?: ""
    })
    private var selected = emptyList<Track>()
    private var requestId: String? = null
    private var started = false
    private var queueLoaded = false
    private var released = false

    init { controller.addListener(this) }

    override fun prepare(tracks: List<Track>, requestId: String) {
        if (tracks.isEmpty() || tracks.any { it.catalogId == null }) throw AlbumUnavailable()
        this.requestId = requestId
        selected = tracks
        started = false
        queueLoaded = false
        onState(PlayerState(buffering = true))
        val provider = CatalogPlaybackQueueItemProvider.Builder()
            .items(MediaItemType.SONG, *tracks.map { it.catalogId!! }.toTypedArray())
            .shuffleMode(PlaybackShuffleMode.SHUFFLE_MODE_OFF).build()
        controller.setRepeatMode(PlaybackRepeatMode.REPEAT_MODE_OFF)
        controller.prepare(provider, true)
    }
    override fun play() = controller.play()
    override fun pause() = controller.pause()
    override fun next() { if (controller.canSkipToNextItem()) controller.skipToNextItem() }
    override fun previous() { if (controller.canSkipToPreviousItem()) controller.skipToPreviousItem() }
    override fun release() { released = true; controller.removeListener(this); controller.stop(); controller.release() }

    private fun publish() {
        if (released) return
        val item = controller.currentItem?.item
        val catalogId = item?.subscriptionStoreId
        val known = selected.firstOrNull { it.catalogId == catalogId }
        val playing = controller.playbackState == PlaybackState.PLAYING
        if (queueLoaded && known != null && playing) started = true
        val track = item?.let {
            Track(id = catalogId ?: controller.currentItem!!.playbackQueueId.toString(), catalogId = catalogId,
                title = it.title ?: known?.title ?: "Untitled song", artist = it.artistName ?: known?.artist ?: "",
                album = it.albumTitle ?: known?.album ?: "", artwork = it.getArtworkUrl(600, 600) ?: known?.artwork,
                url = it.url ?: known?.url)
        }
        onState(PlayerState(track = track, playing = playing,
            buffering = controller.isBuffering || (requestId != null && !started),
            canNext = controller.canSkipToNextItem(), canPrevious = controller.canSkipToPreviousItem(),
            startedRequest = requestId.takeIf { started }, stopped = started && controller.playbackState == PlaybackState.STOPPED))
    }

    override fun onPlaybackError(playerController: MediaPlayerController, error: MediaPlayerException) {
        if (!released) onState(PlayerState(error = "Apple Music could not play this album. Check your subscription and connection, then try again."))
    }
    override fun onCurrentItemChanged(playerController: MediaPlayerController, previousItem: PlayerQueueItem?, currentItem: PlayerQueueItem?) {
        if (currentItem != null) queueLoaded = true
        publish()
    }
    override fun onPlaybackQueueItemsAdded(playerController: MediaPlayerController, queueInsertionType: Int, containerType: Int, itemType: Int) {
        queueLoaded = true; publish()
    }
    override fun onPlaybackStateChanged(playerController: MediaPlayerController, previousState: Int, currentState: Int) = publish()
    override fun onBufferingStateChanged(playerController: MediaPlayerController, buffering: Boolean) = publish()
    override fun onMetadataUpdated(playerController: MediaPlayerController, currentItem: PlayerQueueItem) = publish()
    override fun onPlaybackQueueChanged(playerController: MediaPlayerController, playbackQueueItems: List<PlayerQueueItem>) = publish()
    override fun onPlaybackStateUpdated(playerController: MediaPlayerController) = publish()
    override fun onPlayerStateRestored(playerController: MediaPlayerController) = publish()
    override fun onItemEnded(playerController: MediaPlayerController, queueItem: PlayerQueueItem, endPosition: Long) = publish()
    override fun onPlaybackRepeatModeChanged(playerController: MediaPlayerController, currentRepeatMode: Int) = Unit
    override fun onPlaybackShuffleModeChanged(playerController: MediaPlayerController, currentShuffleMode: Int) = Unit
}

private object NativeMusicKit {
    private val loaded by lazy { System.loadLibrary("appleMusicSDK") }
    fun load() { loaded }
}
