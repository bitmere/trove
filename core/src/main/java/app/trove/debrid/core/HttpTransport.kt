package app.trove.debrid.core

/** Minimal HTTP surface the provider adapters need. Tests substitute a
 *  fake; production uses [OkHttpTransport]. No Android dependency. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val formFields: Map<String, String> = emptyMap(),
    val multipart: List<MultipartPart> = emptyList(),
    val jsonBody: String? = null,
) {
    override fun toString(): String = "HttpRequest($method, [redacted])"
}

data class MultipartPart(
    val name: String,
    val fileName: String?,
    val bytes: ByteArray,
    val contentType: String,
)

data class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse

    /** Raw bytes for binary downloads (.torrent/.nzb files). The
     *  default implementation round-trips through [execute]; real
     *  transports override it with the response bytes directly. */
    suspend fun executeBytes(request: HttpRequest): ByteArray =
        execute(request).body.toByteArray(Charsets.ISO_8859_1)
}
