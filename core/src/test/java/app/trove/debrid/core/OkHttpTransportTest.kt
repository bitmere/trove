package app.trove.debrid.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the real OkHttp transport against a local HTTP server. */
class OkHttpTransportTest {

    private lateinit var server: HttpServer
    private var baseUrl: String = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun serve(path: String, status: Int, body: String, delayMs: Long = 0L) {
        server.createContext(path) { exchange ->
            if (delayMs > 0) Thread.sleep(delayMs)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `execute runs off the calling thread and returns the body`() = runBlocking {
        serve("/ok", 200, """{"success":true}""")
        val threads = ConcurrentHashMap.newKeySet<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                threads += Thread.currentThread().name
                chain.proceed(chain.request())
            }
            .build()
        val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-io").also { it.isDaemon = true }
        }
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()
        try {
            val transport = OkHttpTransport(client, ioDispatcher)
            val resp = transport.execute(HttpRequest("GET", "$baseUrl/ok"))
            assertEquals(200, resp.status)
            assertTrue(resp.body.contains("success"))
            // The blocking call happened on the IO dispatcher thread,
            // never on this test (calling) thread. (Coroutines append
            // " @coroutine#N" to the thread name while running.)
            assertTrue(threads.any { it.startsWith("test-io") })
            assertNotEquals(setOf(Thread.currentThread().name), threads)
        } finally {
            ioDispatcher.close()
            ioExecutor.shutdown()
            ioExecutor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `multipart preserves the raw magnet and binary torrent bytes on the wire`() = runBlocking {
        val magnet = "magnet:?xt=urn:btih:aabbcc&dn=A%20B&tr=https%3A%2F%2Ft.example%2Fa%3Fx%3D1%26y%3D2"
        val torrent = byteArrayOf(0, 1, 127, -1, -2)
        val bodies = mutableListOf<ByteArray>()
        val contentTypes = mutableListOf<String>()
        server.createContext("/create") { exchange ->
            bodies += exchange.requestBody.readBytes()
            contentTypes += exchange.requestHeaders.getFirst("Content-Type")
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val transport = OkHttpTransport()
        for (part in listOf(
            MultipartPart("magnet", null, magnet.toByteArray(), "text/plain"),
            MultipartPart("file", "movie.torrent", torrent, "application/octet-stream"),
        )) {
            transport.execute(HttpRequest("POST", "$baseUrl/create", multipart = listOf(part)))
        }
        assertTrue(contentTypes.all { it.startsWith("multipart/form-data; boundary=") })
        val magnetBody = bodies[0].decodeToString()
        assertTrue(magnetBody.contains("name=\"magnet\""))
        assertTrue(magnetBody.contains("\r\n\r\n$magnet\r\n"))
        val fileBody = bodies[1].toString(Charsets.ISO_8859_1)
        assertTrue(fileBody.contains("name=\"file\"; filename=\"movie.torrent\""))
        assertTrue(fileBody.contains("Content-Type: application/octet-stream"))
        assertTrue(fileBody.contains(torrent.toString(Charsets.ISO_8859_1)))
    }

    @Test
    fun `cancelling the caller cancels the call instead of failing`() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CountDownLatch(1)
        server.createContext("/slow") { exchange ->
            try {
                requestStarted.complete(Unit)
                releaseResponse.await(10, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        val transport = OkHttpTransport(ioDispatcher = Dispatchers.IO)
        val job = launch {
            transport.execute(HttpRequest("GET", "$baseUrl/slow"))
        }
        try {
            withTimeout(5_000) { requestStarted.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally {
            releaseResponse.countDown()
        }
    }

    @Test
    fun `bounded byte read accepts the exact limit and rejects oversized fixed or chunked bodies`() = runBlocking {
        serve("/fixed", 200, "abcd")
        server.createContext("/chunked") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("abcd".toByteArray()) }
        }
        val transport = OkHttpTransport()
        for (path in listOf("fixed", "chunked")) {
            val request = HttpRequest("GET", "$baseUrl/$path")
            assertEquals("abcd", transport.executeBytes(request, 4).decodeToString())
            val error = runCatching { transport.executeBytes(request, 3) }.exceptionOrNull() as? DebridError.Provider
            assertEquals("bodyTooLarge", error?.code)
        }
    }

    @Test
    fun `a failing server is a network error, not a crash`() = runBlocking {
        val transport = OkHttpTransport(ioDispatcher = Dispatchers.IO)
        try {
            // Nothing listens on this port: connection refused.
            transport.execute(HttpRequest("GET", "http://127.0.0.1:1/unreachable"))
            fail("expected DebridError.Network")
        } catch (e: DebridError.Network) {
            // Expected.
        }
    }
}
