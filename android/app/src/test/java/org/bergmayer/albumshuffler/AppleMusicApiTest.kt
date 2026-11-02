package org.bergmayer.albumshuffler

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AppleMusicApiTest {
    private val server = MockWebServer().apply { start() }
    private val api = AppleMusicApi(credentials = { "developer-test" to "user-test" }, origin = server.url("/"))
    @After fun close() = server.shutdown()

    @Test fun albumDetailsAndTracksLoadTogether() = kotlinx.coroutines.runBlocking {
        val reads = java.util.concurrent.CountDownLatch(2)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val body = when (path) {
                    "/v1/me/storefront" -> """{"data":[{"id":"us"}]}"""
                    "/v1/catalog/us/songs/song" -> """{"data":[{"relationships":{"albums":{"data":[{"id":"album"}]}}}]}"""
                    "/v1/catalog/us/albums/album" -> {
                        reads.countDown()
                        check(reads.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "Tracks did not start with album metadata" }
                        """{"data":[{"id":"album","attributes":{"name":"Fixture","artistName":"Artist"}}]}"""
                    }
                    "/v1/catalog/us/albums/album/tracks" -> {
                        reads.countDown()
                        check(reads.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "Album metadata did not start with tracks" }
                        """{"data":[{"id":"song","type":"songs","attributes":{"name":"Track","artistName":"Artist","albumName":"Fixture","trackNumber":1}}]}"""
                    }
                    else -> error("Unexpected path: $path")
                }
                return MockResponse().setBody(body)
            }
        }
        val album = AppleMusicRepository(api).resolveAlbum(Track(id="library",catalogId="song",title="Track",artist="Artist",album="Fixture"))
        assertEquals("Fixture",album.title)
        assertEquals(listOf("song"),album.tracks.map { it.catalogId })
    }

    @Test fun followsEveryPageAndSendsBothTokens() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"one"}],"next":"/v1/me/library/songs?offset=100"}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"two"}]}"""))
        assertEquals(listOf("one", "two"), api.pages("/v1/me/library/songs").map { it.getString("id") })
        val first = server.takeRequest()
        assertEquals("Bearer developer-test", first.getHeader("Authorization"))
        assertEquals("user-test", first.getHeader("Music-User-Token"))
        assertEquals("/v1/me/library/songs?offset=100", server.takeRequest().path)
    }
    @Test fun rejectsOffOriginPaginationBeforeSendingCredentials() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[],"next":"https://untrusted.example/v1/me/library/songs"}"""))
        try { api.pages("/v1/me/library/songs"); fail() } catch (_: MusicFailure) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun repeatedPageIsAnErrorRatherThanInfiniteLoop() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[],"next":"/v1/me/library/songs"}"""))
        try { api.pages("/v1/me/library/songs"); fail() } catch (e: MusicFailure) { assertTrue(e.message!!.contains("repeated")) }
        assertEquals(1, server.requestCount)
    }
    @Test fun partialPageFailureDoesNotReturnPartialLibrary() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"one"}],"next":"/v1/me/library/songs?offset=100"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        try { api.pages("/v1/me/library/songs"); fail() } catch (e: MusicFailure) { assertTrue(e.message!!.contains("503")) }
    }
    @Test fun authorizationAndRateLimitErrorsStayVisible() = runTest {
        for (code in listOf(401, 403, 429)) {
            server.enqueue(MockResponse().setResponseCode(code))
            try { api.pages("/v1/me/library/songs"); fail() }
            catch (e: MusicFailure) { if (code != 429) assertTrue(e is AuthenticationFailure) }
        }
    }
    @Test fun doesNotFollowRedirectWithCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.example/v1/me/library/songs"))
        try { api.pages("/v1/me/library/songs"); fail() } catch (_: MusicFailure) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun parserKeepsCatalogMappingAndDiscPosition() {
        val track = parseTrack(JSONObject("""{"id":"i.123","type":"library-songs","attributes":{"name":"Track","artistName":"Artist","albumName":"Album","discNumber":2,"trackNumber":3,"playParams":{"catalogId":"456"},"artwork":{"url":"https://example.com/{w}x{h}.jpg"}}}"""))
        assertEquals("456", track.catalogId)
        assertEquals(2, track.disc)
        assertEquals(3, track.number)
        assertEquals("https://example.com/300x300.jpg", track.artwork)
    }
    @Test fun parserUsesCatalogRelationshipAndKeepsMissingNumberUnknown() {
        val track = parseTrack(JSONObject("""{"id":"i.123","type":"library-songs","attributes":{"name":"Track","albumName":"Album"},"relationships":{"catalog":{"data":[{"id":"456"}]}}}"""))
        assertEquals("456", track.catalogId)
        assertNull(track.number)
    }
}
