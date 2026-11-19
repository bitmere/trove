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

class AllDebridProviderTest {
    private fun provider(handler: (HttpRequest) -> HttpResponse): Pair<AllDebridProvider, RecordingTransport> {
        val fake = RecordingTransport(handler)
        return AllDebridProvider(KEY, fake, TokenBucket(4.0, 50), TokenBucket(0.5, 10), pollDelayMs = 0) to fake
    }

    @Test
    fun `validates the account with header authentication`() = runBlocking {
        val (provider, fake) = provider {
            assertEquals("GET" to "/v4/user", it.method to it.path)
            success("""{"user":{"username":"alice","isPremium":true}}""")
        }
        assertEquals(AccountInfo("alice", true, "Premium"), provider.validateAccount().getOrThrow())
        assertEquals("Bearer $KEY", fake.requests.single().headers["Authorization"])
        assertFalse(fake.requests.single().url.contains(KEY))
    }

    @Test
    fun `a ready upload uses the ready flag without polling`() = runBlocking {
        val (provider, fake) = provider { upload(ready = true) }
        val download = provider.add(AddInput.Magnet(MAGNET)).getOrThrow()

        assertEquals(DebridService.ALLDEBRID, download.service)
        assertEquals(DownloadStatus.READY, download.status)
        assertEquals(1f, download.progress, 0f)
        val request = fake.requests.single()
        assertEquals("POST" to UPLOAD, request.method to request.path)
        assertEquals(mapOf("magnets[]" to MAGNET), request.formFields)
    }

    @Test
    fun `a nonready upload polls until ready`() = runBlocking {
        var polls = 0
        val (provider, fake) = provider {
            when (it.path) {
                UPLOAD -> upload(ready = false)
                STATUS -> status(if (++polls == 1) "Downloading" else "Ready")
                else -> error("Unexpected request")
            }
        }
        assertEquals(DownloadStatus.READY, provider.add(AddInput.Magnet(MAGNET)).getOrThrow().status)
        assertEquals(listOf(UPLOAD, STATUS, STATUS), fake.requests.map { it.path })
        assertEquals(mapOf("id" to "123"), fake.requests.last().formFields)
    }

    @Test
    fun `a failed upload is deleted`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                UPLOAD -> upload(ready = false)
                STATUS -> status("Upload fail", 5)
                DELETE -> success()
                else -> error("Unexpected request")
            }
        }
        assertTrue(provider.add(AddInput.Magnet(MAGNET)).exceptionOrNull() is DebridError.NotCached)
        assertEquals(listOf(UPLOAD, STATUS, DELETE), fake.requests.map { it.path })
        assertEquals("POST", fake.requests.last().method)
        assertEquals(mapOf("id" to "123"), fake.requests.last().formFields)
    }

    @Test
    fun `uploads still in progress are returned after the poll window`() = runBlocking {
        for ((code, expected) in listOf(0 to DownloadStatus.QUEUED, 1 to DownloadStatus.DOWNLOADING)) {
            val (provider, fake) = provider {
                when (it.path) {
                    UPLOAD -> upload(ready = false)
                    STATUS -> status(expected.name, code)
                    else -> error("Unexpected request")
                }
            }
            val download = provider.add(AddInput.Magnet(MAGNET)).getOrThrow()
            assertEquals("123", download.id)
            assertEquals(expected, download.status)
            assertEquals(10, fake.requests.count { it.path == STATUS })
            assertFalse(fake.requests.any { it.path == DELETE })
        }
    }

    @Test
    fun `a cancelled status poll still deletes the upload`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                UPLOAD -> upload(ready = false)
                STATUS -> throw CancellationException("Cancelled")
                DELETE -> success()
                else -> error("Unexpected request")
            }
        }
        val error = runCatching { provider.add(AddInput.Magnet(MAGNET)) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(listOf(UPLOAD, STATUS, DELETE), fake.requests.map { it.path })
    }

    @Test
    fun `a JVM Error escapes instead of mapping to a network failure`() = runBlocking {
        val error = OutOfMemoryError("test")
        val (provider, _) = provider { throw error }
        assertSame(error, runCatching { provider.validateAccount() }.exceptionOrNull())
    }

    @Test
    fun `string and numeric JSON fields parse identically after the opt migration`() = runBlocking {
        suspend fun downloadFor(payload: HttpResponse): DebridDownload {
            val (provider, _) = provider {
                when (it.path) {
                    STATUS -> payload
                    FILES -> success("""{"magnets":[]}""")
                    LINKS -> success("""{"links":[]}""")
                    else -> error("Unexpected request ${it.path}")
                }
            }
            return provider.listDownloads().getOrThrow().single()
        }
        fun magnetJson(size: String, downloaded: String, speed: String, seeders: String, code: String) =
            success("""{"magnets":[{"id":123,"filename":"Example","status":"Downloading","statusCode":$code,"size":$size,"downloaded":$downloaded,"downloadSpeed":$speed,"seeders":$seeders,"uploadDate":500}]}""")
        val asNumbers = downloadFor(magnetJson("1000", "250", "50", "3", "2"))
        val asStrings = downloadFor(magnetJson("\"1000\"", "\"250\"", "\"50\"", "\"3\"", "\"2\""))
        assertEquals(asNumbers, asStrings)
        assertEquals(1000L, asNumbers.sizeBytes)
        assertEquals(0.25f, asNumbers.progress, 0.001f)
        assertEquals(DownloadStatus.DOWNLOADING, asNumbers.status)
        assertEquals(3, asNumbers.seeds)
        assertEquals(50L, asNumbers.downloadSpeedBytesPerSec)
        assertEquals(500_000L, asNumbers.createdAtEpochMs)
    }

    @Test
    fun `account premium flag reads booleans and strings alike`() = runBlocking {
        for ((body, premium) in listOf(
            """{"user":{"username":"alice","isPremium":true}}""" to true,
            """{"user":{"username":"alice","isPremium":"true"}}""" to true,
            """{"user":{"username":"alice","isPremium":"  "}}""" to false,
            """{"user":{"username":"alice"}}""" to false,
        )) {
            val (provider, _) = provider { success(body) }
            assertEquals(
                AccountInfo("alice", premium, if (premium) "Premium" else "Free"),
                provider.validateAccount().getOrThrow(),
            )
        }
    }

    @Test
    fun `torrent file upload uses the documented multipart field`() = runBlocking {
        val (provider, fake) = provider {
            success("""{"files":[{"id":123,"name":"Example","ready":true}]}""")
        }
        val bytes = byteArrayOf(1, 2, 3)
        assertEquals(DownloadStatus.READY, provider.add(AddInput.TorrentFile(bytes, "a.torrent")).getOrThrow().status)
        val request = fake.requests.single()
        assertEquals("POST" to "$UPLOAD/file", request.method to request.path)
        val part = request.multipart.single()
        assertEquals("files[]", part.name)
        assertEquals("a.torrent", part.fileName)
        assertEquals("application/x-bittorrent", part.contentType)
        assertArrayEquals(bytes, part.bytes)
    }

    @Test
    fun `hoster add unlocks then saves the source under a stable web ID`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                UNLOCK -> unlocked()
                "$LINKS/save" -> success()
                LINKS -> saved()
                else -> error("Unexpected request")
            }
        }
        val download = provider.add(AddInput.HosterLink(HOSTER)).getOrThrow()
        assertEquals(DownloadKind.WEB, download.kind)
        assertEquals("movie.mkv", download.name)
        assertEquals(99L, download.sizeBytes)
        assertEquals(download.id, provider.getDownload(download.id).getOrThrow().id)
        assertEquals(DIRECT, provider.getStreamUrl(download.id, download.files.single().id).getOrThrow().url)
        assertEquals(mapOf("link" to HOSTER), fake.requests[0].formFields)
        assertEquals(mapOf("links[]" to HOSTER), fake.requests[1].formFields)
        assertFalse(download.toString().contains(HOSTER))
        assertFalse(download.toString().contains(DIRECT))
    }

    @Test
    fun `lists magnet statuses and saved links newest first`() = runBlocking {
        val (provider, _) = provider {
            when (it.path) {
                STATUS -> success("""{"magnets":[
                    {"id":1,"filename":"A","status":"Ready","uploadDate":100},
                    {"id":2,"filename":"B","status":"Downloading","size":200,"downloaded":50,"uploadDate":400},
                    {"id":3,"filename":"C","status":"Waiting","uploadDate":300},
                    {"id":4,"filename":"D","statusCode":9,"uploadDate":200},
                    {"id":5,"filename":"E","status":"Unexpected"},
                    {"id":6,"filename":"F","status":"Compressing / Moving","statusCode":2,"uploadDate":250},
                    {"id":7,"filename":"G","status":"Uploading","statusCode":3,"uploadDate":150}
                ]}""")
                FILES -> HttpResponse(503, "")
                LINKS -> saved()
                else -> error("Unexpected request")
            }
        }
        val downloads = provider.listDownloads().getOrThrow()
        assertEquals(listOf("movie.mkv", "B", "C", "F", "D", "G", "A", "E"), downloads.map { it.name })
        assertEquals(listOf(
            DownloadStatus.READY, DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING, DownloadStatus.ERROR, DownloadStatus.DOWNLOADING,
            DownloadStatus.READY, DownloadStatus.UNKNOWN,
        ), downloads.map { it.status })
        assertEquals(DownloadKind.WEB, downloads.first().kind)
        assertTrue(downloads.drop(1).all { it.kind == DownloadKind.TORRENT })
        assertTrue(downloads.drop(1).all { it.files.isEmpty() })
        assertEquals(0.25f, downloads[1].progress, 0f)
        assertEquals(400_000L, downloads[1].createdAtEpochMs)
    }

    @Test
    fun `lists magnet files in one batch and matches trees by ID`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                STATUS -> success("""{"magnets":[{"id":123,"statusCode":4},{"id":456,"statusCode":4}]}""")
                FILES -> success("""{"magnets":[
                    {"id":"456","files":[{"n":"other.mkv","s":77,"l":"https://host.invalid/other"}]},
                    {"id":"123","files":[{"n":"folder","e":[{"n":"movie.mkv","s":99,"l":"$HOSTER"}]}]}
                ]}""")
                LINKS -> success("""{"links":[]}""")
                else -> error("Unexpected request")
            }
        }
        val downloads = provider.listDownloads().getOrThrow().associateBy { it.id }
        val movie = downloads.getValue("123").files.single()
        assertEquals("folder/movie.mkv", movie.path)
        assertEquals(99L, movie.sizeBytes)
        assertEquals("other.mkv", downloads.getValue("456").files.single().path)

        val request = fake.requests.single { it.path == FILES }
        assertEquals("POST", request.method)
        assertEquals(listOf("id[]", "id[]"), request.multipart.map { it.name })
        assertEquals(listOf("123", "456"), request.multipart.map { it.bytes.decodeToString() })
        assertTrue(request.multipart.all { it.fileName == null })
    }

    @Test
    fun `single magnet maps nested files and passes only the selected link to unlock`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                STATUS -> success("""{"magnets":[{"id":123,"statusCode":4,"files":[
                    {"n":"folder","e":[{"n":"movie.mkv","s":99,"l":"$HOSTER"}]},
                    {"n":"other.mkv","s":77,"l":"https://host.invalid/other"}
                ]}]}""")
                UNLOCK -> unlocked()
                else -> error("Unexpected request")
            }
        }
        val download = provider.getDownload("123").getOrThrow()
        val file = download.files.first()
        assertEquals("folder/movie.mkv", file.path)
        assertEquals(99L, file.sizeBytes)
        assertFalse(file.toString().contains(HOSTER))
        assertEquals(DIRECT, provider.getStreamUrl("123", file.id).getOrThrow().url)
        assertEquals(mapOf("id" to "123"), fake.requests.first().formFields)
        assertEquals(mapOf("link" to HOSTER), fake.requests.last().formFields)
    }

    @Test
    fun `delete selects the right endpoint and rejects invalid magnet IDs`() = runBlocking {
        val (provider, fake) = provider {
            when (it.path) {
                STATUS -> success("""{"magnets":[]}""")
                LINKS -> saved()
                DELETE, "$LINKS/delete" -> success()
                else -> error("Unexpected request")
            }
        }
        val webId = provider.listDownloads().getOrThrow().single().id
        provider.delete(webId).getOrThrow()
        provider.delete("123").getOrThrow()
        assertTrue(provider.delete("../user").exceptionOrNull() is DebridError.NotCached)
        val deletes = fake.requests.filter { it.method == "POST" && it.path.endsWith("/delete") }
        assertEquals(listOf("$LINKS/delete", DELETE), deletes.map { it.path })
        assertEquals(mapOf("links[]" to HOSTER), deletes[0].formFields)
        assertEquals(mapOf("id" to "123"), deletes[1].formFields)
    }

    @Test
    fun `maps authentication failures from HTTP and API envelopes`() = runBlocking {
        val (unauthorized, _) = provider { HttpResponse(401, "") }
        val (badKey, _) = provider {
            HttpResponse(200, """{"status":"error","error":{"code":"AUTH_BAD_APIKEY","message":"bad key"}}""")
        }
        assertTrue(unauthorized.validateAccount().exceptionOrNull() is DebridError.AuthInvalid)
        assertTrue(badKey.validateAccount().exceptionOrNull() is DebridError.AuthInvalid)
    }

    @Test
    fun `maps retry after and sanitizes provider errors`() = runBlocking {
        val (limited, _) = provider { HttpResponse(429, "", mapOf("retry-after" to "17")) }
        val rate = limited.validateAccount().exceptionOrNull() as DebridError.RateLimited
        assertEquals(17L, rate.retryAfterSecs)

        val (rejected, _) = provider {
            HttpResponse(400, """{"status":"error","error":{"code":"LINK_DOWN","message":"$KEY $DIRECT"}}""")
        }
        val failure = rejected.add(AddInput.HosterLink(HOSTER)).exceptionOrNull() as DebridError.Provider
        assertEquals("LINK_DOWN", failure.code)
        assertFalse(failure.detail.contains(KEY))
        assertFalse(failure.detail.contains(DIRECT))
    }

    @Test
    fun `stream rejects a direct link containing the encoded API key`() = runBlocking {
        val (provider, _) = provider {
            when (it.path) {
                STATUS -> success("""{"magnets":[]}""")
                LINKS -> saved()
                UNLOCK -> unlocked("https://cdn.invalid/video?key=%74est-key")
                else -> error("Unexpected request")
            }
        }
        val download = provider.listDownloads().getOrThrow().single()
        val failure = provider.getStreamUrl(download.id, download.files.single().id).exceptionOrNull()
        assertTrue(failure is DebridError.Provider)
        assertFalse(failure.toString().contains("%74est-key"))
    }

    @Test
    fun `cache check and unsupported operations make no requests`() = runBlocking {
        val (provider, fake) = provider { error("Unexpected request") }
        assertFalse(provider.capabilities.readOnlyCacheCheck)
        assertTrue(provider.capabilities.cacheProbeMutating)
        assertNull(provider.checkCached(listOf("hash")).getOrThrow())
        assertTrue(provider.control("123", ControlAction.PAUSE).exceptionOrNull() is DebridError.UnsupportedCapability)
        assertTrue(provider.add(AddInput.NzbUrl(HOSTER)).exceptionOrNull() is DebridError.UnsupportedCapability)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `host support checks cached public domains and falls back to false on failure`() = runBlocking {
        val (provider, fake) = provider {
            success("""{"hosts":["host.invalid"],"streams":["stream.invalid"],"redirectors":[]}""")
        }
        assertTrue(provider.supportsUrl(HOSTER).getOrThrow())
        assertTrue(provider.supportsUrl("https://sub.host.invalid/file").getOrThrow())
        assertFalse(provider.supportsUrl("https://fakehost.invalid/file").getOrThrow())
        assertFalse(provider.supportsUrl("https://stream.invalid/file").getOrThrow())
        assertFalse(provider.supportsUrl("bad URL").getOrThrow())
        val request = fake.requests.single()
        assertEquals("GET" to "/v4/hosts/domains", request.method to request.path)
        assertTrue(request.headers.isEmpty())

        val (failed, _) = provider { HttpResponse(503, "") }
        assertFalse(failed.supportsUrl(HOSTER).getOrThrow())
    }

    private fun upload(ready: Boolean) =
        success("""{"magnets":[{"id":123,"name":"Example","ready":$ready}]}""")

    private fun status(status: String, code: Int? = null) =
        success("""{"magnets":[{"id":123,"filename":"Example","status":"$status","statusCode":$code}]}""")

    private fun saved() =
        success("""{"links":[{"link":"$HOSTER","filename":"movie.mkv","size":99,"date":500}]}""")

    private fun unlocked(link: String = DIRECT) =
        success("""{"link":"$link","filename":"movie.mkv","filesize":99}""")

    private fun success(data: String = "{}") = HttpResponse(200, """{"status":"success","data":$data}""")

    companion object {
        private const val KEY = "test-key"
        private const val MAGNET = "magnet:?xt=urn:btih:abc"
        private const val HOSTER = "https://host.invalid/movie"
        private const val DIRECT = "https://cdn.invalid/movie.mkv"
        private const val UPLOAD = "/v4/magnet/upload"
        private const val STATUS = "/v4.1/magnet/status"
        private const val FILES = "/v4/magnet/files"
        private const val DELETE = "/v4/magnet/delete"
        private const val LINKS = "/v4/user/links"
        private const val UNLOCK = "/v4/link/unlock"
        private val HttpRequest.path get() = url.removePrefix(AllDebridProvider.BASE)
    }
}
