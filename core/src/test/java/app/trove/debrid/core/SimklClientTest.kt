package app.trove.debrid.core

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SIMKL Auth V2 client, verified against fake transports only —
 * no live SIMKL app exists yet, so no test here ever touches the
 * real API. The client id under test is a fixed test value; the
 * production placeholder ("SIMKL_CLIENT_ID_NOT_REGISTERED") is
 * covered by [isConfigured].
 */
class SimklClientTest {
    private val nowMs = 1_700_000_000_000L

    private class FakeSimkl(var handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun tokens(expiresInMs: Long = 7 * 24 * 60 * 60 * 1000L) =
        SimklTokens("simkl_at_1", "simkl_rt_1", nowMs + expiresInMs, nowMs + 180 * 24 * 60 * 60 * 1000L)

    private fun client(transport: HttpTransport, stored: () -> SimklTokens?, onTokens: (SimklTokens) -> Unit) =
        SimklClient(transport, clientId = "test-client", appVersion = "test", now = { nowMs },
            tokens = stored, onTokens = onTokens)

    private val deviceJson = """
        {"device_code":"dc-1","user_code":"ABCD-EFGH",
        "verification_uri":"https://simkl.com/pin",
        "verification_uri_complete":"https://simkl.com/pin/ABCD-EFGH",
        "expires_in":900,"interval":5}
    """.trimIndent()

    private val activitiesJson = """
        {"all":"2026-11-21T00:00:00Z","settings":{"all":"2026-11-21T00:00:00Z"},
        "tv_shows":{"all":"2026-11-21T00:00:00Z","watching":"2026-11-21T00:00:00Z",
        "plantowatch":"2026-11-20T00:00:00Z","completed":"2026-11-20T00:00:00Z",
        "hold":"2026-11-20T00:00:00Z","dropped":"2026-11-20T00:00:00Z",
        "removed_from_list":"2026-11-20T00:00:00Z"},
        "movies":{"all":"2026-11-21T00:00:00Z","plantowatch":"2026-11-20T00:00:00Z",
        "completed":"2026-11-20T00:00:00Z","dropped":"2026-11-20T00:00:00Z",
        "removed_from_list":"2026-11-20T00:00:00Z"},
        "anime":{"all":"2026-11-21T00:00:00Z","watching":"2026-11-20T00:00:00Z",
        "plantowatch":"2026-11-20T00:00:00Z","completed":"2026-11-20T00:00:00Z",
        "hold":"2026-11-20T00:00:00Z","dropped":"2026-11-20T00:00:00Z",
        "removed_from_list":"2026-11-20T00:00:00Z"}}
    """.trimIndent()

    private val showItemsJson = """
        {"shows":[{"status":"watching","last_watched":"S01E04","next_to_watch":"S01E05",
        "watched_episodes_count":4,"total_episodes_count":20,
        "next_to_watch_info":{"title":"Chapter Five","season":1,"episode":5},
        "show":{"title":"Example Show","year":2026,
        "ids":{"simkl":123,"slug":"example-show","imdb":"tt1234567","tmdb":"456"}}}]}
    """.trimIndent()

    private val movieItemsJson = """
        {"movies":[{"status":"completed","user_rating":8,
        "movie":{"title":"Example Film","year":2026,
        "ids":{"simkl":789,"slug":"example-film","imdb":"tt7654321","tmdb":"987"}}}]}
    """.trimIndent()

    @Test fun `placeholder client id is not configured`() {
        val client = SimklClient(FakeSimkl { HttpResponse(404, "{}") },
            appVersion = "test", tokens = { null }, onTokens = {})
        assertFalse(client.isConfigured)
        assertNull(client.currentTokens())
    }

    @Test fun `device flow returns the code and polls through every state`() = runBlocking {
        var stored: SimklTokens? = null
        val tokenResponses = ArrayDeque(
            listOf(
                HttpResponse(400, """{"error":"authorization_pending"}"""),
                HttpResponse(400, """{"error":"slow_down"}"""),
                HttpResponse(200, """{"access_token":"simkl_at_2","refresh_token":"simkl_rt_2","expires_in":604800,"token_type":"Bearer"}"""),
            ),
        )
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/oauth2/device") -> HttpResponse(200, deviceJson)
                request.url.contains("/oauth2/token") -> tokenResponses.removeFirst()
                else -> HttpResponse(404, "{}")
            }
        }
        val testClient = client(transport, { stored }, { stored = it })

        val code = testClient.startDeviceFlow()
        assertEquals("ABCD-EFGH", code.userCode)
        assertEquals("https://simkl.com/pin/ABCD-EFGH", code.verificationUriComplete)
        assertEquals(900, code.expiresInSecs)
        assertEquals(5, code.intervalSecs)
        assertEquals(SimklPollResult.Pending, testClient.pollDeviceCode(code))
        assertEquals(SimklPollResult.SlowDown(5), testClient.pollDeviceCode(code))
        assertEquals(SimklPollResult.Authorized, testClient.pollDeviceCode(code))
        assertEquals("simkl_at_2", stored?.accessToken)
    }

    @Test fun `expired device code maps to Expired`() = runBlocking {
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/oauth2/device") -> HttpResponse(200, deviceJson)
                request.url.contains("/oauth2/token") -> HttpResponse(400, """{"error":"expired_token"}""")
                else -> HttpResponse(404, "{}")
            }
        }
        val testClient = client(transport, { null }, {})
        val code = testClient.startDeviceFlow()
        assertEquals(SimklPollResult.Expired, testClient.pollDeviceCode(code))
    }

    @Test fun `invalid client maps to InvalidClient`() = runBlocking {
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/oauth2/device") -> HttpResponse(200, deviceJson)
                request.url.contains("/oauth2/token") -> HttpResponse(401, """{"error":"invalid_client"}""")
                else -> HttpResponse(404, "{}")
            }
        }
        val testClient = client(transport, { null }, {})
        val code = testClient.startDeviceFlow()
        assertEquals(SimklPollResult.InvalidClient, testClient.pollDeviceCode(code))
    }

    @Test fun `activities and all-items parse entries with ids and progress`() = runBlocking {
        var stored: SimklTokens? = tokens()
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/sync/activities") -> HttpResponse(200, activitiesJson)
                "/sync/all-items/shows/watching" in request.url -> HttpResponse(200, showItemsJson)
                "/sync/all-items/movies/completed" in request.url -> HttpResponse(200, movieItemsJson)
                else -> HttpResponse(404, "{}")
            }
        }
        val testClient = client(transport, { stored }, { stored = it })

        val activities = testClient.activities()
        assertEquals("2026-11-21T00:00:00Z", activities.all)
        assertEquals("2026-11-21T00:00:00Z", activities.buckets["tv_shows.watching"])

        val shows = testClient.allItems(SimklType.SHOW, SimklStatus.WATCHING)
        assertEquals(1, shows.size)
        val show = shows.single()
        assertEquals(123, show.simklId)
        assertEquals("Example Show", show.title)
        assertEquals("tt1234567", show.imdbId)
        assertEquals(456, show.tmdbId)
        assertEquals("https://simkl.com/tv/123/example-show", show.simklUrl)
        assertEquals(4, show.watchedEpisodes)
        assertEquals(20, show.totalEpisodes)
        assertEquals(1, show.nextSeason)
        assertEquals(5, show.nextEpisode)
        assertEquals("Chapter Five", show.nextTitle)

        val movies = testClient.allItems(SimklType.MOVIE, SimklStatus.COMPLETED)
        val movie = movies.single()
        assertEquals(789, movie.simklId)
        assertEquals("https://simkl.com/movies/789/example-film", movie.simklUrl)
        assertNull(movie.nextSeason)
    }

    @Test fun `429 with Retry-After throws SimklRateLimited`() = runBlocking {
        var stored: SimklTokens? = tokens()
        val transport = FakeSimkl { request ->
            if (request.url.contains("/sync/activities")) {
                HttpResponse(429, "{}", mapOf("Retry-After" to "120"))
            } else HttpResponse(404, "{}")
        }
        val testClient = client(transport, { stored }, { stored = it })
        try {
            testClient.activities()
            fail("expected SimklRateLimited")
        } catch (e: SimklRateLimited) {
            assertEquals(120_000L, e.retryAfterMs)
        }
    }

    @Test fun `expiring access token is refreshed before the call`() = runBlocking {
        var stored: SimklTokens? = tokens(expiresInMs = 60_000L) // inside the 24h window
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/oauth2/token") ->
                    HttpResponse(200, """{"access_token":"simkl_at_2","refresh_token":"simkl_rt_2","expires_in":604800,"token_type":"Bearer"}""")
                request.url.contains("/sync/activities") -> HttpResponse(200, activitiesJson)
                else -> HttpResponse(404, "{}")
            }
        }
        val testClient = client(transport, { stored }, { stored = it })
        val activities = testClient.activities()
        assertEquals("2026-11-21T00:00:00Z", activities.all)
        assertEquals("simkl_at_2", stored?.accessToken)
        assertEquals(1, transport.requests.count { it.url.contains("/oauth2/token") })
        assertTrue(transport.requests.last().headers["Authorization"] == "Bearer simkl_at_2")
    }

    @Test fun `concurrent calls share one refresh`() = runBlocking {
        var stored: SimklTokens? = tokens(expiresInMs = 60_000L) // inside the 24h window
        var refreshCalls = 0
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/oauth2/token") -> {
                    refreshCalls++
                    HttpResponse(200, """{"access_token":"simkl_at_2","refresh_token":"simkl_rt_2","expires_in":604800,"token_type":"Bearer"}""")
                }
                request.url.contains("/sync/activities") -> HttpResponse(200, activitiesJson)
                else -> HttpResponse(404, "{}")
            }
        }
        val testClient = client(transport, { stored }, { stored = it })
        val first = async { testClient.activities() }
        val second = async { testClient.activities() }
        assertEquals("2026-11-21T00:00:00Z", first.await().all)
        assertEquals("2026-11-21T00:00:00Z", second.await().all)
        assertEquals(1, refreshCalls)
        assertEquals("simkl_at_2", stored?.accessToken)
    }

    @Test fun `401 refreshes once and retries, second 401 signs out`() = runBlocking {
        var stored: SimklTokens? = tokens()
        var activitiesCalls = 0
        val transport = FakeSimkl { request ->
            when {
                request.url.contains("/oauth2/token") ->
                    HttpResponse(200, """{"access_token":"simkl_at_2","refresh_token":"simkl_rt_2","expires_in":604800,"token_type":"Bearer"}""")
                request.url.contains("/sync/activities") -> {
                    activitiesCalls++
                    if (activitiesCalls == 1) HttpResponse(401, "{}") else HttpResponse(200, activitiesJson)
                }
                else -> HttpResponse(404, "{}")
            }
        }
        val testClient = client(transport, { stored }, { stored = it })
        assertEquals("2026-11-21T00:00:00Z", testClient.activities().all)
        assertEquals(1, transport.requests.count { it.url.contains("/oauth2/token") })

        // Second 401 after a refresh: the grant is gone.
        var stored2: SimklTokens? = tokens()
        val transport2 = FakeSimkl { request ->
            when {
                request.url.contains("/oauth2/token") ->
                    HttpResponse(200, """{"access_token":"simkl_at_3","refresh_token":"simkl_rt_3","expires_in":604800,"token_type":"Bearer"}""")
                request.url.contains("/sync/activities") -> HttpResponse(401, "{}")
                else -> HttpResponse(404, "{}")
            }
        }
        val testClient2 = client(transport2, { stored2 }, { stored2 = it })
        try {
            testClient2.activities()
            fail("expected SimklAuthException")
        } catch (_: SimklAuthException) {
            // Expected.
        }
        assertEquals(1, transport2.requests.count { it.url.contains("/oauth2/token") })
    }

    @Test fun `tokens round-trip through encode and decode`() {
        val original = SimklTokens("simkl_at_x", "simkl_rt_y", 1_700_000_000_000L, 1_800_000_000_000L)
        assertEquals(original, SimklTokens.decode(original.encode()))
        assertNull(SimklTokens.decode("{broken"))
        assertNull(SimklTokens.decode("""{"access_token":"only"}"""))
    }

    @Test fun `revoke is best-effort and never throws on transport errors`() = runBlocking {
        var stored: SimklTokens? = tokens()
        val transport = FakeSimkl { HttpResponse(500, "{}") }
        val testClient = client(transport, { stored }, { stored = it })
        testClient.revoke()
    }
}
