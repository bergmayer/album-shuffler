package org.bergmayer.albumshuffler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class AppleMusicApi(
    private val credentials: suspend () -> Pair<String, String>,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).build(),
    private val origin: HttpUrl = "https://api.music.apple.com/".toHttpUrl(),
) {
    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val url = checkedUrl(path)
        val (developer, user) = credentials()
        if (developer.isBlank() || user.isBlank()) throw AuthenticationFailure("Connect to Apple Music to continue.")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $developer")
            .header("Music-User-Token", user).build()
        try {
            client.newCall(request).execute().use { response ->
                coroutineContext.ensureActive()
                when (response.code) {
                    401 -> throw AuthenticationFailure("Apple Music authorization expired. Reconnect in Settings.")
                    403 -> throw AuthenticationFailure("Apple Music access was denied. Check your subscription and reconnect in Settings.")
                    404 -> throw AlbumUnavailable()
                    429 -> throw MusicFailure("Apple Music is receiving too many requests. Please try again shortly.")
                }
                if (!response.isSuccessful) throw MusicFailure("Apple Music could not complete the request (${response.code}). Try again.")
                JSONObject(response.body.string())
            }
        } catch (_: IOException) {
            throw MusicFailure("Could not reach Apple Music. Check your connection and try again.")
        }
    }

    internal fun checkedUrl(path: String): HttpUrl {
        val url = origin.resolve(path) ?: throw MusicFailure("Apple Music returned an invalid page address.")
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || !url.encodedPath.startsWith("/v1/")) {
            throw MusicFailure("Apple Music returned an unexpected page address.")
        }
        return url
    }

    suspend fun pages(path: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val visited = mutableSetOf<String>()
        var next: String? = path
        while (next != null) {
            coroutineContext.ensureActive()
            val canonical = checkedUrl(next).toString()
            if (!visited.add(canonical)) throw MusicFailure("Apple Music repeated a page. Please refresh your library.")
            val response = get(canonical)
            val data = response.optJSONArray("data") ?: throw MusicFailure("Apple Music returned an incomplete response.")
            for (index in 0 until data.length()) result += data.getJSONObject(index)
            next = response.text("next")
        }
        return result
    }
}

class AppleMusicRepository(private val api: AppleMusicApi) : MusicRepository {
    private var storefront: String? = null
    private val sourceCache = mutableMapOf<String, List<Track>>()
    private val albumCache = mutableMapOf<String, Album>()
    override fun invalidate() { sourceCache.clear(); albumCache.clear(); storefront = null }

    override suspend fun playlists() = api.pages("/v1/me/library/playlists?limit=100").map {
        Playlist(it.getString("id"), it.getJSONObject("attributes").optString("name", "Untitled playlist"))
    }.sortedBy { normalized(it.name) }

    override suspend fun tracks(playlistId: String?): List<Track> {
        val key = playlistId ?: "library"
        sourceCache[key]?.let { return it }
        val path = if (playlistId == null) "/v1/me/library/songs?limit=100&include=albums,catalog"
        else "/v1/me/library/playlists/${segment(playlistId)}/tracks?limit=100&include=albums,catalog"
        val tracks = api.pages(path).filter { it.optString("type") in setOf("songs", "library-songs") }.map(::parseTrack)
        sourceCache[key] = tracks
        return tracks
    }

    override suspend fun resolveAlbum(seed: Track): Album {
        seed.albumKey?.let { albumCache[it]?.let { album -> return album } }
        val region = storefront ?: api.get("/v1/me/storefront").getJSONArray("data")
            .getJSONObject(0).getString("id").also { storefront = it }
        val mappedAlbum = try {
            val catalogSongId = seed.catalogId ?: api.pages("/v1/me/library/songs/${segment(seed.id)}/catalog").firstOrNull()?.text("id")
            catalogSongId?.let {
                val song = api.get("/v1/catalog/${segment(region)}/songs/${segment(it)}?include=albums")
                    .getJSONArray("data").optJSONObject(0)
                song?.optJSONObject("relationships")?.optJSONObject("albums")?.optJSONArray("data")
                    ?.optJSONObject(0)?.text("id")
            }
        } catch (_: AlbumUnavailable) { null }
        val albumId = mappedAlbum ?: searchAlbum(seed, region)
        val albumData = api.get("/v1/catalog/${segment(region)}/albums/${segment(albumId)}")
            .getJSONArray("data").optJSONObject(0) ?: throw AlbumUnavailable()
        val attributes = albumData.getJSONObject("attributes")
        val tracks = api.pages("/v1/catalog/${segment(region)}/albums/${segment(albumId)}/tracks?limit=100")
            .filter { it.optString("type") == "songs" }.map(::parseTrack).sortedWith(albumOrder)
        if (tracks.isEmpty() || tracks.any { it.catalogId == null }) throw AlbumUnavailable()
        val album = Album(albumId, attributes.getString("name"), attributes.optString("artistName"), tracks, attributes.text("url"))
        seed.albumKey?.let { albumCache[it] = album }
        return album
    }

    private suspend fun searchAlbum(seed: Track, region: String): String {
        if (seed.album.isBlank()) throw AlbumUnavailable()
        val path = "https://api.music.apple.com/v1/catalog/${segment(region)}/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "${seed.artist} ${seed.album}")
            .addQueryParameter("types", "albums").addQueryParameter("limit", "25").build()
        val albums = api.get(path.encodedPath + "?" + path.encodedQuery).optJSONObject("results")
            ?.optJSONObject("albums")?.optJSONArray("data") ?: throw AlbumUnavailable()
        for (index in 0 until albums.length()) {
            val item = albums.getJSONObject(index)
            val attributes = item.getJSONObject("attributes")
            if (normalized(attributes.optString("name")) == normalized(seed.album) &&
                normalized(attributes.optString("artistName")) == normalized(seed.albumArtist)) return item.getString("id")
        }
        throw AlbumUnavailable()
    }
}

internal fun JSONObject.text(key: String): String? = optString(key).takeUnless { it.isBlank() || it == "null" }
internal fun segment(value: String) = "https://api.music.apple.com/".toHttpUrl().newBuilder()
    .addPathSegment(value).build().encodedPath.removePrefix("/")

internal fun parseTrack(item: JSONObject): Track {
    val a = item.getJSONObject("attributes")
    val play = a.optJSONObject("playParams")
    val catalog = item.optJSONObject("relationships")?.optJSONObject("catalog")?.optJSONArray("data")?.optJSONObject(0)
    val isCatalog = item.optString("type") == "songs"
    return Track(
        id = item.getString("id"),
        catalogId = if (isCatalog) item.getString("id") else play?.text("catalogId") ?: catalog?.text("id"),
        title = a.optString("name", "Untitled song"), artist = a.optString("artistName"),
        album = a.optString("albumName"), albumArtist = a.text("albumArtistName") ?: a.optString("artistName"),
        albumId = item.optJSONObject("relationships")?.optJSONObject("albums")?.optJSONArray("data")?.optJSONObject(0)?.text("id"),
        disc = a.optInt("discNumber", 1).coerceAtLeast(1), number = a.optInt("trackNumber").takeIf { it > 0 },
        artwork = a.optJSONObject("artwork")?.text("url")?.replace("{w}", "300")?.replace("{h}", "300"),
        url = a.text("url") ?: catalog?.optJSONObject("attributes")?.text("url"),
    )
}
