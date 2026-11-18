package app.trove.debrid.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.time.Instant

/**
 * TorBox adapter — the reference [DebridProvider] implementation.
 *
 * API: https://api.torbox.app, `/v1/api/...`, `Authorization: Bearer`.
 * Response shapes are pinned against the live OpenAPI spec
 * (~/workspace/torbox-app/torbox-openapi.json) and DMM's TorBox client
 * types (research clone), because the public spec leaves response
 * schemas empty. Parsing is defensive everywhere: unknown fields are
 * ignored, missing fields become null, unknown state strings map to
 * [DownloadStatus.UNKNOWN].
 *
 * Play resolves a per-file requestdl bearer link. It is private to the
 * selected player: never log, share, or include it in an error.
 */
class TorBoxProvider(
    private val apiKey: String,
    private val transport: HttpTransport,
    private val generalBucket: TokenBucket = TokenBucket(permitsPerSecond = 5.0, burst = 5),
    private val createTorrentBudget: HourlyBudget = HourlyBudget(50),
    private val createWebBudget: HourlyBudget = HourlyBudget(25),
) : DebridProvider {

    override val service: DebridService = DebridService.TORBOX

    override val capabilities = ProviderCapabilities(
        magnet = true,
        torrentFile = true,
        hosterLink = true,
        nzb = true,
        readOnlyCacheCheck = true,
        cacheProbeMutating = false,
        fileSelection = false,
        pauseResume = false,
        restart = false,
        streamingApi = false,
        multiFilePick = true,
        maxActiveDownloads = null, // tier-dependent (Essential: 3)
        authFlow = AuthFlow.API_KEY,
    )

    companion object {
        const val BASE = "https://api.torbox.app/v1/api"
        private const val PLAN_FREE = 0
    }

    // ------------------------------------------------------------------
    // Low-level calls
    // ------------------------------------------------------------------

    private suspend fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        formFields: Map<String, String> = emptyMap(),
        multipart: List<MultipartPart> = emptyList(),
        jsonBody: String? = null,
        bucket: TokenBucket = generalBucket,
    ): JsonObject {
        bucket.acquire()
        val url = buildString {
            append(BASE).append(path)
            if (query.isNotEmpty()) {
                append("?")
                append(query.entries.joinToString("&") { (k, v) ->
                    "${encode(k)}=${encode(v)}"
                })
            }
        }
        val resp = transport.execute(
            HttpRequest(
                method = method,
                url = url,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                formFields = formFields,
                multipart = multipart,
                jsonBody = jsonBody,
            ),
        )
        return parseEnvelope(resp)
    }

    private fun parseEnvelope(resp: HttpResponse): JsonObject {
        if (resp.status == 429) {
            val retryAfter = resp.headers["Retry-After"]?.toLongOrNull()
                ?: resp.headers["retry-after"]?.toLongOrNull()
            throw DebridError.RateLimited(retryAfter)
        }
        val json = runCatching { JsonParser.parseString(resp.body) }.getOrNull()
        val obj = json as? JsonObject
        // Access/plan denials preserve the provider detail; only a real 401 rejects the key.
        if (resp.status == 401) throw DebridError.AuthInvalid()
        if (obj == null) throw DebridError.Provider("http${resp.status}", "TorBox returned an unreadable response")
        if (resp.status !in 200..299) {
            throw DebridError.Provider(safeCode(obj, "http${resp.status}"), safeDetail(obj))
        }
        if (obj.has("success") && !obj.optBoolean("success", true)) {
            throw DebridError.Provider(safeCode(obj, "error"), safeDetail(obj))
        }
        return obj
    }

    private fun safeCode(obj: JsonObject, fallback: String): String =
        obj.optString("error")?.takeIf {
            it.matches(Regex("[A-Za-z0-9_-]{1,80}")) && !it.contains(apiKey)
        } ?: fallback

    private fun safeDetail(obj: JsonObject): String {
        val detail = obj.optString("detail")?.takeIf { it.isNotBlank() }
            ?: return "TorBox could not complete this request. Try again."
        // A server error must never echo a credential-bearing link into UI or logs.
        val decoded = runCatching { java.net.URLDecoder.decode(detail, "UTF-8") }.getOrDefault(detail)
        if (decoded.contains(apiKey) || decoded.contains("requestdl", true) || decoded.contains("http", true)) {
            return "TorBox couldn't complete this request. Try again."
        }
        return detail.take(300)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ------------------------------------------------------------------
    // DebridProvider
    // ------------------------------------------------------------------

    override suspend fun validateAccount(): Result<AccountInfo> = runCatching {
        val data = call("GET", "/user/me").dataObject()
            ?: throw DebridError.Provider("bad-response", "missing account data")
        val email = data.optString("email")
        val plan = data.optInt("plan")
        AccountInfo(
            username = email,
            isPremium = (plan ?: 0) > PLAN_FREE,
            detail = plan?.let { "Plan ${planLabel(it)}" },
        )
    }.mapError()

    override suspend fun listDownloads(): Result<List<DebridDownload>> = runCatching {
        val torrents = listKind("/torrents/mylist", DownloadKind.TORRENT)
        val web = listKind("/webdl/mylist", DownloadKind.WEB)
        val usenet = runCatching { listKind("/usenet/mylist", DownloadKind.USENET) }
            .getOrElse {
                if (it is CancellationException || it is DebridError.AuthInvalid) throw it
                // Usenet is Pro-plan only; Essential accounts get an error
                // here. A failed Usenet list must not kill the whole list
                // (a bad key already fails on the torrents list above).
                emptyList()
            }
        (torrents + web + usenet)
            .sortedByDescending { it.createdAtEpochMs ?: 0L }
    }.mapError()

    override suspend fun getDownload(id: String): Result<DebridDownload> = runCatching {
        val numericId = id.toLongOrNull() ?: throw DebridError.NotCached()
        for ((path, kind) in listOf(
            "/torrents/mylist" to DownloadKind.TORRENT,
            "/webdl/mylist" to DownloadKind.WEB,
            "/usenet/mylist" to DownloadKind.USENET,
        )) {
            val found = listKind(path, kind, id = numericId).firstOrNull()
            if (found != null) return@runCatching found
        }
        throw DebridError.NotCached()
    }.mapError()

    override suspend fun add(input: AddInput): Result<DebridDownload> = runCatching {
        when (input) {
            is AddInput.Magnet -> {
                if (!createTorrentBudget.tryConsume()) {
                    throw DebridError.QuotaExceeded("TorBox allows 50 torrent adds per hour")
                }
                // Synchronous creation returns the ID and refusal detail; async returns data=null.
                val data = call(
                    "POST", "/torrents/createtorrent",
                    multipart = listOf(
                        MultipartPart("magnet", null, (ShareClassifier.cleanMagnet(input.magnetUri)
                            ?: throw DebridError.Provider("magnet", "Enter a magnet URI")).toByteArray(Charsets.UTF_8), "text/plain"),
                    ),
                ).dataObject()
                val newId = data?.optLong("torrent_id") ?: data?.optLong("queued_id")
                    ?: throw DebridError.Provider("create", "TorBox returned no torrent id")
                resolveCreated(newId, DownloadKind.TORRENT, input.magnetUri)
            }
            is AddInput.TorrentFile -> {
                if (!createTorrentBudget.tryConsume()) {
                    throw DebridError.QuotaExceeded("TorBox allows 50 torrent adds per hour")
                }
                val data = call(
                    "POST", "/torrents/createtorrent",
                    multipart = listOf(
                        MultipartPart("file", input.fileName, input.bytes, "application/octet-stream"),
                    ),
                ).dataObject()
                val newId = data?.optLong("torrent_id") ?: data?.optLong("queued_id")
                    ?: throw DebridError.Provider("create", "TorBox returned no torrent id")
                resolveCreated(newId, DownloadKind.TORRENT, input.fileName)
            }
            is AddInput.HosterLink -> {
                if (!createWebBudget.tryConsume()) {
                    throw DebridError.QuotaExceeded("TorBox allows 25 web adds per hour")
                }
                val data = call(
                    "POST", "/webdl/asynccreatewebdownload",
                    formFields = mapOf("link" to input.url),
                ).dataObject()
                val newId = data?.optLong("webdownload_id") ?: data?.optLong("queued_id")
                    ?: throw DebridError.Provider("create", "TorBox returned no web download id")
                resolveCreated(newId, DownloadKind.WEB, input.url)
            }
            is AddInput.Nzb -> {
                val data = call(
                    "POST", "/usenet/asynccreateusenetdownload",
                    multipart = listOf(
                        MultipartPart("file", input.fileName, input.bytes, "application/x-nzb"),
                    ),
                ).dataObject()
                val newId = data?.optLong("usenetdownload_id") ?: data?.optLong("queued_id")
                    ?: throw DebridError.Provider("create", "TorBox returned no Usenet id")
                resolveCreated(newId, DownloadKind.USENET, input.fileName)
            }
            is AddInput.NzbUrl ->
                throw DebridError.UnsupportedCapability("TorBox needs the .nzb file, not a link")
        }
    }.mapError()

    override suspend fun checkCached(hashes: List<String>): Result<Map<String, CachedInfo>?> =
        runCatching {
            if (hashes.isEmpty()) return@runCatching emptyMap<String, CachedInfo>()
            val data = call(
                "GET", "/torrents/checkcached",
                query = mapOf(
                    "hash" to hashes.joinToString(","),
                    "format" to "object",
                    "list_files" to "false",
                ),
            )["data"] ?: return@runCatching emptyMap<String, CachedInfo>()
            val out = mutableMapOf<String, CachedInfo>()
            if (data is JsonObject) {
                for ((hash, item) in data.entrySet()) {
                    val itemObj = item as? JsonObject ?: continue
                    out[hash.lowercase()] = CachedInfo(
                        ready = true,
                        name = itemObj.optString("name"),
                        sizeBytes = itemObj.optLong("size"),
                    )
                }
            } else if (data is JsonArray) {
                for (item in data) {
                    val itemObj = item as? JsonObject ?: continue
                    val hash = itemObj.optString("hash") ?: continue
                    out[hash.lowercase()] = CachedInfo(
                        ready = true,
                        name = itemObj.optString("name"),
                        sizeBytes = itemObj.optLong("size"),
                    )
                }
            }
            out
        }.mapError()

    override suspend fun getStreamUrl(downloadId: String, fileId: String): Result<StreamUrl> =
        runCatching {
            val download = getDownload(downloadId).getOrThrow()
            resolveStream(download, fileId).getOrThrow()
        }.mapError()

    /** The list already identifies the namespace; numeric IDs alone can collide. */
    suspend fun getStreamUrl(download: DebridDownload, fileId: String): Result<StreamUrl> =
        resolveStream(download, fileId)

    suspend fun getDownload(id: String, kind: DownloadKind): Result<DebridDownload> = runCatching {
        val numericId = id.toLongOrNull() ?: throw DebridError.NotCached()
        listKind(kind.listPath(), kind, numericId).firstOrNull { it.id == id }
            ?: throw DebridError.NotCached()
    }.mapError()

    private suspend fun resolveStream(download: DebridDownload, fileId: String): Result<StreamUrl> =
        runCatching {
            val (path, idField) = when (download.kind) {
                DownloadKind.TORRENT -> "/torrents/requestdl" to "torrent_id"
                DownloadKind.WEB -> "/webdl/requestdl" to "web_id"
                DownloadKind.USENET -> "/usenet/requestdl" to "usenet_id"
            }
            // requestdl failures can contain the credential-bearing request URL.
            // Never retain a transport exception or its cause on this path.
            val data = try {
                call("GET", path, query = mapOf(
                    "token" to apiKey, idField to download.id, "file_id" to fileId,
                ))["data"]
            } catch (e: CancellationException) {
                throw e
            } catch (e: DebridError) {
                throw e
            } catch (_: Exception) {
                throw DebridError.Provider("playback", "Couldn't request a playable link. Check your connection and try again.")
            }
            val url = (data as? com.google.gson.JsonPrimitive)
                ?.takeIf { it.isString }?.asString
                ?: throw DebridError.Provider("playback", "TorBox returned no playable link")
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            if (uri?.scheme !in listOf("https", "http") || uri?.host.isNullOrBlank() || uri?.userInfo != null) {
                throw DebridError.Provider("playback", "TorBox returned an invalid playable link")
            }
            StreamUrl(url)
        }.mapError()

    override suspend fun control(id: String, action: ControlAction): Result<Unit> =
        runCatching {
            // TorBox controltorrent operations are reannounce / delete /
            // resume / stop_seeding — there is no pause/resume pair, so
            // v1 does not offer it.
            throw DebridError.UnsupportedCapability("TorBox has no pause/resume")
        }.mapError()

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        delete(getDownload(id).getOrThrow()).getOrThrow()
    }.mapError()

    /** Use the selected namespace: IDs are not globally unique. */
    suspend fun delete(download: DebridDownload): Result<Unit> = runCatching {
        val id = download.id
        val (path, idField) = when (download.kind) {
            DownloadKind.TORRENT -> "/torrents/controltorrent" to "torrent_id"
            DownloadKind.WEB -> "/webdl/controlwebdownload" to "webdl_id"
            DownloadKind.USENET -> "/usenet/controlusenetdownload" to "usenetdownload_id"
        }
        val numericId = id.toLongOrNull() ?: throw DebridError.NotCached()
        call(
            "POST", path,
            jsonBody = """{"operation":"delete","$idField":$numericId}""",
        )
        Unit
    }.mapError()

    override suspend fun supportsUrl(url: String): Result<Boolean> = runCatching {
        val hosters = hosterDomains()
        val host = runCatching {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.")
        }.getOrNull() ?: return@runCatching false
        hosters.any { h -> host == h || host.endsWith(".$h") }
    }.mapError()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun listKind(
        path: String,
        kind: DownloadKind,
        id: Long? = null,
    ): List<DebridDownload> {
        val query = mutableMapOf("limit" to "1000", "files" to "true")
        if (id != null) query["bypass_cache"] = "true"
        if (id != null) query["id"] = id.toString()
        val data = call("GET", path, query = query)["data"]
        val items = when (data) {
            is JsonObject -> if (data.has("id")) listOf(data) else data.entrySet().mapNotNull { it.value as? JsonObject }
            is JsonArray -> data.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        return items.mapNotNull { it.toDownload(kind) }.filter { id == null || it.id == id.toString() }
    }

    private fun DownloadKind.listPath(): String = when (this) {
        DownloadKind.TORRENT -> "/torrents/mylist"
        DownloadKind.WEB -> "/webdl/mylist"
        DownloadKind.USENET -> "/usenet/mylist"
    }

    private suspend fun resolveCreated(
        newId: Long,
        kind: DownloadKind,
        fallbackName: String,
    ): DebridDownload {
        // A successful create can precede list visibility; keep the returned ID
        // as a queued row until the next refresh.
        return runCatching { getDownload(newId.toString(), kind).getOrThrow() }
            .getOrElse {
                if (it is CancellationException) throw it
                DebridDownload(
                    id = newId.toString(),
                    service = DebridService.TORBOX,
                    name = fallbackName,
                    sizeBytes = null,
                    status = DownloadStatus.QUEUED,
                    progress = 0f,
                    kind = kind,
                )
            }
    }

    private var hosterCache: Pair<Long, Set<String>>? = null

    /** Cached hoster domains for the share flow (1h TTL). Public so
     *  the app can classify shared links without an extra call. */
    suspend fun hosterDomains(): Set<String> {
        val cached = hosterCache
        if (cached != null && System.currentTimeMillis() - cached.first < 3_600_000L) return cached.second
        val data = try { call("GET", "/webdl/hosters")["data"] as? JsonArray }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        if (data == null) return emptySet()
        val domains = data.mapNotNull { item ->
            when (item) {
                is com.google.gson.JsonPrimitive -> item.asString.lowercase()
                is JsonObject -> (item.optString("domain") ?: item.optString("host") ?: item.optString("name"))
                    ?.lowercase()?.removePrefix("www.")
                else -> null
            }
        }.toSet()
        hosterCache = System.currentTimeMillis() to domains
        return domains
    }

    private fun JsonObject.toDownload(kind: DownloadKind): DebridDownload? {
        val id = optLong("id") ?: return null
        val finished = optBoolean("download_finished", false)
        val state = optString("download_state")?.lowercase().orEmpty()
        val status = when {
            finished -> DownloadStatus.READY
            state in setOf("cached", "completed", "seeding", "uploading") -> DownloadStatus.READY
            state == "downloading" -> DownloadStatus.DOWNLOADING
            state == "queued" -> DownloadStatus.QUEUED
            state == "paused" -> DownloadStatus.PAUSED
            state.contains("error") || state.contains("fail") -> DownloadStatus.ERROR
            else -> DownloadStatus.UNKNOWN
        }
        val rawProgress = optDouble("progress") ?: 0.0
        val progress = when {
            rawProgress > 1.0 -> (rawProgress / 100.0).coerceIn(0.0, 1.0)
            else -> rawProgress.coerceIn(0.0, 1.0)
        }.toFloat()
        val files = (this["files"] as? JsonArray)?.mapNotNull { item ->
            val f = item as? JsonObject ?: return@mapNotNull null
            val fileId = f.optLong("id") ?: return@mapNotNull null
            DebridFile(
                id = fileId.toString(),
                name = (f.optString("short_name") ?: f.optString("name") ?: "File $fileId")
                    .replace('\\', '/').substringAfterLast('/'),
                sizeBytes = f.optLong("size") ?: 0L,
                path = f.optString("path"),
            )
        }.orEmpty()
        return DebridDownload(
            id = id.toString(),
            service = DebridService.TORBOX,
            name = optString("name") ?: optString("hash") ?: "Download $id",
            sizeBytes = optLong("size"),
            status = status,
            progress = if (status == DownloadStatus.READY) 1f else progress,
            files = files,
            kind = kind,
            createdAtEpochMs = optString("created_at")?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            },
            errorMessage = optString("error"),
            downloadSpeedBytesPerSec = optLong("download_speed")?.takeIf { it > 0 },
            etaSeconds = optLong("eta")?.takeIf { it > 0 },
            seeds = optInt("seeds")?.takeIf { it >= 0 },
            peers = optInt("peers")?.takeIf { it >= 0 },
        )
    }

    private fun JsonObject.dataObject(): JsonObject? = this["data"] as? JsonObject

    private fun planLabel(plan: Int): String = when (plan) {
        1 -> "Essential"
        2 -> "Standard"
        3 -> "Pro"
        else -> "Free"
    }

    /** Maps a failure to a typed [DebridError]. Cancellation is never
     *  a failure: it is rethrown so the caller's coroutine still
     *  cancels instead of reporting a bogus network error. */
    private fun <T> Result<T>.mapError(): Result<T> {
        val failure = exceptionOrNull() ?: return this
        if (failure is CancellationException) throw failure
        return Result.failure(if (failure is DebridError) failure else DebridError.Network(failure))
    }
}

// ------------------------------------------------------------------
// Gson lenient accessors
// ------------------------------------------------------------------

internal fun JsonObject.optString(name: String): String? {
    val el = this[name] ?: return null
    return if (el.isJsonNull) null else runCatching { el.asString }.getOrNull()
}

internal fun JsonObject.optLong(name: String): Long? {
    val el = this[name] ?: return null
    return if (el.isJsonNull) null else runCatching { el.asLong }.getOrNull()
        ?: el.asString.toLongOrNull()
}

internal fun JsonObject.optInt(name: String): Int? = optLong(name)?.toInt()

internal fun JsonObject.optDouble(name: String): Double? {
    val el = this[name] ?: return null
    return if (el.isJsonNull) null else runCatching { el.asDouble }.getOrNull()
        ?: el.asString.toDoubleOrNull()
}

internal fun JsonObject.optBoolean(name: String, default: Boolean): Boolean {
    val el = this[name] ?: return default
    return if (el.isJsonNull) default else runCatching { el.asBoolean }.getOrNull() ?: default
}
