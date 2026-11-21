package app.trove.debrid.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TMDB fallback, verified against fake transports only — no live
 * TMDB key exists, so no test here ever touches the real API.
 */
class TmdbResolverTest {
    private val key = "test-read-access-token"

    private val configJson = """
        {"images":{"secure_base_url":"https://images.example.invalid/t/p/",
        "poster_sizes":["w92","w154","w185","w342","w500","w780","original"]}}
    """.trimIndent()

    private val findMovieJson = """
        {"movie_results":[{"id":123,"title":"Example Film","release_date":"2026-03-01",
        "overview":"Found via id.","poster_path":"/find.jpg","vote_average":7.5}],"tv_results":[]}
    """.trimIndent()

    private val findTvJson = """
        {"movie_results":[],"tv_results":[{"id":456,"name":"Example Show",
        "first_air_date":"2026-01-15","overview":"Found series via id.","poster_path":"/find-tv.jpg",
        "vote_average":8.1}]}
    """.trimIndent()

    private val movieDetailJson = """
        {"id":123,"title":"Example Film","release_date":"2026-03-01","overview":"A synopsis.",
        "genres":[{"name":"Drama"},{"name":"Mystery"}],"runtime":148,
        "poster_path":"/detail.jpg","vote_average":7.5}
    """.trimIndent()

    private val tvDetailJson = """
        {"id":456,"name":"Example Show","first_air_date":"2026-01-15","overview":"A series synopsis.",
        "genres":[{"name":"Drama"}],"episode_run_time":[45],"poster_path":"/show.jpg",
        "created_by":[{"name":"Show Runner"}]}
    """.trimIndent()

    private val movieCreditsJson = """
        {"cast":[{"name":"Actor One"},{"name":"Actor Two"}],
        "crew":[{"name":"Director One","job":"Director"},{"name":"Writer","job":"Writer"}]}
    """.trimIndent()

    private val tvCreditsJson = """
        {"cast":[{"name":"Lead Actor"}],
        "crew":[{"name":"Episode Director","job":"Director"}]}
    """.trimIndent()

    private inner class FakeTmdb : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        var configStatus = 200
        var creditsStatus = 200
        var findJson = """{"movie_results":[],"tv_results":[]}"""
        var searchMovieJson = """{"results":[]}"""
        var searchTvJson = """{"results":[]}"""
        var detailJson = """{}"""
        var creditsJson = """{"cast":[],"crew":[]}"""
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val body = when {
                request.url.endsWith("/3/configuration") ->
                    if (configStatus == 200) configBody else return HttpResponse(configStatus, "{}")
                "/3/find/" in request.url -> findJson
                "/3/search/movie" in request.url -> searchMovieJson
                "/3/search/tv" in request.url -> searchTvJson
                request.url.endsWith("/credits") ->
                    if (creditsStatus == 200) creditsJson else return HttpResponse(creditsStatus, "{}")
                "/3/movie/" in request.url || "/3/tv/" in request.url -> detailJson
                else -> error("Unexpected TMDB request: ${request.url}")
            }
            return HttpResponse(200, body)
        }
        var configBody: String = configJson
    }

    private fun resolver(http: FakeTmdb, apiKey: String? = key) =
        TmdbResolver(http) { apiKey }

    @Test fun `find bridges an IMDb id to a movie`() = runBlocking {
        val http = FakeTmdb().apply {
            findJson = findMovieJson
            detailJson = movieDetailJson
            creditsJson = movieCreditsJson
        }
        val result = resolver(http).resolveById("tt0000001", "movie")
        assertEquals(
            MediaMetadata(
                "Example Film", 2026, "movie",
                "https://images.example.invalid/t/p/w185/detail.jpg",
                imdbId = "tt0000001", overview = "A synopsis.",
                genres = listOf("Drama", "Mystery"),
                cast = listOf("Actor One", "Actor Two"), director = "Director One",
                runtime = "148 min", provenance = EnrichmentProvenance.TMDB,
            ),
            result,
        )
        assertTrue(http.requests.first().url.endsWith("/3/configuration"))
        assertTrue(http.requests.any { it.url.contains("/3/find/tt0000001?external_source=imdb_id") })
        assertTrue(http.requests.all { it.headers["Authorization"] == "Bearer $key" })
        assertTrue(http.requests.none { it.url.contains(key) })
    }

    @Test fun `find bridges an IMDb id to a series`() = runBlocking {
        val http = FakeTmdb().apply {
            findJson = findTvJson
            detailJson = tvDetailJson
            creditsJson = tvCreditsJson
        }
        val result = resolver(http).resolveById("tt0000001", "series")
        assertEquals(
            MediaMetadata(
                "Example Show", 2026, "series",
                "https://images.example.invalid/t/p/w185/show.jpg",
                imdbId = "tt0000001", overview = "A series synopsis.",
                genres = listOf("Drama"), cast = listOf("Lead Actor"),
                director = "Episode Director", runtime = "45 min", provenance = EnrichmentProvenance.TMDB,
            ),
            result,
        )
    }

    @Test fun `find with the wrong type or an invalid id resolves nothing`() = runBlocking {
        val http = FakeTmdb().apply { findJson = findMovieJson }
        assertNull(resolver(http).resolveById("tt0000001", "series"))
        assertNull(resolver(http).resolveById("not-an-id", "movie"))
        assertNull(resolver(http).resolveById("tt0000001", "other"))
        // The first call made two requests (configuration + /find);
        // the invalid id and the bad type never reach the network.
        assertEquals(2, http.requests.size)
    }

    @Test fun `title search falls back when no IMDb id is known`() = runBlocking {
        val http = FakeTmdb().apply {
            searchMovieJson = """
                {"results":[{"id":123,"title":"Example Film","release_date":"2026-03-01",
                "overview":"Search hit.","poster_path":"/search.jpg","vote_average":7.5}]}
            """.trimIndent()
            searchTvJson = """{"results":[]}"""
            detailJson = movieDetailJson
            creditsJson = movieCreditsJson
        }
        val result = resolver(http).resolve(MediaQuery("Example Film", 2026, "movie"))
        assertEquals("Example Film", result?.title)
        // Title search knows no IMDb id, so none is claimed.
        assertNull(result?.imdbId)
        assertEquals("148 min", result?.runtime)
        assertEquals("https://images.example.invalid/t/p/w185/detail.jpg", result?.posterUrl)
        assertTrue(http.requests.any { it.url.contains("/3/search/movie?query=Example%20Film") })
        assertTrue(http.requests.none { "/3/find/" in it.url })
    }

    @Test fun `title search keeps the strict single-candidate year gate`() = runBlocking {
        // Two exact-title, same-year hits: ambiguous, no auto-apply.
        val ambiguous = FakeTmdb().apply {
            searchMovieJson = """
                {"results":[
                {"id":1,"title":"Example Film","release_date":"2026-01-01","poster_path":"/a.jpg"},
                {"id":2,"title":"Example Film","release_date":"2026-06-01","poster_path":"/b.jpg"}]}
            """.trimIndent()
            detailJson = movieDetailJson
        }
        assertNull(resolver(ambiguous).resolve(MediaQuery("Example Film", 2026, "movie")))

        // A one-year drift resolves, like the Cinemeta ±1 fallback.
        val drifted = FakeTmdb().apply {
            searchMovieJson = """
                {"results":[{"id":123,"title":"Example Film","release_date":"2025-03-01",
                "overview":"Drifted.","poster_path":"/d.jpg"}]}
            """.trimIndent()
            detailJson = movieDetailJson
        }
        assertEquals("Example Film", resolver(drifted).resolve(MediaQuery("Example Film", 2026, "movie"))?.title)

        // A different title never matches, however close the year is.
        val wrongTitle = FakeTmdb().apply {
            searchMovieJson = """
                {"results":[{"id":123,"title":"Other Film","release_date":"2026-03-01",
                "overview":"Wrong.","poster_path":"/w.jpg"}]}
            """.trimIndent()
            detailJson = movieDetailJson
        }
        assertNull(resolver(wrongTitle).resolve(MediaQuery("Example Film", 2026, "movie")))
    }

    @Test fun `original title matches and localized title takes priority within the same year`() = runBlocking {
        val originalTitle = """
            {"id":123,"title":"Localized Film","original_title":"Example Film","original_language":"en",
              "release_date":"2026-03-01","popularity":90,"poster_path":"/original.jpg"}
        """.trimIndent()
        val localizedTitle = """
            {"id":124,"title":"Example Film","original_title":"A Different Title","original_language":"fr",
              "release_date":"2026-03-01","popularity":1,"poster_path":"/localized.jpg"}
        """.trimIndent()
        val http = FakeTmdb().apply { searchMovieJson = """{"results":[$originalTitle]}""" }
        val resolver = resolver(http)
        val query = MediaQuery("Example Film", 2026, "movie")
        assertEquals("https://images.example.invalid/t/p/w185/original.jpg", resolver.resolvePoster(query, null))
        http.searchMovieJson = """{"results":[$originalTitle,$localizedTitle]}"""
        assertEquals("https://images.example.invalid/t/p/w185/localized.jpg", resolver.resolvePoster(query, null))
    }

    @Test fun `popularity breaks only compatible exact year ties`() = runBlocking {
        val lessPopular = """
            {"id":123,"title":"Example Film","original_title":"Example Film","original_language":"en",
              "release_date":"2026-03-01","popularity":1,"poster_path":"/less.jpg"}
        """.trimIndent()
        val morePopular = """
            {"id":124,"title":"Example Film","original_title":"Example Film","original_language":"en",
              "release_date":"2026-03-01","popularity":99,"poster_path":"/more.jpg"}
        """.trimIndent()
        val http = FakeTmdb().apply { searchMovieJson = """{"results":[$lessPopular,$morePopular]}""" }
        val resolver = resolver(http)
        val query = MediaQuery("Example Film", 2026, "movie")
        assertEquals("https://images.example.invalid/t/p/w185/more.jpg", resolver.resolvePoster(query, null))
        assertNull(resolver.resolvePoster(query.copy(year = null), null))
        val differentLanguage = morePopular.replace("en", "fr")
        http.searchMovieJson = """{"results":[$lessPopular,$differentLanguage]}"""
        assertNull(resolver.resolvePoster(query, null))
        http.searchMovieJson = """{"results":[$lessPopular,${morePopular.replace("2026-03-01", "2025-03-01")}]}"""
        assertEquals("https://images.example.invalid/t/p/w185/less.jpg", resolver.resolvePoster(query, null))
    }

    @Test fun `no key means no TMDB request at all`() = runBlocking {
        val http = FakeTmdb()
        assertNull(resolver(http, apiKey = null).resolve(MediaQuery("Example Film", 2026, "movie")))
        assertNull(resolver(http, apiKey = "").resolve(MediaQuery("Example Film", 2026, "movie")))
        assertNull(resolver(http, apiKey = "   ").resolveById("tt0000001", "movie"))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `poster URLs come from configuration never a hardcoded host`() = runBlocking {
        val http = FakeTmdb().apply {
            configBody = """
                {"images":{"secure_base_url":"https://cdn.example.invalid/t/p/",
                "poster_sizes":["w185","w342","original"]}}
            """.trimIndent()
            findJson = findMovieJson
            detailJson = movieDetailJson
            creditsJson = movieCreditsJson
        }
        val result = resolver(http).resolveById("tt0000001", "movie")
        // The host and the preferred size both come from /3/configuration.
        assertEquals("https://cdn.example.invalid/t/p/w185/detail.jpg", result?.posterUrl)
    }

    @Test fun `a failed configuration still resolves the title without a poster`() = runBlocking {
        val http = FakeTmdb().apply {
            configStatus = 500
            findJson = findMovieJson
            detailJson = movieDetailJson
            creditsJson = movieCreditsJson
        }
        val result = resolver(http).resolveById("tt0000001", "movie")
        assertEquals("Example Film", result?.title)
        assertNull(result?.posterUrl)
    }

    @Test fun `validateKey reports the configuration check`() = runBlocking {
        val ok = FakeTmdb()
        assertTrue(resolver(ok).validateKey(key))
        assertTrue(ok.requests.single().headers["Authorization"] == "Bearer $key")
        val denied = FakeTmdb().apply { configStatus = 401 }
        assertEquals(false, resolver(denied).validateKey("bad-key"))
    }

    @Test fun `detail for a different id is rejected`() = runBlocking {
        val http = FakeTmdb().apply {
            findJson = findMovieJson
            // Detail returns an object for a different id: rejected.
            detailJson = """{"id":999,"title":"Wrong Film"}"""
            creditsJson = movieCreditsJson
        }
        assertNull(resolver(http).resolveById("tt0000001", "movie"))
    }

    @Test fun `searchCandidates lists TMDB matches in returned order`() = runBlocking {
        val http = FakeTmdb().apply {
            searchMovieJson = """
                {"results":[
                {"id":1,"title":"Example Film","release_date":"2026-01-01","poster_path":"/a.jpg"},
                {"id":2,"title":"Example Film","release_date":"2025-06-01","poster_path":"/b.jpg"}]}
            """.trimIndent()
            searchTvJson = """{"results":[]}"""
        }
        val candidates = resolver(http).searchCandidates(MediaQuery("Example Film", 2026, "movie"))
        assertEquals(2, candidates.size)
        assertEquals(MediaCandidate(null, "Example Film", 2026, "movie", tmdbId = 1), candidates[0])
        assertEquals(MediaCandidate(null, "Example Film", 2025, "movie", tmdbId = 2), candidates[1])
    }

    @Test fun `searchCandidates is empty without a key and makes no requests`() = runBlocking {
        val http = FakeTmdb()
        assertTrue(resolver(http, apiKey = null).searchCandidates(MediaQuery("Example Film", 2026, "movie")).isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `resolveByTmdbId returns detail with a verified IMDb id`() = runBlocking {
        val http = FakeTmdb().apply {
            detailJson = """
                {"id":123,"title":"Example Film","release_date":"2026-03-01","overview":"A synopsis.",
                "genres":[{"name":"Drama"},{"name":"Mystery"}],"runtime":148,
                "poster_path":"/detail.jpg","vote_average":7.5,
                "external_ids":{"imdb_id":"tt0000001"}}
            """.trimIndent()
            creditsJson = movieCreditsJson
        }
        val result = resolver(http).resolveByTmdbId(123, "movie")
        assertEquals("Example Film", result?.title)
        assertEquals("tt0000001", result?.imdbId)
        assertEquals("https://images.example.invalid/t/p/w185/detail.jpg", result?.posterUrl)
        assertEquals(EnrichmentProvenance.TMDB, result?.provenance)
        assertTrue(http.requests.any { it.url.endsWith("/3/movie/123?append_to_response=external_ids") })
    }

    @Test fun `resolveByTmdbId is null without a key or for an invalid id`() = runBlocking {
        val http = FakeTmdb()
        assertNull(resolver(http, apiKey = null).resolveByTmdbId(123, "movie"))
        assertNull(resolver(http).resolveByTmdbId(0, "movie"))
        assertNull(resolver(http).resolveByTmdbId(123, "bogus"))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `credit failures degrade instead of failing the resolve`() = runBlocking {
        val http = FakeTmdb().apply {
            findJson = findMovieJson
            detailJson = movieDetailJson
            creditsStatus = 500
        }
        val result = resolver(http).resolveById("tt0000001", "movie")
        assertEquals("Example Film", result?.title)
        assertTrue(result?.cast?.isEmpty() == true)
        assertNull(result?.director)
    }

    @Test fun `parseManualLink accepts TMDB movie and tv links`() {
        val parsed = resolver(FakeTmdb())
        assertEquals(ManualTmdbLink.ById(123, "movie"),
            parsed.parseManualLink("https://www.themoviedb.org/movie/123", null))
        assertEquals(ManualTmdbLink.ById(456, "series"),
            parsed.parseManualLink("https://themoviedb.org/tv/456", "movie"))
        assertEquals(ManualTmdbLink.ById(123, "movie"),
            parsed.parseManualLink("http://themoviedb.org/movie/123?language=en-US", null))
        assertEquals(ManualTmdbLink.ById(123, "movie"),
            parsed.parseManualLink("themoviedb.org/movie/123/", null))
    }

    @Test fun `parseManualLink accepts a bare id only with a known type`() {
        val parsed = resolver(FakeTmdb())
        assertEquals(ManualTmdbLink.ById(123, "movie"), parsed.parseManualLink("123", "movie"))
        assertEquals(ManualTmdbLink.ById(456, "series"), parsed.parseManualLink(" 456 ", "series"))
        assertEquals(ManualTmdbLink.Invalid, parsed.parseManualLink("123", null))
        assertEquals(ManualTmdbLink.Invalid, parsed.parseManualLink("123", "bogus"))
        assertEquals(ManualTmdbLink.Invalid, parsed.parseManualLink("0", "movie"))
    }

    @Test fun `parseManualLink treats other text as a title and rejects blank or over-long input`() {
        val parsed = resolver(FakeTmdb())
        assertEquals(ManualTmdbLink.ByTitle("Example Film"),
            parsed.parseManualLink("  Example Film  ", "movie"))
        assertEquals(ManualTmdbLink.Invalid, parsed.parseManualLink("", null))
        assertEquals(ManualTmdbLink.Invalid, parsed.parseManualLink("   ", null))
        assertEquals(ManualTmdbLink.Invalid, parsed.parseManualLink("x".repeat(161), null))
        assertEquals(ManualTmdbLink.ByTitle("x".repeat(160)), parsed.parseManualLink("x".repeat(160), null))
        // A non-TMDB link is just text, never an id.
        assertEquals(ManualTmdbLink.ByTitle("https://example.com/movie/123"),
            parsed.parseManualLink("https://example.com/movie/123", null))
    }

    @Test fun `searchManualLink resolves a pasted TMDB link to one candidate`() = runBlocking {
        val http = FakeTmdb().apply {
            detailJson = movieDetailJson
            creditsJson = movieCreditsJson
        }
        val result = resolver(http).searchManualLink("https://themoviedb.org/movie/123", null)
        assertEquals(
            ManualTmdbSearch.Found(listOf(
                MediaCandidate(null, "Example Film", 2026, "movie", tmdbId = 123))),
            result)
        assertTrue(http.requests.any { it.url.endsWith("/3/movie/123?append_to_response=external_ids") })
    }

    @Test fun `searchManualLink reports no match when the linked id has no detail`() = runBlocking {
        val http = FakeTmdb().apply { detailJson = "{}" }
        assertEquals(ManualTmdbSearch.NoMatch, resolver(http).searchManualLink("123", "movie"))
    }

    @Test fun `searchManualLink searches by title`() = runBlocking {
        val http = FakeTmdb().apply {
            searchMovieJson = """
                {"results":[
                {"id":1,"title":"Example Film","release_date":"2026-01-01","poster_path":"/a.jpg"}]}
            """.trimIndent()
            searchTvJson = """{"results":[]}"""
        }
        val result = resolver(http).searchManualLink("Example Film", "movie")
        assertEquals(
            ManualTmdbSearch.Found(listOf(
                MediaCandidate(null, "Example Film", 2026, "movie", tmdbId = 1))),
            result)
    }

    @Test fun `searchManualLink reports no match when the title search is empty`() = runBlocking {
        val http = FakeTmdb()
        assertEquals(ManualTmdbSearch.NoMatch, resolver(http).searchManualLink("No Such Film", "movie"))
    }

    @Test fun `searchManualLink is no match without a key and makes no requests`() = runBlocking {
        val http = FakeTmdb()
        assertEquals(ManualTmdbSearch.NoMatch,
            resolver(http, apiKey = null).searchManualLink("123", "movie"))
        assertEquals(ManualTmdbSearch.NoMatch,
            resolver(http, apiKey = null).searchManualLink("Example Film", "movie"))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `searchManualLink never requests for invalid input`() = runBlocking {
        val http = FakeTmdb()
        assertEquals(ManualTmdbSearch.Invalid, resolver(http).searchManualLink("   ", null))
        assertTrue(http.requests.isEmpty())
    }
}
