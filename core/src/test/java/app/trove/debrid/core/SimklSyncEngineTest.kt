package app.trove.debrid.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Activities-gated sync, verified against a fake transport only.
 * The engine never polls on a timer; every test drives [sync]
 * directly, the way the UI does on sheet open / pull-to-refresh /
 * Settings.
 */
class SimklSyncEngineTest {
    @get:Rule val temporary = TemporaryFolder()

    private val nowMs = 1_700_000_000_000L

    private class FakeSimkl(var handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun activitiesJson(all: String, watching: String, removed: String) = """
        {"all":"$all","settings":{"all":"$all"},
        "tv_shows":{"all":"$all","watching":"$watching",
        "plantowatch":"2026-11-20T00:00:00Z","completed":"2026-11-20T00:00:00Z",
        "hold":"2026-11-20T00:00:00Z","dropped":"2026-11-20T00:00:00Z",
        "removed_from_list":"$removed"},
        "movies":{"all":"$all","plantowatch":"2026-11-20T00:00:00Z",
        "completed":"2026-11-20T00:00:00Z","dropped":"2026-11-20T00:00:00Z",
        "removed_from_list":"2026-11-20T00:00:00Z"},
        "anime":{"all":"$all","watching":"2026-11-20T00:00:00Z",
        "plantowatch":"2026-11-20T00:00:00Z","completed":"2026-11-20T00:00:00Z",
        "hold":"2026-11-20T00:00:00Z","dropped":"2026-11-20T00:00:00Z",
        "removed_from_list":"2026-11-20T00:00:00Z"}}
    """.trimIndent()

    private val oldActivities = activitiesJson(
        "2026-11-20T00:00:00Z", "2026-11-20T00:00:00Z", "2026-11-20T00:00:00Z")
    private val movedActivities = activitiesJson(
        "2026-11-21T00:00:00Z", "2026-11-21T00:00:00Z", "2026-11-20T00:00:00Z")

    private val showItemsJson = """
        {"shows":[{"status":"watching","last_watched":"S01E04","next_to_watch":"S01E05",
        "watched_episodes_count":4,"total_episodes_count":20,
        "show":{"title":"Example Show","year":2026,
        "ids":{"simkl":123,"slug":"example-show","imdb":"tt1234567","tmdb":"456"}}}]}
    """.trimIndent()

    private val emptyItems = """{"shows":[],"movies":[],"anime":[]}"""

    private fun seedEntry(simklId: Int = 123, imdbId: String? = "tt1234567") = SimklEntry(
        simklId = simklId, type = SimklType.SHOW, status = SimklStatus.WATCHING,
        title = "Example Show", imdbId = imdbId, tmdbId = 456, slug = "example-show",
        watchedEpisodes = 4, totalEpisodes = 20, nextSeason = 1, nextEpisode = 5, nextTitle = null,
    )

    private fun seedSnapshot(entries: List<SimklEntry> = listOf(seedEntry())): SimklSnapshot {
        val activities = SimklActivities.parse(
            com.google.gson.JsonParser.parseString(oldActivities).asJsonObject)
        return SimklSnapshot(
            activitiesJson = oldActivities, activities = activities, username = "tester",
            entries = entries, lastCheckedMs = nowMs, quotaBlockedUntilMs = 0L, lastError = null,
        )
    }

    private fun engine(
        transport: HttpTransport,
        stored: SimklTokens?,
        cache: SimklDiskCache?,
        clientId: String = "test-client",
        /** Defaults past the 30-minute cooldown, so the existing
         *  sync tests exercise the network path; cooldown tests
         *  pass an explicit clock. */
        now: Long = nowMs + 31 * 60 * 1000L,
    ): SimklSyncEngine {
        var held = stored
        val client = SimklClient(transport, clientId = clientId, appVersion = "test",
            now = { nowMs }, tokens = { held }, onTokens = { held = it })
        return SimklSyncEngine(client, cache, now = { now })
    }

    private fun validTokens() = SimklTokens(
        "simkl_at_1", "simkl_rt_1", nowMs + 7 * 24 * 60 * 60 * 1000L, nowMs + 180 * 24 * 60 * 60 * 1000L)

    @Test fun `not signed in when no tokens are stored`() = runBlocking {
        val transport = FakeSimkl { HttpResponse(200, oldActivities) }
        val testEngine = engine(transport, null, null)
        assertEquals(SimklSyncResult.NotSignedIn, testEngine.sync())
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `not configured while the client id is the placeholder`() = runBlocking {
        val transport = FakeSimkl { HttpResponse(200, oldActivities) }
        val testEngine = engine(transport, validTokens(), null, clientId = SIMKL_CLIENT_ID)
        assertEquals(SimklSyncResult.NotConfigured, testEngine.sync())
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `unchanged watermark makes no all-items call`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot())
        val transport = FakeSimkl { HttpResponse(200, oldActivities) }
        val testEngine = engine(transport, validTokens(), cache)
        assertEquals(SimklSyncResult.Unchanged, testEngine.sync())
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.contains("/sync/activities"))
        assertEquals("tt1234567", testEngine.entryFor("tt1234567", null)?.imdbId)
    }

    @Test fun `moved bucket fetches only that bucket with date_from`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot())
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/sync/activities") -> HttpResponse(200, movedActivities)
                "/sync/all-items/shows/watching" in request.url -> HttpResponse(200, showItemsJson)
                else -> HttpResponse(404, "{}")
            }
        }
        val testEngine = engine(transport, validTokens(), cache)
        val result = testEngine.sync()
        assertEquals(SimklSyncResult.Changed(1), result)
        val itemCalls = transport.requests.filter { "/sync/all-items/" in it.url }
        assertEquals(1, itemCalls.size)
        assertTrue(itemCalls.single().url.contains("date_from=2026-11-20T00%3A00%3A00Z"))
        assertEquals(123, testEngine.entryFor("tt1234567", null)?.simklId)
        assertEquals(456, testEngine.entryFor(null, 456)?.tmdbId)
    }

    @Test fun `bootstrap fetches every bucket with no date_from`() = runBlocking {
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/sync/activities") -> HttpResponse(200, movedActivities)
                "/sync/all-items/" in request.url -> HttpResponse(200, emptyItems)
                request.url.contains("/users/settings") ->
                    HttpResponse(200, """{"user":{"name":"tester"},"account":{"type":"free"}}""")
                else -> HttpResponse(404, "{}")
            }
        }
        val testEngine = engine(transport, validTokens(), null)
        val result = testEngine.sync()
        assertEquals(SimklSyncResult.Changed(0), result)
        val itemCalls = transport.requests.filter { "/sync/all-items/" in it.url }
        // 5 show + 3 movie + 5 anime buckets.
        assertEquals(13, itemCalls.size)
        assertTrue(itemCalls.none { "date_from" in it.url })
    }

    @Test fun `removed_from_list reconciles deletions by ids only`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot(listOf(seedEntry(123), seedEntry(999, "tt9999999"))))
        val removedActivities = activitiesJson(
            "2026-11-21T00:00:00Z", "2026-11-20T00:00:00Z", "2026-11-21T00:00:00Z")
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/sync/activities") -> HttpResponse(200, removedActivities)
                "/sync/all-items" in request.url && "simkl_ids_only" in request.url ->
                    HttpResponse(200, """{"shows":[{"ids":{"simkl":123}}],"movies":[],"anime":[]}""")
                "/sync/all-items/" in request.url -> HttpResponse(200, emptyItems)
                else -> HttpResponse(404, "{}")
            }
        }
        val testEngine = engine(transport, validTokens(), cache)
        assertEquals(SimklSyncResult.Changed(1), testEngine.sync())
        assertEquals(123, testEngine.entryFor("tt1234567", null)?.simklId)
        assertNull(testEngine.entryFor("tt9999999", null))
    }

    @Test fun `failed sync keeps the last snapshot`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot())
        val transport = FakeSimkl { HttpResponse(500, "{}") }
        val testEngine = engine(transport, validTokens(), cache)
        assertEquals(SimklSyncResult.Failed(SimklSyncFailure.API), testEngine.sync())
        assertEquals(123, testEngine.entryFor("tt1234567", null)?.simklId)
        assertEquals(SimklSyncFailure.API.name, testEngine.currentSnapshot()?.lastError)
    }

    @Test fun `quota block stops further syncs until the reset`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot())
        val transport = FakeSimkl { request ->
            if (request.url.contains("/sync/activities")) {
                HttpResponse(429, "{}", mapOf("Retry-After" to "60"))
            } else HttpResponse(404, "{}")
        }
        val testEngine = engine(transport, validTokens(), cache)
        assertEquals(SimklSyncResult.Failed(SimklSyncFailure.QUOTA), testEngine.sync())
        val callsAfterFirst = transport.requests.size
        assertEquals(SimklSyncResult.Failed(SimklSyncFailure.QUOTA), testEngine.sync())
        assertEquals(callsAfterFirst, transport.requests.size)
        assertEquals(123, testEngine.entryFor("tt1234567", null)?.simklId)
    }

    @Test fun `sign-out clears the snapshot`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot())
        val transport = FakeSimkl { HttpResponse(200, oldActivities) }
        val testEngine = engine(transport, validTokens(), cache)
        testEngine.loadSnapshot()
        assertEquals(123, testEngine.entryFor("tt1234567", null)?.simklId)
        testEngine.clear()
        assertNull(testEngine.entryFor("tt1234567", null))
        assertNull(cache.load())
    }

    @Test fun `cooldown skips the network when the last check is recent`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot())
        val transport = FakeSimkl { HttpResponse(200, oldActivities) }
        val testEngine = engine(transport, validTokens(), cache, now = nowMs)
        assertEquals(SimklSyncResult.CooledDown, testEngine.sync())
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `badges are still served from the cached snapshot while cooled down`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot())
        val transport = FakeSimkl { HttpResponse(200, oldActivities) }
        val testEngine = engine(transport, validTokens(), cache, now = nowMs)
        assertEquals(SimklSyncResult.CooledDown, testEngine.sync())
        assertEquals(123, testEngine.entryFor("tt1234567", null)?.simklId)
        assertEquals(456, testEngine.entryFor(null, 456)?.tmdbId)
    }

    @Test fun `cooldown allows a sync at exactly 30 minutes`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot())
        val transport = FakeSimkl { HttpResponse(200, oldActivities) }
        val testEngine = engine(transport, validTokens(), cache, now = nowMs + 30 * 60 * 1000L)
        assertEquals(SimklSyncResult.Unchanged, testEngine.sync())
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.contains("/sync/activities"))
    }

    @Test fun `force bypasses the cooldown`() = runBlocking {
        val cache = SimklDiskCache(temporary.newFolder())
        cache.save(seedSnapshot())
        val transport = FakeSimkl { HttpResponse(200, oldActivities) }
        val testEngine = engine(transport, validTokens(), cache, now = nowMs)
        assertEquals(SimklSyncResult.Unchanged, testEngine.sync(force = true))
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.contains("/sync/activities"))
    }

    @Test fun `first sync runs with no snapshot even inside the cooldown window`() = runBlocking {
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/sync/activities") -> HttpResponse(200, movedActivities)
                "/sync/all-items/" in request.url -> HttpResponse(200, emptyItems)
                request.url.contains("/users/settings") ->
                    HttpResponse(200, """{"user":{"name":"tester"},"account":{"type":"free"}}""")
                else -> HttpResponse(404, "{}")
            }
        }
        val testEngine = engine(transport, validTokens(), null, now = nowMs)
        assertEquals(SimklSyncResult.Changed(0), testEngine.sync())
        assertTrue(transport.requests.any { "/sync/activities" in it.url })
    }
}
