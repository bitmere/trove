package app.trove.debrid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RealDebridProviderTest {
    private class FakeTransport(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun provider(handler: (HttpRequest) -> HttpResponse): Pair<RealDebridProvider, FakeTransport> {
        val fake = FakeTransport(handler)
        return RealDebridProvider(
            TOKEN, fake,
            generalBucket = TokenBucket(4.0, 50),
            addBudget = TokenBucket(0.5, 10),
            pollDelayMs = 0,
        ) to fake
    }

    private val HttpRequest.path get() = url.removePrefix(RealDebridProvider.BASE)

    @Test
    fun `validates premium and free accounts`() = runBlocking {
        val (provider, fake) = provider {
            assertEquals("GET" to "/user", it.method to it.path)
            if (it.headers["Authorization"] != "Bearer $TOKEN") error("Missing authorization")
            HttpResponse(200, """{"username":"alice","premium":600,"expiration":"2026-12-01T00:00:00Z"}""")
        }
        assertEquals(AccountInfo("alice", true, "Premium until 2026-12-01T00:00:00Z"),
            provider.validateAccount().getOrThrow())
        assertFalse(fake.requests.single().url.contains(TOKEN))
        val (free, _) = provider { HttpResponse(200, """{"type":"free","premium":"bad"}""") }
        assertEquals(AccountInfo(null, false, "Free"), free.validateAccount().getOrThrow())
    }

    @Test
    fun `magnet add selects all then polls until downloading`() = runBlocking {
        var polls = 0
        val (provider, fake) = provider {
            when (it.path) {
                "/torrents/addMagnet" -> HttpResponse(201, """{"id":"T1"}""")
                "/torrents/selectFiles/T1" -> HttpResponse(204, "")
                "/torrents/info/T1" -> torrent(if (++polls == 1) "waiting_files_selection" else "downloading")
                else -> error("Unexpected request")
            }
        }
        val magnet = "magnet:?xt=urn:btih:abc"
        val added = provider.add(AddInput.Magnet(magnet)).getOrThrow()
        assertEquals(DownloadStatus.DOWNLOADING, added.status)
        assertEquals(listOf("POST", "POST", "GET", "GET"), fake.requests.map { it.method })
        assertEquals(mapOf("magnet" to magnet), fake.requests[0].formFields)
        assertEquals(mapOf("files" to "all"), fake.requests[1].formFields)
    }

    @Test
    fun `torrent upload uses multipart then selects all`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                "/torrents/addTorrent" -> HttpResponse(201, """{"id":"T1"}""")
                "/torrents/selectFiles/T1" -> HttpResponse(204, "")
                "/torrents/info/T1" -> torrent("downloaded")
                else -> error("Unexpected request")
            }
        }
        val bytes = byteArrayOf(1, 2, 3)
        assertEquals(DownloadStatus.READY, provider.add(AddInput.TorrentFile(bytes, "video.torrent")).getOrThrow().status)
        val upload = fake.requests.first()
        assertEquals("PUT", upload.method)
        val part = upload.multipart.single()
        assertEquals("file", part.name)
        assertEquals("video.torrent", part.fileName)
        assertArrayEquals(bytes, part.bytes)
        assertEquals(mapOf("files" to "all"), fake.requests[1].formFields)
    }

    @Test
    fun `selection failure deletes the new torrent and preserves the original failure`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                "/torrents/addMagnet" -> HttpResponse(201, """{"id":"T1"}""")
                "/torrents/selectFiles/T1" -> HttpResponse(400, """{"error":"bad files","error_code":2}""")
                "/torrents/delete/T1" -> HttpResponse(503, "")
                else -> error("Unexpected request")
            }
        }
        val failure = provider.add(AddInput.Magnet("magnet:?xt=urn:btih:abc")).exceptionOrNull() as DebridError.Provider
        assertEquals("2", failure.code)
        assertEquals("DELETE" to "/torrents/delete/T1", fake.requests.last().let { it.method to it.path })
    }

    @Test
    fun `polling is bounded when the torrent stays queued`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                "/torrents/addMagnet" -> HttpResponse(201, """{"id":"T1"}""")
                "/torrents/selectFiles/T1" -> HttpResponse(204, "")
                "/torrents/info/T1" -> torrent("queued")
                else -> error("Unexpected request")
            }
        }
        assertEquals(DownloadStatus.QUEUED, provider.add(AddInput.Magnet("magnet:?xt=urn:btih:abc")).getOrThrow().status)
        assertEquals(10, fake.requests.count { it.path == "/torrents/info/T1" })
    }

    @Test
    fun `hoster add returns a ready file without storing the direct URL`() = runBlocking {
        val (provider, fake) = provider {
            when (it.method to it.path) {
                "POST" to "/unrestrict/link" -> HttpResponse(200,
                    """{"id":"W1","filename":"video.mp4","filesize":123,"download":"https://cdn.example/private"}""")
                "GET" to "/downloads?page=1&limit=100" -> HttpResponse(200,
                    """[{"id":"W1","filename":"video.mp4","filesize":123}]""")
                else -> error("Unexpected request")
            }
        }
        val url = "https://host.example/video"
        val download = provider.add(AddInput.HosterLink(url)).getOrThrow()
        assertEquals(mapOf("link" to url), fake.requests.single().formFields)
        assertEquals(DownloadKind.WEB, download.kind)
        assertEquals(DownloadStatus.READY, download.status)
        assertEquals("web:W1", download.id)
        assertEquals(DebridFile("W1", "video.mp4", 123), download.files.single())
        assertFalse(download.toString().contains("https://"))
        assertEquals(download, provider.getDownload(download.id).getOrThrow())
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun `web lookup continues past a full page and returns not cached for an absent row`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                "/downloads?page=1&limit=100" -> HttpResponse(200,
                    (1..100).joinToString(",", "[", "]") { id -> """{"id":"W$id"}""" })
                "/downloads?page=2&limit=100" -> HttpResponse(200, """[{"id":"W101"}]""")
                else -> error("Unexpected request")
            }
        }

        assertEquals("web:W101", provider.getDownload("web:W101").getOrThrow().id)
        assertTrue(provider.getDownload("web:missing").exceptionOrNull() is DebridError.NotCached)
        assertEquals(4, fake.requests.size)
    }

    @Test
    fun `web playback unrestricts the original link from downloads`() = runBlocking {
        val original = "https://host.example/video"
        val fresh = "https://cdn.example/fresh"
        val (provider, fake) = provider {
            when (it.method to it.path) {
                "GET" to "/downloads?page=1&limit=100" -> HttpResponse(200,
                    """[{"id":"W1","link":"$original","download":"https://cdn.example/old"}]""")
                "POST" to "/unrestrict/link" -> HttpResponse(200, """{"download":"$fresh"}""")
                else -> error("Unexpected request")
            }
        }

        assertEquals(StreamUrl(fresh), provider.getStreamUrl("web:W1", "W1").getOrThrow())
        assertEquals(mapOf("link" to original), fake.requests.last().formFields)
    }

    @Test
    fun `web playback validates the generated link when the original is missing`() = runBlocking {
        val valid = "https://cdn.example/video"
        for (url in listOf(valid, "ftp://cdn.example/video", "https://user@cdn.example/video",
            "https://cdn.example/$TOKEN")) {
            val (provider, fake) = provider {
                assertEquals("GET" to "/downloads?page=1&limit=100", it.method to it.path)
                HttpResponse(200, """[{"id":"W1","download":"$url"}]""")
            }
            val result = provider.getStreamUrl("web:W1", "W1")
            if (url == valid) assertEquals(StreamUrl(url), result.getOrThrow())
            else assertTrue(result.exceptionOrNull() is DebridError.Provider)
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun `synthetic web ids are not cached and make no requests`() = runBlocking {
        val (provider, fake) = provider { error("Unexpected request") }
        val id = "web:local:W1"
        assertTrue(provider.getDownload(id).exceptionOrNull() is DebridError.NotCached)
        assertTrue(provider.getStreamUrl(id, id).exceptionOrNull() is DebridError.NotCached)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `list paginates and sorts torrents with hoster history`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                "/torrents?page=1&limit=100" -> HttpResponse(200, (1..100).joinToString(",", "[", "]") { id ->
                    """{"id":"T$id","added":"2026-11-01T00:00:00Z","progress":62,"status":"downloading"}"""
                })
                "/torrents?page=2&limit=100" -> HttpResponse(200,
                    """[{"id":"T101","added":"2026-11-02T00:00:00Z","status":"downloaded"}]""")
                "/downloads?page=1&limit=100" -> HttpResponse(200,
                    """[{"id":"W1","filename":"video.mp4","generated":"2026-11-03T00:00:00Z"}]""")
                else -> error("Unexpected request")
            }
        }
        val downloads = provider.listDownloads().getOrThrow()
        assertEquals(102, downloads.size)
        assertEquals(listOf("web:W1", "T101", "T1"), downloads.take(3).map { it.id })
        assertEquals(0.62f, downloads[2].progress, 0.001f)
        assertTrue(fake.requests.all { it.method == "GET" })
    }

    @Test
    fun `torrent info maps statuses and real file identifiers`() = runBlocking {
        val states = mapOf(
            "magnet_error" to DownloadStatus.QUEUED,
            "magnet_conversion" to DownloadStatus.QUEUED,
            "waiting_files_selection" to DownloadStatus.QUEUED,
            "queued" to DownloadStatus.QUEUED,
            "downloading" to DownloadStatus.DOWNLOADING,
            "compressing" to DownloadStatus.DOWNLOADING,
            "uploading" to DownloadStatus.DOWNLOADING,
            "downloaded" to DownloadStatus.READY,
            "error" to DownloadStatus.ERROR,
            "virus" to DownloadStatus.ERROR,
            "dead" to DownloadStatus.ERROR,
            "future_state" to DownloadStatus.UNKNOWN,
        )
        for ((state, expected) in states) {
            val (provider, _) = provider {
                assertEquals("/torrents/info/T1", it.path)
                torrent(state)
            }
            val download = provider.getDownload("T1").getOrThrow()
            assertEquals(state, expected, download.status)
            assertEquals(DebridFile("27", "video.mkv", 42, "/folder/video.mkv"), download.files.last())
            assertEquals(DownloadKind.TORRENT, download.kind)
        }
    }

    @Test
    fun `both deletion endpoints are idempotent and unknown torrent info is not cached`() = runBlocking {
        val (provider, fake) = provider { HttpResponse(404, "") }
        assertTrue(provider.delete("T1").isSuccess)
        assertTrue(provider.delete("web:W1").isSuccess)
        assertTrue(provider.getDownload("missing").exceptionOrNull() is DebridError.NotCached)
        assertEquals(
            listOf("DELETE" to "/torrents/delete/T1", "DELETE" to "/downloads/delete/W1", "GET" to "/torrents/info/missing"),
            fake.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `maps HTTP errors and redacts tokens and links`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path.substringAfterLast('/')) {
                "auth" -> HttpResponse(401, "")
                "token" -> HttpResponse(403, """{"error":"bad_token","error_code":8}""")
                "rate" -> HttpResponse(429, "", mapOf("rEtRy-AfTeR" to "12"))
                "refused" -> HttpResponse(451, """{"error":"infringing_file","error_code":35}""")
                "echo" -> HttpResponse(403, """{"error":"rejected $TOKEN","error_code":9}""")
                else -> HttpResponse(400, """{"error":"bad https%3A%2F%2Fcdn.example%2Fprivate","error_code":2}""")
            }
        }
        assertTrue(provider.getDownload("auth").exceptionOrNull() is DebridError.AuthInvalid)
        assertTrue(provider.getDownload("token").exceptionOrNull() is DebridError.AuthInvalid)
        assertEquals(12L, (provider.getDownload("rate").exceptionOrNull() as DebridError.RateLimited).retryAfterSecs)
        assertEquals("infringing_file",
            (provider.getDownload("refused").exceptionOrNull() as DebridError.NameRefused).detail)
        val echo = provider.getDownload("echo").exceptionOrNull() as DebridError.Provider
        assertEquals("9", echo.code)
        assertFalse(echo.message.orEmpty().contains(TOKEN))
        val linkError = provider.getDownload("link").exceptionOrNull() as DebridError.Provider
        assertFalse(linkError.detail.contains("cdn.example"))
        assertTrue(provider.getDownload(TOKEN).exceptionOrNull() is DebridError.NotCached)
        assertEquals(6, fake.requests.size)
        assertTrue(fake.requests.all { !it.url.contains(TOKEN) && it.headers["Authorization"] == "Bearer $TOKEN" })
    }

    @Test
    fun `unsupported operations and cache check are local`() = runBlocking {
        val (provider, fake) = provider { error("Unexpected request") }
        assertNull(provider.checkCached(emptyList()).getOrThrow())
        assertNull(provider.checkCached(listOf("abc")).getOrThrow())
        assertTrue(provider.control("T1", ControlAction.PAUSE).exceptionOrNull() is DebridError.UnsupportedCapability)
        assertTrue(provider.add(AddInput.Nzb(byteArrayOf(), "a.nzb")).exceptionOrNull() is DebridError.UnsupportedCapability)
        assertTrue(provider.add(AddInput.NzbUrl("https://example.org/a.nzb")).exceptionOrNull() is DebridError.UnsupportedCapability)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `hoster domains are cached and matched on domain boundaries`() = runBlocking {
        val (provider, fake) = provider {
            assertEquals("GET" to "/hosts/domains", it.method to it.path)
            HttpResponse(200, """["host.example",null,{}]""")
        }
        assertTrue(provider.supportsUrl("https://host.example/video").getOrThrow())
        assertTrue(provider.supportsUrl("https://Sub.HOST.example/video").getOrThrow())
        assertFalse(provider.supportsUrl("https://fakehost.example/video").getOrThrow())
        assertFalse(provider.supportsUrl("bad URL").getOrThrow())
        assertEquals(1, fake.requests.size)
        val (unavailable, _) = provider { HttpResponse(503, "") }
        assertFalse(unavailable.supportsUrl("https://host.example/").getOrThrow())
    }

    @Test
    fun `play matches links to selected files and accepts only safe HTTP URLs`() = runBlocking {
        for (url in listOf("https://cdn.example/ready", "ftp://cdn.example/file",
            "https://user@cdn.example/file", "https://cdn.example/$TOKEN")) {
            val (provider, fake) = provider {
                when (it.path) {
                    "/torrents/info/T1" -> torrent("downloaded")
                    "/unrestrict/link" -> HttpResponse(200, """{"download":"$url"}""")
                    else -> error("Unexpected request")
                }
            }
            val result = provider.getStreamUrl("T1", "27")
            if (url == "https://cdn.example/ready") assertEquals(StreamUrl(url), result.getOrThrow())
            else assertTrue(result.exceptionOrNull() is DebridError.Provider)
            assertEquals(listOf("GET", "POST"), fake.requests.map { it.method })
            assertEquals(mapOf("link" to "https://host.example/video"), fake.requests.last().formFields)
        }
    }

    @Test
    fun `transport errors map to network failures and cancellation escapes`() = runBlocking {
        val (offline, _) = provider { throw IOException("offline") }
        assertTrue(offline.validateAccount().exceptionOrNull() is DebridError.Network)
        val cancellation = CancellationException("cancelled")
        val (cancelled, _) = provider { throw cancellation }
        val thrown = runCatching { cancelled.validateAccount() }.exceptionOrNull()
        assertSame(cancellation, thrown)
    }

    private fun torrent(status: String) = HttpResponse(200, """
        {"id":"T1","filename":"folder","status":"$status","files":[
            {"id":5,"path":"/folder/readme.txt","bytes":2,"selected":0},
            {"id":27,"path":"/folder/video.mkv","bytes":42,"selected":1}
        ],"links":["https://host.example/video"]}
    """.trimIndent())

    companion object {
        private const val TOKEN = "real-debrid-test-secret"
    }
}
