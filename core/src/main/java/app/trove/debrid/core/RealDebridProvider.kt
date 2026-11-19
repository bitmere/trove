package app.trove.debrid.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID

/**
 * Real-Debrid REST 1.0 adapter. Verified against https://api.real-debrid.com/
 * and the official mirrors https://api-2.real-debrid.com/ and https://api-3.real-debrid.com/.
 * https://real-debrid.com/apidocs and the bare REST base were unreachable during review.
 * The addTorrent reference omits the body encoding. Independent examples use raw PUT
 * (https://github.com/brcly/debridarr/blob/main/docs/real-debrid.md and
 * https://gist.github.com/ecsousa/1eb0a2f00ef1258639aea889780a11e1).
 * The current transport sends the requested multipart fallback; its acceptance is unverified.
 */
class RealDebridProvider(
    private val apiToken: String,
    private val transport: HttpTransport,
    private val generalBucket: TokenBucket = TokenBucket(permitsPerSecond = 4.0, burst = 4),
    private val addBudget: TokenBucket = TokenBucket(permitsPerSecond = 0.5, burst = 2),
    private val pollDelayMs: Long = 750,
) : DebridProvider {
    override val service = DebridService.REAL_DEBRID
    override val capabilities = ProviderCapabilities(
        magnet = true,
        torrentFile = true,
        hosterLink = true,
        nzb = false,
        readOnlyCacheCheck = false,
        cacheProbeMutating = true,
        fileSelection = true,
        pauseResume = false,
        restart = false,
        streamingApi = false,
        multiFilePick = true,
        maxActiveDownloads = 100,
        authFlow = AuthFlow.API_KEY,
    )

    private val domainsMutex = Mutex()
    private var cachedDomains = emptySet<String>()
    private var domainsExpireAt = 0L

    override suspend fun validateAccount(): Result<AccountInfo> = runCatching {
        val account = call("GET", "/user").requireObject()
        val premium = account.optString("type").equals("premium", ignoreCase = true) ||
            account.optBoolean("premium", false) || (account.optString("premium")?.toLongOrNull() ?: 0L) > 0
        val expiration = account.optString("expiration")?.takeIf { parseDate(it) != null }
        val detail = when {
            !premium -> "Free"
            expiration != null -> "Premium until $expiration"
            else -> "Premium"
        }
        AccountInfo(account.optString("username"), premium, detail)
    }.mapError()

    override suspend fun listDownloads(): Result<List<DebridDownload>> = runCatching {
        (listPages("/torrents") { it.toTorrent() } + listPages("/downloads") { it.toWebDownload() })
            .sortedByDescending { it.createdAtEpochMs ?: Long.MIN_VALUE }
    }.mapError()

    override suspend fun getDownload(id: String): Result<DebridDownload> = runCatching {
        val download = if (id.startsWith(WEB_PREFIX)) webInfo(id)?.toWebDownload()
        else torrentInfo(id)?.toTorrent()
        download ?: throw DebridError.NotCached()
    }.mapError()

    override suspend fun add(input: AddInput): Result<DebridDownload> = runCatching {
        when (input) {
            is AddInput.Magnet -> {
                addBudget.acquire()
                val data = call("POST", "/torrents/addMagnet", form = mapOf("magnet" to input.magnetUri))
                selectAndPoll(data.requireObject())
            }
            is AddInput.TorrentFile -> {
                addBudget.acquire()
                val data = call(
                    "PUT", "/torrents/addTorrent",
                    multipart = listOf(MultipartPart("file", input.fileName, input.bytes, "application/x-bittorrent")),
                )
                selectAndPoll(data.requireObject())
            }
            is AddInput.HosterLink -> {
                val data = unrestrict(input.url)
                val fallback = UUID.nameUUIDFromBytes(input.url.toByteArray(Charsets.UTF_8)).toString()
                data.toWebDownload(fallback) ?: throw unreadableResponse()
            }
            is AddInput.Nzb, is AddInput.NzbUrl ->
                throw DebridError.UnsupportedCapability("Real-Debrid has no Usenet support")
        }
    }.mapError()

    override suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?> =
        // instantAvailability was disabled on 2024-11-22. Do not build an add-then-delete
        // probe here; that decision belongs to later multi-service Add work.
        Result.success(null)

    override suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl> = runCatching {
        val download = if (downloadId.startsWith(WEB_PREFIX)) {
            val entry = webInfo(downloadId) ?: throw DebridError.NotCached()
            val link = entry.optString("link")?.takeIf(String::isNotBlank)
            if (link != null) unrestrict(link).optString("download") else entry.optString("download")
        } else {
            unrestrict(torrentFileLink(downloadId, fileId)).optString("download")
        } ?: throw unreadableResponse()
        if (httpUri(download) == null || containsToken(download)) throw unreadableResponse()
        // This link is IP-locked. Request it on the phone and play it on that phone.
        StreamUrl(download)
    }.mapError()

    override suspend fun control(id: String, action: ControlAction): Result<Unit> =
        Result.failure(DebridError.UnsupportedCapability("Real-Debrid has no pause/resume"))

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        when {
            id.startsWith(SYNTHETIC_WEB_PREFIX) -> Unit
            id.startsWith(WEB_PREFIX) ->
                call("DELETE", "/downloads/delete/${resourceId(id.removePrefix(WEB_PREFIX))}", allowMissing = true)
            else -> call("DELETE", "/torrents/delete/${resourceId(id)}", allowMissing = true)
        }
        Unit
    }.mapError()

    override suspend fun supportsUrl(url: String): Result<Boolean> = try {
        val host = httpUri(url)?.host?.lowercase()?.trimEnd('.')
        Result.success(host != null && hosterDomains().any { host == it || host.endsWith(".$it") })
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.success(false)
    }

    override suspend fun hosterDomains(): Set<String> = domainsMutex.withLock {
        if (SystemClock.nowMillis() >= domainsExpireAt) {
            cachedDomains = call("GET", "/hosts/domains").requireArray().mapNotNull {
                it.stringValue()?.trim()?.lowercase()?.trimEnd('.')?.takeIf(String::isNotEmpty)
            }.toSet()
            domainsExpireAt = SystemClock.nowMillis() + 3_600_000L
        }
        cachedDomains
    }

    private suspend fun selectAndPoll(created: JsonObject): DebridDownload {
        val id = created.optString("id") ?: throw unreadableResponse()
        val pathId = resourceId(id)
        try {
            call("POST", "/torrents/selectFiles/$pathId", form = mapOf("files" to "all"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delete(id)
            throw e
        }
        for (attempt in 1..MAX_POLLS) {
            val download = getDownload(id).getOrThrow()
            if (download.status in TERMINAL_ADD_STATES || attempt == MAX_POLLS) return download
            delay(pollDelayMs)
        }
        error("Invalid Real-Debrid polling limit")
    }

    private suspend fun torrentFileLink(downloadId: String, fileId: String): String {
        val torrent = torrentInfo(downloadId) ?: throw DebridError.NotCached()
        val selectedFiles = torrent.array("files")?.toList().orEmpty()
            .filter { (it as? JsonObject)?.optString("selected") == "1" }
        val index = selectedFiles.indexOfFirst { (it as? JsonObject)?.optString("id") == fileId }
        val links = torrent.array("links") ?: throw DebridError.NotCached()
        if (index < 0 || links.size() != selectedFiles.size) throw DebridError.NotCached()
        return links[index].stringValue()?.takeIf { it.isNotBlank() } ?: throw DebridError.NotCached()
    }

    private suspend fun torrentInfo(id: String): JsonObject? {
        val data = call("GET", "/torrents/info/${resourceId(id)}", allowMissing = true) ?: return null
        // The reference displays a one-item array schema for this single-resource endpoint.
        return if (data is JsonArray && data.size() == 1) data[0].requireObject() else data.requireObject()
    }

    private suspend fun webInfo(id: String): JsonObject? {
        if (id.startsWith(SYNTHETIC_WEB_PREFIX)) return null
        val remoteId = id.removePrefix(WEB_PREFIX)
        return listPages("/downloads", stopAfterMatch = true) {
            it.takeIf { entry -> entry.optString("id") == remoteId }
        }.firstOrNull()
    }

    private suspend fun unrestrict(link: String): JsonObject {
        addBudget.acquire()
        return call("POST", "/unrestrict/link", form = mapOf("link" to link)).requireObject()
    }

    private suspend fun <T : Any> listPages(
        path: String,
        stopAfterMatch: Boolean = false,
        mapEntry: (JsonObject) -> T?,
    ): List<T> = buildList {
        for (page in 1..MAX_PAGES) {
            val entries = call("GET", "$path?page=$page&limit=$PAGE_SIZE").requireArray()
            addAll(entries.mapNotNull { (it as? JsonObject)?.let(mapEntry) })
            if ((stopAfterMatch && isNotEmpty()) || entries.size() < PAGE_SIZE) break
        }
    }

    private fun JsonObject.toTorrent(): DebridDownload? {
        val id = optString("id")?.takeIf(String::isNotBlank) ?: return null
        return DebridDownload(
            id = id,
            service = service,
            name = optString("filename") ?: optString("original_filename") ?: "Torrent",
            sizeBytes = optString("bytes")?.toLongOrNull(),
            status = when (optString("status")) {
                "magnet_error", "magnet_conversion", "waiting_files_selection", "queued" -> DownloadStatus.QUEUED
                "downloading", "compressing", "uploading" -> DownloadStatus.DOWNLOADING
                "downloaded" -> DownloadStatus.READY
                "error", "virus", "dead" -> DownloadStatus.ERROR
                else -> DownloadStatus.UNKNOWN
            },
            progress = (optString("progress")?.toDoubleOrNull()?.takeIf(Double::isFinite)
                ?.coerceIn(0.0, 100.0)?.div(100.0) ?: 0.0).toFloat(),
            files = array("files")?.toList().orEmpty().mapNotNull { (it as? JsonObject)?.toFile() },
            kind = DownloadKind.TORRENT,
            createdAtEpochMs = parseDate(optString("added")),
            downloadSpeedBytesPerSec = optString("speed")?.toLongOrNull(),
            seeds = optString("seeders")?.toIntOrNull(),
        )
    }

    private fun JsonObject.toFile(): DebridFile? {
        val id = optString("id") ?: return null
        val path = optString("path")
        val name = optString("filename") ?: path?.substringAfterLast('/') ?: "File"
        return DebridFile(id, name, optString("bytes")?.toLongOrNull() ?: 0, path)
    }

    private fun JsonObject.toWebDownload(fallbackId: String? = null): DebridDownload? {
        val remoteId = optString("id")?.takeIf(String::isNotBlank)
        val id = remoteId?.let { WEB_PREFIX + it }
            ?: fallbackId?.let { SYNTHETIC_WEB_PREFIX + it }
            ?: return null
        val filename = optString("filename") ?: "Download"
        val bytes = optString("filesize")?.toLongOrNull()
        return DebridDownload(
            id = id,
            service = service,
            name = filename,
            sizeBytes = bytes,
            status = DownloadStatus.READY,
            progress = 1f,
            files = listOf(DebridFile(remoteId ?: id, filename, bytes ?: 0L)),
            kind = DownloadKind.WEB,
            createdAtEpochMs = parseDate(optString("generated")),
        )
    }

    private suspend fun call(
        method: String,
        path: String,
        form: Map<String, String> = emptyMap(),
        multipart: List<MultipartPart> = emptyList(),
        allowMissing: Boolean = false,
    ): JsonElement? {
        generalBucket.acquire()
        val response = transport.execute(
            HttpRequest(method, BASE + path, mapOf("Authorization" to "Bearer $apiToken"), form, multipart),
        )
        if (allowMissing && response.status == 404) return null
        if (response.status in 200..299 && (method == "DELETE" || path.startsWith("/torrents/selectFiles/"))) {
            return null
        }
        val json = runCatching { JsonParser.parseString(response.body) }.getOrNull()
        if (response.status in 200..299) return json ?: throw unreadableResponse()
        val error = json as? JsonObject
        val message = error?.optString("error") ?: error?.optString("message")
        val code = error?.optString("error_code")?.toIntOrNull()
        throw when {
            response.status == 401 || response.status == 403 && (code == 8 || message == "bad_token") ->
                DebridError.AuthInvalid()
            response.status == 429 -> DebridError.RateLimited(
                response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }
                    ?.value?.trim()?.toLongOrNull(),
            )
            response.status == 451 -> DebridError.NameRefused(safeDetail(message))
            else -> DebridError.Provider(code?.toString() ?: "http${response.status}", safeDetail(message))
        }
    }

    private fun safeDetail(message: String?): String {
        val fallback = "Real-Debrid could not complete this request."
        if (message.isNullOrBlank()) return fallback
        val decoded = decode(message)
        if (containsToken(message) || decoded.contains("http", ignoreCase = true) ||
            decoded.contains("://") || decoded.contains("magnet:", ignoreCase = true)
        ) return fallback
        return message.take(300)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun containsToken(value: String): Boolean =
        apiToken.isNotEmpty() && (value.contains(apiToken) || decode(value).contains(apiToken))

    private fun resourceId(id: String): String {
        if (!id.matches(RESOURCE_ID) || containsToken(id)) throw DebridError.NotCached()
        return id
    }

    private fun httpUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme?.lowercase() in setOf("http", "https") && it.host != null && it.userInfo == null
    }

    private fun parseDate(value: String?): Long? =
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray
    private fun JsonElement.stringValue(): String? =
        if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
    private fun JsonElement?.requireObject(): JsonObject = this as? JsonObject ?: throw unreadableResponse()
    private fun JsonElement?.requireArray(): JsonArray = this as? JsonArray ?: throw unreadableResponse()

    private fun unreadableResponse() = DebridError.Provider("bad-response", "Real-Debrid returned an unreadable response")

    companion object {
        const val BASE = "https://api.real-debrid.com/rest/1.0"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 5
        private const val MAX_POLLS = 10
        private const val WEB_PREFIX = "web:"
        private const val SYNTHETIC_WEB_PREFIX = "web:local:"
        private val RESOURCE_ID = Regex("[A-Za-z0-9_-]+")
        private val TERMINAL_ADD_STATES = setOf(DownloadStatus.DOWNLOADING, DownloadStatus.READY, DownloadStatus.ERROR)
    }
}
