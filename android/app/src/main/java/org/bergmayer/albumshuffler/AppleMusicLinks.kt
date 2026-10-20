package org.bergmayer.albumshuffler

import java.net.URI

/** Keep the album's storefront and edition; never hand off a song URL or web fallback. */
internal fun appleMusicAlbumUrl(album: Album): String {
    val uri = try { URI(album.url ?: "") } catch (_: Exception) { null }
    val segments = uri?.path?.split('/') ?: emptyList()
    if (uri?.scheme != "https" || uri.host != "music.apple.com" ||
        uri.userInfo != null || uri.port != -1 ||
        segments.getOrNull(2) != "album" || segments.lastOrNull() != album.id) {
        throw AlbumUnavailable("Apple Music did not provide a link for this album. Refresh your library and try again.")
    }
    // A song-selection parameter would open a track rather than the album.
    return URI(uri.scheme, uri.authority, uri.path, null, null).toASCIIString()
}
