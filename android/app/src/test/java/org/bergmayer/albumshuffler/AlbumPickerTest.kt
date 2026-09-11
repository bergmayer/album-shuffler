package org.bergmayer.albumshuffler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AlbumPickerTest {
    private fun song(id: String, album: String = "A", number: Int = 1, disc: Int = 1) =
        Track(id, id, "Song $id", "Artist", album, albumId = album, disc = disc, number = number)

    private class Repository(val source: List<Track>, val albums: List<Album>, val failure: Exception? = null) : MusicRepository {
        override suspend fun playlists() = emptyList<Playlist>()
        override suspend fun tracks(playlistId: String?) = source
        override suspend fun resolveAlbum(seed: Track): Album {
            failure?.let { throw it }
            return albums.firstOrNull { it.title == seed.album } ?: throw AlbumUnavailable()
        }
        override fun invalidate() = Unit
    }
    private fun repo(vararg tracks: Track) = Repository(tracks.toList(), tracks.groupBy { it.album }.map { (name, list) -> Album(name, name, "Artist", list) })

    @Test fun subsetRequiresPlaylist() = runTest {
        try { AlbumPicker(repo(song("1"))).pick(null, PlaybackMode.SUBSET, emptySet(), null); fail() }
        catch (e: MusicFailure) { assertTrue(e.message!!.contains("requires a playlist")) }
    }
    @Test fun subsetKeepsOnlySelectedTracksInDiscOrder() {
        val a = song("a", number = 2)
        val b = song("b", number = 1, disc = 2)
        val extra = song("extra", number = 1)
        assertEquals(listOf(a, b), matchSubset(listOf(b, a, a), listOf(extra, a, b)))
    }
    @Test fun subsetDoesNotSubstituteAnotherSongAtSamePosition() {
        val source = song("source").copy(catalogId = null)
        try { matchSubset(listOf(source), listOf(song("different"))); fail() }
        catch (_: AlbumUnavailable) { }
    }
    @Test fun subsetDoesNotSilentlyDropUnavailableTracks() {
        val available = song("a")
        try { matchSubset(listOf(available, song("missing")), listOf(available)); fail() }
        catch (_: AlbumUnavailable) { }
    }
    @Test fun titleFallbackRequiresUnambiguousMatch() {
        val source = song("a").copy(catalogId = null, number = null)
        try { matchSubset(listOf(source), listOf(source.copy(id = "one"), source.copy(id = "two"))); fail() }
        catch (_: AlbumUnavailable) { }
    }
    @Test fun completeIncludesSongsMissingFromPlaylist() = runTest {
        val first = song("first"); val second = song("second", number = 2)
        val repository = Repository(listOf(second), listOf(Album("catalog", "A", "Artist", listOf(second, first))))
        assertEquals(listOf(first, second), AlbumPicker(repository).pick("playlist", PlaybackMode.COMPLETE, emptySet(), null).tracks)
    }
    @Test fun doesNotRepeatWhileAnotherAlbumExists() = runTest {
        val picker = AlbumPicker(repo(song("a", "A"), song("b", "B")), Random(1))
        repeat(20) { assertEquals("B", picker.pick(null, PlaybackMode.COMPLETE, setOf("A"), "A").key) }
    }
    @Test fun completeCycleDoesNotImmediatelyRepeatLastAlbum() = runTest {
        val picker = AlbumPicker(repo(song("a", "A"), song("b", "B")))
        repeat(20) { assertEquals("A", picker.pick(null, PlaybackMode.COMPLETE, setOf("A", "B"), "B").key) }
    }
    @Test fun subsetCycleDoesNotImmediatelyRepeatLastAlbum() = runTest {
        val picker = AlbumPicker(repo(song("a", "A"), song("b", "B")))
        repeat(20) { assertEquals("id:A", picker.pick("playlist", PlaybackMode.SUBSET, setOf("id:A", "id:B"), "id:B").key) }
    }
    @Test fun tinyLibraryCanRepeat() = runTest {
        assertEquals("A", AlbumPicker(repo(song("a"))).pick(null, PlaybackMode.COMPLETE, setOf("A"), "A").key)
    }
    @Test fun historyStartsNewCycleOnExhaustion() {
        val history = AlbumHistory()
        history.record("A"); history.record("B"); history.record("A")
        assertEquals(setOf("A"), history.exclusions())
        history.clear()
        assertNull(history.lastPlayed())
        assertTrue(history.exclusions().isEmpty())
    }
    @Test fun skipsUnavailableAlbumButDoesNotHideAuthenticationFailure() = runTest {
        val available = song("good", "B")
        val repository = Repository(listOf(song("bad"), available), listOf(Album("B", "B", "Artist", listOf(available))))
        assertEquals("B", AlbumPicker(repository).pick(null, PlaybackMode.COMPLETE, emptySet(), null).key)
        val denied = Repository(listOf(available), emptyList(), AuthenticationFailure("denied"))
        try { AlbumPicker(denied).pick(null, PlaybackMode.COMPLETE, emptySet(), null); fail() }
        catch (e: AuthenticationFailure) { assertEquals("denied", e.message) }
    }
    @Test fun cancellationIsNotAnUnavailableAlbum() = runTest {
        val canceled = Repository(listOf(song("a")), emptyList(), CancellationException("canceled"))
        try { AlbumPicker(canceled).pick(null, PlaybackMode.COMPLETE, emptySet(), null); fail() }
        catch (_: CancellationException) { }
    }
    @Test fun emptySourceAndMissingMetadataAreReported() = runTest {
        for (tracks in listOf(emptyList(), listOf(song("a").copy(albumId = null, album = "")))) {
            try { AlbumPicker(Repository(tracks, emptyList())).pick(null, PlaybackMode.COMPLETE, emptySet(), null); fail() }
            catch (_: MusicFailure) { }
        }
    }
    @Test fun compilationUsesAlbumIdentityAcrossTrackArtists() = runTest {
        val a = song("a").copy(artist = "Artist A")
        val b = song("b", number = 2).copy(artist = "Artist B")
        assertEquals(listOf(a, b), AlbumPicker(repo(a, b)).pick("playlist", PlaybackMode.SUBSET, emptySet(), null).tracks)
    }
}
