package app.trove.debrid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.coroutineContext

class MediaMetadataTest {
    @get:Rule val folder = TemporaryFolder()
    private val movieQuery = MediaQuery("Example Film", 2026, "movie")
    private val cachedMovie = MediaMetadata(movieQuery.title, movieQuery.year, movieQuery.type)
    private val unexpectedNetwork = object : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse =
            throw AssertionError("Network must not run")
    }

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

    @Test fun `strips absolute episode numbers only without episode tokens`() {
        assertEquals(MediaQuery("Show", null, "series"), parseMediaName("Show 1071"))
        assertEquals(MediaQuery("One Piece", null, "series"), parseMediaName("One Piece - 1071"))
        assertEquals(MediaQuery("Show 1071", null, "series"), parseMediaName("Show 1071 S01E02"))
        assertEquals(MediaQuery("Show 1071", null, "series"), parseMediaName("Show 1071 1x02"))
        assertEquals(MediaQuery("Show 1071", null, "series"), parseMediaName("Show 1071 Season 1"))
        assertEquals(MediaQuery("Rocky 2", null, null), parseMediaName("Rocky 2"))
        assertEquals(MediaQuery("Apollo 13", null, null), parseMediaName("Apollo 13"))
        assertEquals(MediaQuery("The 100", null, null), parseMediaName("The 100"))
        assertEquals(MediaQuery("1071", null, null), parseMediaName("1071"))
    }

    @Test fun `future year like sequel number stays in the title`() {
        assertEquals(MediaQuery("Blade Runner 2049", null, null), parseMediaName("Blade Runner 2049"))
        assertEquals(MediaQuery("Blade Runner 2049", null, null), parseMediaName("Blade Runner 2049.1080p.mkv"))
        assertEquals(MediaQuery("Blade Runner 2049", 2017, null), parseMediaName("Blade Runner 2049.2017.1080p.mkv"))
    }

    @Test fun `normalizes episode tokens and finds quality after source tags`() {
        assertEquals(ReleaseDetails("S02E05", "1080p"),
            parseReleaseDetails("The.Bear.s02e05.WEB-DL.1080P.720p.mkv"))
        assertEquals(ReleaseDetails("S02E05", null), parseReleaseDetails("The_Bear_2x05.mkv"))
        assertEquals(ReleaseDetails(null, "720p"), parseReleaseDetails("Film.[720p].mkv"))
        assertEquals(ReleaseDetails(null, "480p"), parseReleaseDetails("Film.480p.BluRay.mkv"))
        assertEquals(ReleaseDetails(null, "1080i"), parseReleaseDetails("Film.1080i.mkv"))
    }

    @Test fun `normalizes 4K and 2160p to the same quality`() {
        assertEquals(ReleaseDetails(null, "2160p"), parseReleaseDetails("Film.2160p.mkv"))
        assertEquals(ReleaseDetails(null, "2160p"), parseReleaseDetails("Film.4K.mkv"))
    }

    @Test fun `omits season only tokens and ignores source tags URLs and magnets`() {
        val empty = ReleaseDetails(null, null)
        assertEquals(empty, parseReleaseDetails("The.Bear.Season 2.mkv"))
        assertEquals(empty, parseReleaseDetails("The.Bear.S02.mkv"))
        assertEquals(empty, parseReleaseDetails("Film.mkv"))
        assertEquals(empty, parseReleaseDetails("Film.WEB-DL.BluRay.mkv"))
        assertEquals(empty, parseReleaseDetails("https://example.invalid/Show.S02E05.1080p.mkv"))
        assertEquals(empty, parseReleaseDetails("magnet:?dn=Show.S02E05.1080p.mkv"))
    }

    // Cinemeta canned responses. The record-and-answer shell is the shared
    // RecordingTransport; only the canned catalog/meta routing (test data)
    // lives here, with the same mutable bodies the tests already mutate.
    private class CinemetaTransport : HttpTransport {
        var status = 200
        var catalog = """{"metas":[{"id":"tt0000001","name":"Example Film","releaseInfo":"2026","type":"movie"}]}"""
        var seriesCatalog = """{"metas":[]}"""
        var meta = """{"meta":{"id":"tt0000001","name":"Example Film","year":"2026","type":"movie","poster":"https://images.example.invalid/poster.jpg"}}"""
        private val transport = RecordingTransport { request ->
            val body = when {
                "/meta/" in request.url -> meta
                "/series/" in request.url -> seriesCatalog
                else -> catalog
            }
            HttpResponse(status, body)
        }
        val requests: MutableList<HttpRequest> get() = transport.requests
        override suspend fun execute(request: HttpRequest): HttpResponse = transport.execute(request)
    }

    @Test fun `searches then resolves detail and caches normalized title across concurrent calls`() = runBlocking {
        val http = CinemetaTransport().apply {
            seriesCatalog = catalog.replace("movie", "series")
            catalog = """{"metas":[]}"""
            meta = """
                {"meta":{"id":"tt0000001","name":"Example Film","year":"2026","type":"series",
                  "poster":"https://images.example.invalid/poster.jpg","description":"A synopsis.",
                  "genres":["Drama","Mystery"],"cast":["Actor One",{"name":"Actor Two"}],
                  "director":"Director One","imdbRating":8.2,"runtime":"120m",
                  "videos":[{"season":1,"episode":2,"title":"Second","released":"2026-01-02"}]}}
            """.trimIndent()
        }
        val resolver = CinemetaResolver(http)
        val query = MediaQuery("Example Film", 2026, null)
        val result = resolver.resolve(query)
        assertEquals(
            MediaMetadata(
                "Example Film", 2026, "series", "https://images.example.invalid/poster.jpg",
                imdbId = "tt0000001", overview = "A synopsis.", genres = listOf("Drama", "Mystery"),
                cast = listOf("Actor One", "Actor Two"), director = "Director One", imdbRating = 8.2,
                runtime = "120m", episodes = listOf(MediaEpisode(1, 2, "Second", "2026-01-02")),
            ),
            result,
        )
        assertEquals(3, http.requests.size)
        assertTrue(http.requests.first().url.endsWith("catalog/movie/top/search=Example%20Film.json"))
        assertTrue(http.requests.last().url.endsWith("meta/series/tt0000001.json"))
        assertTrue(http.requests.all { it.headers.isEmpty() })
        coroutineScope { List(5) { async { assertEquals(result, resolver.resolve(query.copy(title = "example film"))) } }.forEach { it.await() } }
        assertEquals(3, http.requests.size)
    }

    @Test fun `wrong year no match malformed and ambiguous results fall back and cache misses`() = runBlocking {
        for (catalog in listOf(
            """{"metas":[]}""", "malformed",
            """{"metas":[{"id":"tt0000001","name":"Example Film","year":"2024","type":"movie"}]}""",
            """{"metas":[{"id":"tt0000001","name":"Other Film","year":"2026","type":"movie"}]}""",
            """{"metas":[{"id":"tt0000001","name":"Example Film","year":"2026","type":"movie"},{"id":"tt0000002","name":"Example Film","year":"2026","type":"movie"}]}"""
        )) {
            val http = CinemetaTransport().apply { this.catalog = catalog }
            val resolver = CinemetaResolver(http)
            val query = MediaQuery("Example Film", 2026, null)
            assertNull(resolver.resolve(query))
            val count = http.requests.size
            assertNull(resolver.resolve(query))
            assertEquals(count, http.requests.size)
        }
    }

    @Test fun `missing optional fields keep defaults and retain IMDb id`() = runBlocking {
        assertEquals(
            MediaMetadata("Example Film", 2026, "movie", "https://images.example.invalid/poster.jpg",
                imdbId = "tt0000001"),
            CinemetaResolver(CinemetaTransport()).resolve(MediaQuery("Example Film", 2026, "movie")),
        )
    }

    @Test fun `optional detail fields ignore malformed values and cap names`() = runBlocking {
        for (rating in listOf("99", "-1", "{}", "1e999")) {
            val http = CinemetaTransport().apply {
                meta = """
                    {"meta":{"id":"tt0000001","name":"Example Film","year":"2026","type":"movie",
                      "description":" ","runtime":123,
                      "genres":["Drama","Drama",false," ","Mystery","Action","Crime","Horror","Comedy"],
                      "cast":[null,{"name":false}," ","A",{"name":"B"},"C","D","E","F"],
                      "director":[null,42," ","Director One","Director Two"],"imdbRating":$rating}}
                """.trimIndent()
            }
            val result = CinemetaResolver(http).resolve(MediaQuery("Example Film", 2026, "movie"))!!
            assertNull(result.overview)
            assertNull(result.runtime)
            assertNull(result.imdbRating)
            assertEquals(listOf("Drama", "Mystery", "Action", "Crime", "Horror"), result.genres)
            assertEquals(listOf("A", "B", "C", "D", "E"), result.cast)
            assertEquals("Director One", result.director)
        }
    }

    @Test fun `imdb rating accepts the numeric string Cinemeta sends`() = runBlocking {
        val http = CinemetaTransport().apply { meta = meta.replace("\"year\":\"2026\"", "\"year\":\"2026\",\"imdbRating\":\"8.2\"") }
        assertEquals(8.2, CinemetaResolver(http).resolve(MediaQuery("Example Film", 2026, "movie"))?.imdbRating)
    }

    @Test fun `series episodes use explicit coordinates or video ids and keep first duplicate`() = runBlocking {
        val http = CinemetaTransport().apply {
            seriesCatalog = catalog.replace("movie", "series")
        }
        val query = MediaQuery("Example Film", 2026, "series")
        val baseMeta = http.meta.replace("movie", "series").dropLast(2)
        http.meta = baseMeta + """,
            "videos":[
              {"id":"tt0000001:2:5","name":"Fifth","released":"2026-02-05T00:00:00Z"},
              {"season":1,"episode":2,"title":"Second","id":"tt0000001:9:9"},
              {"season":0,"id":"tt0000001:9:1"},
              {"id":"tt0000001:2:5","title":"Duplicate"},
              null, "bad", {"episode":3}, {"id":"tt0000001:x:4"},
              {"season":{},"episode":[]}, {"season":1.5,"episode":1},
              {"season":-1,"episode":4,"id":"tt0000001:1:4"},
              {"id":"tt0000001:2:-1"}
            ]}}""".trimIndent()
        val episodes = CinemetaResolver(http).resolve(query)!!.episodes
        val fifth = MediaEpisode(2, 5, "Fifth", "2026-02-05T00:00:00Z")
        assertEquals(listOf(MediaEpisode(0, 1, null, null), MediaEpisode(1, 2, "Second", null), fifth), episodes)
        assertEquals(fifth, matchingEpisode(episodes, "S02E05"))
        assertEquals(fifth, matchingEpisode(episodes, "2x05"))
        assertNull(matchingEpisode(episodes, "S02E06"))
        assertNull(matchingEpisode(episodes, "Season 2"))
        assertNull(matchingEpisode(episodes, null))

        http.meta = baseMeta + ""","videos":[]}}"""
        assertTrue(CinemetaResolver(http).resolve(query)!!.episodes.isEmpty())
    }

    @Test fun `year drift resolves in either direction with the same requests as exact matching`() = runBlocking {
        val query = MediaQuery("Example Film", 2026, null)
        val exact = CinemetaTransport()
        assertEquals(2026, CinemetaResolver(exact).resolve(query)?.year)

        for (year in listOf(2025, 2027, 2024)) {
            val http = CinemetaTransport().apply {
                catalog = catalog.replace("2026", year.toString())
                meta = meta.replace("2026", year.toString())
            }
            val result = CinemetaResolver(http).resolve(query)
            if (year == 2024) {
                assertNull(result)
                assertEquals(exact.requests.dropLast(1), http.requests)
            } else {
                assertEquals(year, result?.year)
                assertEquals(exact.requests, http.requests)
            }
        }
    }

    @Test fun `exact ambiguity cannot fall through to a drifted candidate`() = runBlocking {
        val http = CinemetaTransport().apply {
            catalog = """
                {"metas":[
                  {"id":"tt0000002","name":"Example Film","year":"2026","type":"movie"},
                  {"id":"tt0000003","name":"Example Film","year":"2026","type":"movie"},
                  {"id":"tt0000001","name":"Example Film","year":"2025","type":"movie"}
                ]}
            """.trimIndent()
            meta = meta.replace("2026", "2025")
        }
        assertNull(CinemetaResolver(http).resolve(MediaQuery("Example Film", 2026, "movie")))
        assertEquals(1, http.requests.size)
    }

    @Test fun `detail year must satisfy the tolerance used to select the catalog match`() = runBlocking {
        val query = MediaQuery("Example Film", 2026, "movie")
        val exact = CinemetaTransport().apply { meta = meta.replace("2026", "2025") }
        assertNull(CinemetaResolver(exact).resolve(query))
        assertEquals(2, exact.requests.size)

        val drifted = CinemetaTransport().apply {
            catalog = catalog.replace("2026", "2025")
            meta = meta.replace("2026", "2024")
        }
        assertNull(CinemetaResolver(drifted).resolve(query))
        assertEquals(2, drifted.requests.size)
    }

    @Test fun `transient failures retry after cooldown`() = runBlocking {
        var now = 0L
        val http = CinemetaTransport().apply { status = 503 }
        val resolver = CinemetaResolver(http) { now }
        val query = MediaQuery("Example Film", 2026, "movie")
        assertNull(resolver.resolve(query))
        http.status = 200
        assertNull(resolver.resolve(query))
        assertEquals(1, http.requests.size)
        now = 60_001
        assertNotNull(resolver.resolve(query))
    }

    @Test fun `successful and no-match lookups expire on their own schedules`() = runBlocking {
        for (matches in listOf(true, false)) {
            var time = 1000L
            val http = CinemetaTransport().apply { if (!matches) catalog = """{"metas":[]}""" }
            val resolver = CinemetaResolver(http, now = { time })
            val result = resolver.resolve(movieQuery)
            assertEquals(matches, result != null)
            val requests = http.requests.size
            time += (if (matches) 24 else 1) * 60 * 60 * 1000L - 1
            assertEquals(result, resolver.resolve(movieQuery))
            assertEquals(requests, http.requests.size)
            time += 2
            assertEquals(result, resolver.resolve(movieQuery))
            assertEquals(2 * requests, http.requests.size)
        }
    }

    @Test fun `fresh disk entries skip network`() = runBlocking {
        val disk = MetadataDiskCache(folder.root)
        disk.put(movieQuery, cachedMovie)
        assertEquals(cachedMovie, CinemetaResolver(unexpectedNetwork, diskCache = disk).resolve(movieQuery))
    }

    @Test fun `stale disk hits coalesce background refresh and store the fresh value`() = runBlocking {
        var time = 1000L
        val disk = MetadataDiskCache(folder.root, now = { time })
        disk.put(movieQuery, cachedMovie)
        time += 181L * 24 * 60 * 60 * 1000
        val http = CinemetaTransport()
        val resolver = CinemetaResolver(http, { time }, disk, this)
        assertEquals(cachedMovie, resolver.resolve(movieQuery))
        assertEquals(cachedMovie, resolver.resolve(movieQuery.copy(title = "example-film")))
        assertTrue(http.requests.isEmpty())

        val refreshes = coroutineContext[Job]!!.children.toList()
        assertEquals(1, refreshes.size)
        withTimeout(1000) { refreshes.joinAll() }
        val entry = disk.get(movieQuery)!!
        assertTrue(entry.isFresh(time))
        val fresh = entry.metadata
        assertEquals("tt0000001", fresh.imdbId)
        assertEquals(fresh, resolver.resolve(movieQuery))
        assertEquals(2, http.requests.size)
    }

    @Test fun `inline refresh preserves stale disk data on failure and retries after cooldown`() = runBlocking {
        var time = 1000L
        val disk = MetadataDiskCache(folder.root, now = { time })
        disk.put(movieQuery, cachedMovie)
        time += 181L * 24 * 60 * 60 * 1000
        val http = CinemetaTransport().apply { status = 503 }
        val resolver = CinemetaResolver(http, { time }, disk)
        assertEquals(cachedMovie, resolver.resolve(movieQuery))
        assertEquals(1000L, disk.get(movieQuery)?.fetchedAt)
        http.status = 200
        assertEquals(cachedMovie, resolver.resolve(movieQuery))
        assertEquals(1, http.requests.size)
        time += 60_001
        val fresh = resolver.resolve(movieQuery)
        assertEquals("tt0000001", fresh?.imdbId)
        assertEquals(fresh, disk.get(movieQuery)?.metadata)
        assertEquals(3, http.requests.size)
    }

    @Test fun `successful network lookups persist across resolvers and replace corrupt entries`() = runBlocking {
        val disk = MetadataDiskCache(folder.root)
        val http = CinemetaTransport()
        val result = CinemetaResolver(http, diskCache = disk).resolve(movieQuery)
        assertNotNull(result)
        assertEquals(2, http.requests.size)
        assertEquals(result, CinemetaResolver(unexpectedNetwork, diskCache = disk).resolve(movieQuery))

        folder.root.listFiles()!!.single().writeText("garbage")
        assertEquals(result, CinemetaResolver(http, diskCache = disk).resolve(movieQuery))
        assertEquals(4, http.requests.size)
        assertEquals(result, disk.get(movieQuery)?.metadata)
    }

    @Test fun `bounded cache evicts oldest entry`() = runBlocking {
        val http = CinemetaTransport().apply { catalog = """{"metas":[]}""" }
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

    @Test fun `ambiguous title never auto-resolves but lists both candidates`() = runBlocking {
        val http = CinemetaTransport().apply {
            catalog = """{"metas":[
                {"id":"tt0000001","name":"Example Film","releaseInfo":"2026","type":"movie"},
                {"id":"tt0000002","name":"Example Film","releaseInfo":"2026","type":"movie"}]}"""
        }
        val resolver = CinemetaResolver(http)
        val query = MediaQuery("Example Film", 2026, "movie")
        assertNull(resolver.resolve(query))
        assertEquals(
            listOf(
                MediaCandidate("tt0000001", "Example Film", 2026, "movie"),
                MediaCandidate("tt0000002", "Example Film", 2026, "movie"),
            ),
            resolver.candidates(query),
        )
        // One catalog fetch for the failed automatic match, one for the picker.
        assertEquals(2, http.requests.size)
    }

    @Test fun `candidates are capped at five and empty when nothing matches`() = runBlocking {
        val http = CinemetaTransport().apply {
            catalog = """{"metas":[${
                (1..6).joinToString(",") {
                    """{"id":"tt000000$it","name":"Example Film","releaseInfo":"${2019 + it}","type":"movie"}"""
                }
            }]}"""
        }
        val resolver = CinemetaResolver(http)
        val candidates = resolver.candidates(MediaQuery("Example Film", 2026, "movie"))
        assertEquals(5, candidates.size)
        assertEquals((1..5).map { "tt000000$it" }, candidates.map { it.imdbId })
        assertEquals(emptyList<MediaCandidate>(), resolver.candidates(MediaQuery("No Such Title", null, "movie")))
    }

    @Test fun `resolveById returns the picked title's detail`() = runBlocking {
        val resolver = CinemetaResolver(CinemetaTransport())
        val result = resolver.resolveById("tt0000001", "movie")
        assertEquals("Example Film", result?.title)
        assertEquals(2026, result?.year)
        assertEquals("tt0000001", result?.imdbId)
    }

    @Test fun `resolveById rejects a mismatched detail and invalid ids without a request`() = runBlocking {
        val http = CinemetaTransport().apply {
            meta = """{"meta":{"id":"tt9999999","name":"Other Film","year":"2026","type":"movie"}}"""
        }
        val resolver = CinemetaResolver(http)
        assertNull(resolver.resolveById("tt0000001", "movie"))
        assertEquals(1, http.requests.size)
        assertNull(resolver.resolveById("not-an-id", "movie"))
        assertNull(resolver.resolveById("tt0000001", "book"))
        assertEquals(1, http.requests.size)
    }

    // ---- TMDB fallback (Phase 6) ----

    private fun tmdbTransport(calls: MutableList<HttpRequest>): HttpTransport =
        object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                calls += request
                return when {
                    request.url.endsWith("/3/configuration") -> HttpResponse(200,
                        """{"images":{"secure_base_url":"https://images.example.invalid/t/p/",
                            "poster_sizes":["w342","w500","original"]}}""")
                    "/3/search/movie" in request.url -> HttpResponse(200,
                        """{"results":[{"id":123,"title":"Example Film","release_date":"2026-03-01",
                            "overview":"Fallback hit.","poster_path":"/s.jpg"}]}""")
                    "/3/search/tv" in request.url -> HttpResponse(200, """{"results":[]}""")
                    "/3/movie/123" in request.url && request.url.endsWith("/credits") -> HttpResponse(200,
                        """{"cast":[{"name":"Actor One"}],"crew":[{"name":"Director One","job":"Director"}]}""")
                    "/3/movie/123" in request.url -> HttpResponse(200,
                        """{"id":123,"title":"Example Film","release_date":"2026-03-01",
                            "overview":"Fallback hit.","genres":[{"name":"Drama"}],"runtime":148,
                            "poster_path":"/d.jpg"}""")
                    else -> error("Unexpected TMDB request: ${request.url}")
                }
            }
        }

    @Test fun `cinemeta miss falls back to TMDB and records the source`() = runBlocking {
        val disk = MetadataDiskCache(folder.root)
        val cinemeta = CinemetaTransport().apply { catalog = """{"metas":[]}""" }
        val tmdbCalls = mutableListOf<HttpRequest>()
        val resolver = CinemetaResolver(
            cinemeta,
            diskCache = disk,
            tmdbResolver = TmdbResolver(tmdbTransport(tmdbCalls)) { "test-key" },
        )
        val result = resolver.resolve(movieQuery)
        assertEquals("Example Film", result?.title)
        assertEquals("Fallback hit.", result?.overview)
        assertEquals("https://images.example.invalid/t/p/w500/d.jpg", result?.posterUrl)
        assertTrue(tmdbCalls.isNotEmpty())
        assertTrue(tmdbCalls.all { it.headers["Authorization"] == "Bearer test-key" })
        val entry = disk.get(movieQuery)
        assertEquals("tmdb", entry?.source)
        assertEquals(result, entry?.metadata)
        assertTrue(entry?.isFresh(System.currentTimeMillis()) == true)
    }

    @Test fun `cinemeta hit never calls TMDB`() = runBlocking {
        val tmdbCalls = mutableListOf<HttpRequest>()
        val resolver = CinemetaResolver(
            CinemetaTransport(),
            tmdbResolver = TmdbResolver(tmdbTransport(tmdbCalls)) { "test-key" },
        )
        val result = resolver.resolve(movieQuery)
        assertEquals("Example Film", result?.title)
        assertTrue(tmdbCalls.isEmpty())
    }

    @Test fun `no TMDB key means the fallback makes no network call`() = runBlocking {
        val cinemeta = CinemetaTransport().apply { catalog = """{"metas":[]}""" }
        val tmdbCalls = mutableListOf<HttpRequest>()
        val resolver = CinemetaResolver(
            cinemeta,
            tmdbResolver = TmdbResolver(tmdbTransport(tmdbCalls)) { null },
        )
        assertNull(resolver.resolve(movieQuery))
        assertTrue(tmdbCalls.isEmpty())
        // Cinemeta itself was still consulted exactly once.
        assertEquals(1, cinemeta.requests.size)
    }

    @Test fun `TMDB fallback result is cached in memory like a Cinemeta hit`() = runBlocking {
        val cinemeta = CinemetaTransport().apply { catalog = """{"metas":[]}""" }
        val tmdbCalls = mutableListOf<HttpRequest>()
        val resolver = CinemetaResolver(
            cinemeta,
            tmdbResolver = TmdbResolver(tmdbTransport(tmdbCalls)) { "test-key" },
        )
        val first = resolver.resolve(movieQuery)
        val callsAfterFirst = tmdbCalls.size
        assertEquals(first, resolver.resolve(movieQuery))
        assertEquals(callsAfterFirst, tmdbCalls.size)
    }
}
