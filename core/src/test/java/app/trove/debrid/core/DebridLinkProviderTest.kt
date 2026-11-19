package app.trove.debrid.core

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class DebridLinkProviderTest {
    private fun provider(handler: (HttpRequest) -> HttpResponse): Pair<DebridLinkProvider, RecordingTransport> {
        val fake = RecordingTransport(handler)
        return DebridLinkProvider(KEY, fake, TokenBucket(2.0, 100), TokenBucket(0.2, 20), pollDelayMs = 0) to fake
    }

    @Test
    fun `account probe uses Bearer auth and accepts both account names`() = runBlocking {
        val accounts = listOf(
            """{"pseudo":"legacy","accountType":2,"pts":500}""" to AccountInfo("legacy", true, "Premium · 500 pts"),
            """{"username":"current","accountType":0}""" to AccountInfo("current", false, "Free"),
            """{"accountType":0,"premiumLeft":5}""" to AccountInfo(null, true, "Premium"),
        )
        for ((body, expected) in accounts) {
            val (provider, fake) = provider { ok(body) }
            assertEquals(expected, provider.validateAccount().getOrThrow())
            val request = fake.requests.single()
            assertEquals("GET", request.method)
            assertEquals("https://debrid-link.com/api/v2/account/infos", request.url)
            assertEquals("Bearer $KEY", request.headers["Authorization"])
            assertFalse(request.toString().contains(KEY))
        }
    }

    @Test
    fun `magnet sends both source fields then polls and resolves the selected file`() = runBlocking {
        var polls = 0
        val (provider, _) = provider { request ->
            when (request.route) {
                "/seedbox/add" -> {
                    assertEquals("POST", request.method)
                    val body = JsonParser.parseString(request.jsonBody).asJsonObject
                    assertEquals(MAGNET, body["url"].asString)
                    assertEquals(MAGNET, body["torrentUrl"].asString)
                    assertEquals("list", body["structureType"].asString)
                    ok(torrent(1, 0))
                }
                "/seedbox/list" -> {
                    assertEquals("GET", request.method)
                    assertEquals("ids=t1&perPage=20", URI(request.url).query)
                    ok(if (polls++ == 0) "[${torrent(8, 25)}]" else "[${torrent(8, 100, FILES)}]")
                }
                else -> unexpected(request)
            }
        }
        val download = provider.add(AddInput.Magnet(MAGNET)).getOrThrow()
        assertEquals("seedbox:t1", download.id)
        assertEquals(DownloadStatus.READY, download.status)
        assertEquals(1f, download.progress, 0f)
        assertEquals(listOf("partial", "selected"), download.files.map { it.id })
        assertEquals(StreamUrl(LINK), provider.getStreamUrl(download.id, "selected").getOrThrow())
        assertTrue(provider.getStreamUrl(download.id, "partial").exceptionOrNull() is DebridError.NotCached)
        assertEquals(4, polls)
    }

    @Test
    fun `polling stops after ten calls and keeps the latest transfer`() = runBlocking {
        val (provider, fake) = provider { request ->
            when (request.route) {
                "/seedbox/add" -> ok(torrent(1, 0))
                "/seedbox/list" -> ok("[${torrent(4, 25)}]")
                else -> unexpected(request)
            }
        }
        val result = provider.add(AddInput.Magnet(MAGNET)).getOrThrow()
        assertEquals(DownloadStatus.DOWNLOADING, result.status)
        assertEquals(0.25f, result.progress, 0f)
        assertEquals(10, fake.requests.count { it.route == "/seedbox/list" })
    }

    @Test
    fun `failed and cancelled magnet adds delete their torrent`() = runBlocking {
        for (cancel in listOf(false, true)) {
            val (provider, fake) = provider { request ->
                when (request.route) {
                    "/seedbox/add" -> ok(torrent(1, 0))
                    "/seedbox/list" -> if (cancel) throw CancellationException() else
                        ok("""[{"id":"t1","error":7,"errorString":"Transfer refused"}]""")
                    "/seedbox/t1/remove" -> HttpResponse(200, """{"success":true}""")
                    else -> unexpected(request)
                }
            }
            val failure = runCatching { provider.add(AddInput.Magnet(MAGNET)).getOrThrow() }.exceptionOrNull()
            if (cancel) {
                assertTrue(failure is CancellationException)
            } else {
                assertTrue(failure is DebridError.Provider)
                with(failure as DebridError.Provider) {
                    assertEquals("transfer-failed", code)
                    assertEquals("Transfer refused", detail)
                }
            }
            assertEquals("DELETE" to "/seedbox/t1/remove", fake.requests.last().let { it.method to it.route })
        }
    }

    @Test
    fun `a JVM Error escapes instead of mapping to a network failure`() = runBlocking {
        val error = OutOfMemoryError("test")
        val (provider, _) = provider { throw error }
        assertSame(error, runCatching { provider.validateAccount() }.exceptionOrNull())
    }

    @Test
    fun `string and numeric JSON fields parse identically after the opt migration`() = runBlocking {
        fun torrentJson(status: String, percent: String, size: String, totalSize: String, created: String) =
            """{"id":"t1","name":"Dune","status":$status,"downloadPercent":$percent,"totalSize":$totalSize,"created":$created,"files":[{"id":"f1","name":"dune.mkv","size":$size,"downloadUrl":"$LINK"}]}"""
        suspend fun downloadFor(torrent: String): DebridDownload {
            val (provider, _) = provider { request ->
                when (request.route) {
                    "/seedbox/list" -> page(listOf(torrent))
                    "/downloader/list" -> page(emptyList())
                    else -> unexpected(request)
                }
            }
            return provider.listDownloads().getOrThrow().single()
        }
        val asNumbers = downloadFor(torrentJson("8", "50", "12", "1200", "1700000000"))
        val asStrings = downloadFor(torrentJson("\"8\"", "\"50\"", "\"12\"", "\"1200\"", "\"1700000000\""))
        assertEquals(asNumbers, asStrings)
        assertEquals(1200L, asNumbers.sizeBytes)
        assertEquals(DownloadStatus.DOWNLOADING, asNumbers.status)
        assertEquals(0.5f, asNumbers.progress, 0.001f)
        assertEquals(1_700_000_000_000L, asNumbers.createdAtEpochMs)
        assertEquals(12L, asNumbers.files.single().sizeBytes)
    }

    @Test
    fun `account fields read numbers and numeric strings alike`() = runBlocking {
        for (body in listOf(
            """{"pseudo":"legacy","accountType":2,"pts":500}""",
            """{"pseudo":"legacy","accountType":"2","pts":"500"}""",
        )) {
            val (provider, _) = provider { ok(body) }
            assertEquals(AccountInfo("legacy", true, "Premium · 500 pts"), provider.validateAccount().getOrThrow())
        }
    }

    @Test
    fun `downloader add accepts an object or the first entry of an array`() = runBlocking {
        for (value in listOf(WEB, "[$WEB,{\"id\":\"second\"}]")) {
            val source = "https://host.example/movie"
            val (provider, _) = provider { request ->
                when (request.route) {
                    "/downloader/add" -> {
                        assertEquals("POST", request.method)
                        assertEquals(source, JsonParser.parseString(request.jsonBody).asJsonObject["link"].asString)
                        ok(value)
                    }
                    "/downloader/list" -> ok("[$WEB]")
                    else -> unexpected(request)
                }
            }
            val download = provider.add(AddInput.HosterLink(source)).getOrThrow()
            assertEquals("dl:w1", download.id)
            assertEquals(DownloadKind.WEB, download.kind)
            assertEquals(DownloadStatus.READY, download.status)
            assertEquals(DebridFile("w1", "movie.mkv", 12, "/movie.mkv"), download.files.single())
            assertEquals(StreamUrl(LINK), provider.getStreamUrl(download.id, "w1").getOrThrow())
        }
    }

    @Test
    fun `torrent upload sends the multipart file and polls`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val (provider, fake) = provider { request ->
            when (request.route) {
                "/seedbox/add" -> {
                    assertEquals("POST", request.method)
                    assertEquals(mapOf("structureType" to "list"), request.formFields)
                    assertNull(request.jsonBody)
                    val part = request.multipart.single()
                    assertEquals("file", part.name)
                    assertEquals("file.torrent", part.fileName)
                    assertEquals("application/x-bittorrent", part.contentType)
                    assertArrayEquals(bytes, part.bytes)
                    ok(torrent(1, 0))
                }
                "/seedbox/list" -> ok("[${torrent(100, 100, FILES)}]")
                else -> unexpected(request)
            }
        }
        assertEquals(DownloadStatus.READY, provider.add(AddInput.TorrentFile(bytes, "file.torrent")).getOrThrow().status)
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun `list pages both routes and merges unique downloads newest first`() = runBlocking {
        val firstTorrent = """{"id":"t1","status":0,"addedDate":10,"percentDownload":25}"""
        val nextTorrent = """{"id":"t2","status":2,"created":30}"""
        val nextWeb = """{"id":"w2","expired":true,"isProcessing":true,"created":40}"""
        val (provider, fake) = provider { request ->
            val query = URI(request.url).query
            assertTrue(query.contains("perPage=100"))
            val pageZero = query.startsWith("page=0&")
            if (!pageZero) assertTrue(query.startsWith("page=1&"))
            when (request.route) {
                "/seedbox/list" -> {
                    assertTrue(query.endsWith("&structureType=list"))
                    page(if (pageZero) List(100) { firstTorrent } else listOf(nextTorrent))
                }
                "/downloader/list" -> page(if (pageZero) List(100) { WEB } else listOf(nextWeb))
                else -> unexpected(request)
            }
        }
        val downloads = provider.listDownloads().getOrThrow()
        assertEquals(listOf("dl:w2", "seedbox:t2", "dl:w1", "seedbox:t1"), downloads.map { it.id })
        assertEquals(listOf(40_000L, 30_000L, 20_000L, 10_000L), downloads.map { it.createdAtEpochMs })
        assertEquals(listOf(DownloadKind.WEB, DownloadKind.TORRENT, DownloadKind.WEB, DownloadKind.TORRENT), downloads.map { it.kind })
        assertEquals(
            listOf(DownloadStatus.ERROR, DownloadStatus.DOWNLOADING, DownloadStatus.READY, DownloadStatus.PAUSED),
            downloads.map { it.status },
        )
        assertEquals(4, fake.requests.size)
    }

    @Test
    fun `details expand a zip torrent and try both routes for legacy ids`() = runBlocking {
        val (provider, fake) = provider { request ->
            when (request.route) {
                "/seedbox/list" -> when (URI(request.url).query) {
                    "page=0&perPage=100&structureType=list" ->
                        ok("""[{"id":"t1","isZip":true,"files":[{"id":"zip","name":"bundle.zip"}]}]""")
                    "ids=t1&perPage=20" -> ok("[${torrent(100, 100, FILES)}]")
                    else -> ok("[]")
                }
                "/downloader/list" -> ok(if (URI(request.url).query == "ids=w1") "[$WEB]" else "[]")
                else -> unexpected(request)
            }
        }
        assertEquals("zip", provider.listDownloads().getOrThrow().single().files.single().id)
        val download = provider.getDownload("seedbox:t1").getOrThrow()
        assertEquals(2, download.files.size)
        assertEquals(24L, download.files.last().sizeBytes)
        assertEquals("ids=t1&perPage=20", URI(fake.requests.last().url).query)
        assertEquals("dl:w1", provider.getDownload("w1").getOrThrow().id)
        assertTrue(provider.getDownload("missing").exceptionOrNull() is DebridError.NotCached)
    }

    @Test
    fun `delete routes prefixes and rejects invalid ids without a request`() = runBlocking {
        val (provider, fake) = provider { request ->
            if (request.route == "/downloader/missing/remove") failure("badId")
            else HttpResponse(200, """{"success":true}""")
        }
        provider.delete("seedbox:t1").getOrThrow()
        provider.delete("dl:w1").getOrThrow()
        assertTrue(provider.delete("dl:../w1").exceptionOrNull() is DebridError.NotCached)
        assertTrue(provider.delete("dl:missing").exceptionOrNull() is DebridError.Provider)
        assertEquals(
            listOf("/seedbox/t1/remove", "/downloader/w1/remove", "/downloader/missing/remove"),
            fake.requests.map { it.route },
        )
        assertTrue(fake.requests.all { it.method == "DELETE" })
    }

    @Test
    fun `authentication rate limits and policy errors are typed`() = runBlocking {
        suspend fun errorFor(response: HttpResponse): Throwable? =
            provider { response }.first.validateAccount().exceptionOrNull()

        assertTrue(errorFor(HttpResponse(401, "")) is DebridError.AuthInvalid)
        assertTrue(errorFor(HttpResponse(403, "")) is DebridError.AuthInvalid)
        for (code in listOf("badToken", "expired_token", "access_denied", "invalid_client")) {
            assertTrue(errorFor(failure(code)) is DebridError.AuthInvalid)
        }
        assertEquals(3600L, (errorFor(failure("floodDetected")) as DebridError.RateLimited).retryAfterSecs)
        assertNull((errorFor(failure("freeServerOverload")) as DebridError.RateLimited).retryAfterSecs)
        val limited = HttpResponse(429, "", mapOf("retry-after" to "42"))
        assertEquals(42L, (errorFor(limited) as DebridError.RateLimited).retryAfterSecs)
        assertTrue(errorFor(failure("infringingFile")) is DebridError.NameRefused)
        assertTrue(errorFor(failure("maxTorrent")) is DebridError.QuotaExceeded)
        assertEquals("hostNotValid", (errorFor(failure("hostNotValid")) as DebridError.Provider).code)
    }

    @Test
    fun `cache Usenet controls and non-web support checks make no requests`() = runBlocking {
        val (provider, fake) = provider { unexpected(it) }
        assertNull(provider.checkCached(listOf("hash")).getOrThrow())
        assertTrue(provider.add(AddInput.Nzb(byteArrayOf(), "x.nzb")).exceptionOrNull() is DebridError.UnsupportedCapability)
        assertTrue(provider.add(AddInput.NzbUrl("https://example.com/x.nzb")).exceptionOrNull() is DebridError.UnsupportedCapability)
        assertTrue(provider.control("seedbox:t1", ControlAction.PAUSE).exceptionOrNull() is DebridError.UnsupportedCapability)
        assertFalse(provider.supportsUrl("ftp://host.example/movie").getOrThrow())
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `hoster domains and regexs are cached together and failure returns false`() = runBlocking {
        val (provider, fake) = provider { request ->
            when (request.route) {
                "/downloader/domains" -> ok("""["Host.Example"]""")
                "/downloader/regex" -> ok("""[{"regexs":["^https://alias[.]example/v/","["]}]""")
                else -> unexpected(request)
            }
        }
        assertEquals(setOf("host.example"), provider.hosterDomains())
        assertTrue(provider.supportsUrl("https://host.example/movie").getOrThrow())
        assertTrue(provider.supportsUrl("https://sub.host.example/movie").getOrThrow())
        assertTrue(provider.supportsUrl("https://alias.example/v/movie").getOrThrow())
        assertFalse(provider.supportsUrl("https://not-host.example/movie").getOrThrow())
        assertEquals(2, fake.requests.size)
        val unavailable = provider { failure("floodDetected") }.first
        assertFalse(unavailable.supportsUrl("https://host.example/movie").getOrThrow())
    }

    @Test
    fun `delivery validation rejects credential leaks and errors suppress sensitive text`() = runBlocking {
        for (link in listOf(
            "https://cdn.example/?token=fake%2Bdl%2Fkey",
            "https://cdn.example/?token=$KEY",
            "https://user@cdn.example/movie",
            "ftp://cdn.example/movie",
        )) {
            val (provider, _) = provider { ok("[${WEB.replace(LINK, link)}]") }
            assertTrue(provider.getStreamUrl("dl:w1", "w1").exceptionOrNull() is DebridError.NotCached)
        }
        for (detail in listOf(KEY, "fake%2Bdl%2Fkey", LINK)) {
            val (provider, _) = provider { failure("badArguments", "Unexpected input $detail") }
            val error = provider.validateAccount().exceptionOrNull() as DebridError.Provider
            assertEquals("badArguments", error.code)
            assertEquals("", error.detail)
            assertFalse(error.toString().contains(detail))
        }
    }

    private val HttpRequest.route: String get() = URI(url).path.removePrefix("/api/v2")

    private fun unexpected(request: HttpRequest): Nothing = error("Unexpected ${request.method} ${request.route}")
    private fun ok(value: String) = HttpResponse(200, """{"success":true,"value":$value}""")
    private fun failure(code: String, detail: String = "") =
        HttpResponse(200, """{"success":false,"error":"$code","error_description":"$detail"}""")
    private fun page(entries: List<String>) =
        HttpResponse(200, """{"success":true,"value":[${entries.joinToString()}],"pagination":{"pages":2}}""")
    private fun torrent(status: Int, percent: Int, files: String = "[]") =
        """{"id":"t1","status":$status,"downloadPercent":$percent,"files":$files}"""

    companion object {
        private const val KEY = "fake+dl/key"
        private const val MAGNET = "magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd"
        private const val LINK = "https://cdn.example/video/movie.mkv"
        private const val WEB =
            """{"id":"w1","time":20,"filename":"movie.mkv","size":12,"downloadLink":"$LINK"}"""
        private const val FILES =
            """[{"id":"partial","name":"partial.mkv","size":12,"downloadPercent":50,"downloadUrl":"$LINK"},{"id":"selected","name":"movie.mkv","length":24,"percentDownload":100,"downloadUrl":"$LINK"}]"""
    }
}
