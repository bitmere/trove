package app.trove.debrid.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBoxProviderTest {

    private fun provider(
        apiKey: String = "test-key-123",
        handler: (HttpRequest) -> HttpResponse,
    ): Pair<TorBoxProvider, RecordingTransport> {
        val transport = RecordingTransport(handler)
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
    fun `torrent error wins when web fails first`() {
        val webResponded = CompletableDeferred<Unit>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse =
                when {
                    request.url.contains("/torrents/mylist") -> {
                        webResponded.await()
                        HttpResponse(503, """{"success":false,"error":"torrent_failure"}""")
                    }
                    request.url.contains("/webdl/mylist") -> {
                        webResponded.complete(Unit)
                        HttpResponse(503, """{"success":false,"error":"web_failure"}""")
                    }
                    else -> HttpResponse(200, envelope("[]"))
                }
        }
        val p = TorBoxProvider("test-key-123", transport)

        val result = kotlinx.coroutines.runBlocking { withTimeout(2_000) { p.listDownloads() } }

        assertEquals("torrent_failure", (result.exceptionOrNull() as? DebridError.Provider)?.code)
    }

    @Test
    fun `web list failure fails the download list`() {
        val (p, _) = provider { request ->
            if (request.url.contains("/webdl/mylist")) {
                HttpResponse(503, """{"success":false,"error":"web_failure"}""")
            } else {
                HttpResponse(200, envelope("[]"))
            }
        }

        val result = kotlinx.coroutines.runBlocking { p.listDownloads() }

        assertEquals("web_failure", (result.exceptionOrNull() as? DebridError.Provider)?.code)
    }

    @Test
    fun `usenet failure keeps both torrent and web rows in date order`() {
        val (p, _) = provider { request ->
            when {
                request.url.contains("/torrents/mylist") -> HttpResponse(200, envelope(torrentListJson))
                request.url.contains("/webdl/mylist") -> HttpResponse(
                    200,
                    envelope("""[{"id":21,"name":"Web row","created_at":"2026-11-15T09:30:00Z"}]"""),
                )
                else -> HttpResponse(403, """{"success":false,"error":"pro"}""")
            }
        }

        val rows = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow()

        assertEquals(listOf("13", "21", "12", "11"), rows.map { it.id })
        assertEquals(DownloadKind.WEB, rows[1].kind)
    }

    @Test
    fun `all three mylist calls overlap`() {
        val expectedPaths = setOf("/torrents/mylist", "/webdl/mylist", "/usenet/mylist")
        val arrived = mutableSetOf<String>()
        val allArrived = CompletableDeferred<Unit>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                val path = request.url.removePrefix(TorBoxProvider.BASE).substringBefore('?')
                assertTrue(path in expectedPaths && arrived.add(path))
                if (arrived == expectedPaths) allArrived.complete(Unit)
                allArrived.await()
                return HttpResponse(200, envelope("[]"))
            }
        }
        val p = TorBoxProvider("test-key-123", transport)

        val rows = kotlinx.coroutines.runBlocking { withTimeout(2_000) { p.listDownloads() } }.getOrThrow()

        assertTrue(rows.isEmpty())
        assertEquals(expectedPaths, arrived)
    }

    @Test
    fun `three mylist calls finish within the default bucket burst`() {
        val (p, transport) = provider { HttpResponse(200, envelope("[]")) }

        // A missing burst permit takes about 200 ms to refill at five permits per second.
        val rows = kotlinx.coroutines.runBlocking { withTimeout(150) { p.listDownloads() } }.getOrThrow()

        assertTrue(rows.isEmpty())
        assertEquals(3, transport.requests.size)
    }

    private fun HttpRequest.queryParameters(): Map<String, String> =
        java.net.URI(url).rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
            it.substringBefore("=") to it.substringAfter("=")
        }

    private fun pagedTorrents(vararg pages: List<Int>): Pair<TorBoxProvider, RecordingTransport> {
        val transport = RecordingTransport { request ->
            assertTrue(request.url.contains("/mylist"))
            val rows = if (request.url.contains("/torrents/mylist")) {
                val offset = request.queryParameters().getValue("offset").toInt()
                assertEquals(0, offset % 2)
                pages[offset / 2].joinToString(",", "[", "]") { id ->
                    """{"id":$id,"name":"Item $id at offset $offset"}"""
                }
            } else "[]"
            HttpResponse(200, envelope(rows))
        }
        return TorBoxProvider("test-key-123", transport, listPageSize = 2) to transport
    }

    @Test
    fun `listDownloads accumulates three torrent pages in server order`() {
        val (p, transport) = pagedTorrents(listOf(1, 2), listOf(3, 4), listOf(5))
        val rows = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow()
        assertEquals(listOf("1", "2", "3", "4", "5"), rows.map { it.id })
        assertTrue(rows.all { it.kind == DownloadKind.TORRENT })
        val queries = transport.requests.filter { it.url.contains("/torrents/mylist") }
            .map { it.queryParameters() }
        assertEquals(listOf("0", "2", "4"), queries.map { it["offset"] })
        assertTrue(queries.all { it["limit"] == "2" && "bypass_cache" !in it })
    }

    @Test
    fun `fresh list stops after a short page and bypasses cache on both pages`() {
        val (p, transport) = pagedTorrents(listOf(1, 2), listOf(3))
        val rows = kotlinx.coroutines.runBlocking { p.listDownloadsFresh() }.getOrThrow()
        assertEquals(listOf("1", "2", "3"), rows.map { it.id })
        val queries = transport.requests.filter { it.url.contains("/torrents/mylist") }
            .map { it.queryParameters() }
        assertEquals(listOf("0", "2"), queries.map { it["offset"] })
        assertTrue(queries.all { it["bypass_cache"] == "true" })
    }

    @Test
    fun `listDownloads keeps the first occurrence and counts duplicates toward page size`() {
        val (p, _) = pagedTorrents(listOf(1, 2), listOf(2, 3), listOf(4))
        val rows = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow()
        assertEquals(listOf("1", "2", "3", "4"), rows.map { it.id })
        assertEquals("Item 2 at offset 0", rows.single { it.id == "2" }.name)
    }

    @Test
    fun `empty torrent list stops after its first page`() {
        val (p, transport) = pagedTorrents(emptyList())
        val rows = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow()
        assertTrue(rows.isEmpty())
        val request = transport.requests.single { it.url.contains("/torrents/mylist") }
        assertEquals("0", request.queryParameters()["offset"])
    }

    @Test
    fun `getDownload makes one ID request per kind attempted`() {
        val transport = RecordingTransport { request ->
            val rows = if (request.url.contains("/webdl/mylist")) {
                """{"id":11,"name":"Web download"}"""
            } else {
                """[{"id":1},{"id":2}]"""
            }
            HttpResponse(200, envelope(rows))
        }
        val p = TorBoxProvider("test-key-123", transport, listPageSize = 2)
        val row = kotlinx.coroutines.runBlocking { p.getDownload("11") }.getOrThrow()
        assertEquals("11", row.id)
        assertEquals(DownloadKind.WEB, row.kind)
        assertEquals(
            listOf("/v1/api/torrents/mylist", "/v1/api/webdl/mylist"),
            transport.requests.map { java.net.URI(it.url).path },
        )
        for (request in transport.requests) {
            val query = request.queryParameters()
            assertEquals("11", query["id"])
            assertEquals("true", query["bypass_cache"])
            assertFalse("offset" in query)
        }
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

    // MD5("https://example.com/file.zip"), pinned with an independent
    // hashlib vector — TorBox's webdl `hash` is the MD5 of the link.
    private val webLink = "https://example.com/file.zip"
    private val webLinkMd5 = "0dfbb8e892f2c02333149d2846b5488e"

    private fun webRowJson(id: Long, hash: String) =
        """{"id":$id,"hash":"$hash","name":"file.zip","download_state":"queued","progress":0,"download_finished":false,"created_at":"2026-11-15T10:00:00Z","files":[]}"""

    @Test
    fun `webdl add posts to the sync endpoint and resolves a numeric id`() {
        val (p, transport) = provider { req ->
            when {
                req.url.contains("createwebdownload") ->
                    HttpResponse(200, envelope("""{"webdownload_id":42}"""))
                req.url.contains("/webdl/mylist") ->
                    HttpResponse(200, envelope("""[${webRowJson(42, webLinkMd5)}]"""))
                else -> HttpResponse(404, "{}")
            }
        }
        val added = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }.getOrThrow()
        assertEquals("42", added.id)
        assertEquals(DownloadKind.WEB, added.kind)
        assertEquals("file.zip", added.name)
        val create = transport.requests.first { it.url.contains("createwebdownload") }
        assertEquals("${TorBoxProvider.BASE}/webdl/createwebdownload", create.url)
        assertEquals("POST", create.method)
        assertEquals(mapOf("link" to webLink), create.formFields)
        assertTrue(transport.requests.none { it.url.contains("async") })
    }

    @Test
    fun `webdl add resolves a string id and a queued id`() {
        for (data in listOf("""{"webdownload_id":"42"}""", """{"queued_id":7}""")) {
            val expectedId = if (data.contains("queued")) "7" else "42"
            val (p, transport) = provider { req ->
                when {
                    req.url.contains("createwebdownload") -> HttpResponse(200, envelope(data))
                    req.url.contains("/webdl/mylist") ->
                        HttpResponse(200, envelope("""[${webRowJson(expectedId.toLong(), webLinkMd5)}]"""))
                    else -> HttpResponse(404, "{}")
                }
            }
            val added = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }.getOrThrow()
            assertEquals(expectedId, added.id)
            assertTrue(transport.requests.single { it.url.contains("createwebdownload") }
                .url.endsWith("/webdl/createwebdownload"))
        }
    }

    @Test
    fun `webdl add recovers a hash-only create from mylist with one create call`() {
        val (p, transport) = provider { req ->
            when {
                req.url.contains("createwebdownload") ->
                    HttpResponse(200, envelope("""{"hash":"$webLinkMd5"}"""))
                req.url.contains("/webdl/mylist") ->
                    HttpResponse(200, envelope("""[${webRowJson(42, webLinkMd5)}]"""))
                else -> HttpResponse(404, "{}")
            }
        }
        val added = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }.getOrThrow()
        assertEquals("42", added.id)
        assertEquals(DownloadKind.WEB, added.kind)
        assertEquals(DownloadStatus.QUEUED, added.status)
        assertEquals(1, transport.requests.count { it.url.contains("createwebdownload") })
        val lookup = transport.requests.single { it.url.contains("/webdl/mylist") }
        assertEquals("true", lookup.queryParameters()["bypass_cache"])
    }

    @Test
    fun `webdl add recovers null data from mylist`() {
        val (p, transport) = provider { req ->
            when {
                req.url.contains("createwebdownload") -> HttpResponse(200, envelope("null"))
                req.url.contains("/webdl/mylist") ->
                    HttpResponse(200, envelope("""[${webRowJson(42, webLinkMd5)}]"""))
                else -> HttpResponse(404, "{}")
            }
        }
        val added = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }.getOrThrow()
        assertEquals("42", added.id)
    }

    @Test
    fun `webdl add throws the no-id error when mylist has no match`() {
        val (p, transport) = provider { req ->
            when {
                req.url.contains("createwebdownload") -> HttpResponse(200, envelope("{}"))
                req.url.contains("/webdl/mylist") -> HttpResponse(200, envelope("[]"))
                else -> HttpResponse(404, "{}")
            }
        }
        val error = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }
            .exceptionOrNull() as DebridError.Provider
        assertEquals("create", error.code)
        assertEquals("TorBox returned no web download id", error.detail)
        assertEquals(1, transport.requests.count { it.url.contains("createwebdownload") })
        assertEquals(1, transport.requests.count { it.url.contains("/webdl/mylist") })
    }

    @Test
    fun `webdl add surfaces TorBox detail on a no-id create`() {
        val (p, _) = provider { req ->
            when {
                req.url.contains("createwebdownload") -> HttpResponse(
                    200,
                    """{"success":true,"error":null,"detail":"Link accepted but still processing","data":{}}""",
                )
                req.url.contains("/webdl/mylist") -> HttpResponse(200, envelope("[]"))
                else -> HttpResponse(404, "{}")
            }
        }
        val error = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }
            .exceptionOrNull() as DebridError.Provider
        assertEquals("create", error.code)
        assertEquals("Link accepted but still processing", error.detail)
    }

    @Test
    fun `usenet add posts to the sync endpoint and resolves a numeric id`() {
        val bytes = "nzb-bytes".toByteArray()
        val (p, transport) = provider { req ->
            when {
                req.url.contains("createusenetdownload") ->
                    HttpResponse(200, envelope("""{"usenetdownload_id":55}"""))
                req.url.contains("/usenet/mylist") ->
                    HttpResponse(200, envelope("""[{"id":55,"hash":"aabbcc","name":"show.nzb","download_state":"queued","progress":0,"download_finished":false,"created_at":"2026-11-15T10:00:00Z","files":[]}]"""))
                else -> HttpResponse(404, "{}")
            }
        }
        val added = kotlinx.coroutines.runBlocking { p.add(AddInput.Nzb(bytes, "show.nzb")) }.getOrThrow()
        assertEquals("55", added.id)
        assertEquals(DownloadKind.USENET, added.kind)
        val create = transport.requests.first { it.url.contains("createusenetdownload") }
        assertEquals("${TorBoxProvider.BASE}/usenet/createusenetdownload", create.url)
        assertEquals("POST", create.method)
        val file = create.multipart.single()
        assertEquals("file", file.name)
        assertEquals("show.nzb", file.fileName)
        assertEquals("application/x-nzb", file.contentType)
        assertTrue(bytes.contentEquals(file.bytes))
        assertTrue(transport.requests.none { it.url.contains("async") })
    }

    @Test
    fun `usenet add resolves a queued id`() {
        val (p, _) = provider { req ->
            when {
                req.url.contains("createusenetdownload") -> HttpResponse(200, envelope("""{"queued_id":9}"""))
                req.url.contains("/usenet/mylist") ->
                    HttpResponse(200, envelope("""[{"id":9,"hash":"aabbcc","name":"show.nzb","download_state":"queued"}]"""))
                else -> HttpResponse(404, "{}")
            }
        }
        val added = kotlinx.coroutines.runBlocking {
            p.add(AddInput.Nzb("nzb".toByteArray(), "show.nzb"))
        }.getOrThrow()
        assertEquals("9", added.id)
    }

    @Test
    fun `usenet add recovers a hash-only create from mylist`() {
        val hash = "abcdef1234567890abcdef1234567890"
        val (p, transport) = provider { req ->
            when {
                req.url.contains("createusenetdownload") ->
                    HttpResponse(200, envelope("""{"hash":"$hash"}"""))
                req.url.contains("/usenet/mylist") ->
                    HttpResponse(200, envelope("""[{"id":61,"hash":"$hash","name":"show.nzb","download_state":"queued"}]"""))
                else -> HttpResponse(404, "{}")
            }
        }
        val added = kotlinx.coroutines.runBlocking {
            p.add(AddInput.Nzb("nzb".toByteArray(), "show.nzb"))
        }.getOrThrow()
        assertEquals("61", added.id)
        assertEquals(DownloadKind.USENET, added.kind)
        assertEquals(1, transport.requests.count { it.url.contains("createusenetdownload") })
        val lookup = transport.requests.single { it.url.contains("/usenet/mylist") }
        assertEquals("true", lookup.queryParameters()["bypass_cache"])
    }

    @Test
    fun `usenet add without a hash throws the no-id error without a mylist lookup`() {
        val (p, transport) = provider { req ->
            when {
                req.url.contains("createusenetdownload") -> HttpResponse(200, envelope("{}"))
                else -> HttpResponse(404, "{}")
            }
        }
        val error = kotlinx.coroutines.runBlocking {
            p.add(AddInput.Nzb("nzb".toByteArray(), "show.nzb"))
        }.exceptionOrNull() as DebridError.Provider
        assertEquals("create", error.code)
        assertEquals("TorBox returned no Usenet id", error.detail)
        assertTrue(transport.requests.none { it.url.contains("/usenet/mylist") })
    }

    @Test
    fun `duplicate webdl surfaces the real detail with no mylist lookup`() {
        val (p, transport) = provider { req ->
            when {
                req.url.contains("createwebdownload") -> HttpResponse(
                    200,
                    """{"success":false,"error":"DUPLICATE_ITEM","detail":"This item already exists.","data":null}""",
                )
                else -> HttpResponse(404, "{}")
            }
        }
        val error = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }
            .exceptionOrNull() as DebridError.Provider
        assertEquals("DUPLICATE_ITEM", error.code)
        assertEquals("This item already exists.", error.detail)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `422 detail array renders the first validation message`() {
        val (p, _) = provider { req ->
            when {
                req.url.contains("createwebdownload") -> HttpResponse(
                    422,
                    """{"success":false,"error":"VALIDATION","detail":[{"loc":["body","link"],"msg":"Field required","type":"missing"}]}""",
                )
                else -> HttpResponse(404, "{}")
            }
        }
        val error = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }
            .exceptionOrNull() as DebridError.Provider
        assertEquals("VALIDATION", error.code)
        assertEquals("body.link: Field required", error.detail)
    }

    @Test
    fun `diagnostic sink logs one redacted line on a no-id create`() {
        val lines = mutableListOf<String>()
        val transport = RecordingTransport { req ->
            when {
                req.url.contains("createwebdownload") -> HttpResponse(200, envelope("{}"))
                req.url.contains("/webdl/mylist") -> HttpResponse(200, envelope("[]"))
                else -> HttpResponse(404, "{}")
            }
        }
        val p = TorBoxProvider("test-key-123", transport, diagnosticLog = { lines += it })
        val error = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }
            .exceptionOrNull() as DebridError.Provider
        assertEquals("TorBox returned no web download id", error.detail)
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.contains("/webdl/createwebdownload"))
        assertTrue(line.contains("idFields=[]"))
        assertFalse(line.contains(webLink))
        assertFalse(line.contains("test-key-123"))
    }

    @Test
    fun `diagnostic sink stays silent on a successful create`() {
        val lines = mutableListOf<String>()
        val transport = RecordingTransport { req ->
            when {
                req.url.contains("createwebdownload") ->
                    HttpResponse(200, envelope("""{"webdownload_id":42}"""))
                req.url.contains("/webdl/mylist") ->
                    HttpResponse(200, envelope("""[${webRowJson(42, webLinkMd5)}]"""))
                else -> HttpResponse(404, "{}")
            }
        }
        val p = TorBoxProvider("test-key-123", transport, diagnosticLog = { lines += it })
        val added = kotlinx.coroutines.runBlocking { p.add(AddInput.HosterLink(webLink)) }.getOrThrow()
        assertEquals("42", added.id)
        assertTrue(lines.isEmpty())
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
            val adapter: DebridProvider = p
            val stream = kotlinx.coroutines.runBlocking {
                adapter.getStreamUrl(DownloadRef(download.kind, download.id), "7")
            }.getOrThrow()
            assertEquals(link, stream.url)
            assertFalse(stream.toString().contains(link))
            assertFalse(stream.toString().contains("test-key-123"))
            assertFalse(transport.requests.toString().contains("test-key-123"))
            assertEquals(1, transport.requests.size)
            assertTrue(transport.requests.none { it.url.contains("/stream/") })
        }
    }

    @Test
    fun `reference looks up only the selected kind when IDs overlap`() {
        for ((kind, namespace) in listOf(
            DownloadKind.TORRENT to "torrents",
            DownloadKind.WEB to "webdl",
            DownloadKind.USENET to "usenet",
        )) {
            val (provider, transport) = provider {
                HttpResponse(200, envelope("""{"id":11,"name":"Movie"}"""))
            }
            val adapter: DebridProvider = provider
            val detail = kotlinx.coroutines.runBlocking {
                adapter.getDownload(DownloadRef(kind, "11")).getOrThrow()
            }
            assertEquals(kind, detail.kind)
            assertEquals("11", detail.id)
            assertEquals("${TorBoxProvider.BASE}/$namespace/mylist", transport.requests.single().url.substringBefore('?'))
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
    fun `metaDL stalled and checking_resume_data map to downloading with progress`() {
        val states = listOf("metaDL", "stalled (no seeds)", "checkingResumeData", "checking_resume_data")
        val (p, _) = provider { request ->
            val rows = states.mapIndexed { i, state ->
                """{"id":$i,"download_state":"$state","progress":0.37,"eta":900,"seeds":3,"peers":5,"download_speed":8000}"""
            }.joinToString(",", "[", "]")
            HttpResponse(200, envelope(if (request.url.contains("/torrents/")) rows else "[]"))
        }
        val list = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow()
        assertEquals(states.size, list.size)
        for (row in list) {
            assertEquals(DownloadStatus.DOWNLOADING, row.status)
            assertEquals(0.37f, row.progress, 0.001f)
            assertEquals(900L, row.etaSeconds)
            assertEquals(3, row.seeds)
            assertEquals(5, row.peers)
            assertEquals(8_000L, row.downloadSpeedBytesPerSec)
        }
    }

    @Test
    // The old expectations encoded the bug: READY at 1.0 hid the bar; rewritten deliberately.
    fun `active download state beats cached and present flags`() {
        val rowsJson = """[
            {"id":1,"download_state":"completed","download_finished":false,"progress":0.4},
            {"id":2,"download_state":"completed","download_finished":true,"progress":0.4},
            {"id":3,"download_state":"downloading","download_present":true,"progress":0.4},
            {"id":4,"download_state":"downloading","cached":true,"progress":0.4},
            {"id":5,"download_state":"uploading","progress":0.4}
        ]"""
        val (p, _) = provider { request ->
            HttpResponse(200, envelope(if (request.url.contains("/torrents/mylist")) rowsJson else "[]"))
        }
        val rows = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow().associateBy { it.id }
        assertEquals(DownloadStatus.UNKNOWN, rows.getValue("1").status)
        for (id in listOf("3", "4")) {
            val row = rows.getValue(id)
            assertEquals(DownloadStatus.DOWNLOADING, row.status)
            assertEquals(0.4f, row.progress)
        }
        for (id in listOf("2", "5")) {
            val row = rows.getValue(id)
            assertEquals(DownloadStatus.READY, row.status)
            assertEquals(1f, row.progress)
        }
    }

    @Test
    fun `downloading retains reported progress despite readiness flags unless finished at full progress`() {
        val rowsJson = """[
            {"id":1,"download_state":"downloading","cached":true,"progress":0.23},
            {"id":2,"download_state":"downloading","download_present":true,"progress":42},
            {"id":3,"download_state":"downloading","download_finished":true,"progress":1.0},
            {"id":4,"download_state":"downloading","download_finished":true,"progress":100},
            {"id":5,"download_state":"downloading","download_finished":true,"progress":0.4}
        ]"""
        val (p, _) = provider { request ->
            HttpResponse(200, envelope(if (request.url.contains("/torrents/mylist")) rowsJson else "[]"))
        }
        val rows = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow().associateBy { it.id }
        for ((id, progress) in listOf("1" to 0.23f, "2" to 0.42f, "5" to 0.4f)) {
            assertEquals(DownloadStatus.DOWNLOADING, rows.getValue(id).status)
            assertEquals(progress, rows.getValue(id).progress, 0.001f)
        }
        for (id in listOf("3", "4")) {
            assertEquals(DownloadStatus.READY, rows.getValue(id).status)
            assertEquals(1f, rows.getValue(id).progress)
        }
    }

    @Test
    fun `queuedDL and active allocation checking and forced states preserve progress`() {
        val states = listOf("queuedDL", "queued_dl", "allocating", "checkingDL", "checking_dl", "forcedDL", "forced_dl")
        val (p, _) = provider { request ->
            val rows = states.mapIndexed { i, state ->
                """{"id":$i,"download_state":"$state","cached":true,"progress":${if (i == 2) 0 else 0.37}}"""
            }.joinToString(",", "[", "]")
            HttpResponse(200, envelope(if (request.url.contains("/torrents/mylist")) rows else "[]"))
        }
        val rows = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow().associateBy { it.id }
        for ((i, state) in states.withIndex()) {
            val row = rows.getValue(i.toString())
            assertEquals(if (i < 2) DownloadStatus.QUEUED else DownloadStatus.DOWNLOADING, row.status)
            assertEquals(if (i == 2) 0f else 0.37f, row.progress, 0.001f)
            assertEquals(state.lowercase(), row.rawState)
        }
    }

    @Test
    fun `completed without finished remains unknown and blank raw states are absent`() {
        val rowsJson = """[
            {"id":1,"download_state":"completed","download_finished":false,"progress":0.4},
            {"id":2,"download_state":" "},
            {"id":3}
        ]"""
        val (p, _) = provider { request ->
            HttpResponse(200, envelope(if (request.url.contains("/torrents/mylist")) rowsJson else "[]"))
        }
        val rows = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow().associateBy { it.id }
        assertEquals(DownloadStatus.UNKNOWN, rows.getValue("1").status)
        assertEquals(0.4f, rows.getValue("1").progress)
        assertEquals("completed", rows.getValue("1").rawState)
        assertEquals(null, rows.getValue("2").rawState)
        assertEquals(null, rows.getValue("3").rawState)
    }

    @Test
    fun `web mylist downloading retains progress speed and ETA`() {
        val (p, _) = provider { request ->
            val rows = """[{"id":1,"download_state":"downloading","progress":0.77,"download_speed":74000000,"eta":123}]"""
            HttpResponse(200, envelope(if (request.url.contains("/webdl/mylist")) rows else "[]"))
        }
        val row = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow().single()
        assertEquals(DownloadKind.WEB, row.kind)
        assertEquals(DownloadStatus.DOWNLOADING, row.status)
        assertEquals(0.77f, row.progress, 0.001f)
        assertEquals(74_000_000L, row.downloadSpeedBytesPerSec)
        assertEquals(123L, row.etaSeconds)
    }

    @Test
    fun `mylist exposes nullable active cached and expiry fields`() {
        val rowsJson = """[
            {"id":1,"active":true,"cached":true,"expires_at":"2026-12-01T00:00:00Z"},
            {"id":2,"download_state":"cached"},
            {"id":3,"active":false,"cached":false,"expires_at":"invalid"}
        ]"""
        val (p, _) = provider { request ->
            HttpResponse(200, envelope(if (request.url.contains("/torrents/mylist")) rowsJson else "[]"))
        }
        val rows = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow().associateBy { it.id }
        val present = rows.getValue("1")
        assertEquals(true, present.active)
        assertEquals(true, present.cached)
        assertEquals(java.time.Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), present.expiresAtEpochMs)

        val absent = rows.getValue("2")
        assertEquals(null, absent.active)
        assertEquals(null, absent.cached)
        assertEquals(null, absent.expiresAtEpochMs)

        val explicitFalse = rows.getValue("3")
        assertEquals(false, explicitFalse.active)
        assertEquals(false, explicitFalse.cached)
        assertEquals(null, explicitFalse.expiresAtEpochMs)
    }

    @Test
    fun `fresh list load bypasses the cache on all three lists and normal load does not`() {
        val (freshProvider, freshTransport) = provider {
            HttpResponse(200, envelope("[]"))
        }
        kotlinx.coroutines.runBlocking { freshProvider.listDownloadsFresh() }.getOrThrow()
        val freshLists = freshTransport.requests.filter { it.url.contains("/mylist") }
        assertEquals(3, freshLists.size)
        assertTrue(freshLists.all { it.url.contains("bypass_cache=true") })

        val (cachedProvider, cachedTransport) = provider {
            HttpResponse(200, envelope("[]"))
        }
        kotlinx.coroutines.runBlocking { cachedProvider.listDownloads() }.getOrThrow()
        val cachedLists = cachedTransport.requests.filter { it.url.contains("/mylist") }
        assertEquals(3, cachedLists.size)
        assertTrue(cachedLists.none { it.url.contains("bypass_cache") })
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

    @Test
    fun `listPage fetches one kind page with offset and limit and touches no other kind`() {
        val (p, transport) = pagedTorrents(listOf(1, 2), listOf(3, 4), listOf(5))
        val rows = kotlinx.coroutines.runBlocking { p.listPage(DownloadKind.TORRENT, 2, 2) }.getOrThrow()
        assertEquals(listOf("3", "4"), rows.rows.map { it.id })
        assertTrue(rows.rows.all { it.kind == DownloadKind.TORRENT })
        val requests = transport.requests.filter { it.url.contains("/mylist") }
        assertEquals(1, requests.size)
        val query = requests.single().queryParameters()
        assertEquals("2", query["offset"])
        assertEquals("2", query["limit"])
        assertTrue("bypass_cache" !in query)
    }

    @Test
    fun `listPage passes bypass cache through for fresh pages`() {
        val (p, transport) = pagedTorrents(listOf(1, 2), listOf(3))
        val rows = kotlinx.coroutines.runBlocking {
            p.listPage(DownloadKind.TORRENT, 0, 2, bypassCache = true)
        }.getOrThrow()
        assertEquals(listOf("1", "2"), rows.rows.map { it.id })
        val query = transport.requests.single { it.url.contains("/torrents/mylist") }.queryParameters()
        assertEquals("true", query["bypass_cache"])
    }

    @Test
    fun `listPage dedupes within a page and keeps the first row`() {
        val (p, _) = provider { request ->
            val rows = if (request.url.contains("/torrents/mylist")) {
                """[{"id":7,"name":"First"},{"id":7,"name":"Second"},{"id":8,"name":"Other"},null,{}]"""
            } else "[]"
            HttpResponse(200, envelope(rows))
        }
        val rows = kotlinx.coroutines.runBlocking { p.listPage(DownloadKind.TORRENT, 0, 3) }.getOrThrow()
        assertEquals(listOf("7", "8"), rows.rows.map { it.id })
        assertEquals(5, rows.rawCount)
        assertEquals("First", rows.rows.single { it.id == "7" }.name)
    }

    @Test
    fun `listPage returns a usenet failure instead of swallowing it`() {
        val (p, _) = provider { request ->
            if (request.url.contains("/usenet/mylist")) {
                HttpResponse(403, """{"success":false,"error":"pro","detail":"Usenet is Pro only"}""")
            } else {
                HttpResponse(200, envelope("[]"))
            }
        }
        val result = kotlinx.coroutines.runBlocking { p.listPage(DownloadKind.USENET, 0, 100) }
        assertTrue(result.isFailure)
        assertEquals("pro", (result.exceptionOrNull() as? DebridError.Provider)?.code)
        // The full sync still swallows the same failure (unchanged).
        val full = kotlinx.coroutines.runBlocking { p.listDownloads() }.getOrThrow()
        assertTrue(full.isEmpty())
    }

    @Test
    fun `a JVM Error escapes instead of mapping to a network failure`() {
        // Stage 1 decision: TorBox used to wrap non-Exception
        // throwables in Network; the shared mapError rethrows them.
        val error = OutOfMemoryError("test")
        val (p, _) = provider { throw error }
        val thrown = kotlinx.coroutines.runBlocking {
            runCatching { p.validateAccount() }.exceptionOrNull()
        }
        assertSame(error, thrown)
    }

    private fun readyDownload() = DebridDownload("11", DebridService.TORBOX, "Movie", 100,
        DownloadStatus.READY, 1f, kind = DownloadKind.TORRENT)
}
