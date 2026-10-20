package org.bergmayer.albumshuffler

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ShufflerState(
    val connected: Boolean = false,
    val playlists: List<Playlist> = emptyList(),
    val sourceId: String? = null,
    val sourceName: String = "Entire Library",
    val mode: PlaybackMode = PlaybackMode.COMPLETE,
    val loading: Boolean = false,
    val shuffling: Boolean = false,
    val error: String? = null,
) {
    val canShuffle get() = connected && !loading && !shuffling && (mode != PlaybackMode.SUBSET || sourceId != null)
}

class ShufflerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ShufflerApplication
    private val settings = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(ShufflerState(
        connected = app.credentials.userToken() != null,
        sourceId = settings.getString("source", null),
        sourceName = settings.getString("sourceName", "Entire Library") ?: "Entire Library",
        mode = PlaybackMode.entries.firstOrNull { it.name == settings.getString("mode", null) } ?: PlaybackMode.COMPLETE,
    ))
    val state = mutableState.asStateFlow()
    val playerState = app.playerState.asStateFlow()
    private val history = AlbumHistory()
    private var loadJob: Job? = null
    private var loadVersion = 0
    private var shuffleJob: Job? = null
    private var contextVersion = 0
    private var pendingPick: Triple<String, String, Int>? = null
    private var pendingDeepLink = false

    init {
        refresh()
        viewModelScope.launch {
            app.playerState.collect { player ->
                pendingPick?.let { (requestId, key, version) ->
                    if (player.startedRequest == requestId) {
                        if (contextVersion == version) history.record(key)
                        pendingPick = null
                        mutableState.update { it.copy(shuffling = false) }
                    } else if (player.error != null) {
                        pendingPick = null
                        mutableState.update { it.copy(shuffling = false) }
                    }
                }
            }
        }
    }

    fun refresh() {
        if (!state.value.connected) return
        loadJob?.cancel()
        val requestVersion = ++loadVersion
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            try {
                app.credentials.developerToken()
                val playlists = app.repository.playlists()
                val source = state.value.sourceId?.let { id -> playlists.firstOrNull { it.id == id } }
                if (state.value.sourceId != null && source == null) {
                    selectSource(null)
                }
                mutableState.update { it.copy(playlists = playlists, sourceName = source?.name ?: "Entire Library") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
            finally { if (requestVersion == loadVersion) mutableState.update { it.copy(loading = false) } }
            if (pendingDeepLink) { pendingDeepLink = false; shuffle() }
        }
    }

    fun refreshLibrary() {
        changeContext()
        app.repository.invalidate()
        refresh()
    }

    fun selectSource(playlist: Playlist?) {
        if (playlist?.id == state.value.sourceId) return
        changeContext()
        settings.edit().putString("source", playlist?.id).putString("sourceName", playlist?.name ?: "Entire Library").apply()
        mutableState.update { it.copy(sourceId = playlist?.id, sourceName = playlist?.name ?: "Entire Library") }
    }

    fun selectMode(mode: PlaybackMode) {
        if (mode == state.value.mode) return
        changeContext()
        settings.edit().putString("mode", mode.name).apply()
        mutableState.update { it.copy(mode = mode) }
    }

    private fun changeContext() {
        contextVersion++
        shuffleJob?.cancel()
        app.pendingQueue = null
        pendingPick = null
        history.clear()
        mutableState.update { it.copy(shuffling = false) }
    }

    fun shuffle() {
        if (!state.value.canShuffle) return
        val snapshot = state.value
        val version = contextVersion
        mutableState.update { it.copy(shuffling = true, error = null) }
        shuffleJob = viewModelScope.launch {
            try {
                app.credentials.developerToken()
                val pick = AlbumPicker(app.repository).pick(snapshot.sourceId, snapshot.mode, history.exclusions(), history.lastPlayed())
                if (version != contextVersion) return@launch
                if (snapshot.mode == PlaybackMode.COMPLETE) {
                    val album = pick.album ?: throw AlbumUnavailable()
                    val link = appleMusicAlbumUrl(album)
                    try {
                        app.startActivity(Intent(Intent.ACTION_VIEW, link.toUri())
                            .setPackage("com.apple.android.music").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        throw MusicFailure("Install Apple Music to open this album.")
                    }
                    // Stop any subset player before it can compete with Apple Music.
                    app.stopService(Intent(app, PlaybackService::class.java))
                    app.playerState.value = PlayerState(track = pick.tracks.first(), externalAlbum = album)
                    // This is a successful handoff, not confirmation that playback began.
                    history.record(pick.key)
                    mutableState.update { it.copy(shuffling = false) }
                    return@launch
                }
                val requestId = UUID.randomUUID().toString()
                pendingPick = Triple(requestId, pick.key, version)
                app.pendingQueue = PendingQueue(requestId, pick.tracks)
                app.playerState.update { PlayerState() }
                PlaybackService.send(app, PlaybackService.PREPARE)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                pendingPick = null
                app.pendingQueue = null
                mutableState.update { it.copy(shuffling = false) }
                report(e)
            }
        }
    }

    fun deepLink() {
        if (state.value.loading || !state.value.connected) pendingDeepLink = true else shuffle()
    }

    fun authorize(launch: (Intent) -> Unit) {
        if (state.value.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try { launch(app.platform.authorizationIntent(app.credentials.developerToken())) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
            finally { mutableState.update { it.copy(loading = false) } }
        }
    }

    fun authorizationResult(resultCode: Int, data: Intent?) {
        try {
            app.credentials.saveUserToken(app.platform.authorizationResult(resultCode, data))
            app.repository.invalidate()
            mutableState.update { it.copy(connected = true, error = null) }
            refresh()
        } catch (e: Exception) { report(e) }
    }

    fun disconnect() {
        changeContext()
        loadJob?.cancel()
        loadVersion++
        pendingDeepLink = false
        app.stopService(Intent(app, PlaybackService::class.java))
        app.playerState.value = PlayerState()
        app.credentials.clearUser()
        app.repository.invalidate()
        settings.edit().remove("source").remove("sourceName").apply()
        mutableState.update { it.copy(connected = false, playlists = emptyList(), sourceId = null, sourceName = "Entire Library", loading = false) }
    }

    fun transport(action: String) {
        if (app.playerState.value.externalAlbum != null) return
        try { PlaybackService.send(app, action) } catch (e: Exception) { report(e) }
    }
    fun dismissError() {
        mutableState.update { it.copy(error = null) }
        app.playerState.update { it.copy(error = null) }
    }
    private fun report(error: Exception) {
        mutableState.update { it.copy(error = if (error is MusicFailure) error.message else "Could not complete this action. Please try again.") }
    }
}
