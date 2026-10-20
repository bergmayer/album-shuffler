package org.bergmayer.albumshuffler

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AppleMusicLinksTest {
    private fun album(url: String?) = Album("123", "Album", "Artist", emptyList(), url)

    @Test fun preservesStorefrontAndEditionButRemovesSongSelection() {
        assertEquals("https://music.apple.com/gb/album/edition/123",
            appleMusicAlbumUrl(album("https://music.apple.com/gb/album/edition/123?i=456#song")))
    }

    @Test fun refusesMissingWrongAlbumAndUntrustedLinks() {
        for (url in listOf(null, "", "https://music.apple.com/us/album/title/999",
            "https://example.com/us/album/title/123", "http://music.apple.com/us/album/title/123",
            "https://music.apple.com/us/song/title/123", "https://music.apple.com@evil.test/us/album/title/123")) {
            try { appleMusicAlbumUrl(album(url)); fail("Accepted $url") }
            catch (_: AlbumUnavailable) { }
        }
    }
}
