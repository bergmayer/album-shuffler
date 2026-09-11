package org.bergmayer.albumshuffler

import java.util.Locale
import kotlin.random.Random

enum class PlaybackMode(val title: String, val detail: String) {
    SUBSET("Playlist Subset", "Plays only the album tracks that exist in the chosen playlist, in album order."),
    COMPLETE("Complete Album", "Plays the entire album a random track is from.")
}

data class Playlist(val id: String, val name: String)
data class Track(
    val id: String,
    val catalogId: String? = null,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = artist,
    val albumId: String? = null,
    val disc: Int = 1,
    val number: Int? = null,
    val artwork: String? = null,
    val url: String? = null,
) {
    val albumKey: String? get() = albumId?.let { "id:$it" }
        ?: album.takeIf { it.isNotBlank() }?.let { "name:${normalized(albumArtist)}|${normalized(it)}" }
}
data class Album(val id: String, val title: String, val artist: String, val tracks: List<Track>)
data class AlbumPick(val key: String, val tracks: List<Track>)

fun normalized(value: String) = value.trim().lowercase(Locale.ROOT)
val albumOrder = compareBy<Track> { it.disc }.thenBy { it.number ?: Int.MAX_VALUE }

open class MusicFailure(message: String) : Exception(message)
class AlbumUnavailable(message: String = "Could not find this album in the Apple Music catalog.") : MusicFailure(message)
class AuthenticationFailure(message: String) : MusicFailure(message)

interface MusicRepository {
    suspend fun playlists(): List<Playlist>
    suspend fun tracks(playlistId: String?): List<Track>
    suspend fun resolveAlbum(seed: Track): Album
    fun invalidate()
}

/** A shuffle cycle excludes successful picks until every available album has been tried. */
class AlbumHistory {
    private val played = linkedSetOf<String>()
    private var last: String? = null
    fun exclusions(): Set<String> = played.toSet()
    fun lastPlayed(): String? = last
    fun record(key: String) { if (key in played) played.clear(); played += key; last = key }
    fun clear() { played.clear(); last = null }
}

class AlbumPicker(private val repository: MusicRepository, private val random: Random = Random.Default) {
    suspend fun pick(playlistId: String?, mode: PlaybackMode, excluded: Set<String>, last: String?): AlbumPick {
        if (mode == PlaybackMode.SUBSET && playlistId == null) {
            throw MusicFailure("Subset mode requires a playlist source.")
        }
        val tracks = repository.tracks(playlistId)
        if (tracks.isEmpty()) throw MusicFailure("The selected source has no songs.")
        val groups = tracks.filter { it.albumKey != null }.groupBy { it.albumKey!! }
        if (groups.isEmpty()) throw MusicFailure("Could not determine the albums in this source.")
        val candidates = groups.entries.shuffled(random)
        val repeats = mutableListOf<AlbumPick>()
        for ((sourceKey, members) in candidates) {
            // Keep subset identity source-based; complete mode uses resolved catalog identity.
            val album = try { repository.resolveAlbum(members.first()) } catch (_: AlbumUnavailable) { continue }
            val pick = try {
                if (mode == PlaybackMode.SUBSET) AlbumPick(sourceKey, matchSubset(members, album.tracks))
                else AlbumPick(album.id, album.tracks.sortedWith(albumOrder))
            } catch (_: AlbumUnavailable) { continue }
            if (pick.tracks.isEmpty()) continue
            if (pick.key !in excluded) return pick
            repeats += pick
        }
        return repeats.firstOrNull { it.key != last } ?: repeats.firstOrNull()
            ?: throw AlbumUnavailable("No playable catalog album was found in this source. Uploaded or local-only songs may not be available on Android.")
    }
}

/** Never silently broaden a playlist or drop one of its tracks during catalog resolution. */
fun matchSubset(library: List<Track>, catalog: List<Track>): List<Track> {
    val result = library.distinctBy { it.catalogId ?: it.id }.map { track ->
        val identity = track.catalogId?.let { id -> catalog.firstOrNull { it.catalogId == id || it.id == id } }
        val byPosition = if (track.number != null) catalog.singleOrNull {
            it.disc == track.disc && it.number == track.number && normalized(it.title) == normalized(track.title)
        } else null
        identity ?: byPosition ?: catalog.singleOrNull {
            normalized(it.title) == normalized(track.title) && normalized(it.artist) == normalized(track.artist)
        } ?: throw AlbumUnavailable("Some playlist tracks are unavailable on this catalog album. The playlist subset was not changed.")
    }
    return result.distinctBy { it.id }.sortedWith(albumOrder)
}
