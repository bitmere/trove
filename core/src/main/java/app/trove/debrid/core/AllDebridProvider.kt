package app.trove.debrid.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * Official API reference verified at https://docs.alldebrid.com/ (HTTPS base and Bearer auth,
 * User, Magnet, Links, User links, Hosts, and Errors). Requests use https://api.alldebrid.com/v4
 * except magnet status, which uses https://api.alldebrid.com/v4.1/magnet/status.
 * List files come from /v4/magnet/files. Single-magnet status includes its file tree.
 * API key only. PIN authentication is deferred. The current endpoint index does not document
 * an enabled /magnet/instant probe or pause/resume. checkCached performs no HTTP requests.
 */
class AllDebridProvider(
    private val apiKey: String,
    private val transport: HttpTransport,
    private val generalBucket: TokenBucket = TokenBucket(permitsPerSecond = 4.0, burst = 4),
    private val addBudget: TokenBucket = TokenBucket(permitsPerSecond = 0.5, burst = 2),
    private val pollDelayMs: Long = 750,
) : DebridProvider {
    override val service = DebridService.ALLDEBRID
    override val capabilities = ProviderCapabilities(
        magnet = true,
        torrentFile = true,
        hosterLink = true,
        nzb = false,
        readOnlyCacheCheck = false,
        cacheProbeMutating = true,
        fileSelection = false,
        pauseResume = false,
        authFlow = AuthFlow.API_KEY,
    )

    private val domainsMutex = Mutex()
    private var cachedDomains = emptySet<String>()
    private var domainsExpireAt = 0L

    override suspend fun validateAccount(): Result<AccountInfo> = runCatching {
        val user = call("GET", "/v4/user").get("user") as? JsonObject ?: throw unreadableResponse()
        val premium = user.optNonBlankString("isPremium") == "true"
        AccountInfo(user.optNonBlankString("username"), premium, if (premium) "Premium" else "Free")
    }.mapError()

    override suspend fun listDownloads(): Result<List<DebridDownload>> = runCatching {
        val magnets = call("POST", MAGNET_STATUS).objects("magnets").map { it.toTorrent() }
        val filesByMagnet = if (magnets.isEmpty()) emptyMap() else try {
            call(
                "POST", "/v4/magnet/files",
                multipart = magnets.map { MultipartPart("id[]", null, it.id.toByteArray(), "text/plain") },
            ).objects("magnets").mapNotNull { magnet ->
                magnet.optNonBlankString("id")?.let { id -> id to magnet.files().map { it.second } }
            }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        val links = savedLinks().map { (link, entry) ->
            webDownload(link, entry.optNonBlankString("filename"), entry.optLong("size"), entry.epochMs("date"))
        }
        val torrents = magnets.map { it.copy(files = filesByMagnet[it.id].orEmpty()) }
        (torrents + links).sortedByDescending { it.createdAtEpochMs ?: Long.MIN_VALUE }
    }.mapError()

    override suspend fun getDownload(id: String): Result<DebridDownload> = runCatching {
        if (id.startsWith(WEB_PREFIX)) {
            val (link, entry) = savedLink(id)
            webDownload(link, entry.optNonBlankString("filename"), entry.optLong("size"), entry.epochMs("date"))
        } else {
            magnet(id).toTorrent()
        }
    }.mapError()

    override suspend fun add(input: AddInput): Result<DebridDownload> = runCatching {
        when (input) {
            is AddInput.Magnet -> {
                addBudget.acquire()
                val uploaded = call("POST", "/v4/magnet/upload", mapOf("magnets[]" to input.magnetUri))
                finishUpload(uploaded.objects("magnets").singleOrNull() ?: throw unreadableResponse())
            }
            is AddInput.TorrentFile -> {
                addBudget.acquire()
                val uploaded = call(
                    "POST", "/v4/magnet/upload/file",
                    multipart = listOf(MultipartPart("files[]", input.fileName, input.bytes, "application/x-bittorrent")),
                )
                finishUpload(uploaded.objects("files").singleOrNull() ?: throw unreadableResponse())
            }
            is AddInput.HosterLink -> {
                val link = checkedLink(input.url)
                val unlocked = unlock(link)
                directLink(unlocked)
                call("POST", "/v4/user/links/save", mapOf("links[]" to link))
                webDownload(link, unlocked.optNonBlankString("filename"), unlocked.optLong("filesize"), SystemClock.nowMillis())
            }
            is AddInput.Nzb, is AddInput.NzbUrl ->
                throw DebridError.UnsupportedCapability("AllDebrid has no Usenet support")
        }
    }.mapError()

    override suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?> =
        Result.success(null)

    override suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl> = runCatching {
        val link = if (downloadId.startsWith(WEB_PREFIX)) {
            if (fileId != downloadId) throw DebridError.NotCached()
            savedLink(downloadId).first
        } else {
            magnet(downloadId).files().firstOrNull { it.second.id == fileId }?.first
                ?: throw DebridError.NotCached()
        }
        StreamUrl(directLink(unlock(link)))
    }.mapError()

    override suspend fun control(id: String, action: ControlAction): Result<Unit> =
        Result.failure(DebridError.UnsupportedCapability("AllDebrid has no pause/resume"))

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        if (id.startsWith(WEB_PREFIX)) {
            savedLinks().firstOrNull { webId(it.first) == id }?.let { (link, _) ->
                call("POST", "/v4/user/links/delete", mapOf("links[]" to link))
            }
        } else {
            deleteMagnet(resourceId(id))
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
            val domains = call("GET", "/v4/hosts/domains", authenticated = false).get("hosts") as? JsonArray
                ?: throw unreadableResponse()
            cachedDomains = domains.mapNotNull {
                if (it.isJsonPrimitive && it.asJsonPrimitive.isString) {
                    it.asString.trim().lowercase().trimEnd('.').takeIf(String::isNotEmpty)
                } else null
            }.toSet()
            domainsExpireAt = SystemClock.nowMillis() + 3_600_000L
        }
        cachedDomains
    }

    private suspend fun finishUpload(entry: JsonObject): DebridDownload {
        (entry.get("error") as? JsonObject)?.let { throw apiError(it, "upload-failed") }
        val id = resourceId(entry.optNonBlankString("id") ?: throw unreadableResponse())
        if (entry.optNonBlankString("ready") == "true") return entry.toTorrent(DownloadStatus.READY)
        try {
            repeat(MAX_POLLS) { attempt ->
                if (attempt > 0) delay(pollDelayMs)
                val download = magnet(id).toTorrent()
                if (download.status == DownloadStatus.READY) return download
                if (download.status == DownloadStatus.ERROR) throw DebridError.NotCached()
                if (attempt == MAX_POLLS - 1 &&
                    (download.status == DownloadStatus.QUEUED || download.status == DownloadStatus.DOWNLOADING)
                ) return download
            }
            throw unreadableResponse()
        } catch (failure: Exception) {
            if (failure !is CancellationException && failure !is DebridError.NotCached) throw failure
            try {
                withContext(NonCancellable) { deleteMagnet(id) }
            } catch (cleanup: Exception) {
                if (failure is CancellationException) throw failure
                // Surface a failed cleanup so the caller knows the upload may still exist.
                if (cleanup is DebridError) throw cleanup
                throw DebridError.Provider("cleanup-failed", "AllDebrid could not delete the pending magnet")
            }
            throw failure
        }
    }

    private suspend fun deleteMagnet(id: String) {
        try {
            call("POST", "/v4/magnet/delete", mapOf("id" to id))
        } catch (e: DebridError.Provider) {
            if (e.code != "MAGNET_INVALID_ID") throw e
        }
    }

    private suspend fun magnet(id: String): JsonObject {
        val verifiedId = resourceId(id)
        return call("POST", MAGNET_STATUS, mapOf("id" to verifiedId)).objects("magnets")
            .firstOrNull { it.optNonBlankString("id") == verifiedId } ?: throw DebridError.NotCached()
    }

    private suspend fun savedLinks(): List<Pair<String, JsonObject>> =
        call("GET", "/v4/user/links").objects("links").mapNotNull { entry ->
            entry.optNonBlankString("link")?.takeIf { httpUri(it) != null && !containsKey(it) }?.let { it to entry }
        }

    private suspend fun savedLink(id: String): Pair<String, JsonObject> =
        savedLinks().firstOrNull { webId(it.first) == id } ?: throw DebridError.NotCached()

    private suspend fun unlock(link: String): JsonObject {
        addBudget.acquire()
        return call("POST", "/v4/link/unlock", mapOf("link" to checkedLink(link)))
    }

    private suspend fun directLink(unlocked: JsonObject): String {
        val delayed = unlocked.optLong("delayed")?.takeIf { it > 0 }
        if (delayed == null) return checkedLink(unlocked.optNonBlankString("link"))
        repeat(MAX_POLLS) {
            // The API asks for at least five seconds between delayed-link checks.
            delay(pollDelayMs.coerceAtLeast(5_000L))
            val data = call("POST", "/v4/link/delayed", mapOf("id" to delayed.toString()))
            when (data.optNonBlankString("status")) {
                "1" -> Unit
                "2" -> return checkedLink(data.optNonBlankString("link"))
                else -> throw DebridError.Provider("delayed-link-failed", "AllDebrid could not generate the link")
            }
        }
        throw DebridError.Provider("delayed-link-timeout", "AllDebrid is still generating the link")
    }

    private fun JsonObject.toTorrent(status: DownloadStatus = magnetStatus()): DebridDownload {
        val size = optLong("size")
        val downloaded = optLong("downloaded") ?: 0
        return DebridDownload(
            id = resourceId(optNonBlankString("id") ?: throw unreadableResponse()),
            service = service,
            name = optNonBlankString("filename") ?: optNonBlankString("name") ?: "Torrent",
            sizeBytes = size,
            status = status,
            progress = when {
                status == DownloadStatus.READY -> 1f
                size != null && size > 0 -> (downloaded.toDouble() / size).coerceIn(0.0, 1.0).toFloat()
                else -> 0f
            },
            files = files().map { it.second },
            kind = DownloadKind.TORRENT,
            createdAtEpochMs = epochMs("uploadDate"),
            downloadSpeedBytesPerSec = optLong("downloadSpeed"),
            seeds = optInt("seeders"),
        )
    }

    private fun JsonObject.magnetStatus(): DownloadStatus = when (optInt("statusCode")) {
        0 -> DownloadStatus.QUEUED
        in 1..3 -> DownloadStatus.DOWNLOADING
        4 -> DownloadStatus.READY
        in 5..15 -> DownloadStatus.ERROR
        else -> when (optNonBlankString("status")?.lowercase()) {
            "ready" -> DownloadStatus.READY
            "downloading" -> DownloadStatus.DOWNLOADING
            "in queue", "queued", "waiting" -> DownloadStatus.QUEUED
            "error" -> DownloadStatus.ERROR
            else -> DownloadStatus.UNKNOWN
        }
    }

    private fun JsonObject.files(): List<Pair<String, DebridFile>> = buildList {
        fun visit(entries: JsonArray, parent: String) {
            for (entry in entries.filterIsInstance<JsonObject>()) {
                val name = entry.optNonBlankString("n") ?: continue
                val path = parent + name
                val children = entry.get("e") as? JsonArray
                if (children != null) visit(children, "$path/")
                val link = entry.optNonBlankString("l")?.takeIf { httpUri(it) != null && !containsKey(it) } ?: continue
                // The tree has no file IDs. Hash the path to avoid exposing bearer links.
                add(link to DebridFile(opaqueId(path), name, entry.optLong("s") ?: 0, path))
            }
        }
        (get("files") as? JsonArray)?.let { visit(it, "") }
    }

    private fun webDownload(link: String, filename: String?, size: Long?, date: Long?): DebridDownload {
        val id = webId(link)
        val name = filename ?: "Download"
        return DebridDownload(
            id, service, name, size, DownloadStatus.READY, 1f,
            files = listOf(DebridFile(id, name, size ?: 0)),
            kind = DownloadKind.WEB,
            createdAtEpochMs = date,
        )
    }

    private suspend fun call(
        method: String,
        path: String,
        form: Map<String, String> = emptyMap(),
        multipart: List<MultipartPart> = emptyList(),
        authenticated: Boolean = true,
    ): JsonObject {
        generalBucket.acquire()
        val response = transport.execute(HttpRequest(
            method, BASE + path,
            headers = if (authenticated) mapOf("Authorization" to "Bearer $apiKey") else emptyMap(),
            formFields = form,
            multipart = multipart,
        ))
        if (response.status == 429) {
            val retryAfter = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }
                ?.value?.trim()?.toLongOrNull()
            throw DebridError.RateLimited(retryAfter)
        }
        if (response.status == 401) throw DebridError.AuthInvalid()
        val envelope = runCatching { JsonParser.parseString(response.body) }.getOrNull() as? JsonObject
        if (response.status !in 200..299 || envelope?.optNonBlankString("status") == "error") {
            throw apiError(envelope?.get("error") as? JsonObject, "http${response.status}")
        }
        if (envelope?.optNonBlankString("status") != "success") throw unreadableResponse()
        return envelope.get("data") as? JsonObject ?: throw unreadableResponse()
    }

    private fun apiError(error: JsonObject?, fallbackCode: String): DebridError {
        val code = error?.optNonBlankString("code")
        if (code?.startsWith("AUTH_") == true) return DebridError.AuthInvalid()
        val safeCode = code?.takeIf { it.matches(ERROR_CODE) && !containsKey(it) } ?: fallbackCode
        return DebridError.Provider(safeCode, safeDetail(error?.optNonBlankString("message")))
    }

    private fun safeDetail(message: String?): String {
        if (message.isNullOrBlank()) return "AllDebrid could not complete this request"
        val decoded = decode(message)
        if (containsKey(message) || decoded.contains("http", true) || decoded.contains("://") ||
            decoded.contains("magnet:", true) || decoded.contains("www.", true)
        ) return "AllDebrid could not complete this request"
        return message.take(300)
    }

    private fun checkedLink(link: String?): String =
        link?.takeIf { httpUri(it) != null && !containsKey(it) } ?: throw unreadableResponse()

    private fun containsKey(value: String): Boolean =
        apiKey.isNotEmpty() && (value.contains(apiKey) || decode(value).contains(apiKey))

    private fun resourceId(id: String): String {
        if (id.toLongOrNull()?.let { it > 0 } != true || !id.all { it in '0'..'9' } || containsKey(id)) {
            throw DebridError.NotCached()
        }
        return id
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun httpUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme?.lowercase() in setOf("http", "https") && it.host != null && it.userInfo == null
    }

    private fun webId(link: String) = WEB_PREFIX + opaqueId(link)
    private fun opaqueId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (get(key) as? JsonArray)?.filterIsInstance<JsonObject>() ?: throw unreadableResponse()

    private fun JsonObject.epochMs(key: String): Long? = optLong(key)
        ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)

    private fun unreadableResponse() =
        DebridError.Provider("bad-response", "AllDebrid returned an unreadable response")

    companion object {
        const val BASE = "https://api.alldebrid.com"
        private const val MAGNET_STATUS = "/v4.1/magnet/status"
        private const val MAX_POLLS = 10
        private const val WEB_PREFIX = "web:"
        private val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,79}")
    }
}
