package app.trove.debrid.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OkHttp implementation of [HttpTransport]. One shared client.
 *
 *  Main-safe: the blocking `Call.execute()` (and body consumption)
 *  always runs on [ioDispatcher], never on the caller's thread, so a
 *  ViewModel on Main can never trip NetworkOnMainThreadException.
 *  Cancelling the caller cancels the in-flight OkHttp call, and a
 *  cancelled call still surfaces as [kotlinx.coroutines.CancellationException],
 *  never as a network error. */
class OkHttpTransport(
    client: OkHttpClient? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HttpTransport {
    private val client = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(ioDispatcher) {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val body = when {
            request.multipart.isNotEmpty() -> {
                val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                request.formFields.forEach { (k, v) -> mp.addFormDataPart(k, v) }
                request.multipart.forEach { part ->
                    if (part.fileName == null) {
                        mp.addFormDataPart(part.name, part.bytes.decodeToString())
                    } else {
                        val rb = part.bytes.toRequestBody(part.contentType.toMediaType())
                        mp.addFormDataPart(part.name, part.fileName, rb)
                    }
                }
                mp.build()
            }
            request.formFields.isNotEmpty() -> {
                val fb = FormBody.Builder()
                request.formFields.forEach { (k, v) -> fb.add(k, v) }
                fb.build()
            }
            request.jsonBody != null ->
                request.jsonBody.toRequestBody("application/json".toMediaType())
            request.method == "POST" || request.method == "PUT" ->
                ByteArray(0).toRequestBody()
            else -> null
        }
        builder.method(request.method, body)
        client.newCall(builder.build()).readResponse { resp ->
            HttpResponse(
                status = resp.code,
                body = resp.body?.string().orEmpty(),
                headers = resp.headers.toMultimap()
                    .mapValues { it.value.firstOrNull().orEmpty() },
            )
        }
    }

    override suspend fun executeBytes(request: HttpRequest): ByteArray = executeBytes(request, maxBytes = null)

    /** Bound buffering for callers with a response size limit, including chunked bodies. */
    suspend fun executeBytes(request: HttpRequest, maxBytes: Int?): ByteArray = withContext(ioDispatcher) {
        require(maxBytes == null || maxBytes > 0)
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).readResponse { resp ->
            if (resp.code !in 200..299) throw DebridError.Provider("http${resp.code}", "")
            val body = resp.body ?: return@readResponse ByteArray(0)
            if (maxBytes == null) return@readResponse body.bytes()
            val source = body.source()
            if (body.contentLength() > maxBytes || source.request(maxBytes.toLong() + 1)) {
                throw IOException("Response exceeds the byte limit")
            }
            source.readByteArray()
        }
    }

    private suspend fun <T> Call.readResponse(read: (Response) -> T): T = try {
        suspendCancellableCoroutine { continuation ->
            // Runs when cancellation starts, even while this IO thread is blocked.
            continuation.invokeOnCancellation { cancel() }
            if (continuation.isActive) continuation.resumeWith(runCatching { execute().use(read) })
        }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw DebridError.Network(e)
    }
}
