/*
 * Research checked 2026-11-18 against the official API data and error list
 * (https://debrid-link.com/api/api_doc/infos and /api/api_doc/errors),
 * stremthru's store/debridlink client and types, and awesome-debrid's service list.
 * Use https://debrid-link.com/api/v2 and Bearer API-key auth as in stremthru.
 * Older research and the docs' OAuth try-it-out use an access_token query parameter.
 * OAuth device flow is deferred. The live envelope uses success/value/error,
 * while the docs' sampleResult still uses the v1 result envelope.
 * Seedbox add sends both url (stremthru) and torrentUrl (official docs).
 * Accept file size/length and downloadPercent/percentDownload, torrent
 * created/addedDate and downloadPercent/percentDownload, downloader
 * downloadUrl/downloadLink, name/filename, created/time, and account username/pseudo.
 * The read-only /seedbox/cached endpoint was removed. Client buckets are
 * conservative: general 2/s burst 2, add 0.2/s burst 1, ten polls at 750 ms.
 * UNVERIFIED: authenticated live API and on-device behavior (no account here),
 * downloader/add array shape, isZip expansion via ids, and acceptance of both add keys.
 */
package app.trove.debrid.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class DebridLinkProvider(
    private val apiKey: String,
    private val transport: HttpTransport,
    private val generalBucket: TokenBucket = TokenBucket(2.0, 2),
    private val addBudget: TokenBucket = TokenBucket(0.2, 1),
    private val pollDelayMs: Long = 750,
) : DebridProvider {
    override val service = DebridService.DEBRID_LINK
    override val capabilities = ProviderCapabilities(
        magnet = true,
        torrentFile = true,
        hosterLink = true,
        nzb = false,
        readOnlyCacheCheck = false,
        cacheProbeMutating = true,
        fileSelection = false,
        pauseResume = false,
        multiFilePick = true,
        maxActiveDownloads = 20,
        authFlow = AuthFlow.API_KEY,
    )

    private class DownloadRecord(val download: DebridDownload, val links: Map<String, String>)
    private class HosterRules(val domains: Set<String>, val patterns: List<Regex>)

    private val hosterMutex = Mutex()
    private var cachedRules: HosterRules? = null
    private var rulesExpireAt = 0L

    override suspend fun validateAccount(): Result<AccountInfo> = runCatching {
        val account = call("GET", "/account/infos").get("value").obj()
        val premium = account.number("accountType") in setOf(1L, 2L) ||
            (account.number("premiumLeft") ?: 0) > 0
        val plan = if (premium) "Premium" else "Free"
        val points = account.number("pts")
        AccountInfo(
            safeText(account.value("username", "pseudo")),
            premium,
            if (points == null) plan else "$plan · $points pts",
        )
    }.mapError()

    override suspend fun listDownloads(): Result<List<DebridDownload>> = runCatching {
        val downloads = mutableListOf<DebridDownload>()
        collectPages("/seedbox/list", "&structureType=list") { downloads += it.seedbox().download }
        collectPages("/downloader/list") { downloads += it.downloader().download }
        downloads.distinctBy { it.id }.sortedByDescending { it.createdAtEpochMs ?: Long.MIN_VALUE }
    }.mapError()

    private suspend fun collectPages(path: String, options: String = "", visit: (JsonObject) -> Unit) {
        var page = 0
        while (true) {
            val response = call("GET", "$path?page=$page&perPage=$PAGE_SIZE$options")
            val entries = response.get("value").array()
            entries.forEach { visit(it.obj()) }
            val pages = (response.get("pagination") as? JsonObject)?.number("pages")
            page++
            if (entries.size() < PAGE_SIZE || pages == null || page >= pages) return
        }
    }

    override suspend fun getDownload(id: String): Result<DebridDownload> = runCatching {
        getRecord(id).download
    }.mapError()

    private suspend fun getRecord(id: String): DownloadRecord = when {
        id.startsWith(SEEDBOX_PREFIX) -> findSeedbox(resourceId(id.removePrefix(SEEDBOX_PREFIX)))
        id.startsWith(DOWNLOADER_PREFIX) -> findDownloader(resourceId(id.removePrefix(DOWNLOADER_PREFIX)))
        else -> resourceId(id).let { findSeedbox(it) ?: findDownloader(it) }
    } ?: throw DebridError.NotCached()

    private suspend fun findSeedbox(id: String): DownloadRecord? =
        find("/seedbox/list?ids=$id&perPage=20", id)?.seedbox()

    private suspend fun findDownloader(id: String): DownloadRecord? =
        find("/downloader/list?ids=$id", id)?.downloader()

    private suspend fun find(path: String, id: String): JsonObject? = try {
        call("GET", path).get("value").array().asSequence().map { it.obj() }.firstOrNull { it.value("id") == id }
    } catch (error: DebridError.Provider) {
        if (error.code != "badId") throw error
        null
    }

    override suspend fun add(input: AddInput): Result<DebridDownload> = runCatching {
        when (input) {
            is AddInput.Magnet -> addTorrent(json = JsonObject().apply {
                if (containsKey(input.magnetUri)) throw invalidSource()
                addProperty("url", input.magnetUri)
                addProperty("torrentUrl", input.magnetUri)
                addProperty("structureType", "list")
            })
            is AddInput.TorrentFile -> addTorrent(upload = listOf(
                MultipartPart("file", safeText(input.fileName) ?: "upload.torrent", input.bytes, "application/x-bittorrent"),
            ))
            is AddInput.HosterLink -> {
                if (containsKey(input.url) || webUri(input.url) == null) throw invalidSource()
                addBudget.acquire()
                val body = JsonObject().apply { addProperty("link", input.url) }
                val value = call("POST", "/downloader/add", json = body).get("value")
                val entry = if (value is JsonArray) value.firstOrNull() else value
                entry.obj().downloader().download
            }
            is AddInput.Nzb, is AddInput.NzbUrl ->
                throw DebridError.UnsupportedCapability("Debrid-Link has no Usenet support")
        }
    }.mapError()

    private suspend fun addTorrent(
        json: JsonObject? = null,
        upload: List<MultipartPart> = emptyList(),
    ): DebridDownload {
        addBudget.acquire()
        val created = call(
            "POST", "/seedbox/add",
            form = if (upload.isEmpty()) emptyMap() else mapOf("structureType" to "list"),
            multipart = upload, json = json,
        ).get("value").obj()
        val id = created.id()
        try {
            var latest = created.seedbox().download
            if (latest.status == DownloadStatus.READY && latest.files.isNotEmpty() &&
                created.value("isZip") != "true"
            ) {
                currentCoroutineContext().ensureActive()
                return latest
            }
            repeat(MAX_POLLS) {
                currentCoroutineContext().ensureActive()
                if (latest.status == DownloadStatus.ERROR) return finishTorrentAdd(latest)
                delay(pollDelayMs)
                val refreshed = findSeedbox(id)?.download
                if (refreshed != null) {
                    latest = refreshed
                    if (latest.status == DownloadStatus.READY && latest.files.isNotEmpty()) return latest
                }
            }
            currentCoroutineContext().ensureActive()
            return finishTorrentAdd(latest)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { delete(SEEDBOX_PREFIX + id) }
            throw cancelled
        }
    }

    private suspend fun finishTorrentAdd(download: DebridDownload): DebridDownload {
        if (download.status == DownloadStatus.ERROR) {
            delete(download.id)
            throw DebridError.Provider("transfer-failed", download.errorMessage ?: "Debrid-Link transfer failed")
        }
        return download
    }

    // The endpoint was removed. The caller decides whether to attempt a mutating probe.
    override suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?> =
        Result.success(null)

    override suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl> = runCatching {
        val link = getRecord(downloadId).links[fileId]?.takeUnless(::containsKey)
            ?.takeIf { webUri(it) != null } ?: throw DebridError.NotCached()
        StreamUrl(link)
    }.mapError()

    override suspend fun control(id: String, action: ControlAction): Result<Unit> =
        Result.failure(DebridError.UnsupportedCapability("Debrid-Link has no pause/resume"))

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        val downloader = id.startsWith(DOWNLOADER_PREFIX)
        val route = if (downloader) "downloader" else "seedbox"
        val rawId = resourceId(id.removePrefix(if (downloader) DOWNLOADER_PREFIX else SEEDBOX_PREFIX))
        call("DELETE", "/$route/$rawId/remove")
        Unit
    }.mapError()

    override suspend fun supportsUrl(url: String): Result<Boolean> = try {
        val host = webUri(url)?.host?.lowercase()?.trimEnd('.')
        val supported = if (host == null || containsKey(url)) false else {
            val rules = hosterRules()
            rules.domains.any { host == it || host.endsWith(".$it") } ||
                rules.patterns.any { it.containsMatchIn(url) }
        }
        Result.success(supported)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.success(false)
    }

    suspend fun hosterDomains(): Set<String> = hosterRules().domains

    private suspend fun hosterRules(): HosterRules = hosterMutex.withLock {
        cachedRules?.takeIf { SystemClock.nowMillis() < rulesExpireAt }?.let { return@withLock it }
        val domains = call("GET", "/downloader/domains").get("value").array()
            .mapNotNull { it.text()?.trim()?.lowercase()?.trimEnd('.') }
            .filter { DOMAIN.matches(it) && !containsKey(it) }.toSet()
        val patterns = call("GET", "/downloader/regex").get("value").array()
            .flatMap { (it.obj().get("regexs") as? JsonArray)?.toList().orEmpty() }
            .mapNotNull { entry -> entry.text()?.let { runCatching { Regex(it) }.getOrNull() } }
        HosterRules(domains, patterns).also {
            cachedRules = it
            rulesExpireAt = SystemClock.nowMillis() + 3_600_000
        }
    }

    private fun JsonObject.seedbox(): DownloadRecord {
        val percent = percent()
        val status = if ((value("error")?.toIntOrNull() ?: 0) != 0) DownloadStatus.ERROR else {
            when (value("status")?.toIntOrNull()) {
                0 -> DownloadStatus.PAUSED
                1 -> DownloadStatus.QUEUED
                2, 4 -> DownloadStatus.DOWNLOADING
                8 -> if (percent == null || percent >= 100f) DownloadStatus.READY else DownloadStatus.DOWNLOADING
                100 -> DownloadStatus.READY
                else -> DownloadStatus.UNKNOWN
            }
        }
        val files = mutableListOf<DebridFile>()
        val links = mutableMapOf<String, String>()
        (get("files") as? JsonArray)?.forEach { entry ->
            val file = entry.obj()
            val id = file.value("id")?.takeUnless(::containsKey) ?: return@forEach
            val name = safeText(file.value("name")) ?: "File"
            files += DebridFile(id, name.substringAfterLast('/'), file.number("size", "length") ?: 0, "/" + name.trimStart('/'))
            if (file.percent() == 100f) file.value("downloadUrl", "downloadLink")?.let { links[id] = it }
        }
        return DownloadRecord(
            DebridDownload(
                SEEDBOX_PREFIX + id(), service, safeText(value("name")) ?: "Debrid-Link torrent",
                number("totalSize"), status, (percent ?: 0f).div(100f).coerceIn(0f, 1f),
                files = files, kind = DownloadKind.TORRENT,
                createdAtEpochMs = epochMillis("created", "addedDate"),
                errorMessage = if (status == DownloadStatus.ERROR) safeText(value("errorString", "message")) else null,
                downloadSpeedBytesPerSec = number("downloadSpeed"),
            ),
            links,
        )
    }

    private fun JsonObject.downloader(): DownloadRecord {
        val id = id()
        val name = safeText(value("name", "filename")) ?: "Debrid-Link download"
        val size = number("size", "length")
        val link = value("downloadUrl", "downloadLink")
        val status = when {
            value("expired") == "true" -> DownloadStatus.ERROR
            value("isProcessing") == "true" -> DownloadStatus.DOWNLOADING
            link != null -> DownloadStatus.READY
            else -> DownloadStatus.DOWNLOADING
        }
        return DownloadRecord(
            DebridDownload(
                DOWNLOADER_PREFIX + id, service, name, size, status,
                if (status == DownloadStatus.READY) 1f else 0f,
                files = listOf(DebridFile(id, name, size ?: 0, "/$name")),
                kind = DownloadKind.WEB,
                createdAtEpochMs = epochMillis("created", "time"),
            ),
            if (link == null) emptyMap() else mapOf(id to link),
        )
    }

    private suspend fun call(
        method: String,
        path: String,
        form: Map<String, String> = emptyMap(),
        multipart: List<MultipartPart> = emptyList(),
        json: JsonObject? = null,
    ): JsonObject {
        if (apiKey.isBlank()) throw DebridError.AuthInvalid()
        generalBucket.acquire()
        val request = HttpRequest(
            method, "$BASE/v2$path", headers = mapOf("Authorization" to "Bearer $apiKey"),
            formFields = form, multipart = multipart, jsonBody = json?.toString(),
        )
        val response = try {
            transport.execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw DebridError.Network(IllegalStateException("Debrid-Link request failed"))
        }
        if (response.status in setOf(401, 403)) throw DebridError.AuthInvalid()
        if (response.status == 429) {
            val retryAfter = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }
                ?.value?.trim()?.let(::retryAfterSeconds)
            throw DebridError.RateLimited(retryAfter)
        }
        val envelope = try {
            JsonParser.parseString(response.body) as? JsonObject
        } catch (_: JsonParseException) {
            null
        }
        val success = envelope?.value("success")
        if (response.status !in 200..299 || success == "false") {
            val code = envelope?.value("error")?.takeIf { RESOURCE_ID.matches(it) && !containsKey(it) }
                ?: "http${response.status}"
            val detail = safeText(envelope?.value("error_description")).orEmpty()
            throw if (method == "DELETE" && success == "false") DebridError.Provider(code, detail) else when (code) {
                in AUTH_CODES -> DebridError.AuthInvalid()
                "floodDetected" -> DebridError.RateLimited(3600)
                "freeServerOverload" -> DebridError.RateLimited(null)
                "infringingFile" -> DebridError.NameRefused(detail)
                in QUOTA_CODES -> DebridError.QuotaExceeded(detail)
                else -> DebridError.Provider(code, detail)
            }
        }
        return envelope?.takeIf { success == "true" } ?: throw unreadableResponse()
    }

    private fun retryAfterSeconds(value: String): Long? {
        value.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
        return runCatching {
            val deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
            (deadline - SystemClock.nowMillis() / 1000).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun resourceId(id: String): String =
        id.takeIf { RESOURCE_ID.matches(it) && !containsKey(it) } ?: throw DebridError.NotCached()

    private fun containsKey(value: String): Boolean =
        apiKey.isNotEmpty() && (value.contains(apiKey) || decode(value).contains(apiKey) ||
            decode(value.replace("+", "%2B")).contains(apiKey))

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }

    private fun safeText(value: String?): String? =
        value?.takeUnless { containsKey(it) || LINK.containsMatchIn(decode(it)) }?.take(240)

    private fun webUri(value: String): URI? = runCatching { URI(value) }.getOrNull()
        ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") && it.host != null && it.userInfo == null }

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: throw unreadableResponse()
    private fun JsonElement?.array(): JsonArray = this as? JsonArray ?: throw unreadableResponse()
    private fun JsonElement?.text(): String? = this?.takeIf { it.isJsonPrimitive }
        ?.asString?.takeIf { it.isNotBlank() }
    private fun JsonObject.value(key: String, alias: String? = null): String? =
        get(key).text() ?: alias?.let { get(it).text() }
    private fun JsonObject.number(key: String, alias: String? = null): Long? =
        value(key, alias)?.toLongOrNull()?.takeIf { it >= 0 }
    private fun JsonObject.percent(): Float? =
        value("downloadPercent", "percentDownload")?.toFloatOrNull()?.takeIf { it.isFinite() }
    private fun JsonObject.epochMillis(key: String, alias: String): Long? =
        number(key, alias)?.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000)
    private fun JsonObject.id(): String = resourceId(value("id") ?: throw unreadableResponse())

    private fun invalidSource() = DebridError.Provider("invalid-input", "Invalid source link")
    private fun unreadableResponse() = DebridError.Provider("bad-response", "Debrid-Link returned an unreadable response")

    private fun <T> Result<T>.mapError(): Result<T> {
        val failure = exceptionOrNull() ?: return this
        if (failure is CancellationException || failure !is Exception) throw failure
        return Result.failure(if (failure is DebridError) failure else DebridError.Network(failure))
    }

    companion object {
        const val BASE = "https://debrid-link.com/api"
        private const val SEEDBOX_PREFIX = "seedbox:"
        private const val DOWNLOADER_PREFIX = "dl:"
        private const val MAX_POLLS = 10
        private const val PAGE_SIZE = 100
        private val RESOURCE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val DOMAIN = Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
        private val LINK = Regex("(?:https?://|magnet:)", RegexOption.IGNORE_CASE)
        private val AUTH_CODES = setOf("badToken", "expired_token", "access_denied", "invalid_client")
        private val QUOTA_CODES = setOf(
            "maxTorrent", "maxTransfer", "maxLink", "maxLinkHost", "maxData", "maxDataHost", "torrentTooBig",
        )
    }
}
