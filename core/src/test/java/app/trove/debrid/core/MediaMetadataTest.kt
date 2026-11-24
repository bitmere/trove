package app.trove.debrid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
    private val cachedMovie = MediaMetadata(
        movieQuery.title, movieQuery.year, movieQuery.type, posterUrl = "https://images.example.invalid/cached.jpg",
        provenance = EnrichmentProvenance.CINEMETA,
    )
    private val unexpectedNetwork = object : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse =
            throw AssertionError("Network must not run")
    }

    @Test fun `candidate catalog preserves synopsis and missing artwork independently`() = runBlocking {
        val http = CinemetaTransport().apply {
            catalog = """{"metas":[
                {"id":"tt1","name":"Example Film","type":"movie","poster":"https://images.example.invalid/a.jpg","description":"First synopsis"},
                {"id":"tt2","name":"Example Film","type":"movie","description":"Second synopsis"},
                {"id":"tt3","name":"Example Film","type":"movie","poster":"https://images.example.invalid/b.jpg"},
                {"id":"tt4","name":"Example Film","type":"movie","description":" "}]}"""
        }
        val candidates = CinemetaResolver(http).candidates(movieQuery)
        assertEquals(listOf("First synopsis", "Second synopsis", null, null), candidates.map { it.overview })
        assertEquals(listOf("https://images.example.invalid/a.jpg", null,
            "https://images.example.invalid/b.jpg", null), candidates.map { it.posterUrl })
    }

    @Test fun `strips only structurally delimited leading domain banners`() {
        for (banner in listOf("www.1TamilMV.ceo - ", "[example.technology] ", "(example.org) ",
            "{example.org} ", "example.org - ", "example.co.uk--", "example.org  ")) {
            assertEquals(banner, MediaQuery("Film", null, null), parseMediaName("${banner}Film"))
        }
        assertEquals(MediaQuery("418 TRUE", null, null), parseMediaName("www 1TamilMV ceo - 418 TRUE"))
        for ((name, title) in listOf(
            "O.M Operation Mole" to "O M Operation Mole", "S.H.I.E.L.D." to "S H I E L D",
            "Spider-Man" to "Spider-Man", "example.org Film" to "example org Film",
            "example.org-Film" to "example org-Film", "Naruto-Kun.org - Film" to "Naruto-Kun org - Film",
            "www 1TamilMV ceo Film" to "www 1TamilMV ceo Film",
        )) assertEquals(name, MediaQuery(title, null, null), parseMediaName(name))
    }

    @Test fun `supplied releases share the same primary title and grouped year`() {
        val releases = listOf(
            "O M Operation Mole (2026) 1080p Telugu DVDScr - x264 - AAC - 7.5GB",
            "www.1TamilMV.ceo - O.M Operation Mole (2026) Telugu HQ HDTS - 720p - x264 - HQ Clean - AAC - 1.7GB.mkv",
        )
        for (release in releases) {
            assertEquals(MediaQuery("O M Operation Mole", 2026, null), parseMediaName(release))
            assertEquals(listOf(MediaQuery("O M Operation Mole", 2026, null),
                MediaQuery("O M Operation Mole", null, null)), mediaQueryVariants(release))
        }
    }

    @Test fun `prefers first standalone grouped year and otherwise last bare year`() {
        for (year in listOf("(2026)", "[2026]", "{2026}", "( 2026 )")) {
            assertEquals(MediaQuery("Film", 2026, null), parseMediaName("Film $year Telugu HQ HDTS"))
        }
        assertEquals(MediaQuery("Film", 2020, null), parseMediaName("Film (2020) 2021 1080p"))
        assertEquals(MediaQuery("Film", 2020, null), parseMediaName("Film (2020) [2021] 1080p"))
        assertEquals(MediaQuery("Film 2020", 2021, null), parseMediaName("Film 2020 2021 1080p"))
        val variants = mediaQueryVariants("Blade.Runner.2049.(Hindi.Dubbed.2024).2017.1080p.mkv")
        assertEquals(MediaQuery("Blade Runner 2049", 2017, null), variants.first())
        assertTrue(variants.all { it.year == null || it.year == 2017 })
        val next = java.time.Year.now().value + 1
        assertEquals(next, parseMediaName("Film ($next)").year)
        assertEquals(MediaQuery("Film ${next + 1}", null, null), parseMediaName("Film ${next + 1} 1080p"))
        assertTrue(mediaQueryVariants("Film (${next + 1})").all { it.year == null })
    }

    @Test fun `recognizes bounded compound technical families before punctuation cleanup`() {
        val tokens = listOf("HDTS", "HD-TS", "TELESYNC", "HDCAM", "HD-CAMRip", "CAMRip", "HDTC", "HD-TC",
            "TELECINE", "DVDScr", "DVDScreener", "SCREENER", "WEB-DL", "WEBRip", "BluRay", "Blu-Ray",
            "BDRip", "BRRip", "HDRip", "DVDRip", "HDTV", "576p", "1080i", "2160p", "4K", "FHD",
            "x264", "h.265", "HEVC", "AVC", "Xvid", "AV1", "AAC", "HE-AAC", "AAC-LC", "AC3",
            "DDP", "DD5.1", "DDP7.1", "DTS-HD-MA", "TrueHD", "Atmos", "1.7GB", "7.5 GB", "700MB",
            "remux", "HDR10+", "DV", "multi", "proper", "repack")
        for (token in tokens) assertEquals(token, MediaQuery("Film", null, null), parseMediaName("Film $token"))
        for (token in listOf("TS", "TC", "CAM", "WEB", "HD", "DD", "SCR", "5.1", "7.1", "2.0")) {
            assertEquals(token, MediaQuery("Film", null, null), parseMediaName("Film $token 720p"))
            assertEquals(token, MediaQuery("Film", 2026, null), parseMediaName("Film 2026 $token"))
            assertEquals(token, MediaQuery("Film $token".replace('.', ' '), null, null), parseMediaName("Film $token"))
        }
        assertEquals(MediaQuery("Film", null, null), parseMediaName("Film HQ HDTS 720p"))
        assertEquals(MediaQuery("Film", null, null), parseMediaName("Film HQ CAM 720p"))
        assertEquals(MediaQuery("Film HQ", null, null), parseMediaName("Film HQ AAC"))
    }

    @Test fun `preserves weak title words languages and excluded ambiguities`() {
        for (title in listOf("Cam", "The Web", "HQ Clean", "Film HQ", "Film HQ CAM", "The Telugu Film",
            "Film Clean", "418 TRUE", "Film-GROUP", "Webster", "Scary", "FilmXvid", "AACademy")) {
            assertEquals(title, MediaQuery(title, null, null), parseMediaName(title))
        }
        assertEquals(MediaQuery("Film Telugu", null, null), parseMediaName("Film Telugu HDTS"))
        assertEquals(MediaQuery("Film", 2026, null), parseMediaName("Film (2026) Telugu"))
        assertEquals(MediaQuery("Film", null, null), parseMediaName("Film HDTS Telugu"))
        assertEquals(MediaQuery("Class of", 1984, null), parseMediaName("Class of 1984"))
        assertEquals(MediaQuery("Room", null, "series"), parseMediaName("Room 237"))
        assertTrue(mediaQueryVariants("[REC]").all { it.title.isBlank() })
    }

    @Test fun `rejects embedded and leading release years across variants`() {
        for (title in listOf("Film A2012B", "1917", "2012", "300", "42")) {
            assertEquals(MediaQuery(title, null, null), parseMediaName(title))
            assertTrue(mediaQueryVariants(title).all { it.year == null })
        }
        for (title in listOf("[1917]", "(1917)", "1917.")) {
            assertEquals(listOf(MediaQuery("1917", null, null)), mediaQueryVariants(title))
            assertEquals(MediaQuery("1917", null, null), parseMediaName(title))
        }
        for (title in listOf("(1917) (2019) 1080p", "1917.2019.1080p")) {
            assertEquals(MediaQuery("1917", 2019, null), parseMediaName(title))
            assertTrue(mediaQueryVariants(title).all { it.year == null || it.year == 2019 })
        }
        assertEquals(MediaQuery("1923", null, "series"), parseMediaName("1923 S01E01"))
    }

    @Test fun `recovers existing raw title only when clean primary is blank`() = runBlocking {
        for (release in listOf("(Alien)", "(Alien) 1080p")) {
            assertEquals(MediaQuery("Alien", null, null), parseMediaName(release))
            val variants = mediaQueryVariants(release)
            assertEquals(listOf(MediaQuery("Alien", null, null)), variants)
            val http = CinemetaTransport().apply {
                catalog = """{"metas":[{"id":"tt1","name":"Alien","type":"movie"}]}"""
            }
            assertEquals("Alien", CinemetaResolver(http).candidates(variants.first()).single().title)
            assertTrue(http.requests.any { "/catalog/" in it.url && "search=Alien" in it.url })
        }
        for (release in listOf("", "...", "(Hindi Dubbed)", "(1080p)", "1080p", "1080p x264",
            "https://example.org/Film.mkv", "magnet:?dn=(Alien)")) {
            assertTrue(release, parseMediaName(release).title.isBlank())
            val http = CinemetaTransport()
            assertNull(CinemetaResolver(http).resolve(mediaQueryVariants(release)))
            assertTrue(release, http.requests.isEmpty())
        }
    }

    @Test fun `numeric punctuation retains all digits without weak episode inference`() {
        for (title in listOf("1.618", "1-618", "4:18", "418.", "1071", "42", "0")) {
            val query = parseMediaName(title)
            assertEquals(title, normalizedTitle(title), normalizedTitle(query.title))
            assertNull(query.type)
            assertNull(query.year)
        }
        assertEquals(MediaQuery("1917", null, "series"), parseMediaName("1917 S01E01"))
        assertEquals(MediaQuery("Show", null, "series"), parseMediaName("Show 1071"))
        assertEquals(MediaQuery("One Piece", null, "series"), parseMediaName("One Piece - 1071"))
        assertEquals(MediaQuery("The 100", null, null), parseMediaName("The 100"))
    }

    @Test fun `expanded search boundaries do not expand display quality labels`() {
        for (source in listOf("HDTS", "DVDScr", "HDRip", "576p", "FHD", "AV1", "TrueHD")) {
            assertEquals(ReleaseDetails(null, null), parseReleaseDetails("Film $source"))
        }
        assertEquals(ReleaseDetails("S01E02", "720p"), parseReleaseDetails("Show S01E02 HQ HDTS 720p"))
        for ((token, label) in listOf("4K" to "2160p", "1080p" to "1080p", "1080i" to "1080i",
            "720p" to "720p", "480p" to "480p")) {
            assertEquals(ReleaseDetails(null, label), parseReleaseDetails("Film $token"))
        }
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

    @Test fun `strips edition and parenthetical dub tags before searching`() {
        for (edition in listOf("Extended", "Unrated", "Directors.Cut", "Director's.Cut", "Remastered", "IMAX", "3D")) {
            assertEquals(MediaQuery("Avatar", 2009, null), parseMediaName("Avatar.$edition.2009.1080p.mkv"))
        }
        assertEquals(MediaQuery("Avatar The Way of Water", 2022, null),
            parseMediaName("Avatar.The.Way.of.Water.Extended.2022.1080p.mkv"))
        assertEquals(MediaQuery("Blade Runner 2049", 2017, null),
            parseMediaName("Blade.Runner.2049.(Hindi.Dubbed.2024).2017.1080p.mkv"))
        assertEquals(MediaQuery("1917", null, null), parseMediaName("[GROUP] 1917 (Hindi Dubbed).1080p.mkv"))
        assertEquals(MediaQuery("Avatar", 2009, null), parseMediaName("Avatar.Hindi.Dubbed.2009.1080p.mkv"))
        assertEquals(MediaQuery("Avatar", 2009, null), parseMediaName("Avatar.Dual.Audio.2009.1080p.mkv"))
    }

    @Test fun `orders distinct variants by year edition article and raw title`() {
        assertEquals(
            listOf(
                MediaQuery("The Extended Voyage", 2026, null),
                MediaQuery("The Extended Voyage", null, null),
                MediaQuery("The Voyage", 2026, null),
                MediaQuery("Voyage", 2026, null),
                MediaQuery("Voyage, The", 2026, null),
                MediaQuery("The Extended Voyage Hindi Dubbed", 2026, null),
            ),
            mediaQueryVariants("The.Extended.Voyage.(Hindi.Dubbed).2026.1080p.mkv"),
        )
        assertEquals(
            listOf("Voyage, The", "Voyage", "The Voyage"),
            mediaQueryVariants("Voyage, The.2026.mkv").filter { it.year == 2026 }.map { it.title },
        )
    }

    @Test fun `variants keep ambiguity strict and stop on the first hit`() = runBlocking {
        val http = CinemetaTransport().apply {
            catalog = """
                {"metas":[
                  {"id":"tt0000002","name":"The Example Film","year":"2026","type":"movie"},
                  {"id":"tt0000003","name":"The Example Film","year":"2026","type":"movie"},
                  {"id":"tt0000001","name":"Example Film","year":"2026","type":"movie"}
                ]}
            """.trimIndent()
        }
        val variants = mediaQueryVariants("The.Example.Film.(Hindi.Dubbed).2026.mkv")
            .map { it.copy(type = "movie") }
        assertEquals("tt0000001", CinemetaResolver(http).resolve(variants)?.imdbId)
        assertEquals(
            listOf("search=The%20Example%20Film.json", "search=The%20Example%20Film.json",
                "search=Example%20Film.json", "tt0000001.json"),
            http.requests.map { it.url.substringAfterLast('/') },
        )
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
                provenance = EnrichmentProvenance.CINEMETA,
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
                imdbId = "tt0000001", provenance = EnrichmentProvenance.CINEMETA),
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

    @Test fun `explicit refresh bypasses a miss but keeps the failure cooldown`() = runBlocking {
        val http = CinemetaTransport().apply { catalog = """{"metas":[]}""" }
        var time = 1000L
        val resolver = CinemetaResolver(http) { time }
        assertNull(resolver.resolve(movieQuery))
        time += 3_599_999
        assertNull(resolver.resolve(movieQuery))
        assertEquals(1, http.requests.size)
        http.status = 503
        assertNull(resolver.resolve(movieQuery, bypassMiss = true))
        assertEquals(2, http.requests.size)
        http.status = 200
        time += 59_999
        assertNull(resolver.resolve(movieQuery, bypassMiss = true))
        assertEquals(2, http.requests.size)
        time++
        assertNull(resolver.resolve(movieQuery))
        assertEquals(3, http.requests.size)
        time += 3_600_000
        assertNull(resolver.resolve(movieQuery))
        assertEquals(4, http.requests.size)
    }

    @Test fun `fresh disk entries skip network`() = runBlocking {
        val disk = MetadataDiskCache(folder.root)
        disk.put(movieQuery, cachedMovie)
        assertEquals(cachedMovie, CinemetaResolver(unexpectedNetwork, diskCache = disk).resolve(movieQuery))
    }

    private fun legacyDisk(time: () -> Long): MetadataDiskCache =
        MetadataDiskCache(folder.root, now = time).also { disk ->
            disk.put(movieQuery, cachedMovie)
            folder.root.listFiles()!!.single().writeText(
                """{"schemaVersion":1,"source":"cinemeta","fetchedAt":1000,"title":"Example Film",
                    "year":2026,"type":"movie","posterUrl":"https://images.example.invalid/cached.jpg"}""",
            )
        }

    @Test fun `upgrade serves a literal legacy poster before any call with TMDB configured`() = runBlocking {
        val disk = legacyDisk { 1001 }
        val resolver = CinemetaResolver(unexpectedNetwork, { 1001 }, disk, this,
            TmdbResolver(unexpectedNetwork) { "key" })
        val served = resolver.resolveOutcome(listOf(movieQuery))
        assertEquals(cachedMovie, served.metadata)
        assertEquals(EnrichmentProvenance.CINEMETA, served.provenance)
        assertEquals(MetadataDiskCache.DiskEntry(cachedMovie, 1000, false), disk.get(movieQuery))
        assertSame(served.revalidation, resolver.resolveOutcome(listOf(movieQuery)).revalidation)
        served.revalidation!!.cancelAndJoin()
    }

    @Test fun `cancelling an active legacy revalidation keeps the disk poster`() = runBlocking {
        val disk = legacyDisk { 1001 }
        val started = CompletableDeferred<Unit>()
        val http = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val resolver = CinemetaResolver(unexpectedNetwork, { 1001 }, disk, this, TmdbResolver(http) { "key" })
        val served = resolver.resolveOutcome(listOf(movieQuery))
        assertEquals(cachedMovie, served.metadata)
        withTimeout(1000) { started.await() }
        served.revalidation!!.cancelAndJoin()
        assertEquals(MetadataDiskCache.DiskEntry(cachedMovie, 1000, false), disk.get(movieQuery))
    }

    @Test fun `posterless legacy revalidation preserves the original entry and result`() = runBlocking {
        val disk = legacyDisk { 1001 }
        val cinemeta = CinemetaTransport().apply { catalog = """{"metas":[]}""" }
        val tmdb = TmdbResolver(primaryTransport(hasPoster = false)) { "key" }
        val resolver = CinemetaResolver(cinemeta, { 1001 }, disk, this, tmdb)
        val served = resolver.resolveOutcome(listOf(movieQuery))
        assertEquals(cachedMovie, served.revalidation!!.await()?.metadata)
        assertEquals(MetadataDiskCache.DiskEntry(cachedMovie, 1000, false), disk.get(movieQuery))
        assertEquals(cachedMovie, resolver.resolve(movieQuery))
    }

    @Test fun `legacy revalidation rate limit preserves art and retries after cooldown`() = runBlocking {
        var time = 1001L
        val disk = legacyDisk { time }
        val http = primaryTransport()
        var attempts = 0
        val limited = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                attempts++
                return if (time == 1001L) {
                    HttpResponse(429, "", mapOf("Retry-After" to "5"))
                } else http.execute(request)
            }
        }
        val tmdb = TmdbResolver(limited, now = { time }) { "key" }
        val resolver = CinemetaResolver(CinemetaTransport(), { time }, disk, this, tmdb)
        val first = resolver.resolveOutcome(listOf(movieQuery)).revalidation!!.await()!!
        assertEquals(cachedMovie, first.metadata)
        assertEquals(6001L, first.retryAfter)
        assertEquals(MetadataDiskCache.DiskEntry(cachedMovie, 1000, false), disk.get(movieQuery))
        assertNull(resolver.resolveOutcome(listOf(movieQuery), bypassMiss = true).revalidation)
        assertEquals(1, attempts)
        time = first.retryAfter
        val retried = resolver.resolveOutcome(listOf(movieQuery), bypassMiss = true)
        assertEquals(cachedMovie, retried.metadata)
        assertEquals(EnrichmentProvenance.TMDB, retried.revalidation!!.await()?.provenance)
        assertTrue(disk.get(movieQuery)!!.tmdbFirst)
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
                    "/3/find/tt0000001?external_source=imdb_id" in request.url -> HttpResponse(200,
                        """{"movie_results":[{"id":123,"poster_path":"/find.jpg"}]}""")
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

    private fun primaryTransport(
        hasPoster: Boolean = true,
        imdbId: String? = null,
        configurationStatus: Int = 200,
    ): HttpTransport {
        val base = tmdbTransport(mutableListOf())
        return object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                val response = base.execute(request)
                var body = response.body
                if (!hasPoster) body = body.replace("\"/s.jpg\"", "null").replace("\"/d.jpg\"", "null")
                if (imdbId != null && request.url.endsWith("?append_to_response=external_ids")) {
                    body = body.dropLast(1) + ""","external_ids":{"imdb_id":"$imdbId"}}"""
                }
                val status = if (request.url.endsWith("/configuration")) configurationStatus else response.status
                return response.copy(body = body, status = status)
            }
        }
    }

    @Test fun `posterless TMDB uses strict Cinemeta title match for artwork alone`() = runBlocking {
        val cinemeta = CinemetaTransport().apply {
            meta = """{"meta":{"id":"tt0000001","name":"Example Film","year":2026,"type":"movie",
                "poster":"https://images.example.invalid/alternate.jpg","imdbRating":"8.3","description":"Other text"}}"""
        }
        for (configurationStatus in listOf(200, 503)) {
            cinemeta.requests.clear()
            val http = primaryTransport(hasPoster = configurationStatus != 200, configurationStatus = configurationStatus)
            val result = CinemetaResolver(cinemeta, tmdbResolver = TmdbResolver(http) { "key" }).resolve(movieQuery)!!
            assertEquals("https://images.example.invalid/alternate.jpg", result.posterUrl)
            assertEquals(EnrichmentProvenance.TMDB_CINEMETA_POSTER, result.provenance)
            assertEquals("Fallback hit.", result.overview)
            assertNull(result.imdbId)
            assertNull(result.imdbRating)
            assertEquals(2, cinemeta.requests.size)
        }
        cinemeta.catalog = cinemeta.catalog.replace("2026", "2025")
        val wrongYear = CinemetaResolver(cinemeta, tmdbResolver = TmdbResolver(primaryTransport(false)) { "key" })
        assertNull(wrongYear.resolve(movieQuery)!!.posterUrl)
    }

    @Test fun `posterless TMDB uses a verified IMDb id directly for artwork`() = runBlocking {
        val cinemeta = CinemetaTransport()
        val tmdb = TmdbResolver(primaryTransport(hasPoster = false, imdbId = "tt0000001")) { "key" }
        val result = CinemetaResolver(cinemeta, tmdbResolver = tmdb).resolve(movieQuery)!!
        assertEquals("https://images.example.invalid/poster.jpg", result.posterUrl)
        assertEquals("tt0000001", result.imdbId)
        assertTrue(cinemeta.requests.single().url.endsWith("/meta/movie/tt0000001.json"))
    }

    @Test fun `posterless TMDB and IMDb backfill use strict title artwork without replacing identity`() = runBlocking {
        val backfillPath = "/meta/movie/tt0000002.json"
        val artwork = "https://images.example.invalid/poster.jpg"
        for (backfillStatus in listOf(200, 503)) {
            val catalog = CinemetaTransport()
            val calls = mutableListOf<String>()
            val cinemeta = object : HttpTransport {
                override suspend fun execute(request: HttpRequest): HttpResponse {
                    calls += request.url
                    return if (request.url.endsWith(backfillPath)) {
                        HttpResponse(backfillStatus,
                            """{"meta":{"id":"tt0000002","type":"movie","name":"Example Film",
                                "year":2026,"imdbRating":"8.3"}}""")
                    } else catalog.execute(request)
                }
            }
            val primary = TmdbResolver(primaryTransport(hasPoster = false, imdbId = "tt0000002")) { "key" }
            val result = CinemetaResolver(cinemeta, tmdbResolver = primary).resolve(movieQuery)!!
            assertEquals(artwork, result.posterUrl)
            assertEquals(EnrichmentProvenance.TMDB_CINEMETA_POSTER, result.provenance)
            assertEquals("tt0000002", result.imdbId)
            assertEquals("Example Film", result.title)
            assertEquals(2026, result.year)
            assertEquals("Fallback hit.", result.overview)
            assertEquals(8.3.takeIf { backfillStatus == 200 }, result.imdbRating)
            assertTrue(calls.first().endsWith(backfillPath))
            assertTrue(calls[1].contains("/catalog/movie/"))
            assertTrue(calls.last().endsWith("/meta/movie/tt0000001.json"))

            calls.clear()
            val tmdbCalls = mutableListOf<HttpRequest>()
            val noKey = TmdbResolver(tmdbTransport(tmdbCalls)) { null }
            val cinemetaFirst = CinemetaResolver(cinemeta, tmdbResolver = noKey).resolve(movieQuery)!!
            assertEquals(artwork, cinemetaFirst.posterUrl)
            assertEquals(EnrichmentProvenance.CINEMETA, cinemetaFirst.provenance)
            assertEquals("tt0000001", cinemetaFirst.imdbId)
            assertTrue(tmdbCalls.isEmpty())
            assertEquals(2, calls.size)

            catalog.catalog = catalog.catalog.replace("2026", "2025")
            val wrongYear = CinemetaResolver(cinemeta, tmdbResolver = primary).resolve(movieQuery)!!
            assertNull(wrongYear.posterUrl)
            assertEquals("tt0000002", wrongYear.imdbId)
        }
    }

    @Test fun `failed TMDB image promotes Cinemeta artwork in memory and on disk`() = runBlocking {
        val disk = MetadataDiskCache(folder.root)
        for (id in listOf(null, "tt0000001")) {
            disk.clear()
            val cinemeta = CinemetaTransport()
            val tmdb = TmdbResolver(primaryTransport(imdbId = id)) { "key" }
            val resolver = CinemetaResolver(cinemeta, diskCache = disk, tmdbResolver = tmdb)
            val primary = resolver.resolve(movieQuery)!!
            assertEquals("https://images.example.invalid/t/p/w342/d.jpg", primary.posterUrl)
            val failedImage = PosterSource<String>(null, { byteArrayOf(1) }, { null })
            assertNull(failedImage.load(primary.posterUrl!!))
            val calls = cinemeta.requests.size
            val alternate = resolver.replaceFailedPoster(movieQuery, primary)!!
            assertEquals("https://images.example.invalid/poster.jpg", alternate.posterUrl)
            assertEquals(primary.imdbId, alternate.imdbId)
            assertEquals(primary.overview, alternate.overview)
            assertEquals(if (id == null) calls + 2 else calls, cinemeta.requests.size)
            assertEquals(alternate, resolver.resolve(movieQuery))
            assertEquals(alternate, disk.get(movieQuery)?.metadata)
        }
    }

    @Test fun `a strong result without IMDb id uses TMDB alone without starting backfill`() = runBlocking {
        val disk = MetadataDiskCache(folder.root)
        val cinemeta = CinemetaTransport()
        val tmdbCalls = mutableListOf<HttpRequest>()
        val resolver = CinemetaResolver(
            cinemeta,
            diskCache = disk,
            tmdbResolver = TmdbResolver(tmdbTransport(tmdbCalls)) { "test-key" },
        )
        val result = resolver.resolve(movieQuery)
        assertEquals("Example Film", result?.title)
        assertEquals("Fallback hit.", result?.overview)
        assertEquals("https://images.example.invalid/t/p/w342/d.jpg", result?.posterUrl)
        assertTrue(cinemeta.requests.isEmpty())
        assertNull(result?.imdbId)
        assertNull(result?.imdbRating)
        assertTrue(result?.episodes?.isEmpty() == true)
        assertTrue(tmdbCalls.isNotEmpty())
        assertTrue(tmdbCalls.all { it.headers["Authorization"] == "Bearer test-key" })
        val entry = disk.get(movieQuery)
        assertEquals(EnrichmentProvenance.TMDB, entry?.metadata?.provenance)
        assertEquals(result, entry?.metadata)
        assertTrue(entry?.isFresh(System.currentTimeMillis()) == true)
    }

    @Test fun `cinemeta hit with a poster and no key never calls TMDB`() = runBlocking {
        val tmdbCalls = mutableListOf<HttpRequest>()
        val resolver = CinemetaResolver(
            CinemetaTransport(),
            tmdbResolver = TmdbResolver(tmdbTransport(tmdbCalls)) { null },
        )
        val result = resolver.resolve(movieQuery)
        assertEquals("Example Film", result?.title)
        assertTrue(tmdbCalls.isEmpty())
    }

    @Test fun `posterless Cinemeta hit fills only the poster by IMDb id`() = runBlocking {
        val http = CinemetaTransport().apply {
            meta = """{"meta":{"id":"tt0000001","name":"Example Film","type":"movie","year":"2026",
                "description":"Cinemeta synopsis","cast":["Cinemeta Actor"],"imdbRating":"7.4"}}"""
        }
        val withoutKeyCalls = mutableListOf<HttpRequest>()
        val original = CinemetaResolver(http, tmdbResolver = TmdbResolver(tmdbTransport(withoutKeyCalls)) { null })
            .resolve(movieQuery)
        assertEquals("Cinemeta synopsis", original?.overview)
        assertNull(original?.posterUrl)
        assertTrue(withoutKeyCalls.isEmpty())

        val calls = mutableListOf<HttpRequest>()
        val result = CinemetaResolver(http, tmdbResolver = TmdbResolver(tmdbTransport(calls)) { "test-key" })
            .resolveById("tt0000001", "movie")
        assertEquals(original?.copy(
            posterUrl = "https://images.example.invalid/t/p/w342/find.jpg",
            provenance = EnrichmentProvenance.CINEMETA_TMDB_POSTER,
        ), result)
        assertEquals(
            listOf("find/tt0000001?external_source=imdb_id", "configuration"),
            calls.map { it.url.substringAfter("/3/") },
        )
    }

    @Test fun `fresh posterless disk entry revalidates and a filled entry skips network`() = runBlocking {
        val disk = MetadataDiskCache(folder.root)
        val incomplete = cachedMovie.copy(imdbId = "tt0000001", posterUrl = null, overview = "Cached synopsis")
        disk.put(movieQuery, incomplete, tmdbFirst = true)
        val calls = mutableListOf<HttpRequest>()
        val result = CinemetaResolver(
            unexpectedNetwork, diskCache = disk, tmdbResolver = TmdbResolver(tmdbTransport(calls)) { "test-key" },
        ).resolve(movieQuery)
        assertEquals(incomplete.copy(posterUrl = "https://images.example.invalid/t/p/w342/find.jpg",
            provenance = EnrichmentProvenance.CINEMETA_TMDB_POSTER), result)
        assertTrue(calls.any { "/3/find/tt0000001" in it.url })
        assertEquals(EnrichmentProvenance.CINEMETA_TMDB_POSTER, disk.get(movieQuery)?.metadata?.provenance)

        assertEquals(result, CinemetaResolver(
            unexpectedNetwork, diskCache = disk, tmdbResolver = TmdbResolver(unexpectedNetwork) { "test-key" },
        ).resolve(movieQuery))
    }

    @Test fun `posterless disk entry without an IMDb id uses title search`() = runBlocking {
        val disk = MetadataDiskCache(folder.root)
        val incomplete = cachedMovie.copy(posterUrl = null)
        disk.put(movieQuery, incomplete, tmdbFirst = true)
        val calls = mutableListOf<HttpRequest>()
        val result = CinemetaResolver(
            unexpectedNetwork, diskCache = disk, tmdbResolver = TmdbResolver(tmdbTransport(calls)) { "test-key" },
        ).resolve(movieQuery)
        assertEquals(incomplete.copy(posterUrl = "https://images.example.invalid/t/p/w342/s.jpg",
            provenance = EnrichmentProvenance.CINEMETA_TMDB_POSTER), result)
        assertTrue(calls.any { "/3/search/movie?query=Example%20Film" in it.url })
        assertTrue(calls.none { "/3/find/" in it.url })
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
    @Test fun `TMDB runs first and publishes its poster before by ID Cinemeta backfill`() = runBlocking {
        val order = mutableListOf<String>()
        val backfill = CompletableDeferred<Unit>()
        val tmdb = tmdbTransport(mutableListOf())
        val cinemeta = CinemetaTransport().apply {
            meta = """{"meta":{"id":"tt0000001","title":"Example Film","name":"Example Film","type":"movie",
                "imdbRating":"8.4","description":"Cinemeta text"}}"""
        }
        val primary = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                order += request.url.substringAfter("/3/")
                val response = tmdb.execute(request)
                return if (request.url.endsWith("?append_to_response=external_ids")) {
                    response.copy(body = response.body.dropLast(1) + ""","external_ids":{"imdb_id":"tt0000001"}}""")
                } else response
            }
        }
        val secondary = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                order += request.url.substringAfter("strem.io/")
                backfill.await()
                return cinemeta.execute(request)
            }
        }
        val resolver = CinemetaResolver(secondary, tmdbResolver = TmdbResolver(primary) { "key" })
        val poster = CompletableDeferred<String>()
        val result = async { resolver.resolveOutcome(listOf(movieQuery)) { poster.complete(it) } }
        assertEquals("https://images.example.invalid/t/p/w342/d.jpg", withTimeout(1000) { poster.await() })
        assertFalse(result.isCompleted)
        backfill.complete(Unit)
        val enriched = result.await()
        assertEquals("search/movie?query=Example%20Film", order.first())
        assertEquals("meta/movie/tt0000001.json", order.last())
        assertEquals("Fallback hit.", enriched.metadata?.overview)
        assertEquals(8.4, enriched.metadata?.imdbRating)
        assertEquals("tt0000001", enriched.metadata?.imdbId)
        assertEquals(EnrichmentProvenance.TMDB_CINEMETA_BACKFILL, enriched.provenance)
    }

    @Test fun `confirmed series keeps Cinemeta episodes without using TMDB votes as IMDb rating`() = runBlocking {
        val tmdb = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = HttpResponse(200, when {
                "/3/search/tv" in request.url ->
                    """{"results":[{"id":44,"name":"Example Show","first_air_date":"2026-01-01"}]}"""
                request.url.endsWith("external_ids") ->
                    """{"id":44,"name":"Example Show","vote_average":9.4,"external_ids":{"imdb_id":"tt0000001"}}"""
                request.url.endsWith("/credits") -> "{}"
                request.url.endsWith("/configuration") -> """{"images":{}}"""
                else -> error("Unexpected TMDB request")
            })
        }
        val cinemeta = CinemetaTransport().apply {
            meta = """{"meta":{"type":"series","id":"tt0000001","name":"Example Show",
                "poster":"https://example.invalid/show.jpg","videos":[{"season":1,"episode":2,"name":"Second"}]}}"""
        }
        val result = CinemetaResolver(cinemeta, tmdbResolver = TmdbResolver(tmdb) { "key" })
            .resolve(MediaQuery("Example Show", 2026, "series"))
        assertEquals(listOf(MediaEpisode(1, 2, "Second", null)), result?.episodes)
        assertEquals("https://example.invalid/show.jpg", result?.posterUrl)
        assertNull(result?.imdbRating)
        assertEquals(EnrichmentProvenance.TMDB_CINEMETA_BACKFILL, result?.provenance)
    }

    @Test fun `429 retries no TMDB requests before Retry After and falls back to Cinemeta`() = runBlocking {
        var time = 10_000L
        var attempts = 0
        val tmdb = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                attempts++
                return if (attempts == 1) HttpResponse(429, "", mapOf("retry-after" to "5"))
                else HttpResponse(200, """{"results":[]}""")
            }
        }
        val cinemeta = CinemetaTransport()
        val resolver = CinemetaResolver(cinemeta, now = { time },
            tmdbResolver = TmdbResolver(tmdb, now = { time }) { "key" })
        assertEquals(EnrichmentProvenance.CINEMETA, resolver.resolveOutcome(listOf(movieQuery)).provenance)
        time += 4_999
        assertNotNull(resolver.resolve(movieQuery, bypassMiss = true))
        assertEquals(1, attempts)
        time++
        assertNotNull(resolver.resolve(movieQuery))
        assertEquals(2, attempts)
    }

    @Test fun `rate limit HTTP date blocks new queries across the resolver`() = runBlocking {
        var time = 0L
        var attempts = 0
        val tmdb = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                attempts++
                return if (attempts == 1) {
                    HttpResponse(429, "", mapOf("Retry-After" to "Thu, 1 Jan 1970 00:00:10 GMT"))
                } else HttpResponse(200, """{"results":[]}""")
            }
        }
        val cinemeta = CinemetaTransport()
        val resolver = CinemetaResolver(cinemeta, now = { time },
            tmdbResolver = TmdbResolver(tmdb, now = { time }) { "key" })
        assertNotNull(resolver.resolve(movieQuery))
        time = 9_999
        assertNull(resolver.resolve(movieQuery.copy(title = "Other Film")))
        assertEquals(1, attempts)
        time++
        assertNull(resolver.resolve(movieQuery.copy(title = "Other Film")))
        assertEquals(2, attempts)
    }

    @Test fun `key enable and clear invalidate opposite order in memory and on disk`() = runBlocking {
        var token: String? = null
        val disk = MetadataDiskCache(folder.root)
        val cinemeta = CinemetaTransport()
        val calls = mutableListOf<HttpRequest>()
        val tmdb = TmdbResolver(tmdbTransport(calls)) { token }
        val resolver = CinemetaResolver(cinemeta, diskCache = disk, revalidationScope = this, tmdbResolver = tmdb)
        assertEquals(EnrichmentProvenance.CINEMETA, resolver.resolve(movieQuery)?.provenance)
        assertTrue(calls.isEmpty())
        assertFalse(disk.get(movieQuery)!!.tmdbFirst)

        token = "key"
        cinemeta.catalog = """{"metas":[]}"""
        val enabling = resolver.resolveOutcome(listOf(movieQuery))
        assertEquals(EnrichmentProvenance.CINEMETA, enabling.provenance)
        assertEquals(EnrichmentProvenance.TMDB, enabling.revalidation!!.await()?.provenance)
        assertTrue(disk.get(movieQuery)!!.tmdbFirst)
        val requestCount = calls.size
        token = "replacement-key"
        val reopened = CinemetaResolver(unexpectedNetwork, diskCache = disk, tmdbResolver = tmdb)
        assertEquals(EnrichmentProvenance.TMDB, reopened.resolve(movieQuery)?.provenance)
        assertEquals(requestCount, calls.size)

        token = null
        cinemeta.catalog = CinemetaTransport().catalog
        val cleared = CinemetaResolver(cinemeta, diskCache = disk, revalidationScope = this, tmdbResolver = tmdb)
        val clearing = cleared.resolveOutcome(listOf(movieQuery))
        assertEquals(EnrichmentProvenance.TMDB, clearing.provenance)
        assertEquals(EnrichmentProvenance.CINEMETA, clearing.revalidation!!.await()?.provenance)
        assertEquals(EnrichmentProvenance.CINEMETA, resolver.resolve(movieQuery)?.provenance)
        assertEquals(requestCount, calls.size)
        assertFalse(disk.get(movieQuery)!!.tmdbFirst)
    }

    @Test fun `no match and lookup failure are distinct including cached outcomes`() = runBlocking {
        val miss = CinemetaResolver(CinemetaTransport().apply { catalog = """{"metas":[]}""" })
        val failed = CinemetaResolver(CinemetaTransport().apply { status = 503 })
        repeat(2) {
            assertEquals(EnrichmentProvenance.NO_MATCH, miss.resolveOutcome(listOf(movieQuery)).provenance)
            assertEquals(EnrichmentProvenance.UNAVAILABLE, failed.resolveOutcome(listOf(movieQuery)).provenance)
        }
    }

    @Test fun `candidates falls back to TMDB when Cinemeta lists nothing`() = runBlocking {
        val cinemeta = CinemetaTransport().apply { catalog = """{"metas":[]}""" }
        val calls = mutableListOf<HttpRequest>()
        val resolver = CinemetaResolver(cinemeta, tmdbResolver = TmdbResolver(tmdbTransport(calls)) { "key" })
        assertEquals(
            listOf(MediaCandidate(null, "Example Film", 2026, "movie", tmdbId = 123,
                posterUrl = "https://images.example.invalid/t/p/w342/s.jpg",
                overview = "Fallback hit.")),
            resolver.candidates(movieQuery),
        )
        assertTrue(calls.any { "/3/search/movie" in it.url })
    }

    @Test fun `automatic picker keeps the exact gate for a partial title`() = runBlocking {
        val cinemeta = CinemetaTransport().apply { catalog = """{"metas":[]}""" }
        val calls = mutableListOf<HttpRequest>()
        val resolver = CinemetaResolver(cinemeta, tmdbResolver = TmdbResolver(tmdbTransport(calls)) { "test-key" })
        assertTrue(resolver.candidates(MediaQuery("Example", null, "movie")).isEmpty())
        assertTrue(calls.any { "/3/search/movie" in it.url })
    }

    @Test fun `candidates stays Cinemeta-only when Cinemeta lists anything`() = runBlocking {
        val cinemeta = CinemetaTransport()
        val calls = mutableListOf<HttpRequest>()
        val resolver = CinemetaResolver(cinemeta, tmdbResolver = TmdbResolver(tmdbTransport(calls)) { "key" })
        assertEquals(
            listOf(MediaCandidate("tt0000001", "Example Film", 2026, "movie")),
            resolver.candidates(movieQuery),
        )
        assertTrue(calls.isEmpty())
    }

    @Test fun `candidates is empty without a TMDB key when Cinemeta lists nothing`() = runBlocking {
        val cinemeta = CinemetaTransport().apply { catalog = """{"metas":[]}""" }
        val resolver = CinemetaResolver(cinemeta, tmdbResolver = TmdbResolver(tmdbTransport(mutableListOf())) { null })
        assertTrue(resolver.candidates(movieQuery).isEmpty())
        assertFalse(resolver.isTmdbConfigured())
    }

    @Test fun `resolveByTmdbId backfills from Cinemeta with a verified IMDb id`() = runBlocking {
        val resolver = CinemetaResolver(
            CinemetaTransport(),
            tmdbResolver = TmdbResolver(primaryTransport(imdbId = "tt0000001")) { "key" },
        )
        val result = resolver.resolveByTmdbId(123, "movie")
        assertEquals("Example Film", result?.title)
        assertEquals("tt0000001", result?.imdbId)
        // The backfill adds only the alternate poster here, so the
        // shared merge keeps the TMDB provenance — exactly as the
        // automatic TMDB-first path does for the same fixture.
        assertEquals(EnrichmentProvenance.TMDB, result?.provenance)
        assertEquals("https://images.example.invalid/t/p/w342/d.jpg", result?.posterUrl)
        assertEquals("https://images.example.invalid/poster.jpg", result?.alternatePosterUrl)

        // When the backfill adds more than the alternate poster — an
        // IMDb rating here — the merge records the backfill provenance.
        val rated = CinemetaResolver(
            CinemetaTransport().apply {
                meta = """{"meta":{"id":"tt0000001","name":"Example Film","year":2026,"type":"movie",
                    "poster":"https://images.example.invalid/alternate.jpg","imdbRating":"8.3"}}"""
            },
            tmdbResolver = TmdbResolver(primaryTransport(imdbId = "tt0000001")) { "key" },
        )
        val enriched = rated.resolveByTmdbId(123, "movie")
        assertEquals(EnrichmentProvenance.TMDB_CINEMETA_BACKFILL, enriched?.provenance)
        assertEquals(8.3, enriched?.imdbRating)
        assertEquals("https://images.example.invalid/alternate.jpg", enriched?.alternatePosterUrl)
    }

    @Test fun `resolveByTmdbId serves TMDB as-is without a verified IMDb id`() = runBlocking {
        val resolver = CinemetaResolver(
            CinemetaTransport(),
            tmdbResolver = TmdbResolver(primaryTransport()) { "key" },
        )
        val result = resolver.resolveByTmdbId(123, "movie")
        assertNull(result?.imdbId)
        assertEquals(EnrichmentProvenance.TMDB, result?.provenance)
        assertEquals("Fallback hit.", result?.overview)
    }

    @Test fun `resolveByTmdbId is null without a key`() = runBlocking {
        val resolver = CinemetaResolver(
            CinemetaTransport(),
            tmdbResolver = TmdbResolver(primaryTransport()) { null },
        )
        assertNull(resolver.resolveByTmdbId(123, "movie"))
    }

}
