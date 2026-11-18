package app.trove.debrid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBoxProviderTest {

    class FakeTransport(
        private val handler: (HttpRequest) -> HttpResponse,
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun provider(
        apiKey: String = "test-key-123",
        handler: (HttpRequest) -> HttpResponse,
    ): Pair<TorBoxProvider, FakeTransport> {
        val transport = FakeTransport(handler)
        return TorBoxProvider(apiKey, transport) to transport
    }

    private fun envelope(dataJson: String): String =
        """{"success":true,"error":null,"detail":"","data":$dataJson}"""

    private val torrentListJson = """[
        {"id":11,"hash":"aabbcc","name":"Dune Part Two 2024","size":18400000000,
         "download_state":"cached","progress":1.0,"download_speed":0,
         "download_finished":true,"created_at":"2026-11-14T10:00:00Z",
         "files":[{"id":1,"name":"dune.mkv","size":18400000000}]},
        {"id":12,"hash":"ddeeff","name":"The Last of Us S02E05","size":3200000000,
         "download_state":"downloading","progress":0.62,"download_speed":4800000,
         "download_finished":false,"created_at":"2026-11-15T09:00:00Z","files":[]},
        {"id":13,"hash":"112233","name":"Mystery State","size":100,
         "download_state":"something_new","progress":0,
         "download_finished":false,"created_at":"2026-11-15T10:00:00Z","files":null}
    ]"""

    @Test
    fun `listDownloads parses all three kinds and maps states`() {
        val (p, transport) = provider { req ->
            when {
                req.url.contains("/torrents/mylist") -> HttpResponse(200, envelope(torrentListJson))
                req.url.contains("/webdl/mylist") -> HttpResponse(200, envelope("[]"))
                req.url.contains("/usenet/mylist") -> HttpResponse(200, envelope("[]"))
                else -> HttpResponse(404, """{"success":false,"error":"nf","detail":""}""")
            }
        }
        val result = kotlinx.coroutines.runBlocking { p.listDownloads() }
        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(3, list.size)
        // Newest first: 13 (10:00), 12 (09:00), 11 (yesterday).
        assertEquals("13", list[0].id)
        assertEquals(DownloadStatus.UNKNOWN, list[0].status)
        assertEquals("12", list[1].id)
        assertEquals(DownloadStatus.DOWNLOADING, list[1].status)
        assertEquals(0.62f, list[1].progress, 0.001f)
        assertEquals(4_800_000L, list[1].downloadSpeedBytesPerSec)
        assertEquals("11", list[2].id)
        assertEquals(DownloadStatus.READY, list[2].status)
        assertEquals(1f, list[2].progress)
        assertEquals("dune.mkv", list[2].files.first().name)
        assertEquals(java.time.Instant.parse("2026-11-14T10:00:00Z").toEpochMilli(), list[2].createdAtEpochMs)
        assertEquals(18_400_000_000L, list[2].sizeBytes)
        assertEquals(1, list[2].files.size)
        assertTrue(transport.requests.all { it.headers["Authorization"] == "Bearer test-key-123" })
        assertTrue(transport.requests.none { it.url.contains("test-key-123") })
    }

    @Test
    fun `listDownloads survives a failing usenet list`() {
        val (p, _) = provider { req ->
            when {
                req.url.contains("/torrents/mylist") -> HttpResponse(200, envelope(torrentListJson))
                req.url.contains("/webdl/mylist") -> HttpResponse(200, envelope("[]"))
                else -> HttpResponse(403, """{"success":false,"error":"pro","detail":"Usenet is Pro only"}""")
            }
        }
        val list = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow()
        assertEquals(3, list.size)
    }

    @Test
    fun `validateAccount reads plan and email`() {
        val (p, _) = provider {
            HttpResponse(200, envelope("""{"email":"c@example.com","plan":1,"is_subscribed":true}"""))
        }
        val info = kotlinx.coroutines.runBlocking { p.validateAccount() }.getOrThrow()
        assertEquals("c@example.com", info.username)
        assertTrue(info.isPremium)
        assertEquals("Plan Essential", info.detail)
    }

    @Test
    fun `401 maps to AuthInvalid`() {
        val (p, _) = provider { HttpResponse(401, """{"success":false,"error":"bad token","detail":""}""") }
        val result = kotlinx.coroutines.runBlocking { p.listDownloads() }
        assertTrue(result.exceptionOrNull() is DebridError.AuthInvalid)
    }

    @Test
    fun `429 maps to RateLimited with retry-after`() {
        val (p, _) = provider {
            HttpResponse(429, "", mapOf("Retry-After" to "30"))
        }
        val result = kotlinx.coroutines.runBlocking { p.listDownloads() }
        val err = result.exceptionOrNull() as? DebridError.RateLimited
        assertTrue(err != null)
        assertEquals(30L, err?.retryAfterSecs)
    }

    @Test
    fun `checkCached returns cached entries`() {
        val (p, _) = provider { req ->
            assertTrue(req.url.contains("format=object"))
            HttpResponse(200, envelope("""{"aabbcc":{"name":"Dune","size":100,"hash":"aabbcc"}}"""))
        }
        val map = kotlinx.coroutines.runBlocking {
            p.checkCached(listOf("AABBCC"))
        }.getOrThrow()
        assertEquals(1, map?.size)
        assertEquals("Dune", map?.get("aabbcc")?.name)
        assertTrue(map?.get("aabbcc")?.ready == true)
    }

    @Test
    fun `control returns UnsupportedCapability`() {
        val (p, _) = provider { HttpResponse(200, envelope("true")) }
        val result = kotlinx.coroutines.runBlocking { p.control("11", ControlAction.PAUSE) }
        assertTrue(result.exceptionOrNull() is DebridError.UnsupportedCapability)
    }

    @Test
    fun `delete routes by kind`() {
        val (p, transport) = provider { req ->
            when {
                req.url.contains("/torrents/mylist") ->
                    HttpResponse(200, envelope("""[{"id":11,"hash":"aabbcc","name":"Dune","size":100,"download_state":"cached","progress":1,"download_finished":true,"created_at":"2026-11-14T10:00:00Z","files":[]}]"""))
                req.url.contains("/webdl/mylist") -> HttpResponse(200, envelope("[]"))
                req.url.contains("/usenet/mylist") -> HttpResponse(200, envelope("[]"))
                req.url.contains("controltorrent") -> HttpResponse(200, envelope("true"))
                else -> HttpResponse(404, "{}")
            }
        }
        val result = kotlinx.coroutines.runBlocking { p.delete("11") }
        assertTrue(result.isSuccess)
        val del = transport.requests.first { it.url.contains("controltorrent") }
        assertTrue(del.jsonBody?.contains("\"operation\":\"delete\"") == true)
        assertTrue(del.jsonBody?.contains("\"torrent_id\":11") == true)
    }

    @Test
    fun `add magnet posts multipart and resolves the created item`() {
        val (p, transport) = provider { req ->
            when {
                req.url.contains("createtorrent") ->
                    HttpResponse(200, envelope("""{"torrent_id":99,"hash":"aabbcc"}"""))
                req.url.contains("/torrents/mylist") ->
                    HttpResponse(200, envelope("""[{"id":99,"hash":"aabbcc","name":"Dune","size":100,"download_state":"queued","progress":0,"download_finished":false,"created_at":"2026-11-15T10:00:00Z","files":[]}]"""))
                req.url.contains("/webdl/mylist") -> HttpResponse(200, envelope("[]"))
                req.url.contains("/usenet/mylist") -> HttpResponse(200, envelope("[]"))
                else -> HttpResponse(404, "{}")
            }
        }
        val added = kotlinx.coroutines.runBlocking {
            p.add(AddInput.Magnet("magnet:?xt=urn:btih:aabbcc"))
        }.getOrThrow()
        assertEquals("99", added.id)
        assertEquals(DownloadStatus.QUEUED, added.status)
        val create = transport.requests.first { it.url.contains("createtorrent") }
        assertEquals("POST", create.method)
        assertEquals("${TorBoxProvider.BASE}/torrents/createtorrent", create.url)
        assertEquals(emptyMap<String, String>(), create.formFields)
        val magnet = create.multipart.single()
        assertEquals("magnet", magnet.name)
        assertEquals(null, magnet.fileName)
        assertEquals("magnet:?xt=urn:btih:aabbcc", magnet.bytes.decodeToString())
    }





    // Real /webdl/hosters shape: objects with name + domains arrays + status.
    // Turbobit is disabled here; the plain string keeps the legacy branch covered.
    private val hostersJson = """
        [
          {"name":"Rapidgator","domains":["rapidgator.net","rg.to"],"status":true},
          {"name":"1Fichier","domains":["1Fichier.COM","www.1fichier.com","alterupload.com"],"status":true},
          {"name":"Turbobit","domains":["turbobit.net","turbo.to"],"status":false},
          "legacy.example"
        ]
    """.trimIndent()

    @Test
    fun `supportsUrl matches hoster domains`() {
        val (p, _) = provider { req ->
            if (req.url.contains("/webdl/hosters")) {
                HttpResponse(200, envelope(hostersJson))
            } else HttpResponse(404, "{}")
        }
        assertTrue(kotlinx.coroutines.runBlocking { p.supportsUrl("https://rapidgator.net/file/abc") }.getOrThrow())
        assertTrue(kotlinx.coroutines.runBlocking { p.supportsUrl("https://rg.to/file/abc") }.getOrThrow())
        assertTrue(kotlinx.coroutines.runBlocking { p.supportsUrl("https://sub.1fichier.com/abc") }.getOrThrow())
        assertFalse(kotlinx.coroutines.runBlocking { p.supportsUrl("https://turbobit.net/abc") }.getOrThrow())
        assertFalse(kotlinx.coroutines.runBlocking { p.supportsUrl("https://example.org/x") }.getOrThrow())
    }

    @Test
    fun `hosterDomains parses real hoster objects not display names`() = kotlinx.coroutines.runBlocking {
        val (p, _) = provider { req ->
            if (req.url.contains("/webdl/hosters")) HttpResponse(200, envelope(hostersJson))
            else HttpResponse(404, "{}")
        }
        val domains = p.hosterDomains()
        assertEquals(
            setOf("rapidgator.net", "rg.to", "1fichier.com", "alterupload.com", "legacy.example"),
            domains,
        )
        assertFalse("Rapidgator" in domains)
        assertFalse("1Fichier" in domains)
        assertFalse("turbobit.net" in domains)
        assertFalse("turbo.to" in domains)
        for (url in listOf("https://rapidgator.net/file/abc", "https://rg.to/file/abc", "https://1fichier.com/abc")) {
            assertTrue(ShareClassifier.classify(ShareInput(text = url), domains) is ClassifiedShare.HosterLink)
        }
        assertTrue(
            ShareClassifier.classify(ShareInput(text = "https://unknown.example/abc"), domains)
                is ClassifiedShare.UnsupportedHoster,
        )
        assertTrue(p.supportsUrl("https://rapidgator.net/file/abc").getOrThrow())
        assertFalse(p.supportsUrl("https://turbobit.net/abc").getOrThrow())
    }

    @Test
    fun `hoster discovery retries after a transient error then caches success`() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val (provider, _) = provider {
            if (++calls == 1) HttpResponse(503, "") else HttpResponse(200, envelope("""[{"name":"Example","domains":["example.com"],"status":true}]"""))
        }
        assertTrue(provider.hosterDomains().isEmpty())
        assertEquals(setOf("example.com"), provider.hosterDomains())
        assertEquals(setOf("example.com"), provider.hosterDomains())
        assertEquals(2, calls)
    }

    @Test
    fun `requestdl plays each namespace with the exact file and both credentials`() {
        for ((kind, path, idField) in listOf(
            Triple(DownloadKind.TORRENT, "torrents", "torrent_id"),
            Triple(DownloadKind.WEB, "webdl", "web_id"),
            Triple(DownloadKind.USENET, "usenet", "usenet_id"),
        )) {
            val link = "https://cdn.example.com/video?token=test-key-123"
            val (p, transport) = provider { request ->
                assertTrue(request.url.startsWith("${TorBoxProvider.BASE}/$path/requestdl?"))
                assertEquals("GET", request.method)
                assertEquals("Bearer test-key-123", request.headers["Authorization"])
                val query = java.net.URI(request.url).query.split("&").associate {
                    it.substringBefore("=") to it.substringAfter("=")
                }
                assertEquals(mapOf("token" to "test-key-123", idField to "11", "file_id" to "7"), query)
                HttpResponse(200, envelope("\"$link\""))
            }
            val download = DebridDownload("11", DebridService.TORBOX, "Movie", 100, DownloadStatus.READY, 1f, kind = kind)
            val stream = kotlinx.coroutines.runBlocking { p.getStreamUrl(download, "7") }.getOrThrow()
            assertEquals(link, stream.url)
            assertFalse(stream.toString().contains(link))
            assertFalse(stream.toString().contains("test-key-123"))
            assertFalse(transport.requests.toString().contains("test-key-123"))
            assertEquals(1, transport.requests.size)
            assertTrue(transport.requests.none { it.url.contains("/stream/") })
        }
    }

    @Test
    fun `ID lookup accepts single and keyed objects and preserves file size and ID`() {
        for (keyed in listOf(false, true)) {
            val item = """{"id":11,"name":"Movie","size":3600000000,"download_finished":true,"files":[{"id":7,"name":"folder/movie.mkv","size":3500000000}]}"""
            val (p, transport) = provider { req ->
                if (req.url.contains("mylist")) HttpResponse(200, envelope(if (keyed) """{"11":$item}""" else item))
                else HttpResponse(200, envelope("\"https://example.com/play\""))
            }
            val detail = kotlinx.coroutines.runBlocking { p.getDownload("11") }.getOrThrow()
            assertEquals("7", detail.files.single().id)
            assertEquals("movie.mkv", detail.files.single().name)
            assertEquals(3_500_000_000L, detail.files.single().sizeBytes)
            assertEquals(3_600_000_000L, detail.sizeBytes)
            assertTrue(transport.requests.single().url.contains("bypass_cache=true"))
            assertEquals("https://example.com/play", kotlinx.coroutines.runBlocking { p.getStreamUrl("11", "7") }.getOrThrow().url)
            assertTrue(transport.requests.last().url.contains("file_id=7"))
        }
    }

    @Test
    fun `plan restricted requestdl preserves provider detail and only 401 blames key`() {
        for (status in listOf(200, 403)) {
            val (p, _) = provider { HttpResponse(status, """{"success":false,"error":"PLAN_RESTRICTED_FEATURE","detail":"User is not allowed to use web streaming. Please upgrade your plan."}""") }
            val error = kotlinx.coroutines.runBlocking { p.getStreamUrl(readyDownload(), "7") }.exceptionOrNull() as DebridError.Provider
            assertEquals("PLAN_RESTRICTED_FEATURE", error.code)
            assertEquals("User is not allowed to use web streaming. Please upgrade your plan.", error.detail)
        }
        for (status in listOf(401, 403)) {
            val (p, transport) = provider { HttpResponse(status, """{"success":false,"error":"BAD_TOKEN","detail":"Token invalid"}""") }
            val error = kotlinx.coroutines.runBlocking { p.getStreamUrl(readyDownload(), "7") }.exceptionOrNull()
            assertEquals(status == 401, error is DebridError.AuthInvalid)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `malformed response and unsafe schemes never expose a playable URL`() {
        for (payload in listOf("{}", "null", "\"file:///private/movie\"", "\"https://user:password@example.com/movie\"")) {
            val (p, _) = provider { HttpResponse(200, envelope(payload)) }
            val result = kotlinx.coroutines.runBlocking { p.getStreamUrl(readyDownload(), "7") }
            assertTrue(result.exceptionOrNull() is DebridError.Provider)
            assertFalse(result.exceptionOrNull()!!.message!!.contains(payload))
        }
    }

    @Test
    fun `requestdl provider and transport errors redact bearer links`() {
        val link = "https://example.com/requestdl?token=test-key-123"
        for (transportFailure in listOf(false, true)) {
            val (p, _) = provider {
                if (transportFailure) throw java.io.IOException("Failed $link")
                HttpResponse(400, """{"success":false,"error":"playback","detail":"Failed $link"}""")
            }
            val error = kotlinx.coroutines.runBlocking { p.getStreamUrl(readyDownload(), "7") }.exceptionOrNull()!!
            assertFalse(error.stackTraceToString().contains(link))
            assertFalse(error.stackTraceToString().contains("test-key-123"))
        }
    }

    @Test
    fun `torrent file is a binary multipart upload with its filename`() {
        val bytes = "d4:infod4:name4:testee".toByteArray()
        val (p, transport) = provider { request ->
            if (request.method == "POST") HttpResponse(200, envelope("""{"torrent_id":99}"""))
            else HttpResponse(200, envelope("[]"))
        }
        val added = kotlinx.coroutines.runBlocking { p.add(AddInput.TorrentFile(bytes, "test.torrent")) }.getOrThrow()
        assertEquals("99", added.id)
        val create = transport.requests.first()
        assertEquals("${TorBoxProvider.BASE}/torrents/createtorrent", create.url)
        val file = create.multipart.single()
        assertEquals("file", file.name)
        assertEquals("test.torrent", file.fileName)
        assertEquals("application/octet-stream", file.contentType)
        assertTrue(bytes.contentEquals(file.bytes))
        assertTrue(create.formFields.isEmpty()) // seed and allow_zip use account/server defaults.
    }

    @Test
    fun `magnet is extracted without encoding the whole URI or decoding tracker parameters`() {
        val magnet = "magnet:?xt=urn:btih:aabbcc&dn=Film%20Name&tr=https%3A%2F%2Ftracker.example%2Fa%3Fx%3D1%26y%3D2"
        val (p, transport) = provider { request ->
            if (request.method == "POST") HttpResponse(200, envelope("""{"torrent_id":99}"""))
            else HttpResponse(200, envelope("[]"))
        }
        kotlinx.coroutines.runBlocking { p.add(AddInput.Magnet("Watch this: <$magnet>\nShared from browser")) }.getOrThrow()
        assertEquals(magnet, transport.requests.first().multipart.single().bytes.decodeToString())
    }

    @Test
    fun `BOZO_TORRENT refusal retains the provider detail and never resolves a download`() {
        for (status in listOf(200, 400, 500)) {
            val (p, transport) = provider {
                HttpResponse(status, """{"success":false,"error":"BOZO_TORRENT","detail":"The torrent sent is not a valid torrent.","data":null}""")
            }
            val error = kotlinx.coroutines.runBlocking { p.add(AddInput.Magnet("magnet:?xt=urn:btih:bad")) }
                .exceptionOrNull() as DebridError.Provider
            assertEquals("BOZO_TORRENT", error.code)
            assertEquals("The torrent sent is not a valid torrent.", error.detail)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `mylist parses ETA and swarm counts and seeding is not downloading`() {
        val states = listOf("downloading", "uploading", "paused", "queued", "cached")
        val (p, _) = provider { request ->
            val rows = states.mapIndexed { i, state ->
                """{"id":$i,"download_state":"$state","progress":0.5,"eta":150,"seeds":12,"peers":0,"download_speed":12000}"""
            }.joinToString(",", "[", "]")
            HttpResponse(200, envelope(if (request.url.contains("/torrents/")) rows else "[]"))
        }
        val list = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow()
        assertEquals(150L, list.first().etaSeconds)
        assertEquals(12, list.first().seeds)
        assertEquals(0, list.first().peers)
        assertEquals(DownloadStatus.DOWNLOADING, list[0].status)
        assertEquals(DownloadStatus.READY, list[1].status)
        assertEquals(DownloadStatus.PAUSED, list[2].status)
        assertEquals(DownloadStatus.QUEUED, list[3].status)
    }

    @Test
    fun `missing null zero and negative stats remain unavailable`() {
        for (fields in listOf("", ",\"eta\":null,\"download_speed\":null", ",\"eta\":0,\"download_speed\":0", ",\"eta\":-1,\"download_speed\":-1,\"seeds\":-1,\"peers\":-1")) {
            val (p, _) = provider { request ->
                HttpResponse(200, envelope(if (request.url.contains("/torrents/"))
                    """[{"id":1,"download_state":"downloading"$fields}]""" else "[]"))
            }
            val row = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow().single()
            assertEquals(null, row.etaSeconds)
            assertEquals(null, row.downloadSpeedBytesPerSec)
            assertEquals(null, row.seeds)
            assertEquals(null, row.peers)
        }
    }

    private fun readyDownload() = DebridDownload("11", DebridService.TORBOX, "Movie", 100,
        DownloadStatus.READY, 1f, kind = DownloadKind.TORRENT)
}
