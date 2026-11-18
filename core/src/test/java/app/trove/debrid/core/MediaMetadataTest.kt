package app.trove.debrid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaMetadataTest {
    @Test fun `parses release titles years and tags`() {
        assertEquals(MediaQuery("Baththa", 2026, null), parseMediaName("Baththa.2026.1080p.WEB-Rip.HEVC-GROUP.mkv"))
        assertEquals(MediaQuery("Baahubali The Beginning", 2015, null),
            parseMediaName("Baahubali.The.Beginning.2015.1080p.BluRay.x264-GROUP.mkv"))
        assertEquals(MediaQuery("Example Show", 2020, "series"), parseMediaName("[GROUP] Example_Show_2020_S01E02_720p.mkv"))
        assertEquals(MediaQuery("Example Film", null, null), parseMediaName("Example.Film.WEB-DL.HEVC-GROUP.mkv"))
        assertEquals(MediaQuery("Example", 2026, null), parseMediaName("Example.[2026].1080p.mkv"))
        assertEquals("1917", parseMediaName("1917.2019.1080p.mkv").title)
        assertEquals("", parseMediaName("https://example.invalid/private.mkv").title)
        assertEquals("", parseMediaName("magnet:?xt=synthetic").title)
    }

    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        var status = 200
        var catalog = """{"metas":[{"id":"tt0000001","name":"Example Film","releaseInfo":"2026","type":"movie"}]}"""
        var meta = """{"meta":{"id":"tt0000001","name":"Example Film","year":"2026","type":"movie","poster":"https://images.example.invalid/poster.jpg"}}"""
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val body = when {
                "/meta/" in request.url -> meta
                "/series/" in request.url -> """{"metas":[]}"""
                else -> catalog
            }
            return HttpResponse(status, body)
        }
    }

    @Test fun `searches then resolves detail and caches normalized title across concurrent calls`() = runBlocking {
        val http = FakeHttp()
        val resolver = CinemetaResolver(http)
        val query = MediaQuery("Example Film", 2026, null)
        val result = resolver.resolve(query)
        assertEquals(MediaMetadata("Example Film", 2026, "movie", "https://images.example.invalid/poster.jpg"), result)
        assertEquals(3, http.requests.size)
        assertTrue(http.requests.first().url.endsWith("catalog/movie/top/search=Example%20Film.json"))
        assertTrue(http.requests.last().url.endsWith("meta/movie/tt0000001.json"))
        assertTrue(http.requests.all { it.headers.isEmpty() })
        coroutineScope { List(5) { async { assertEquals(result, resolver.resolve(query.copy(title = "example film"))) } }.forEach { it.await() } }
        assertEquals(3, http.requests.size)
    }

    @Test fun `wrong year no match malformed and ambiguous results fall back and cache misses`() = runBlocking {
        for (catalog in listOf(
            """{"metas":[]}""", "malformed",
            """{"metas":[{"id":"tt0000001","name":"Example Film","year":"2025","type":"movie"}]}""",
            """{"metas":[{"id":"tt0000001","name":"Other Film","year":"2026","type":"movie"}]}""",
            """{"metas":[{"id":"tt0000001","name":"Example Film","year":"2026","type":"movie"},{"id":"tt0000002","name":"Example Film","year":"2026","type":"movie"}]}"""
        )) {
            val http = FakeHttp().apply { this.catalog = catalog }
            val resolver = CinemetaResolver(http)
            val query = MediaQuery("Example Film", 2026, null)
            assertNull(resolver.resolve(query))
            val count = http.requests.size
            assertNull(resolver.resolve(query))
            assertEquals(count, http.requests.size)
        }
    }

    @Test fun `transient failures retry after cooldown`() = runBlocking {
        var now = 0L
        val http = FakeHttp().apply { status = 503 }
        val resolver = CinemetaResolver(http) { now }
        val query = MediaQuery("Example Film", 2026, "movie")
        assertNull(resolver.resolve(query))
        http.status = 200
        assertNull(resolver.resolve(query))
        assertEquals(1, http.requests.size)
        now = 60_001
        assertNotNull(resolver.resolve(query))
    }

    @Test fun `bounded cache evicts oldest entry`() = runBlocking {
        val http = FakeHttp().apply { catalog = """{"metas":[]}""" }
        val resolver = CinemetaResolver(http)
        repeat(257) { resolver.resolve(MediaQuery("Synthetic $it", null, "movie")) }
        assertEquals(257, http.requests.size)
        resolver.resolve(MediaQuery("Synthetic 0", null, "movie"))
        assertEquals(258, http.requests.size)
    }

    @Test fun `cancellation propagates`() = runBlocking {
        val resolver = CinemetaResolver(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = throw CancellationException()
        })
        try { resolver.resolve(MediaQuery("Example", null, null)); fail("Must cancel") }
        catch (_: CancellationException) { }
    }

    @Test fun `rejects unsafe poster URLs`() {
        assertNull(publicPosterUrl("http://images.example.invalid/p.jpg"))
        assertNull(publicPosterUrl("https://example.invalid/requestdl"))
        assertNull(publicPosterUrl("https://example.invalid/p?token=synthetic"))
        assertNull(publicPosterUrl("https://user@example.invalid/p"))
    }
}
