package org.bergmayer.albumshuffler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private val app get() = application as ShufflerApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: PlaybackEngine? = null
    private var engineDeveloperToken: String? = null
    private lateinit var mediaSession: MediaSession
    private var timeout: Job? = null
    private var foreground = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW))
        mediaSession = MediaSession(this, "Album Shuffler").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { send(this@PlaybackService, PLAY) }
                override fun onPause() { pause() }
                override fun onSkipToNext() { engine?.next() }
                override fun onSkipToPrevious() { engine?.previous() }
                override fun onStop() { stopSelf() }
            })
            setSessionActivity(openApp())
        }
        // MusicKit owns audio focus, including interruption/resume. A second focus request
        // here would compete with the SDK's listener and pause our own player.
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always meet the foreground deadline before SDK initialization or network work.
        startForeground(NOTIFICATION, notification(app.playerState.value))
        foreground = true
        try {
            if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
            if (intent?.action != PREPARE && app.playerState.value.track == null) {
                stopSelf(); return START_NOT_STICKY
            }
            val developerToken = app.credentials.currentDeveloperToken()
            if (engine == null || engineDeveloperToken != developerToken) {
                engine?.release()
                engine = app.platform.createEngine(this, ::onPlayerState)
                engineDeveloperToken = developerToken
            }
            when (intent?.action) {
                PREPARE -> {
                    val queue = app.pendingQueue.also { app.pendingQueue = null }
                    if (queue == null) { if (app.playerState.value.track == null) stopSelf(); return START_NOT_STICKY }
                    mediaSession.isActive = true
                    engine!!.prepare(queue.tracks, queue.requestId)
                    timeout?.cancel()
                    timeout = scope.launch {
                        delay(45_000)
                        if (app.playerState.value.startedRequest != queue.requestId) {
                            fail("Apple Music did not start playback. Check your connection and try again.")
                            stopSelf()
                        }
                    }
                }
                PLAY -> { mediaSession.isActive = true; engine?.play() }
                PAUSE -> pause()
                NEXT -> engine?.next()
                PREVIOUS -> engine?.previous()
                STOP -> stopSelf()
            }
        } catch (e: Exception) {
            fail(if (e is MusicFailure) e.message ?: "Playback failed." else "Apple Music could not start playback. Please reconnect and try again.")
            stopSelf()
        } catch (_: LinkageError) {
            fail("This build's Apple Music playback library is incompatible with this device.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun pause() {
        engine?.pause()
        leaveForeground()
    }

    private fun leaveForeground() {
        if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
    }

    private fun onPlayerState(state: PlayerState) {
        if (app.playerState.value.externalAlbum != null) return
        app.playerState.value = state
        if (state.error != null) { timeout?.cancel(); stopSelf(); return }
        if (state.startedRequest != null) timeout?.cancel()
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.track?.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.track?.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, state.track?.album).build())
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (state.canNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (state.canPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        mediaSession.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if (state.buffering) PlaybackState.STATE_BUFFERING else if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (state.playing) 1f else 0f).build())
        // A temporary focus interruption keeps the service ready for MusicKit to resume.
        if (state.stopped) leaveForeground()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
    }

    private fun fail(message: String) { app.playerState.update { it.copy(error = message, playing = false, buffering = false) } }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun action(action: String) = PendingIntent.getForegroundService(this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(state: PlayerState): Notification {
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_music)
            .setContentTitle(state.track?.title ?: "Album Shuffler")
            .setContentText(state.track?.let { "${it.artist} — ${it.album}" } ?: "Preparing Apple Music…")
            .setContentIntent(openApp()).setDeleteIntent(action(STOP)).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(state.playing).setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", action(PREVIOUS)).build())
            .addAction(Notification.Action.Builder(if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.playing) "Pause" else "Play", action(if (state.playing) PAUSE else PLAY)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", action(NEXT)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        timeout?.cancel()
        engine?.release()
        unregisterReceiver(noisy)
        mediaSession.release()
        scope.cancel()
        app.playerState.update { if (it.externalAlbum != null) it else PlayerState(error = it.error ?: if (it.buffering) "Playback was stopped before the album started. Try again." else null) }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        super.onDestroy()
    }

    companion object {
        const val PREPARE = "prepare"
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val STOP = "stop"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 1
        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java).setAction(action))
        }
    }
}
