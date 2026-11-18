/*
 * Verified against https://www.premiumize.me/api on 2026-11-18.
 * Base https://www.premiumize.me/api. API keys use Authorization: Bearer.
 * GET /account/info, /transfer/list, /folder/list, /item/listall, /item/details, /services/list.
 * POST /transfer/directdl, /transfer/create (src URL or multipart source),
 * /transfer/delete, /item/delete and /cache/check (repeated items[], parallel result arrays).
 * NZB: .nzb files and NZB URLs go through /transfer/create as `src`
 * (multipart file upload, or a URL string). The API reference mentions
 * .nzb only as a fetched source type, so NZB behaviour is unverified
 * against the live API (no Premiumize account exists here).
 * The reference documents rate_limit_reached without a numeric quota. Buckets are client defaults.
 * Direct downloads have no documented history endpoint. direct: IDs and their links are
 * instance-local. item: IDs refer to existing cloud files. Bare IDs refer to transfer records.
 */
package app.trove.debrid.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PremiumizeProvider(
    private val apiKey: String,
    private val transport: HttpTransport,
    private val generalBucket: TokenBucket = TokenBucket(4.0, 4),
    private val addBudget: TokenBucket = TokenBucket(0.5, 2),
    private val pollDelayMs: Long = 750,
) : DebridProvider {
    override val service = DebridService.PREMIUMIZE
    override val capabilities = ProviderCapabilities(
        magnet = true,
        torrentFile = true,
        hosterLink = true,
        nzb = true,
        readOnlyCacheCheck = true,
        cacheProbeMutating = false,
        fileSelection = false,
        pauseResume = false,
        authFlow = AuthFlow.API_KEY,
    )

    private class DirectDownload(val download: DebridDownload, val links: Map<String, StreamUrl>)

    private val directDownloads = ConcurrentHashMap<String, DirectDownload>()
    // Transfer list has no documented source kind. Keep the kind for this instance's NZB uploads.
    private val usenetTransfers = ConcurrentHashMap.newKeySet<String>()
    private val domainsMutex = Mutex()

    override suspend fun validateAccount(): Result<AccountInfo> = runCatching {
        val account = call("GET", "/account/info")
        val premium = (account.long("premium_until") ?: 0) > SystemClock.nowMillis() / 1000
        val plan = if (premium) "Premium" else "Free"
        val points = account.long("booster_points")
        AccountInfo(
            account.safeValue("customer_id"),
            premium,
            if (points == null) plan else "$plan · $points booster points",
        )
    }.mapError()

    override suspend fun listDownloads(): Result<List<DebridDownload>> = runCatching {
        val transfers = transfers()
        val cloudItems = call("GET", "/item/listall").objects("files").associateBy { it.id() }
        val downloads = transfers.map { it.toTransfer(cloudItems) }
        val transferFileIds = downloads.flatMap { it.files }.mapTo(mutableSetOf()) { it.id }
        val standaloneItems = cloudItems.filterKeys { it !in transferFileIds }.values.map { it.toItem() }
        (downloads + standaloneItems + directDownloads.values.map { it.download })
            .sortedByDescending { it.createdAtEpochMs ?: Long.MIN_VALUE }
    }.mapError()

    override suspend fun getDownload(id: String): Result<DebridDownload> = runCatching {
        when {
            id.startsWith(DIRECT_PREFIX) -> directDownload(id).download
            id.startsWith(ITEM_PREFIX) -> item(id.removePrefix(ITEM_PREFIX)).toItem()
            else -> transfer(id).toTransfer()
        }
    }.mapError()

    override suspend fun add(input: AddInput): Result<DebridDownload> = runCatching {
        addBudget.acquire()
        when (input) {
            is AddInput.Magnet -> {
                val source = input.magnetUri
                if (containsKey(source)) throw unreadableResponse()
                val direct = try {
                    directDownload(source, DownloadKind.TORRENT)
                } catch (e: DebridError.Provider) {
                    if (e.code !in DIRECT_MISS_CODES &&
                        !(e.code == "http200" && e.detail.contains("not cached", ignoreCase = true))
                    ) throw e
                    null
                }
                direct ?: createTransfer(DownloadKind.TORRENT, source)
            }
            is AddInput.HosterLink -> directDownload(checkedLink(input.url), DownloadKind.WEB)
                ?: throw DebridError.NotCached()
            is AddInput.TorrentFile -> upload(DownloadKind.TORRENT, input.bytes, input.fileName, "application/x-bittorrent")
            is AddInput.Nzb -> upload(DownloadKind.USENET, input.bytes, input.fileName, "application/x-nzb")
            is AddInput.NzbUrl -> createTransfer(DownloadKind.USENET, checkedLink(input.url))
        }
    }.mapError()

    override suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?> = runCatching {
        if (hashes.isEmpty()) return@runCatching emptyMap<String, CachedInfo>()
        val response = call(
            "POST", "/cache/check",
            multipart = hashes.map { hash ->
                val source = if (hash.startsWith("magnet:", true)) hash else "magnet:?xt=urn:btih:$hash"
                if (containsKey(source)) throw unreadableResponse()
                MultipartPart("items[]", null, source.toByteArray(), "text/plain")
            },
        )
        val hits = response.array("response")
        if (hits.size() != hashes.size) throw unreadableResponse()
        val names = response.get("filename") as? JsonArray
        val sizes = response.get("filesize") as? JsonArray
        hashes.mapIndexed { index, hash ->
            val hit = hits[index].text()?.toBooleanStrictOrNull() ?: throw unreadableResponse()
            hash to if (hit) {
                CachedInfo(true, safeText(names?.getOrNull(index).text()), sizes?.getOrNull(index).text()?.toLongOrNull())
            } else CachedInfo(false, null, null)
        }.toMap()
    }.mapError()

    override suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl> = runCatching {
        if (downloadId.startsWith(DIRECT_PREFIX)) {
            return@runCatching directDownload(downloadId).links[fileId] ?: throw DebridError.NotCached()
        }
        val cloudFile = if (downloadId.startsWith(ITEM_PREFIX)) {
            if (downloadId.removePrefix(ITEM_PREFIX) != fileId) throw DebridError.NotCached()
            item(fileId)
        } else {
            val entry = transfer(downloadId)
            if (entry.transferStatus() != DownloadStatus.READY) throw DebridError.NotCached()
            val singleFileId = entry.value("file_id")
            if (singleFileId != null) {
                if (singleFileId != fileId) throw DebridError.NotCached()
                item(fileId)
            } else {
                val folder = entry.value("folder_id") ?: throw DebridError.NotCached()
                folderFiles(folder).firstOrNull { it.first.value("id") == fileId }?.first
                    ?: throw DebridError.NotCached()
            }
        }
        // Cloud APIs already return a per-file CDN link.
        StreamUrl(checkedLink(cloudFile.value("link")))
    }.mapError()

    override suspend fun control(id: String, action: ControlAction): Result<Unit> =
        Result.failure(DebridError.UnsupportedCapability("Premiumize transfer controls are unavailable"))

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        when {
            id.startsWith(DIRECT_PREFIX) -> directDownloads.remove(id)
            id.startsWith(ITEM_PREFIX) -> call("POST", "/item/delete", mapOf("id" to resourceId(id.removePrefix(ITEM_PREFIX))))
            else -> deleteTransfer(id)
        }
        Unit
    }.mapError()

    override suspend fun supportsUrl(url: String): Result<Boolean> = try {
        val host = httpUri(url)?.host?.lowercase()?.trimEnd('.')
        Result.success(host != null && (hosterDomains().any { host == it || host.endsWith(".$it") } ||
            hosterPatterns().any { pattern -> runCatching { pattern.matches(url) }.getOrDefault(false) }))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.success(false)
    }

    /** The documented URL-detection patterns from /services/list
     *  (regexpatterns), cached with the hoster domains. Service names
     *  in `directdl` are not domains, so patterns are the reliable
     *  match for real hoster URLs; the domain list stays as a fallback. */
    private suspend fun hosterPatterns(): List<Regex> {
        hosterDomains()
        return cachedPatterns
    }

    private var cachedDomains = emptySet<String>()
    private var cachedPatterns = emptyList<Regex>()
    private var domainsExpireAt = 0L

    suspend fun hosterDomains(): Set<String> = domainsMutex.withLock {
        if (SystemClock.nowMillis() >= domainsExpireAt) {
            val services = try {
                call("GET", "/services/list", authenticated = false)
            } catch (_: DebridError.AuthInvalid) {
                call("GET", "/services/list")
            }
            val names = services.array("directdl").mapNotNull { it.text() }
            val aliases = services.get("aliases") as? JsonObject
            cachedDomains = names.flatMap { name ->
                listOf(name) + (aliases?.get(name) as? JsonArray).orEmptyStrings()
            }.mapNotNull { name ->
                val domain = name.trim().lowercase().trimEnd('.')
                domain.takeIf { it.matches(DOMAIN) }
            }.toSet()
            cachedPatterns = (services.get("regexpatterns") as? JsonObject)?.entrySet()
                ?.flatMap { (_, patterns) -> (patterns as? JsonArray).orEmptyStrings() }
                ?.mapNotNull { pattern -> runCatching { Regex(pattern) }.getOrNull() }
                .orEmpty()
            domainsExpireAt = SystemClock.nowMillis() + 3_600_000
        }
        cachedDomains
    }

    private suspend fun directDownload(source: String, kind: DownloadKind): DebridDownload? {
        val content = call("POST", "/transfer/directdl", mapOf("src" to source)).objects("content")
        if (content.isEmpty()) return null
        val id = DIRECT_PREFIX + opaqueId("${kind.name}:$source")
        val links = mutableMapOf<String, StreamUrl>()
        val files = content.map { entry ->
            val path = entry.safeValue("path") ?: throw unreadableResponse()
            val fileId = opaqueId(path)
            links[fileId] = StreamUrl(checkedLink(entry.value("link")))
            DebridFile(fileId, path.substringAfterLast('/'), entry.long("size") ?: 0, path)
        }
        val download = DebridDownload(
            id, service, files.first().name, files.sumOf { it.sizeBytes }, DownloadStatus.READY, 1f,
            files = files, kind = kind, createdAtEpochMs = SystemClock.nowMillis(),
        )
        directDownloads[id] = DirectDownload(download, links)
        return download
    }

    private fun directDownload(id: String): DirectDownload =
        directDownloads[id] ?: throw DebridError.NotCached()

    private suspend fun upload(kind: DownloadKind, bytes: ByteArray, name: String, mime: String): DebridDownload =
        createTransfer(kind, upload = listOf(MultipartPart("src", safeText(name) ?: "transfer", bytes, mime)))

    private suspend fun createTransfer(
        kind: DownloadKind,
        source: String? = null,
        upload: List<MultipartPart> = emptyList(),
    ): DebridDownload {
        val created = call("POST", "/transfer/create", source?.let { mapOf("src" to it) }.orEmpty(), upload)
        val id = created.id()
        if (kind == DownloadKind.USENET) usenetTransfers.add(id)
        var latest = DebridDownload(
            id, service, created.safeValue("name") ?: "Transfer", null, DownloadStatus.QUEUED, 0f, kind = kind,
        )
        try {
            repeat(MAX_POLLS) { attempt ->
                currentCoroutineContext().ensureActive()
                if (attempt > 0) delay(pollDelayMs)
                val entry = transfers().firstOrNull { it.value("id") == id } ?: return@repeat
                latest = entry.toTransfer()
                if (latest.status == DownloadStatus.READY) return latest
                if (latest.status == DownloadStatus.ERROR) {
                    throw DebridError.Provider("transfer-failed", latest.errorMessage.orEmpty())
                }
            }
            return latest
        } catch (failure: Exception) {
            if (failure !is CancellationException &&
                !(failure is DebridError.Provider && failure.code == "transfer-failed")
            ) throw failure
            try {
                withContext(NonCancellable) { deleteTransfer(id) }
            } catch (cleanup: Exception) {
                if (failure !is CancellationException) throw cleanup
            }
            throw failure
        }
    }

    private suspend fun deleteTransfer(id: String) {
        call("POST", "/transfer/delete", mapOf("id" to resourceId(id)))
        usenetTransfers.remove(id)
    }

    private suspend fun transfers(): List<JsonObject> = call("GET", "/transfer/list").objects("transfers")

    private suspend fun transfer(id: String): JsonObject {
        val verifiedId = resourceId(id)
        return transfers().firstOrNull { it.value("id") == verifiedId } ?: throw DebridError.NotCached()
    }

    private suspend fun item(id: String): JsonObject =
        call("GET", "/item/details?id=${encode(resourceId(id))}")

    private suspend fun folderFiles(id: String): List<Pair<JsonObject, String?>> {
        val pending = ArrayDeque<Pair<String, String?>>()
        val visited = mutableSetOf<String>()
        val files = mutableListOf<Pair<JsonObject, String?>>()
        pending.add(id to null)
        while (pending.isNotEmpty()) {
            val (folderId, dir) = pending.removeFirst()
            if (!visited.add(folderId)) continue
            val response = call("GET", "/folder/list?id=${encode(resourceId(folderId))}")
            for (entry in response.objects("content")) {
                val name = entry.safeValue("name")
                val path = if (dir == null) name else "$dir/${name ?: "File"}"
                when (entry.value("type")) {
                    "folder" -> pending.add(entry.id() to path)
                    "file" -> files.add(entry to path)
                }
            }
        }
        return files
    }

    private suspend fun JsonObject.toTransfer(cloudItems: Map<String, JsonObject>? = null): DebridDownload {
        val id = id()
        val status = transferStatus()
        val fileId = value("file_id")
        val folderId = value("folder_id")
        val files = try {
            when {
                status != DownloadStatus.READY -> emptyList()
                fileId != null -> {
                    val file = if (cloudItems == null) item(fileId) else cloudItems[fileId]
                    file?.let { listOf(it.toFile(path = null)) }.orEmpty()
                }
                folderId != null -> folderFiles(folderId).map { (entry, path) -> entry.toFile(path) }
                else -> emptyList()
            }
        } catch (e: DebridError.Provider) {
            if (e.code != "not_found") throw e
            emptyList()
        }
        return DebridDownload(
            id, service, safeValue("name") ?: "Transfer",
            files.takeIf { it.isNotEmpty() }?.sumOf { it.sizeBytes },
            status,
            if (status == DownloadStatus.READY) 1f else
                value("progress")?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
            files = files,
            kind = if (id in usenetTransfers) DownloadKind.USENET else DownloadKind.TORRENT,
            createdAtEpochMs = epochMs(),
            errorMessage = if (status == DownloadStatus.ERROR) safeDetail(value("message")) else null,
        )
    }

    private fun JsonObject.transferStatus(): DownloadStatus = when (value("status")?.lowercase()) {
        "finished", "seeding" -> DownloadStatus.READY
        "running", "downloading" -> DownloadStatus.DOWNLOADING
        "queued", "waiting" -> DownloadStatus.QUEUED
        "error" -> DownloadStatus.ERROR
        else -> DownloadStatus.UNKNOWN
    }

    private fun JsonObject.toItem(): DebridDownload {
        val file = toFile()
        return DebridDownload(
            ITEM_PREFIX + file.id, service, file.name, long("size"), DownloadStatus.READY, 1f,
            files = listOf(file), kind = DownloadKind.WEB, createdAtEpochMs = epochMs(),
        )
    }

    private fun JsonObject.toFile(path: String? = safeValue("path")) =
        DebridFile(id(), safeValue("name") ?: "File", long("size") ?: 0, path)

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
        val envelope = runCatching { JsonParser.parseString(response.body) }.getOrNull() as? JsonObject
        val code = envelope?.value("code")
        if (response.status == 429 || code == "rate_limit_reached") {
            val retryAfter = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }
                ?.value?.trim()?.let(::retryAfterSeconds)
            throw DebridError.RateLimited(retryAfter)
        }
        if (response.status == 401 || code == "authentication_failed") throw DebridError.AuthInvalid()
        if (response.status !in 200..299 || envelope?.value("status") == "error") {
            throw apiError(code, envelope?.value("message"), "http${response.status}")
        }
        if (envelope?.value("status") != "success") throw unreadableResponse()
        return envelope
    }

    private fun apiError(code: String?, message: String?, fallbackCode: String): DebridError {
        val normalized = message.orEmpty().lowercase()
        if ("authentication failed" in normalized || AUTH_MESSAGE.containsMatchIn(normalized)) {
            return DebridError.AuthInvalid()
        }
        val safeCode = code?.takeIf { it.matches(ERROR_CODE) && !containsKey(it) } ?: fallbackCode
        val detail = when {
            "points" in normalized && POINTS_LIMIT.containsMatchIn(normalized) ->
                "Premiumize points exhausted. Wait for points to replenish or add booster points."
            code == "account_limit_reached" ->
                "Premiumize reached its account limit for points or active transfers."
            else -> safeDetail(message)
        }
        return DebridError.Provider(safeCode, detail)
    }

    private fun retryAfterSeconds(value: String): Long? {
        value.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
        return runCatching {
            val epochSeconds = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
            (epochSeconds - SystemClock.nowMillis() / 1000).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun safeDetail(message: String?): String =
        safeText(message)?.take(300) ?: "Premiumize could not complete this request"

    private fun safeText(value: String?): String? {
        if (value.isNullOrBlank() || containsKey(value)) return null
        val decoded = decode(value)
        return value.takeUnless {
            decoded.contains("http", true) || decoded.contains("://") ||
                decoded.contains("magnet:", true) || decoded.contains("www.", true)
        }
    }

    private fun checkedLink(value: String?): String =
        value?.takeIf { httpUri(it) != null && !containsKey(it) } ?: throw unreadableResponse()

    private fun httpUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        (it.scheme.equals("http", true) || it.scheme.equals("https", true)) && it.host != null && it.userInfo == null
    }

    private fun containsKey(value: String): Boolean {
        if (apiKey.isEmpty()) return false
        // Decode query components without treating literal plus signs as spaces.
        val decoded = decode(value)
        return value.contains(apiKey) || decoded.contains(apiKey) ||
            decode(value.replace("+", "%2B")).contains(apiKey)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun opaqueId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray()).toString()

    private fun resourceId(value: String): String =
        value.takeIf { it.matches(RESOURCE_ID) && !containsKey(it) } ?: throw DebridError.NotCached()

    private fun JsonObject.id(): String = resourceId(value("id") ?: throw unreadableResponse())
    private fun JsonObject.safeValue(key: String): String? = safeText(value(key))
    private fun JsonObject.value(key: String): String? = get(key).text()
    private fun JsonObject.long(key: String): Long? = value(key)?.toLongOrNull()
    private fun JsonObject.epochMs(): Long? = long("created_at")
        ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)

    private fun JsonElement?.text(): String? = this?.takeIf { it.isJsonPrimitive }
        ?.asString?.takeIf(String::isNotBlank)

    private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index < size()) get(index) else null
    private fun JsonArray?.orEmptyStrings(): List<String> = this?.mapNotNull { it.text() }.orEmpty()
    private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: throw unreadableResponse()
    private fun JsonObject.objects(key: String): List<JsonObject> = array(key).filterIsInstance<JsonObject>()

    private fun unreadableResponse() =
        DebridError.Provider("bad-response", "Premiumize returned an unreadable response")

    private fun <T> Result<T>.mapError(): Result<T> {
        val failure = exceptionOrNull() ?: return this
        if (failure is CancellationException || failure !is Exception) throw failure
        return Result.failure(if (failure is DebridError) failure else DebridError.Network(failure))
    }

    companion object {
        const val BASE = "https://www.premiumize.me/api"
        private const val MAX_POLLS = 10
        private const val DIRECT_PREFIX = "direct:"
        private const val ITEM_PREFIX = "item:"
        private val DIRECT_MISS_CODES = setOf("not_found", "link_generation_failed")
        private val RESOURCE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val ERROR_CODE = Regex("[a-zA-Z][a-zA-Z0-9_]{0,79}")
        private val DOMAIN = Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
        private val AUTH_MESSAGE = Regex("(?:invalid|missing|expired).*?(?:api[ _-]?key|token)|(?:api[ _-]?key|token).*?(?:invalid|missing|expired)")
        private val POINTS_LIMIT = Regex("exhaust|insufficient|not enough|limit|no points")
    }
}
