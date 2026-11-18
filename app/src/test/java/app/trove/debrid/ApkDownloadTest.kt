package app.trove.debrid

import app.trove.debrid.core.update.LatestRelease
import app.trove.debrid.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ApkDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val release = LatestRelease("0.2.0", 21,
        "https://github.com/bitmere/trove/releases/download/v0.2.0/Trove-v0.2.0.apk",
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "", 3)
    private val cdn = "https://release-assets.githubusercontent.com/assets/apk?signature=fixture"
    private class Connection(
        override val status: Int = 200,
        override val input: InputStream = ByteArrayInputStream("abc".toByteArray()),
        override val length: Long? = null,
        override val location: String? = null,
        override val contentRange: String? = null,
        override val chunked: Boolean = false,
    ) : UpdateConnection {
        var closed = false
        override fun close() { input.close(); closed = true }
    }
    @Test fun streamsKnownHashWithUnknownSizeAndCloses() = runTest {
        val connection = Connection()
        val file = ApkDownload(UpdateStream { assertEquals(release.apkUrl, it.url); connection }).download(temporary.root, release) { bytes, total ->
            assertEquals(3L, bytes); assertEquals(3L, total)
        }
        assertEquals("abc", file.readText())
        assertTrue(connection.closed)
    }
    @Test fun failuresDeletePartialAndReleaseGateForRetry() = runTest {
        val badRead = object : InputStream() { override fun read(): Int = throw IOException("fixture") }
        for (connection in listOf(Connection(status = 503), Connection(status = 302), Connection(input = badRead), Connection(length = 9), Connection(input = ByteArrayInputStream(ByteArray(9))), Connection(input = ByteArrayInputStream("abd".toByteArray())), Connection(length = 4))) {
            assertTrue(runCatching { ApkDownload(UpdateStream { connection }).download(temporary.root, release, maxBytes = 8) { _, _ -> } }.isFailure)
            assertTrue(connection.closed)
            assertTrue(java.io.File(temporary.root, "updates").listFiles().orEmpty().isEmpty())
        }
        assertTrue(ApkDownload(UpdateStream { Connection() }).download(temporary.root, release) { _, _ -> }.exists())
    }
    @Test fun redirectsStayOnReleaseCdnAndStopAfterThree() = runTest {
        var calls = 0
        val loop = ApkDownload(UpdateStream { calls++; Connection(status = 302, location = cdn) })
        assertTrue(runCatching { loop.download(temporary.root, release) { _, _ -> } }.isFailure)
        assertEquals(4, calls)
        calls = 0
        val hostile = ApkDownload(UpdateStream { calls++; Connection(status = 307, location = "https://evil.test/file.apk") })
        assertTrue(runCatching { hostile.download(temporary.root, release) { _, _ -> } }.isFailure)
        assertEquals(1, calls)
    }
    @Test fun cancellationAtEofDeletesEvenFullyWrittenFile() = runTest {
        val attempt = launch {
            val owner = currentCoroutineContext().job
            val input = object : ByteArrayInputStream("abc".toByteArray()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (count == -1) owner.cancel()
                    return count
                }
            }
            ApkDownload(UpdateStream { Connection(input = input) }).download(temporary.root, release) { _, _ -> }
        }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertTrue(java.io.File(temporary.root, "updates").listFiles().orEmpty().isEmpty())
    }
    @Test fun concurrentDownloadWaitsUntilFirstConnectionCloses() = runTest {
        var active = 0
        var opened = 0
        val downloader = ApkDownload(UpdateStream {
            assertEquals(0, active)
            active++; opened++
            object : UpdateConnection {
                override val status = 200
                override val length: Long? = null
                override val location: String? = null
                override val input = ByteArrayInputStream("abc".toByteArray())
                override fun close() { active-- }
            }
        })
        var second: Job? = null
        downloader.download(temporary.root, release) { _, _ ->
            second = launch(start = CoroutineStart.UNDISPATCHED) { downloader.download(temporary.root, release) { _, _ -> } }
            assertEquals(1, opened)
        }
        requireNotNull(second).join()
        assertEquals(2, opened)
        assertEquals(0, active)
    }
    @Test fun manifestReaderBoundsBytesBeforeAllocatingAndCloses() = runTest {
        val connection = Connection(input = ByteArrayInputStream(ByteArray(app.trove.debrid.core.update.UpdatePolicy.MAX_MANIFEST_BYTES + 1)))
        val failure = runCatching { PublicManifestTransport(UpdateStream { connection }).execute(app.trove.debrid.core.HttpRequest("GET", UPDATE_LATEST_URL)) }.exceptionOrNull()
        assertTrue(failure is UpdateException)
        assertTrue(connection.closed)
    }
    private fun body(text: String) = ByteArrayInputStream(text.toByteArray())
    private suspend fun failure(stream: UpdateStream, expected: LatestRelease = release): UpdateException =
        try {
            ApkDownload(stream).download(temporary.root, expected) { _, _ -> }
            error("Expected failure")
        } catch (e: UpdateException) { e }

    @Test fun emptyTruncatedAndDigestMismatchHaveDistinctIssues() = runTest {
        assertEquals(UpdateIssue.EMPTY, failure(UpdateStream { Connection(input = body("")) }).issue)
        assertEquals(UpdateIssue.TRUNCATED, failure(UpdateStream { Connection(input = body("a"), length = 3) }).issue)
        assertEquals(UpdateIssue.DIGEST_MISMATCH, failure(UpdateStream { Connection(input = body("abd"), length = 3) }).issue)
    }
    @Test fun diagnosticsAreBoundedAndContainOnlySafeMeasurements() = runTest {
        val secretBody = "https://example.invalid/requestdl?token=fake-bearer api-key=fake-key"
        val error = failure(UpdateStream { Connection(input = body(secretBody), length = secretBody.length.toLong(),
            contentRange = secretBody, location = secretBody, chunked = true) }, release.copy(size = secretBody.length.toLong()))
        val record = requireNotNull(error.diagnostics)
        val detail = record.toString()
        assertEquals(secretBody.length.toLong(), record.count)
        assertEquals(secretBody.length.toLong(), record.total)
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(secretBody.toByteArray()).hex(), record.digest)
        assertEquals(UpdateFraming.CHUNKED, record.framing)
        assertTrue(detail.length < 400)
        for (privateText in listOf(release.apkUrl, "https://", "requestdl", "fake-bearer", "fake-key", "api-key")) {
            assertFalse(detail.contains(privateText))
            assertFalse(error.toString().contains(privateText))
        }
        assertTrue(java.io.File(temporary.root, "updates").listFiles().orEmpty().isEmpty())
    }
    @Test fun transientNetworkFailureRetriesWithBackoffThenSucceeds() = runTest {
        var calls = 0
        val start = testScheduler.currentTime
        val file = ApkDownload(UpdateStream {
            calls++
            if (calls < 3) throw IOException("private transport message")
            Connection(length = 3)
        }).download(temporary.root, release) { _, _ -> }
        assertEquals("abc", file.readText())
        assertEquals(3, calls)
        assertEquals(1500L, testScheduler.currentTime - start)
    }
    @Test fun permanentNetworkAndDigestFailuresStopAfterTwoRetries() = runTest {
        for (network in listOf(true, false)) {
            var calls = 0
            val error = failure(UpdateStream { request ->
                calls++
                assertEquals("no-cache", request.headers["Cache-Control"])
                assertEquals("no-cache", request.headers["Pragma"])
                if (network) throw IOException("private transport message")
                Connection(input = body("abd"), length = 3)
            })
            assertEquals(3, calls)
            assertEquals(if (network) UpdateIssue.DOWNLOAD else UpdateIssue.DIGEST_MISMATCH, error.issue)
        }
    }
    @Test fun matchingRangeResumesAndVerifiesWholeFile() = runTest {
        var calls = 0
        var cleanup = 0
        val file = ApkDownload(UpdateStream { request ->
            calls++
            assertEquals("no-cache", request.headers["Cache-Control"])
            assertEquals("no-cache", request.headers["Pragma"])
            if (calls == 1) {
                assertNull(request.headers["Range"])
                Connection(input = body("a"), length = 3)
            } else {
                assertEquals("bytes=1-", request.headers["Range"])
                Connection(status = 206, input = body("bc"), length = 2, contentRange = "bytes 1-2/3")
            }
        }, { cleanup++ }).download(temporary.root, release) { _, total -> assertEquals(3L, total) }
        assertEquals("abc", file.readText())
        assertEquals(release.sha256, sha256(file))
        assertEquals(2, calls)
        assertEquals(1, cleanup)
    }
    @Test fun ignoredRangeUsesFullResponseWithoutAppending() = runTest {
        var calls = 0
        val file = ApkDownload(UpdateStream { request ->
            calls++
            if (calls == 1) Connection(input = body("a"), length = 3)
            else {
                assertEquals("bytes=1-", request.headers["Range"])
                Connection(length = 3)
            }
        }).download(temporary.root, release) { _, _ -> }
        assertEquals("abc", file.readText())
        assertEquals(2, calls)
    }
    @Test fun invalidContentRangeFallsBackToFullDownload() = runTest {
        for ((range, length) in listOf("bytes 0-1/3" to 2L, "bytes 1-2/4" to 2L, "bytes 1-3/3" to 2L, "bytes 1-2/*" to 2L, "private-header" to 2L, "bytes 1-2/3" to 3L, null to 2L)) {
            var calls = 0
            val rejected = Connection(status = 206, input = body("bc"), length = length, contentRange = range)
            val file = ApkDownload(UpdateStream { request ->
                when (++calls) {
                    1 -> Connection(input = body("a"), length = 3)
                    2 -> { assertEquals("bytes=1-", request.headers["Range"]); rejected }
                    else -> { assertNull(request.headers["Range"]); Connection(length = 3) }
                }
            }).download(temporary.root, release) { _, _ -> }
            assertEquals("abc", file.readText())
            assertTrue(rejected.closed)
            assertEquals(3, calls)
        }
    }
    @Test fun wrongResumedHashFailsAsDigestMismatchAndRestartsFull() = runTest {
        var calls = 0
        val error = failure(UpdateStream { request ->
            when (++calls) {
                1 -> Connection(input = body("a"), length = 3)
                2 -> Connection(status = 206, input = body("bd"), length = 2, contentRange = "bytes 1-2/3")
                else -> { assertNull(request.headers["Range"]); Connection(input = body("abd"), length = 3) }
            }
        })
        assertEquals(3, calls)
        assertEquals(UpdateIssue.DIGEST_MISMATCH, error.issue)
    }
    @Test fun prematureReadExceptionCanResume() = runTest {
        var calls = 0
        val broken = object : InputStream() {
            var sent = false
            override fun read(): Int = throw IOException("private")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (sent) throw java.net.ProtocolException("unexpected end of stream")
                sent = true; buffer[offset] = 'a'.code.toByte(); return 1
            }
        }
        val file = ApkDownload(UpdateStream {
            if (++calls == 1) Connection(input = broken, length = 3)
            else Connection(status = 206, input = body("bc"), length = 2, contentRange = "bytes 1-2/3")
        }).download(temporary.root, release) { _, _ -> }
        assertEquals("abc", file.readText())
        assertEquals(2, calls)
    }
    @Test fun cancellationDuringBackoffDeletesPartialAndDoesNotRetry() = runTest {
        var calls = 0
        val job = launch {
            ApkDownload(UpdateStream { calls++; Connection(input = body("a"), length = 3) })
                .download(temporary.root, release) { _, _ -> }
        }
        testScheduler.runCurrent()
        assertEquals(1, calls)
        job.cancelAndJoin()
        testScheduler.advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(java.io.File(temporary.root, "updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun fullRetryResetsReportedProgressBeforeReadingReplacement() = runTest {
        var calls = 0
        val reported = mutableListOf<Pair<Long, Long?>>()
        val file = ApkDownload(UpdateStream {
            if (++calls == 1) Connection(input = body("a"), length = 3) else Connection()
        }).download(temporary.root, release) { count, total -> reported += count to total }
        assertEquals(listOf(1L to 3L, 0L to 3L, 3L to 3L), reported)
        assertEquals("abc", file.readText())
    }

    @Test fun sizeCapDiagnosticHashesOnlyBytesWrittenToDisk() = runTest {
        val failure = runCatching {
            ApkDownload(UpdateStream { Connection(input = body("abcd")) }).download(temporary.root, release, maxBytes = 3) { _, _ -> }
        }.exceptionOrNull() as UpdateException
        assertEquals(4L, failure.diagnostics?.count)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", failure.diagnostics?.digest)
    }

    @Test fun manifestTransportForwardsNoCacheAndRejectsCredentialHeaders() = runTest {
        var calls = 0
        val transport = PublicManifestTransport(UpdateStream { request ->
            calls++
            assertEquals(UPDATE_NO_CACHE_HEADERS, request.headers)
            Connection()
        })
        transport.execute(app.trove.debrid.core.HttpRequest("GET", UPDATE_LATEST_URL, UPDATE_NO_CACHE_HEADERS))
        val error = runCatching { transport.execute(app.trove.debrid.core.HttpRequest("GET", UPDATE_LATEST_URL,
            mapOf("Authorization" to "fixture-only"))) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(1, calls)
    }
    @Test fun rangeHeadersSurviveAllowedRedirects() = runTest {
        var calls = 0
        val file = ApkDownload(UpdateStream { request ->
            assertEquals("no-cache", request.headers["Cache-Control"])
            assertEquals("no-cache", request.headers["Pragma"])
            when (++calls) {
                1 -> Connection(input = body("a"), length = 3)
                2 -> { assertEquals("bytes=1-", request.headers["Range"]); Connection(status = 307, location = cdn) }
                else -> { assertEquals("bytes=1-", request.headers["Range"])
                    Connection(status = 206, input = body("bc"), length = 2, contentRange = "bytes 1-2/3") }
            }
        }).download(temporary.root, release) { _, _ -> }
        assertEquals("abc", file.readText())
        assertEquals(3, calls)
    }
    @Test fun unknownLengthPartialResponseUsesApiSizeForRange() = runTest {
        var calls = 0
        val file = ApkDownload(UpdateStream { request ->
            assertEquals(if (calls == 0) null else "bytes=1-", request.headers["Range"])
            if (++calls == 1) Connection(input = body("a")) else Connection()
        }).download(temporary.root, release) { _, _ -> }
        assertEquals("abc", file.readText())
        assertEquals(2, calls)
    }

    @Test fun publicConnectionSendsNoCacheAndRangeWithoutEnablingCaches() {
        val connection = object : java.net.HttpURLConnection(java.net.URL(release.apkUrl)) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 206
            override fun getContentLengthLong() = 2L
            override fun getHeaderField(name: String): String? = when (name) {
                "Content-Range" -> "bytes 1-2/3"
                "Transfer-Encoding" -> "chunked"
                else -> null
            }
        }
        val stream = PublicUpdateStream { connection }
        stream.open(app.trove.debrid.core.HttpRequest("GET", release.apkUrl,
            UPDATE_NO_CACHE_HEADERS + ("Range" to "bytes=1-"))).use { response ->
            assertEquals("no-cache", connection.getRequestProperty("Cache-Control"))
            assertEquals("no-cache", connection.getRequestProperty("Pragma"))
            assertEquals("bytes=1-", connection.getRequestProperty("Range"))
            assertFalse(connection.useCaches)
            assertFalse(connection.instanceFollowRedirects)
            assertEquals(15000, connection.connectTimeout)
            assertEquals(30000, connection.readTimeout)
            assertEquals("bytes 1-2/3", response.contentRange)
            assertTrue(response.chunked)
        }
    }

    @Test fun cancellationClosesBlockedReadAndDeletesFileBeforeReturning() = runTest {
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val downloader = ApkDownload(UpdateStream {
            calls.incrementAndGet()
            object : UpdateConnection {
                override val status = 200
                override val length = 3L
                override val location: String? = null
                override val input = object : InputStream() {
                    override fun read(): Int {
                        entered.countDown()
                        check(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw IOException("closed fixture")
                    }
                }
                override fun close() { closes.incrementAndGet(); released.countDown() }
            }
        })
        val job = launch(Dispatchers.IO) { downloader.download(temporary.root, release) { _, _ -> } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            // The joined job runs on real IO threads, so this timeout must use real time:
            // runTest virtual time would fire it before the real job finishes.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5000) { job.cancelAndJoin() }
            }
            assertEquals(1, calls.get())
            assertEquals(1, closes.get())
            assertTrue(java.io.File(temporary.root, "updates").listFiles().orEmpty().isEmpty())
        } finally { released.countDown(); job.cancelAndJoin() }
    }

}
