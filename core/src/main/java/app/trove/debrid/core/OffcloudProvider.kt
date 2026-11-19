/*
 * Public sources checked 2026-11-18. https://offcloud.com/api redirects to login.
 * Offcloud's published reference (https://github.com/Offcloud/offcloud-api)
 * documents ?key= on https://offcloud.com, POST /api/cloud (url),
 * POST /api/cloud/status (requestId), GET /api/cloud/explore/{requestId},
 * POST /api/remote/accounts (authenticated account probe), and POST /api/cache (hashes).
 * https://packagist.org/packages/patriotblog/php-offcloud confirms query auth and POST
 * variables but uses the older ?apikey= spelling. This adapter uses ?key=.
 * POST /cloud/history (page) and GET /cloud/remove/{requestId} are shown by
 * https://github.com/mirror/jdownloader/blob/master/src/jd/plugins/hoster/OffCloudCom.java.
 * API-key auth on those two routes is UNVERIFIED: that client uses a session.
 * POST /api/sites is described at https://pkg.go.dev/github.com/junkerderprovinz/knightloader/internal/resolver/debrid.
 * Status, cache and file response examples are corroborated by
 * https://pkg.go.dev/github.com/SillyHippy/stremthru/store/offcloud.
 * Account identity/plan endpoint with API-key auth: UNVERIFIED. The probe returns remote
 * account records, so those usernames aren't used as the Offcloud account label.
 * Single-transfer refresh uses cloud/status. History supplies source kind and creation time.
 * checkCached uses the documented read-only batch endpoint and its cachedItems response.
 * Raw .torrent and NZB upload routes: UNVERIFIED. The public Usenet page describes UI
 * uploads and newsgroup links, but gives no API upload contract. NZB URLs use the
 * documented cloud URL submission, based on https://offcloud.com/usenet and Offcloud's
 * published Fetch integration (https://github.com/Offcloud/offcloud-fetch).
 * Pause/resume: UNVERIFIED. The npm offcloud-api README was inaccessible.
 * No documented numeric rate quota. Client buckets: general 2.0/s, add 0.5/s, burst 2 each.
 * no live Offcloud account exists here — no live API verification, no on-device check yet
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
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

class OffcloudProvider(
    private val apiKey: String,
    private val transport: HttpTransport,
    private val generalBucket: TokenBucket = TokenBucket(2.0, 2),
    private val addBudget: TokenBucket = TokenBucket(0.5, 2),
    private val pollDelayMs: Long = 750,
) : DebridProvider {
    override val service = DebridService.OFFCLOUD
    override val capabilities = ProviderCapabilities(
        magnet = true,
        torrentFile = false,
        hosterLink = true,
        nzb = true,
        readOnlyCacheCheck = true,
        cacheProbeMutating = false,
        pauseResume = false,
        multiFilePick = true,
        authFlow = AuthFlow.API_KEY,
    )

    private class CloudFile(val file: DebridFile, val link: StreamUrl)

    override suspend fun validateAccount(): Result<AccountInfo> = runCatching {
        if (call("POST", "/api/remote/accounts") !is JsonArray) throw unreadableResponse()
        AccountInfo(username = null, isPremium = false, detail = null)
    }.mapError()

    override suspend fun listDownloads(): Result<List<DebridDownload>> = runCatching {
        val downloads = mutableListOf<DebridDownload>()
        visitHistory { entries ->
            downloads += entries.map { it.toDownload() }
            false
        }
        downloads.distinctBy { it.id }.sortedByDescending { it.createdAtEpochMs ?: Long.MIN_VALUE }
    }.mapError()

    override suspend fun getDownload(id: String): Result<DebridDownload> = runCatching {
        val safeId = resourceId(id)
        var entry: DebridDownload? = null
        visitHistory { entries ->
            entry = entries.firstOrNull { it.optNonBlankString("requestId") == safeId }?.toDownload()
            entry != null
        }
        val fresh = status(safeId, entry)
        withFiles(fresh)
    }.mapError()

    override suspend fun add(input: AddInput): Result<DebridDownload> = runCatching {
        when (input) {
            is AddInput.Magnet -> addCloud(input.magnetUri, DownloadKind.TORRENT, poll = true)
            is AddInput.HosterLink -> addCloud(input.url, DownloadKind.WEB)
            is AddInput.NzbUrl -> addCloud(input.url, DownloadKind.USENET)
            is AddInput.TorrentFile -> throw DebridError.UnsupportedCapability("Offcloud torrent file upload")
            is AddInput.Nzb -> throw DebridError.UnsupportedCapability("Offcloud NZB file upload")
        }
    }.mapError()

    private suspend fun addCloud(url: String, kind: DownloadKind, poll: Boolean = false): DebridDownload {
        if (containsKey(url)) throw DebridError.Provider("invalid-input", "Invalid source link")
        addBudget.acquire()
        val created = call("POST", "/api/cloud", form = mapOf("url" to url)).obj()
        val id = resourceId(created.optNonBlankString("requestId") ?: throw unreadableResponse())
        try {
            var download = created.toDownload(id = id, kind = kind)
            repeat(if (poll) MAX_POLLS else 0) {
                currentCoroutineContext().ensureActive()
                if (download.status == DownloadStatus.READY || download.status == DownloadStatus.ERROR) {
                    return finishAdd(download)
                }
                delay(pollDelayMs)
                download = status(id, download)
            }
            currentCoroutineContext().ensureActive()
            return finishAdd(download)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { delete(id) }
            throw cancelled
        }
    }

    private suspend fun finishAdd(download: DebridDownload): DebridDownload {
        if (download.status == DownloadStatus.ERROR) {
            delete(download.id)
            throw DebridError.Provider("transfer-failed", download.errorMessage ?: "Offcloud transfer failed")
        }
        return withFiles(download)
    }

    override suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?> = runCatching {
        if (hashes.isEmpty()) return@runCatching emptyMap()
        if (hashes.any { !INFO_HASH.matches(it) || containsKey(it) }) {
            throw DebridError.Provider("invalid-hash", "Invalid BitTorrent info hash")
        }
        val query = JsonArray().apply { hashes.map { it.lowercase() }.distinct().forEach { add(it) } }
        val body = JsonObject().apply { add("hashes", query) }
        val response = call("POST", "/api/cache", json = body).obj()
        val cached = (response.get("cachedItems") as? JsonArray ?: throw unreadableResponse())
            .mapNotNull { it.text()?.lowercase() }.toSet()
        hashes.associateWith { CachedInfo(it.lowercase() in cached, null, null) }
    }.mapError()

    override suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl> = runCatching {
        val id = resourceId(downloadId)
        val file = resourceId(fileId)
        cloudFiles(id).firstOrNull { it.file.id == file }?.link ?: throw DebridError.NotCached()
    }.mapError()

    override suspend fun control(id: String, action: ControlAction): Result<Unit> =
        Result.failure(DebridError.UnsupportedCapability("Offcloud download controls"))

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        val response = call("GET", "/cloud/remove/${resourceId(id)}").obj()
        if (response.optNonBlankString("success") != "true") {
            throw DebridError.Provider("delete-failed", "Offcloud could not remove the download")
        }
    }.mapError()

    override suspend fun supportsUrl(url: String): Result<Boolean> {
        val result = runCatching {
            val host = webUri(url)?.host?.lowercase() ?: return@runCatching false
            val response = call("POST", "/api/sites")
            val sites = if (response is JsonArray) response else response.obj().get("sites") as? JsonArray
                ?: throw unreadableResponse()
            sites.any { site ->
                val entry = site as? JsonObject
                val state = (entry?.optNonBlankString("status") ?: entry?.optNonBlankString("isActive"))?.lowercase()
                val domain = (entry?.optNonBlankString("host") ?: entry?.optNonBlankString("domain") ?: site.text())
                    ?.lowercase()?.takeIf { DOMAIN.matches(it) } ?: return@any false
                (state == null || state in ACTIVE_SITE_STATES) &&
                    (host == domain || host.endsWith(".$domain"))
            }
        }.mapError()
        return Result.success(result.getOrDefault(false))
    }

    private suspend fun visitHistory(visit: (List<JsonObject>) -> Boolean) {
        var page = 0
        val seen = mutableSetOf<String>()
        while (true) {
            val body = JsonObject().apply { addProperty("page", page++) }
            val response = call("POST", "/cloud/history", json = body)
            val entries = (if (response is JsonArray) response else response.obj().get("history") as? JsonArray)
                ?: throw unreadableResponse()
            val records = entries.map { it.obj() }
            if (visit(records) || entries.size() == 0 || response is JsonArray ||
                response.obj().optNonBlankString("isEnd") != "false"
            ) return
            if (!seen.addAll(records.mapNotNull { it.optNonBlankString("requestId") })) throw unreadableResponse()
        }
    }

    private suspend fun status(id: String, previous: DebridDownload?): DebridDownload {
        val response = call("POST", "/api/cloud/status", form = mapOf("requestId" to id)).obj()
        val record = response.get("status") as? JsonObject ?: response
        return record.toDownload(id, previous = previous)
    }

    private suspend fun withFiles(download: DebridDownload): DebridDownload =
        if (download.status == DownloadStatus.READY) {
            download.copy(files = cloudFiles(download.id).map { it.file })
        } else {
            download
        }

    private suspend fun cloudFiles(id: String): List<CloudFile> {
        val response = call("GET", "/api/cloud/explore/$id")
        val files = (if (response is JsonArray) response else response.obj().get("files") as? JsonArray)
            ?: throw unreadableResponse()
        return files.mapNotNull { entry ->
            val metadata = entry as? JsonObject
            val link = metadata?.optNonBlankString("url") ?: entry.text() ?: return@mapNotNull null
            val uri = webUri(link)?.takeUnless { containsKey(link) } ?: return@mapNotNull null
            val path = metadata?.safeValue("path") ?: safeText(uri.path) ?: return@mapNotNull null
            val name = metadata?.safeValue("name") ?: path.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val fileId = metadata?.safeValue("id")?.takeIf { RESOURCE_ID.matches(it) }
                ?: UUID.nameUUIDFromBytes(path.toByteArray(Charsets.UTF_8)).toString()
            if (containsKey(fileId)) return@mapNotNull null
            val fileSize = metadata?.optLong("size")?.takeIf { it >= 0 } ?: 0L
            CloudFile(DebridFile(fileId, name, fileSize, path), StreamUrl(link))
        }.distinctBy { it.file.id }
    }

    private fun JsonObject.toDownload(
        id: String = resourceId(optNonBlankString("requestId") ?: throw unreadableResponse()),
        kind: DownloadKind? = null,
        previous: DebridDownload? = null,
    ): DebridDownload {
        val status = when (optNonBlankString("status")?.lowercase()) {
            "created", "queued" -> DownloadStatus.QUEUED
            "downloading" -> DownloadStatus.DOWNLOADING
            "downloaded" -> DownloadStatus.READY
            "error", "failed", "canceled", "cancelled" -> DownloadStatus.ERROR
            else -> DownloadStatus.UNKNOWN
        }
        val size = optLong("fileSize")?.takeIf { it >= 0 } ?: previous?.sizeBytes
        val amount = optLong("amount")?.takeIf { it >= 0 }
        val link = optNonBlankString("originalLink")
        return DebridDownload(
            id = id,
            service = service,
            name = safeValue("fileName") ?: previous?.name ?: "Offcloud download",
            sizeBytes = size,
            status = status,
            progress = when {
                status == DownloadStatus.READY -> 1f
                size != null && size > 0 && amount != null -> (amount.toDouble() / size).toFloat()
                    .coerceIn(0f, 1f)
                else -> 0f
            },
            kind = kind ?: previous?.kind ?: downloadKind(link, optNonBlankString("site")),
            createdAtEpochMs = optNonBlankString("createdOn")?.let(::epochMillis) ?: previous?.createdAtEpochMs,
            errorMessage = if (status == DownloadStatus.ERROR) {
                safeText(optNonBlankString("error") ?: optNonBlankString("message"))
            } else null,
        )
    }

    private fun downloadKind(link: String?, site: String?): DownloadKind {
        val path = link?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        return when {
            path.startsWith("magnet:") || path.endsWith(".torrent") ||
                site.equals("bittorrent", true) || site.equals("torrent", true) -> DownloadKind.TORRENT
            path.endsWith(".nzb") || site.equals("usenet", true) || site.equals("nzb", true) -> DownloadKind.USENET
            else -> DownloadKind.WEB
        }
    }

    private suspend fun call(
        method: String,
        path: String,
        form: Map<String, String> = emptyMap(),
        json: JsonObject? = null,
    ): JsonElement {
        if (apiKey.isBlank()) throw DebridError.AuthInvalid()
        generalBucket.acquire()
        val request = HttpRequest(
            method, "$BASE$path?key=${URLEncoder.encode(apiKey, "UTF-8")}",
            formFields = form, jsonBody = json?.toString(),
        )
        val response = transport.execute(request)
        val body = try {
            JsonParser.parseString(response.body)
        } catch (_: JsonParseException) {
            null
        }
        val fields = body as? JsonObject
        val error = fields?.optNonBlankString("error")?.takeUnless { it == "false" || it == "0" }
            ?: fields?.optNonBlankString("not_available")
        val detail = fields?.optNonBlankString("message") ?: error
        val marker = listOfNotNull(error, detail).joinToString(" ").lowercase()
        when {
            response.status in setOf(401, 403) || error.equals("NOAUTH", true) ||
                AUTH_ERROR.containsMatchIn(marker) -> throw DebridError.AuthInvalid()
            response.status == 429 || RATE_ERROR.containsMatchIn(marker) ->
                throw DebridError.RateLimited(response.headers.entries
                    .firstOrNull { it.key.equals("Retry-After", true) }?.value?.toLongOrNull())
            response.status !in 200..299 || error != null || fields?.optNonBlankString("success") == "false" ->
                throw DebridError.Provider("http${response.status}", safeText(detail).orEmpty())
        }
        return body?.takeIf { it.isJsonObject || it.isJsonArray } ?: throw unreadableResponse()
    }

    private fun resourceId(value: String): String =
        value.takeIf { RESOURCE_ID.matches(it) && !containsKey(it) } ?: throw DebridError.NotCached()

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

    private fun epochMillis(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw unreadableResponse()
    private fun JsonObject.safeValue(key: String): String? = safeText(optNonBlankString(key))
    private fun JsonElement?.text(): String? = this?.takeIf { it.isJsonPrimitive }
        ?.asString?.takeIf { it.isNotBlank() }

    private fun unreadableResponse() = DebridError.Provider("bad-response", "Offcloud returned an unreadable response")

    companion object {
        const val BASE = "https://offcloud.com"
        private const val MAX_POLLS = 10
        private val RESOURCE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val DOMAIN = Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
        private val INFO_HASH = Regex("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})")
        private val LINK = Regex("(?:https?://|magnet:)", RegexOption.IGNORE_CASE)
        private val AUTH_ERROR = Regex("(?:invalid|expired|missing|incorrect).*?(?:api[ _-]?)?key|(?:api[ _-]?)?key.*?(?:invalid|expired|missing|incorrect)|unauthori[sz]ed")
        private val RATE_ERROR = Regex("rate[ _-]*limit|too many requests")
        private val ACTIVE_SITE_STATES = setOf("online", "working", "active", "healthy", "cloud only", "true")
    }
}
