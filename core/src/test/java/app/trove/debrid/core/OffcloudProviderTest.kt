package app.trove.debrid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class OffcloudProviderTest {
    private class FakeTransport(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun provider(handler: (HttpRequest) -> HttpResponse): Pair<OffcloudProvider, FakeTransport> {
        val fake = FakeTransport(handler)
        return OffcloudProvider(KEY, fake, TokenBucket(2.0, 100), TokenBucket(0.5, 20), pollDelayMs = 0) to fake
    }

    @Test
    fun `account probe uses encoded query auth and ignores remote account usernames`() = runBlocking {
        val (provider, fake) = provider {
            assertEquals("POST" to "/api/remote/accounts", it.method to it.path)
            ok("""[{"username":"remote-user"}]""")
        }
        assertEquals(AccountInfo(null, false, null), provider.validateAccount().getOrThrow())
        val request = fake.requests.single()
        assertEquals("key=fake%2Boffcloud%2Fkey", URI(request.url).rawQuery)
        assertEquals("offcloud.com", URI(request.url).host)
        assertTrue(request.headers.isEmpty())
        assertFalse(request.toString().contains("fake"))
    }

    @Test
    fun `magnet polls until ready and resolves the selected file`() = runBlocking {
        var polls = 0
        val (provider, fake) = provider {
            when (it.path) {
                "/api/cloud" -> {
                    assertEquals("POST", it.method)
                    assertEquals(mapOf("url" to MAGNET), it.formFields)
                    assertTrue(it.multipart.isEmpty())
                    created()
                }
                "/api/cloud/status" -> {
                    assertEquals("POST", it.method)
                    assertEquals(mapOf("requestId" to ID), it.formFields)
                    status(if (polls++ == 0) "downloading" else "downloaded")
                }
                "/api/cloud/explore/$ID" -> {
                    assertEquals("GET", it.method)
                    ok("""["https://cdn.example/0/extras.mkv","$LINK"]""")
                }
                else -> unexpected(it)
            }
        }
        val download = provider.add(AddInput.Magnet(MAGNET)).getOrThrow()
        assertEquals(DebridService.OFFCLOUD, download.service)
        assertEquals(DownloadKind.TORRENT, download.kind)
        assertEquals(DownloadStatus.READY, download.status)
        assertEquals(1f, download.progress, 0f)
        assertEquals("video.mkv", download.files.last().name)
        assertEquals(StreamUrl(LINK), provider.getStreamUrl(ID, download.files.last().id).getOrThrow())
        assertEquals(2, polls)
        assertTrue(fake.requests.none { it.url.contains(LINK) || it.toString().contains(KEY) })
    }

    @Test
    fun `polling stops after ten calls and preserves active transfers`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                "/api/cloud" -> created()
                "/api/cloud/status" -> status("downloading")
                else -> unexpected(it)
            }
        }
        val download = provider.add(AddInput.Magnet(MAGNET)).getOrThrow()
        assertEquals(DownloadStatus.DOWNLOADING, download.status)
        assertEquals(0.25f, download.progress, 0f)
        assertEquals(10, fake.requests.count { it.path == "/api/cloud/status" })
    }

    @Test
    fun `failed and cancelled magnet adds remove the transfer`() = runBlocking {
        for (cancel in listOf(false, true)) {
            val (provider, fake) = provider {
                when (it.path) {
                    "/api/cloud" -> created()
                    "/api/cloud/status" -> if (cancel) throw CancellationException() else status("canceled")
                    "/cloud/remove/$ID" -> ok("""{"success":true}""")
                    else -> unexpected(it)
                }
            }
            val failure = runCatching { provider.add(AddInput.Magnet(MAGNET)).getOrThrow() }.exceptionOrNull()
            assertTrue(if (cancel) failure is CancellationException else failure is DebridError.Provider)
            assertEquals("/cloud/remove/$ID", fake.requests.last().path)
        }
    }

    @Test
    fun `hoster and NZB URLs use cloud submission`() = runBlocking {
        val (provider, fake) = provider {
            assertEquals("POST" to "/api/cloud", it.method to it.path)
            created()
        }
        val hoster = "https://host.example/video.mkv"
        val nzb = "https://host.example/video.nzb"
        assertEquals(DownloadKind.WEB, provider.add(AddInput.HosterLink(hoster)).getOrThrow().kind)
        assertEquals(DownloadKind.USENET, provider.add(AddInput.NzbUrl(nzb)).getOrThrow().kind)
        assertEquals(listOf(hoster, nzb), fake.requests.map { it.formFields["url"] })
    }

    @Test
    fun `history maps kinds and statuses across pages and detail refreshes the selected entry`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                "/cloud/history" -> {
                    assertEquals("POST", it.method)
                    if (it.jsonBody == """{"page":0}""") {
                        ok("""{"isEnd":false,"history":[
                            {"requestId":"web","fileName":"web.mkv","status":"error","createdOn":"2026-11-01T00:00:00Z"},
                            {"requestId":"nzb","fileName":"news.mkv","originalLink":"https://host.example/news.nzb?q=1",
                             "status":"created","createdOn":"2026-11-02T00:00:00Z"}]}""")
                    } else {
                        assertEquals("""{"page":1}""", it.jsonBody)
                        ok("""{"isEnd":true,"history":[
                            {"requestId":"$ID","fileName":"video.mkv","originalLink":"$MAGNET",
                             "status":"downloading","createdOn":"2026-11-03T00:00:00Z"}]}""")
                    }
                }
                "/api/cloud/status" -> status("downloaded")
                "/api/cloud/explore/$ID" -> ok("""{"files":[{"id":"video","name":"video.mkv",
                    "path":"Disc1/video.mkv","size":400,"url":"$LINK"}]}""")
                else -> unexpected(it)
            }
        }
        val downloads = provider.listDownloads().getOrThrow()
        assertEquals(listOf(ID, "nzb", "web"), downloads.map { it.id })
        assertEquals(listOf(DownloadKind.TORRENT, DownloadKind.USENET, DownloadKind.WEB), downloads.map { it.kind })
        assertEquals(listOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.ERROR),
            downloads.map { it.status })
        val detail = provider.getDownload(ID).getOrThrow()
        assertEquals(DownloadKind.TORRENT, detail.kind)
        assertEquals(DownloadStatus.READY, detail.status)
        assertEquals(DebridFile("video", "video.mkv", 400, "Disc1/video.mkv"), detail.files.single())
        assertFalse(detail.toString().contains(LINK))
        assertEquals(4, fake.requests.count { it.path == "/cloud/history" })
    }

    @Test
    fun `delete validates download ID before contacting Offcloud`() = runBlocking {
        val (provider, fake) = provider {
            assertEquals("GET" to "/cloud/remove/$ID", it.method to it.path)
            ok("""{"success":true}""")
        }
        assertTrue(provider.delete(ID).isSuccess)
        assertTrue(provider.delete("../another").exceptionOrNull() is DebridError.NotCached)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `maps HTTP and JSON auth failures`() = runBlocking {
        for (response in listOf(HttpResponse(401, ""), ok("""{"error":"NOAUTH"}"""),
            ok("""{"error":"Invalid API key"}"""))) {
            val (provider, _) = provider { response }
            assertTrue(provider.validateAccount().exceptionOrNull() is DebridError.AuthInvalid)
        }
    }

    @Test
    fun `rate limit preserves retry after header`() = runBlocking {
        val (provider, _) = provider { HttpResponse(429, "", mapOf("retry-after" to "42")) }
        val failure = provider.validateAccount().exceptionOrNull() as DebridError.RateLimited
        assertEquals(42L, failure.retryAfterSecs)
    }

    @Test
    fun `cache lookup batches hashes and returns both hits and misses`() = runBlocking {
        val one = "a".repeat(40)
        val two = "b".repeat(40)
        val sha256 = "c".repeat(64)
        val (provider, fake) = provider {
            assertEquals("POST" to "/api/cache", it.method to it.path)
            assertEquals("""{"hashes":["$one","$two","$sha256"]}""", it.jsonBody)
            ok("""{"cachedItems":["$one","$sha256"]}""")
        }
        assertEquals(emptyMap<String, CachedInfo>(), provider.checkCached(emptyList()).getOrThrow())
        assertEquals(
            mapOf(
                one.uppercase() to CachedInfo(true, null, null),
                two to CachedInfo(false, null, null),
                sha256 to CachedInfo(true, null, null),
            ),
            provider.checkCached(listOf(one.uppercase(), two, sha256)).getOrThrow(),
        )
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `unsupported uploads and controls make no requests`() = runBlocking {
        val (provider, fake) = provider(::unexpected)
        assertTrue(provider.add(AddInput.TorrentFile(byteArrayOf(1), "demo.torrent")).exceptionOrNull()
            is DebridError.UnsupportedCapability)
        assertTrue(provider.add(AddInput.Nzb(byteArrayOf(1), "demo.nzb")).exceptionOrNull()
            is DebridError.UnsupportedCapability)
        assertTrue(provider.control(ID, ControlAction.PAUSE).exceptionOrNull() is DebridError.UnsupportedCapability)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `supports only listed active domains and treats sites failures as unsupported`() = runBlocking {
        val (provider, _) = provider {
            assertEquals("POST" to "/api/sites", it.method to it.path)
            ok("""{"sites":[{"host":"host.example","status":"working"},{"host":"down.example","status":"offline"}]}""")
        }
        assertTrue(provider.supportsUrl("https://www.host.example/movie").getOrThrow())
        assertFalse(provider.supportsUrl("https://nothost.example/movie").getOrThrow())
        assertFalse(provider.supportsUrl("https://down.example/movie").getOrThrow())
        val (unavailable, _) = provider { HttpResponse(500, "") }
        assertFalse(unavailable.supportsUrl("https://host.example/movie").getOrThrow())
    }

    @Test
    fun `rejects key bearing delivery URLs and suppresses sensitive error text`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                "/api/cloud/explore/$ID" -> ok("""{"files":[{"id":"video","name":"video.mkv",
                    "url":"https://cdn.example/video.mkv?k=fake%2Boffcloud%2Fkey"}]}""")
                "/cloud/remove/$ID" -> HttpResponse(500, """{"error":"Download refused: $LINK"}""")
                else -> unexpected(it)
            }
        }
        assertTrue(provider.getStreamUrl(ID, "video").exceptionOrNull() is DebridError.NotCached)
        val error = provider.delete(ID).exceptionOrNull() as DebridError.Provider
        assertFalse(error.message.orEmpty().contains(LINK))
        assertTrue(fake.requests.none { it.toString().contains("fake") || it.url.contains("cdn.example") })
    }

    private val HttpRequest.path: String get() = URI(url).path

    private fun ok(body: String) = HttpResponse(200, body)

    private fun created() = ok("""{"requestId":"$ID","fileName":"video.mkv","status":"created"}""")

    private fun status(state: String) =
        ok("""{"status":{"requestId":"$ID","status":"$state","amount":100,"fileSize":400}}""")

    private fun unexpected(request: HttpRequest): Nothing = error("Unexpected ${request.method} ${request.path}")

    companion object {
        private const val KEY = "fake+offcloud/key"
        private const val ID = "cloud-1"
        private const val MAGNET = "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val LINK = "https://cdn.example/1/video.mkv?token=private"
    }
}
