package app.trove.debrid.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val listPageSize: Int = 1000,
    /** Redacted diagnostic sink for creates that return no id; no-op by default. */
    private val diagnosticLog: (String) -> Unit = {},
) : DebridProvider {

    init {
        require(listPageSize > 0)
    }

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
        val detail = detailText(obj)
            ?: return "TorBox could not complete this request. Try again."
        return redactDetail(detail) ?: "TorBox couldn't complete this request. Try again."
    }

    /** The redacted `detail` when the envelope carries a usable one, else null. */
    private fun safeDetailOrNull(obj: JsonObject): String? =
        detailText(obj)?.let { redactDetail(it) }

    /** Renders the envelope `detail` without redaction: a plain string,
     *  or the first `ValidationError.msg` with its `loc` when `detail`
     *  is a 422 array. Null when absent or unusable. */
    private fun detailText(obj: JsonObject): String? {
        return when (val el = obj["detail"]) {
            null -> null
            is JsonArray -> {
                val first = el.firstOrNull() as? JsonObject ?: return null
                val msg = first.optString("msg")?.takeIf { it.isNotBlank() } ?: return null
                val loc = (first["loc"] as? JsonArray)
                    ?.mapNotNull { seg ->
                        (seg as? JsonPrimitive)?.let { runCatching { it.asString }.getOrNull() }
                    }
                    ?.filter { it.isNotEmpty() }
                    ?.joinToString(".")
                    .orEmpty()
                if (loc.isBlank()) msg else "$loc: $msg"
            }
            else -> obj.optString("detail")?.takeIf { it.isNotBlank() }
        }
    }

    /** Applies the credential/link redaction; null when the text must
     *  not be shown in UI or logs. The returned text is capped at 300 chars. */
    private fun redactDetail(detail: String): String? {
        // A server error must never echo a credential-bearing link into UI or logs.
        val decoded = runCatching { java.net.URLDecoder.decode(detail, "UTF-8") }.getOrDefault(detail)
        if (decoded.contains(apiKey) || decoded.contains("requestdl", true) || decoded.contains("http", true)) {
            return null
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

    override suspend fun listDownloads(): Result<List<DebridDownload>> =
        listDownloadsInternal(bypassCache = false)

    /** A user-initiated refresh must see a just-added download: TorBox
     *  caches mylist responses, so the fresh load passes
     *  `bypass_cache=true` on all three lists. */
    override suspend fun listDownloadsFresh(): Result<List<DebridDownload>> =
        listDownloadsInternal(bypassCache = true)

    private suspend fun listDownloadsInternal(bypassCache: Boolean): Result<List<DebridDownload>> = runCatching {
        coroutineScope {
            // Capture failures in each child so awaits determine which error surfaces.
            val torrentLoad = async {
                runCatching { listKind("/torrents/mylist", DownloadKind.TORRENT, bypassCache = bypassCache) }
            }
            val webLoad = async {
                runCatching { listKind("/webdl/mylist", DownloadKind.WEB, bypassCache = bypassCache) }
            }
            val usenetLoad = async {
                runCatching { listKind("/usenet/mylist", DownloadKind.USENET, bypassCache = bypassCache) }
            }

            val torrents = torrentLoad.await().getOrThrow()
            val web = webLoad.await().getOrThrow()
            val usenet = usenetLoad.await().getOrElse {
                if (it is CancellationException || it is DebridError.AuthInvalid) throw it
                // Usenet is Pro-plan only; Essential accounts get an error
                // here. A failed Usenet list must not kill the whole list
                // (a bad key already fails on the torrents list above).
                emptyList()
            }
            (torrents + web + usenet)
                .sortedByDescending { it.createdAtEpochMs ?: 0L }
        }
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
                // Synchronous creation returns the ID and refusal detail.
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
                val envelope = call(
                    "POST", "/webdl/createwebdownload",
                    formFields = mapOf("link" to input.url),
                )
                val data = envelope.dataObject()
                val newId = data?.optLong("webdownload_id") ?: data?.optLong("queued_id")
                if (newId != null) {
                    resolveCreated(newId, DownloadKind.WEB, input.url)
                } else {
                    logCreateWithoutId(
                        "/webdl/createwebdownload", envelope, data,
                        listOf("webdownload_id", "queued_id"),
                    )
                    findByHash("/webdl/mylist", DownloadKind.WEB, md5Hex(input.url))
                        ?: throw DebridError.Provider(
                            "create",
                            safeDetailOrNull(envelope) ?: "TorBox returned no web download id",
                        )
                }
            }
            is AddInput.Nzb -> {
                val envelope = call(
                    "POST", "/usenet/createusenetdownload",
                    multipart = listOf(
                        MultipartPart("file", input.fileName, input.bytes, "application/x-nzb"),
                    ),
                )
                val data = envelope.dataObject()
                val newId = data?.optLong("usenetdownload_id") ?: data?.optLong("queued_id")
                if (newId != null) {
                    resolveCreated(newId, DownloadKind.USENET, input.fileName)
                } else {
                    logCreateWithoutId(
                        "/usenet/createusenetdownload", envelope, data,
                        listOf("usenetdownload_id", "queued_id"),
                    )
                    // Without a hash from the create response there is
                    // nothing to match a mylist row on, so no lookup runs.
                    val hash = data?.optString("hash")?.takeIf { it.isNotBlank() }
                    val recovered = hash?.let { findByHash("/usenet/mylist", DownloadKind.USENET, it) }
                    recovered ?: throw DebridError.Provider(
                        "create",
                        safeDetailOrNull(envelope) ?: "TorBox returned no Usenet id",
                    )
                }
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
            getStreamUrl(download, fileId).getOrThrow()
        }.mapError()

    /** The list already identifies the namespace; numeric IDs alone can collide. */
    suspend fun getStreamUrl(download: DebridDownload, fileId: String): Result<StreamUrl> =
        getStreamUrl(DownloadRef(download.kind, download.id), fileId)

    override suspend fun getStreamUrl(ref: DownloadRef, fileId: String): Result<StreamUrl> =
        resolveStream(ref, fileId)

    override suspend fun getDownload(ref: DownloadRef): Result<DebridDownload> =
        getDownload(ref.id, ref.kind)

    suspend fun getDownload(id: String, kind: DownloadKind): Result<DebridDownload> = runCatching {
        val numericId = id.toLongOrNull() ?: throw DebridError.NotCached()
        listKind(kind.listPath(), kind, numericId).firstOrNull { it.id == id }
            ?: throw DebridError.NotCached()
    }.mapError()

    private suspend fun resolveStream(ref: DownloadRef, fileId: String): Result<StreamUrl> =
        runCatching {
            val (path, idField) = when (ref.kind) {
                DownloadKind.TORRENT -> "/torrents/requestdl" to "torrent_id"
                DownloadKind.WEB -> "/webdl/requestdl" to "web_id"
                DownloadKind.USENET -> "/usenet/requestdl" to "usenet_id"
            }
            // requestdl failures can contain the credential-bearing request URL.
            // Never retain a transport exception or its cause on this path.
            val data = try {
                call("GET", path, query = mapOf(
                    "token" to apiKey, idField to ref.id, "file_id" to fileId,
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
    suspend fun delete(download: DebridDownload): Result<Unit> =
        delete(DownloadRef(download.kind, download.id))

    override suspend fun delete(ref: DownloadRef): Result<Unit> = runCatching {
        val (path, idField) = when (ref.kind) {
            DownloadKind.TORRENT -> "/torrents/controltorrent" to "torrent_id"
            DownloadKind.WEB -> "/webdl/controlwebdownload" to "webdl_id"
            DownloadKind.USENET -> "/usenet/controlusenetdownload" to "usenetdownload_id"
        }
        val numericId = ref.id.toLongOrNull() ?: throw DebridError.NotCached()
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
        bypassCache: Boolean = false,
    ): List<DebridDownload> {
        if (id != null) {
            return listKindPage(path, id = id, bypassCache = bypassCache)
                .mapNotNull { (it as? JsonObject)?.toDownload(kind) }
                .filter { it.id == id.toString() }
        }

        // This map is scoped to one kind and retains the first row for each ID.
        val downloads = linkedMapOf<String, DebridDownload>()
        var offset = 0
        do {
            val page = listKindPage(path, offset = offset, bypassCache = bypassCache)
            for (item in page) {
                val download = (item as? JsonObject)?.toDownload(kind) ?: continue
                downloads.putIfAbsent(download.id, download)
            }
            offset += listPageSize
        } while (page.size >= listPageSize)
        return downloads.values.toList()
    }

    private suspend fun listKindPage(
        path: String,
        offset: Int? = null,
        id: Long? = null,
        bypassCache: Boolean,
        limit: Int = listPageSize,
    ): List<JsonElement> {
        val query = mutableMapOf("limit" to limit.toString(), "files" to "true")
        if (offset != null) query["offset"] = offset.toString()
        if (id != null || bypassCache) query["bypass_cache"] = "true"
        if (id != null) query["id"] = id.toString()
        return when (val data = call("GET", path, query = query)["data"]) {
            is JsonObject -> if (data.has("id")) listOf(data) else data.entrySet().map { it.value }
            is JsonArray -> data.toList()
            else -> emptyList()
        }
    }

    /** One viewport page of one kind, for per-tab paging. Unlike the
     *  full sync ([listDownloads]), a failure here is returned as-is:
     *  a Usenet page error is surfaced to the caller, never swallowed,
     *  so the UI can offer a Retry for just that page. The page is
     *  deduped by id (first row wins), matching [listKind]. */
    data class ListPage(val rows: List<DebridDownload>, val rawCount: Int)

    suspend fun listPage(
        kind: DownloadKind,
        offset: Int,
        limit: Int,
        bypassCache: Boolean = false,
    ): Result<ListPage> = runCatching {
        require(offset >= 0) { "offset must be >= 0" }
        require(limit > 0) { "limit must be > 0" }
        val seen = linkedMapOf<String, DebridDownload>()
        val raw = listKindPage(kind.listPath(), offset = offset, bypassCache = bypassCache, limit = limit)
        for (item in raw) {
            val download = (item as? JsonObject)?.toDownload(kind) ?: continue
            seen.putIfAbsent(download.id, download)
        }
        ListPage(seen.values.toList(), raw.size)
    }.mapError()

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

    /** MD5 hex (lowercase) of the exact string — TorBox's webdl `hash`
     *  is the MD5 of the link. The byte is masked before formatting:
     *  `%x` sign-extends a negative byte. */
    private fun md5Hex(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Finds a mylist row by its `hash` field, paging with
     *  `bypass_cache=true` so a just-created row is visible. Null when
     *  no page contains it. The create call is never retried. */
    private suspend fun findByHash(
        path: String,
        kind: DownloadKind,
        hash: String,
    ): DebridDownload? {
        var offset = 0
        do {
            val page = listKindPage(path, offset = offset, bypassCache = true)
            for (item in page) {
                val row = item as? JsonObject ?: continue
                if (row.optString("hash")?.equals(hash, ignoreCase = true) == true) {
                    row.toDownload(kind)?.let { return it }
                }
            }
            offset += listPageSize
        } while (page.size >= listPageSize)
        return null
    }

    /** One redacted diagnostic line for a create that returned success
     *  without an id: endpoint, 2xx status (only 2xx reaches this
     *  path), `success`, the redacted `detail`, and which id fields
     *  were present (names only). Never the link, the key, or the body. */
    private fun logCreateWithoutId(
        endpoint: String,
        envelope: JsonObject,
        data: JsonObject?,
        idFields: List<String>,
    ) {
        val success = if (envelope.has("success")) {
            envelope.optBoolean("success", true).toString()
        } else {
            "absent"
        }
        val detail = (safeDetailOrNull(envelope) ?: "none")
            .replace('\n', ' ').replace('\r', ' ')
        val present = idFields.filter { data?.has(it) == true }
        diagnosticLog(
            "TorBox create without id: endpoint=$endpoint status=2xx success=$success " +
                "detail=$detail data=${if (data == null) "null" else "object"} idFields=$present",
        )
    }

    private var hosterCache: Pair<Long, Set<String>>? = null

    /** Cached hoster domains for the share flow (1h TTL). Public so
     *  the app can classify shared links without an extra call. */
    override suspend fun hosterDomains(): Set<String> {
        val cached = hosterCache
        if (cached != null && System.currentTimeMillis() - cached.first < 3_600_000L) return cached.second
        val data = try { call("GET", "/webdl/hosters")["data"] as? JsonArray }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        if (data == null) return emptySet()
        val domains = data.flatMap { item ->
            when (item) {
                // Tolerate a plain-string list; the live API returns objects.
                is com.google.gson.JsonPrimitive -> listOfNotNull(
                    item.asString.takeIf { item.isString }?.normalizeHosterDomain(),
                )
                is JsonObject -> {
                    // Live shape: {"name", "domains": [...], "status": bool}.
                    // Only active hosters contribute; an absent status means active.
                    if (!item.optBoolean("status", true)) emptyList()
                    else {
                        val array = item["domains"] as? JsonArray
                        if (array != null) array.mapNotNull { entry ->
                            (entry as? com.google.gson.JsonPrimitive)
                                ?.asString?.takeIf { entry.isString }?.normalizeHosterDomain()
                        } else listOfNotNull(
                            (item.optString("domain") ?: item.optString("host") ?: item.optString("name"))
                                ?.normalizeHosterDomain(),
                        )
                    }
                }
                else -> emptyList()
            }
        }.toSet()
        hosterCache = System.currentTimeMillis() to domains
        return domains
    }

    private fun JsonObject.toDownload(kind: DownloadKind): DebridDownload? {
        val id = optLong("id") ?: return null
        val finished = optBoolean("download_finished", false)
        val downloadPresent = optBoolean("download_present", false)
        val cachedFlag = optBoolean("cached", false)
        val state = optString("download_state")?.lowercase().orEmpty()
        val status = when {
            finished || downloadPresent || cachedFlag ||
                state in setOf("cached", "seeding", "uploading") -> DownloadStatus.READY
            // TorBox's states also include metaDL (downloading metadata —
            // every magnet starts here), "stalled (no seeds)" and
            // checkingResumeData. All three are still trying to
            // download, so they show the progress row instead of
            // UNKNOWN; "stalled" is prefix-matched because the server
            // appends a reason in parentheses.
            state == "downloading" || state == "metadl" || state.replace("_", "") == "checkingresumedata" ||
                state.startsWith("stalled") -> DownloadStatus.DOWNLOADING
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
            active = optBooleanOrNull("active"),
            cached = optBooleanOrNull("cached"),
            expiresAtEpochMs = optString("expires_at")?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            },
        )
    }

    private fun JsonObject.dataObject(): JsonObject? = this["data"] as? JsonObject

    private fun planLabel(plan: Int): String = when (plan) {
        1 -> "Essential"
        2 -> "Standard"
        3 -> "Pro"
        else -> "Free"
    }
}

// ------------------------------------------------------------------
// Hoster-domain helper
// ------------------------------------------------------------------

/** Lowercase, strip a www. prefix, drop blanks. */
private fun String.normalizeHosterDomain(): String? =
    trim().lowercase().removePrefix("www.").takeIf { it.isNotEmpty() }
