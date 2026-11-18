package app.trove.debrid.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumizeProviderTest {
    private class FakeTransport(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun provider(handler: (HttpRequest) -> HttpResponse): Pair<PremiumizeProvider, FakeTransport> {
        val fake = FakeTransport(handler)
        return PremiumizeProvider(KEY, fake, TokenBucket(4.0, 100), TokenBucket(0.5, 20), pollDelayMs = 0) to fake
    }

    @Test
    fun `validates account with bearer header and displays booster points`() = runBlocking {
        val (provider, fake) = provider {
            assertEquals("GET" to "/account/info", it.method to it.path)
            ok(""" "customer_id":"42","premium_until":4102444800,"limit_used":0.42,"booster_points":1234 """)
        }
        assertEquals(AccountInfo("42", true, "Premium · 1234 booster points"), provider.validateAccount().getOrThrow())
        assertEquals("Bearer $KEY", fake.requests.single().headers["Authorization"])
        assertFalse(fake.requests.single().url.contains(KEY))
        assertFalse(fake.requests.single().toString().contains(KEY))
    }

    @Test
    fun `cached magnet yields distinct playable files in one request`() = runBlocking {
        val (provider, fake) = provider {
            assertEquals("POST" to "/transfer/directdl", it.method to it.path)
            assertEquals(mapOf("src" to MAGNET), it.formFields)
            ok(""" "content":[
                {"path":"Disc1/video.mkv","size":123,"link":"https://cdn.example/one"},
                {"path":"Disc2/video.mkv","size":456,"link":"https://cdn.example/two"}] """)
        }
        val download = provider.add(AddInput.Magnet(MAGNET)).getOrThrow()
        assertEquals(DebridService.PREMIUMIZE, download.service)
        assertEquals(DownloadKind.TORRENT, download.kind)
        assertEquals(DownloadStatus.READY, download.status)
        assertEquals(579L, download.sizeBytes)
        assertEquals(download, provider.getDownload(download.id).getOrThrow())
        val file = download.files.last()
        assertEquals("Disc2/video.mkv", file.path)
        assertEquals(StreamUrl("https://cdn.example/two"), provider.getStreamUrl(download.id, file.id).getOrThrow())
        assertTrue(provider.getStreamUrl(download.id, "other").exceptionOrNull() is DebridError.NotCached)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `uncached magnet creates a transfer and polls until seeding`() = runBlocking {
        var polls = 0
        val (provider, fake) = provider {
            when (it.path) {
                "/transfer/directdl" -> error("not_found", "Source is not cached")
                "/transfer/create" -> {
                    assertEquals(mapOf("src" to MAGNET), it.formFields)
                    ok(""" "id":"transfer-a","name":"Video" """)
                }
                "/transfer/list" -> if (polls++ == 0) transfer("waiting") else transfer("seeding", ""","file_id":"file-a" """)
                "/item/details" -> {
                    assertTrue(it.url.endsWith("?id=file-a"))
                    item()
                }
                else -> unexpected(it)
            }
        }
        val download = provider.add(AddInput.Magnet(MAGNET)).getOrThrow()
        assertEquals(DownloadStatus.READY, download.status)
        assertEquals(1f, download.progress, 0f)
        assertEquals("file-a", download.files.single().id)
        assertEquals(listOf("/transfer/directdl", "/transfer/create", "/transfer/list", "/transfer/list", "/item/details"),
            fake.requests.map { it.path })
        assertEquals(StreamUrl(CDN_LINK), provider.getStreamUrl(download.id, "file-a").getOrThrow())
    }

    @Test
    fun `poll window preserves an active transfer`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                "/transfer/directdl" -> ok(""" "content":[] """)
                "/transfer/create" -> ok(""" "id":"transfer-a" """)
                "/transfer/list" -> transfer("running", ""","progress":0.42""")
                else -> unexpected(it)
            }
        }
        val download = provider.add(AddInput.Magnet(MAGNET)).getOrThrow()
        assertEquals(DownloadStatus.DOWNLOADING, download.status)
        assertEquals(0.42f, download.progress, 0f)
        assertEquals(10, fake.requests.count { it.path == "/transfer/list" })
    }

    @Test
    fun `failed and cancelled transfers are cleaned up`() = runBlocking {
        for (cancelled in listOf(false, true)) {
            val (provider, fake) = provider {
                when (it.path) {
                    "/transfer/create" -> ok(""" "id":"transfer-a" """)
                    "/transfer/list" -> if (cancelled) throw CancellationException("cancelled") else transfer("error")
                    "/transfer/delete" -> ok()
                    else -> unexpected(it)
                }
            }
            if (cancelled) {
                val failure = runCatching { provider.add(AddInput.TorrentFile(byteArrayOf(1), "a.torrent")) }
                assertTrue(failure.exceptionOrNull() is CancellationException)
            } else {
                val failure = provider.add(AddInput.TorrentFile(byteArrayOf(1), "a.torrent")).exceptionOrNull()
                assertEquals("transfer-failed", (failure as DebridError.Provider).code)
            }
            assertEquals("/transfer/delete", fake.requests.last().path)
            assertEquals(mapOf("id" to "transfer-a"), fake.requests.last().formFields)
        }
    }

    @Test
    fun `hoster direct download is a WEB row and deletion removes its local entry`() = runBlocking {
        val source = "https://host.example/file"
        val (provider, fake) = provider {
            assertEquals("/transfer/directdl", it.path)
            assertEquals(source, it.formFields["src"])
            ok(""" "content":[{"path":"movie.mkv","size":123,"link":"$CDN_LINK"}] """)
        }
        val download = provider.add(AddInput.HosterLink(source)).getOrThrow()
        assertEquals(DownloadKind.WEB, download.kind)
        assertFalse(download.id.contains(source))
        assertEquals(StreamUrl(CDN_LINK), provider.getStreamUrl(download.id, download.files.single().id).getOrThrow())
        provider.delete(download.id).getOrThrow()
        assertTrue(provider.getDownload(download.id).exceptionOrNull() is DebridError.NotCached)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `torrent and NZB files upload as multipart src while NZB URLs use a form field`() = runBlocking {
        val inputs = listOf(
            AddInput.TorrentFile(byteArrayOf(1, 2), "test.torrent"),
            AddInput.Nzb(byteArrayOf(3, 4), "test.nzb"),
            AddInput.NzbUrl("https://example.org/test.nzb"),
        )
        for (input in inputs) {
            val (provider, fake) = provider {
                when (it.path) {
                    "/transfer/create" -> ok(""" "id":"transfer-a" """)
                    "/transfer/list" -> transfer("finished")
                    else -> unexpected(it)
                }
            }
            assertTrue(provider.capabilities.torrentFile && provider.capabilities.nzb)
            val download = provider.add(input).getOrThrow()
            assertEquals(if (input is AddInput.TorrentFile) DownloadKind.TORRENT else DownloadKind.USENET, download.kind)
            val request = fake.requests.first()
            assertEquals("POST", request.method)
            if (input is AddInput.NzbUrl) {
                assertEquals(mapOf("src" to input.url), request.formFields)
                assertTrue(request.multipart.isEmpty())
            } else {
                val part = request.multipart.single()
                assertEquals("src", part.name)
                if (input is AddInput.TorrentFile) {
                    assertEquals(input.fileName, part.fileName)
                    assertEquals("application/x-bittorrent", part.contentType)
                    assertArrayEquals(input.bytes, part.bytes)
                } else {
                    input as AddInput.Nzb
                    assertEquals(input.fileName, part.fileName)
                    assertEquals("application/x-nzb", part.contentType)
                    assertArrayEquals(input.bytes, part.bytes)
                }
            }
        }
    }

    @Test
    fun `lists newest first with cloud files grouped under transfers and standalone items addressable`() = runBlocking {
        val (provider, _) = provider {
            when (it.path) {
                "/transfer/list" -> ok(""" "transfers":[
                    {"id":"running","status":"running","progress":1.4,"created_at":125},
                    {"id":"transfer-a","name":"Folder","status":"finished","folder_id":"folder-a","created_at":120},
                    {"id":"failed","status":"error","message":"Could not download","created_at":119},
                    {"id":"unknown","status":"new-status","progress":"NaN","created_at":118}] """)
                "/item/listall" -> ok(""" "files":[
                    {"id":"file-a","name":"movie.mkv","size":123,"created_at":126},
                    {"id":"file-b","name":"other.mkv","size":456,"created_at":130}] """)
                "/folder/list" -> if (it.url.endsWith("id=folder-a")) {
                    ok(""" "content":[{"type":"folder","id":"nested","name":"Subfolder"}] """)
                } else {
                    ok(""" "content":[{"type":"file","id":"file-a","name":"movie.mkv","size":123,"link":"$CDN_LINK"}] """)
                }
                "/item/details" -> item("file-b")
                else -> unexpected(it)
            }
        }
        val downloads = provider.listDownloads().getOrThrow()
        assertEquals(listOf("item:file-b", "running", "transfer-a", "failed", "unknown"), downloads.map { it.id })
        assertEquals(listOf(DownloadStatus.READY, DownloadStatus.DOWNLOADING, DownloadStatus.READY,
            DownloadStatus.ERROR, DownloadStatus.UNKNOWN), downloads.map { it.status })
        assertEquals(1f, downloads[1].progress, 0f)
        assertEquals(0f, downloads.last().progress, 0f)
        assertEquals("Could not download", downloads[3].errorMessage)
        assertEquals(130_000L, downloads.first().createdAtEpochMs)
        assertEquals(DownloadKind.WEB, provider.getDownload("item:file-b").getOrThrow().kind)
        val transfer = provider.getDownload("transfer-a").getOrThrow()
        assertEquals(downloads[2], transfer)
        assertEquals(DebridFile("file-a", "movie.mkv", 123, "Subfolder/movie.mkv"), transfer.files.single())
        assertEquals(StreamUrl(CDN_LINK), provider.getStreamUrl(transfer.id, "file-a").getOrThrow())
        assertTrue(provider.getStreamUrl(transfer.id, "file-b").exceptionOrNull() is DebridError.NotCached)
    }

    @Test
    fun `deletes a transfer record or a cloud item by its own ID`() = runBlocking {
        val (provider, fake) = provider { ok() }
        provider.delete("transfer-a").getOrThrow()
        provider.delete("item:file-a").getOrThrow()
        assertEquals(listOf("/transfer/delete", "/item/delete"), fake.requests.map { it.path })
        assertEquals(listOf("transfer-a", "file-a"), fake.requests.map { it.formFields["id"] })
        assertTrue(fake.requests.all { it.method == "POST" })
    }

    @Test
    fun `maps HTTP and envelope auth rate and account errors`() = runBlocking {
        for (response in listOf(HttpResponse(401, ""), error("authentication_failed", "Key rejected", 403),
            error("authentication_failed", "Key rejected"), error("permanent_error", "Invalid API key"))) {
            val (provider, _) = provider { response }
            assertTrue(provider.validateAccount().exceptionOrNull() is DebridError.AuthInvalid)
        }
        for (response in listOf(HttpResponse(429, "", mapOf("retry-after" to "17")),
            error("rate_limit_reached", "Slow down").copy(headers = mapOf("Retry-After" to "17")))) {
            val (provider, _) = provider { response }
            val failure = provider.validateAccount().exceptionOrNull() as DebridError.RateLimited
            assertEquals(17L, failure.retryAfterSecs)
        }
        val (provider, fake) = provider {
            error("account_limit_reached", "Not enough fair-use points for $MAGNET, key $KEY")
        }
        val failure = provider.add(AddInput.Magnet(MAGNET)).exceptionOrNull() as DebridError.Provider
        assertEquals("account_limit_reached", failure.code)
        assertTrue(failure.detail.contains("points exhausted"))
        assertFalse(failure.detail.contains(KEY))
        assertFalse(failure.detail.contains(MAGNET))
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `rejects links containing plain or encoded API keys and sanitizes error detail`() = runBlocking {
        for (secret in listOf(KEY, "test%2Bkey")) {
            val (provider, _) = provider { item(link = "https://cdn.example/movie?credential=$secret") }
            val failure = provider.getStreamUrl("item:file-a", "file-a").exceptionOrNull() as DebridError.Provider
            assertFalse(failure.detail.contains(secret))
        }
        for (message in listOf("Problem with $KEY", "Problem with $CDN_LINK", "Problem with $MAGNET")) {
            val (provider, _) = provider { error("invalid_request", message) }
            val failure = provider.validateAccount().exceptionOrNull() as DebridError.Provider
            assertEquals("invalid_request", failure.code)
            assertEquals("Premiumize could not complete this request", failure.detail)
        }
    }

    @Test
    fun `cache probe preserves input keys and maps results by position`() = runBlocking {
        for (hits in listOf(listOf(true, true), listOf(false, false), listOf(false, true))) {
            val (provider, fake) = provider {
                assertEquals("POST" to "/cache/check", it.method to it.path)
                assertEquals(listOf("items[]", "items[]"), it.multipart.map { part -> part.name })
                assertEquals(listOf("magnet:?xt=urn:btih:AA", "magnet:?xt=urn:btih:bb"),
                    it.multipart.map { part -> part.bytes.decodeToString() })
                ok(""" "response":[${hits.joinToString()}],"filename":["a","b"],"filesize":["11",22] """)
            }
            assertTrue(provider.capabilities.readOnlyCacheCheck)
            assertFalse(provider.capabilities.cacheProbeMutating)
            assertEquals(emptyMap<String, CachedInfo>(), provider.checkCached(emptyList()).getOrThrow())
            val result = provider.checkCached(listOf("AA", "bb")).getOrThrow()!!
            assertEquals(CachedInfo(hits[0], "a".takeIf { hits[0] }, 11L.takeIf { hits[0] }), result["AA"])
            assertEquals(CachedInfo(hits[1], "b".takeIf { hits[1] }, 22L.takeIf { hits[1] }), result["bb"])
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun `pause and resume fail without accessing the API`() = runBlocking {
        val (provider, fake) = provider { unexpected(it) }
        for (action in listOf(ControlAction.PAUSE, ControlAction.RESUME)) {
            assertTrue(provider.control("transfer-a", action).exceptionOrNull() is DebridError.UnsupportedCapability)
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `hoster domains and aliases are cached and lookup failures are false`() = runBlocking {
        val (provider, fake) = provider {
            assertEquals("GET" to "/services/list", it.method to it.path)
            assertNull(it.headers["Authorization"])
            ok(""" "directdl":["host","other.example"],"aliases":{"host":["host.example"]},"queue":["queue.example"],"regexpatterns":{"host":["^https?://(?:www\\.)?regexhost\\.example/.*$"]} """)
        }
        assertTrue(provider.supportsUrl("https://videos.HOST.example/a").getOrThrow())
        assertTrue(provider.supportsUrl("https://other.example/a").getOrThrow())
        assertTrue(provider.supportsUrl("https://regexhost.example/a").getOrThrow())
        assertFalse(provider.supportsUrl("https://fakehost.example/a").getOrThrow())
        assertFalse(provider.supportsUrl("https://queue.example/a").getOrThrow())
        assertEquals(1, fake.requests.size)

        val (unavailable, failed) = provider { HttpResponse(500, "Server is unavailable") }
        assertFalse(unavailable.supportsUrl("not a URL").getOrThrow())
        assertTrue(failed.requests.isEmpty())
        assertFalse(unavailable.supportsUrl("https://host.example/a").getOrThrow())
        assertEquals(1, failed.requests.size)
    }

    private val HttpRequest.path: String
        get() = url.removePrefix(PremiumizeProvider.BASE).substringBefore('?')

    private fun unexpected(request: HttpRequest): Nothing = throw AssertionError("Unexpected request: $request")

    private fun ok(fields: String = "") = HttpResponse(200, if (fields.isBlank()) """{"status":"success"}"""
        else """{"status":"success",$fields}""")

    private fun error(code: String, message: String, status: Int = 200) =
        HttpResponse(status, """{"status":"error","code":"$code","message":"$message"}""")

    private fun transfer(status: String, fields: String = "") =
        ok(""" "transfers":[{"id":"transfer-a","name":"Video","status":"$status"$fields}] """)

    private fun item(id: String = "file-a", link: String = CDN_LINK) =
        ok(""" "id":"$id","name":"movie.mkv","size":123,"created_at":125,"link":"$link" """)

    private companion object {
        const val KEY = "test+key"
        const val MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        const val CDN_LINK = "https://cdn.example/token/movie.mkv"
    }
}
