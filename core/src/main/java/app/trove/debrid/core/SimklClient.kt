package app.trove.debrid.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

/**
 * SIMKL client ID for the V2 app.
 *
 * PLACEHOLDER — the V2 app is not registered yet. Register it at
 * `simkl.com/settings/developer/` under the bitmere account (free, no
 * approval), client type "Mobile, desktop & browser apps", and replace
 * this value with the minted `client_id`. That client type has no
 * `client_secret` — never ship one in the app. Until this is replaced,
 * [SimklClient.isConfigured] is false, sign-in is unavailable, and
 * syncs are skipped without any network call. Do not invent a value.
 *
 * A client ID saved in Settings overrides this constant (see
 * [SimklClient]'s `clientId` resolver), so a user with their own
 * SIMKL app can sign in without a baked-in ID.
 */
const val SIMKL_CLIENT_ID = "SIMKL_CLIENT_ID_NOT_REGISTERED"

/** Which SIMKL catalog an entry belongs to. */
enum class SimklType(val apiValue: String, val responseKey: String, val mediaKey: String) {
    MOVIE("movies", "movies", "movie"),
    SHOW("shows", "shows", "show"),
    ANIME("anime", "anime", "show"),
}

/** Watchlist status. Movies never use [WATCHING] or [HOLD]. */
enum class SimklStatus(val apiValue: String) {
    WATCHING("watching"),
    PLAN_TO_WATCH("plantowatch"),
    COMPLETED("completed"),
    HOLD("hold"),
    DROPPED("dropped");

    companion object {
        fun fromApi(value: String?): SimklStatus? = entries.firstOrNull { it.apiValue == value }
    }
}

/** One library entry, as the badges need it. No secrets: the only
 *  identifier here is the public simkl.com URL. */
data class SimklEntry(
    val simklId: Int,
    val type: SimklType,
    val status: SimklStatus,
    val title: String?,
    val imdbId: String?,
    val tmdbId: Int?,
    val slug: String?,
    val watchedEpisodes: Int?,
    val totalEpisodes: Int?,
    val nextSeason: Int?,
    val nextEpisode: Int?,
    val nextTitle: String?,
) {
    /** Canonical simkl.com page for this item — the mandatory
     *  attribution target. The slug is optional in the path. */
    val simklUrl: String
        get() = buildString {
            append("https://simkl.com/")
            append(when (type) {
                SimklType.MOVIE -> "movies"
                SimklType.SHOW -> "tv"
                SimklType.ANIME -> "anime"
            })
            append('/').append(simklId)
            slug?.takeIf { it.isNotBlank() }?.let { append('/').append(it) }
        }
}

/** OAuth tokens. Both values are secrets: [toString] is redacted,
 *  and the pair is stored only in the encrypted credential store. */
data class SimklTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMs: Long,
    val refreshExpiresAtMs: Long,
) {
    override fun toString(): String = "SimklTokens([redacted])"

    fun encode(): String = JsonObject().apply {
        addProperty("access_token", accessToken)
        addProperty("refresh_token", refreshToken)
        addProperty("access_expires_at", accessExpiresAtMs)
        addProperty("refresh_expires_at", refreshExpiresAtMs)
    }.toString()

    companion object {
        fun decode(json: String): SimklTokens? = runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            val access = obj.optString("access_token") ?: return null
            val refresh = obj.optString("refresh_token") ?: return null
            val accessExpires = obj.optLong("access_expires_at") ?: return null
            val refreshExpires = obj.optLong("refresh_expires_at") ?: return null
            SimklTokens(access, refresh, accessExpires, refreshExpires)
        }.getOrNull()
    }
}

/** Device/PIN flow code to show the user. The [deviceCode] is a
 *  credential: [toString] redacts it, and it is never displayed,
 *  logged, or put in a URL. */
data class SimklDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSecs: Int,
    val intervalSecs: Int,
) {
    override fun toString(): String =
        "SimklDeviceCode(userCode=$userCode, verificationUri=$verificationUri, expiresInSecs=$expiresInSecs)"
}

sealed interface SimklPollResult {
    data object Pending : SimklPollResult
    data class SlowDown(val extraSeconds: Int) : SimklPollResult
    data object Authorized : SimklPollResult
    data object Expired : SimklPollResult
    data object InvalidClient : SimklPollResult
    data class Failed(val detail: String) : SimklPollResult
}

/** Parsed `/sync/activities`: the top-level watermark plus every
 *  domain bucket timestamp, keyed `"tv_shows.watching"` etc. */
data class SimklActivities(
    val all: String?,
    val settingsAll: String?,
    val buckets: Map<String, String>,
) {
    companion object {
        fun parse(json: JsonObject): SimklActivities {
            val buckets = mutableMapOf<String, String>()
            for (domain in listOf("tv_shows", "anime", "movies")) {
                val block = json.getAsJsonObject(domain) ?: continue
                for ((key, value) in block.entrySet()) {
                    value.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let {
                        buckets["$domain.$key"] = it
                    }
                }
            }
            return SimklActivities(
                all = json.optString("all"),
                settingsAll = json.getAsJsonObject("settings")?.optString("all"),
                buckets = buckets,
            )
        }
    }
}

data class SimklUserSettings(val username: String?, val accountType: String?)

/** Why a sync failed. [QUOTA] means the daily allowance is spent
 *  and no further sync attempt should run until the reset. */
enum class SimklSyncFailure { NETWORK, AUTH, RATE_LIMITED, QUOTA, API }

sealed interface SimklSyncResult {
    data object NotSignedIn : SimklSyncResult
    data object NotConfigured : SimklSyncResult
    data object Unchanged : SimklSyncResult
    /** Automatic sync skipped: the last check is inside the
     *  cooldown window, so no network call was made. */
    data object CooledDown : SimklSyncResult
    data class Changed(val entryCount: Int) : SimklSyncResult
    data class Failed(val failure: SimklSyncFailure) : SimklSyncResult
}

/** Thrown when the grant is gone: expired/revoked tokens, or a
 *  refresh that failed. The caller signs the user out. */
class SimklAuthException : Exception("SIMKL authorization is no longer valid")

/** Thrown on HTTP 429. [retryAfterMs] is the backoff from the
 *  `Retry-After` header when present, else null. */
class SimklRateLimited(val retryAfterMs: Long?) : Exception("SIMKL rate limited")

class SimklApiException(val status: Int, val code: String? = null) :
    Exception("SIMKL request failed ($status)")

/**
 * Read-only SIMKL API client on the shared [HttpTransport]. Auth V2
 * only (V1 retires ~April 2027). Device/PIN sign-in with scope
 * `media:read` only; no two-way calls exist in this client.
 *
 * Every request carries `client_id`, `app-name`, `app-version` and a
 * `User-Agent`; user calls carry `Authorization: Bearer`. Tokens are
 * supplied by [tokens] and persisted through [onTokens] — the client
 * never stores them itself. The client id is resolved on every call
 * through [clientId] (a saved Settings value overrides the
 * compile-time placeholder), so saving a new ID takes effect
 * without rebuilding the client. Refresh is single-flight, proactive a
 * day before expiry, and retried once after a 401; a second 401
 * throws [SimklAuthException]. A 429 throws [SimklRateLimited].
 *
 * Reads are sequential, never parallel, and never on a timer — the
 * sync engine gates every sync on `/sync/activities` first.
 */
class SimklClient(
    private val transport: HttpTransport,
    private val clientId: () -> String = { SIMKL_CLIENT_ID },
    private val appName: String = "Trove",
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val tokens: () -> SimklTokens?,
    private val onTokens: (SimklTokens) -> Unit,
) {
    private companion object {
        const val BASE_URL = "https://api.simkl.com"
        const val PROACTIVE_REFRESH_MS = 24 * 60 * 60 * 1000L
        const val REFRESH_TTL_MS = 180L * 24 * 60 * 60 * 1000
        const val ACCESS_TTL_FALLBACK_MS = 7L * 24 * 60 * 60 * 1000
        val imdbIdPattern = Regex("tt\\d+")
        val nextToWatchPattern = Regex("(?i)S(\\d+)E(\\d+)")
    }

    /** False while [SIMKL_CLIENT_ID] is still the placeholder. */
    val isConfigured: Boolean get() = clientId() != SIMKL_CLIENT_ID

    /** Last `X-RateLimit-Remaining` seen on a counted response, or
     *  null when no counted response has been seen yet. A value of 0
     *  means the daily allowance is spent. */
    @Volatile var lastRateLimitRemaining: Int? = null
        private set

    private val refreshMutex = Mutex()

    fun currentTokens(): SimklTokens? = tokens()

    // ------------------------------------------------------------------
    // Device / PIN sign-in
    // ------------------------------------------------------------------

    /** Starts the device flow. Throws [IllegalStateException] while
     *  the client id is the placeholder, and [SimklApiException] when
     *  the registration is rejected. */
    suspend fun startDeviceFlow(): SimklDeviceCode {
        check(isConfigured) { "SIMKL client is not registered" }
        val json = postFormJson(
            "/oauth2/device",
            mapOf("client_id" to clientId(), "scope" to "media:read"),
        )
        return SimklDeviceCode(
            deviceCode = json.optString("device_code") ?: throw SimklApiException(200, "bad_device_response"),
            userCode = json.optString("user_code") ?: throw SimklApiException(200, "bad_device_response"),
            verificationUri = json.optString("verification_uri") ?: "https://simkl.com/pin",
            verificationUriComplete = json.optString("verification_uri_complete")
                ?: json.optString("verification_uri") ?: "https://simkl.com/pin",
            expiresInSecs = json.optInt("expires_in") ?: 900,
            intervalSecs = json.optInt("interval") ?: 5,
        )
    }

    /** One poll of the device-code grant. Saves the tokens and
     *  returns [SimklPollResult.Authorized] on approval. */
    suspend fun pollDeviceCode(deviceCode: SimklDeviceCode): SimklPollResult {
        val response = postFormRaw(
            "/oauth2/token",
            mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to clientId(),
                "device_code" to deviceCode.deviceCode,
            ),
        )
        return when (response.status) {
            in 200..299 -> {
                val json = JsonParser.parseString(response.body).asJsonObject
                val access = json.optString("access_token") ?: return SimklPollResult.Failed("bad_token_response")
                val refresh = json.optString("refresh_token") ?: return SimklPollResult.Failed("bad_token_response")
                val expiresIn = json.optLong("expires_in") ?: (ACCESS_TTL_FALLBACK_MS / 1000)
                onTokens(
                    SimklTokens(
                        access, refresh,
                        now() + expiresIn * 1000, now() + REFRESH_TTL_MS,
                    ),
                )
                SimklPollResult.Authorized
            }
            400 -> when (response.errorField()) {
                "authorization_pending" -> SimklPollResult.Pending
                "slow_down" -> SimklPollResult.SlowDown(5)
                "expired_token" -> SimklPollResult.Expired
                else -> SimklPollResult.Failed(response.errorField() ?: "token_poll_failed")
            }
            401 -> SimklPollResult.InvalidClient
            429 -> SimklPollResult.SlowDown(retryAfterSeconds(response)?.toInt() ?: 5)
            else -> SimklPollResult.Failed("http${response.status}")
        }
    }

    /** Best-effort revoke of the current grant. Always returns
     *  normally: revoke answers 200 even for unknown tokens, and a
     *  failure here must never block sign-out. */
    suspend fun revoke() {
        val held = tokens() ?: return
        runCatching {
            postFormRaw("/oauth2/revoke", mapOf("client_id" to clientId(), "token" to held.accessToken))
        }
    }

    // ------------------------------------------------------------------
    // User data (all require a bearer token)
    // ------------------------------------------------------------------

    suspend fun activities(): SimklActivities {
        val json = authorizedGetJson("/sync/activities")
        return SimklActivities.parse(json)
    }

    /** One type/status bucket. [dateFrom] is the saved activities
     *  `all` watermark, passed exactly as returned; null fetches the
     *  whole bucket (bootstrap). */
    suspend fun allItems(
        type: SimklType,
        status: SimklStatus,
        dateFrom: String? = null,
        extended: String = "full",
    ): List<SimklEntry> {
        val params = mutableMapOf("extended" to extended, "next_watch_info" to "yes")
        if (dateFrom != null) params["date_from"] = dateFrom
        val json = authorizedGetJson("/sync/all-items/${type.apiValue}/${status.apiValue}", params)
        return parseAllItems(json, type)
    }

    /** Every library id, cheapest payload — for removal
     *  reconciliation only. Ids-only rows carry no status or media
     *  block, so the id is read straight from the `ids` object
     *  instead of going through [parseEntry]. */
    suspend fun allItemIds(): Set<Int> {
        val json = authorizedGetJson("/sync/all-items", mapOf("extended" to "simkl_ids_only"))
        val ids = mutableSetOf<Int>()
        for (type in SimklType.entries) {
            json.getAsJsonArray(type.responseKey)?.forEach { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                item.getAsJsonObject("ids")?.optInt("simkl")?.let { ids += it }
            }
        }
        return ids
    }

    suspend fun userSettings(): SimklUserSettings {
        val json = authorizedGetJson("/users/settings")
        val user = json.getAsJsonObject("user")
        return SimklUserSettings(
            username = user?.optString("name")?.takeIf { it.isNotBlank() },
            accountType = json.getAsJsonObject("account")?.optString("type"),
        )
    }

    // ------------------------------------------------------------------
    // Transport + auth
    // ------------------------------------------------------------------

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val all = linkedMapOf(
            "client_id" to clientId(),
            "app-name" to appName,
            "app-version" to appVersion,
        ) + params
        return "$BASE_URL$path?" + all.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    private fun userAgentHeaders(): Map<String, String> = mapOf("User-Agent" to "$appName/$appVersion")

    private fun authHeaders(): Map<String, String> {
        val access = tokens()?.accessToken ?: throw SimklAuthException()
        return userAgentHeaders() + ("Authorization" to "Bearer $access")
    }

    private suspend fun authorizedGetJson(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        ensureFreshAccessToken()
        var response = transport.execute(HttpRequest("GET", url(path, params), authHeaders()))
        if (response.status == 401) {
            refreshAccessToken()
            response = transport.execute(HttpRequest("GET", url(path, params), authHeaders()))
            if (response.status == 401) throw SimklAuthException()
        }
        return checkedJson(response)
    }

    private suspend fun postFormJson(path: String, fields: Map<String, String>): JsonObject {
        val response = postFormRaw(path, fields)
        return checkedJson(response)
    }

    private suspend fun postFormRaw(path: String, fields: Map<String, String>): HttpResponse {
        val response = transport.execute(
            HttpRequest("POST", url(path), userAgentHeaders(), formFields = fields),
        )
        noteRateLimit(response)
        return response
    }

    private fun checkedJson(response: HttpResponse): JsonObject {
        noteRateLimit(response)
        when (response.status) {
            in 200..299 -> return JsonParser.parseString(response.body).asJsonObject
            429 -> throw SimklRateLimited(retryAfterMs(response))
            else -> throw SimklApiException(response.status, response.errorField())
        }
    }

    /** Reads `X-RateLimit-Remaining` when present. Absent on the
     *  first request with a new token and on exempt endpoints — that
     *  is normal, not an error. */
    private fun noteRateLimit(response: HttpResponse) {
        response.headers.entries.firstOrNull { it.key.equals("X-RateLimit-Remaining", true) }
            ?.value?.trim()?.toIntOrNull()?.let { lastRateLimitRemaining = it }
    }

    private fun HttpResponse.errorField(): String? = runCatching {
        JsonParser.parseString(body).asJsonObject.optString("error")
    }.getOrNull()

    private fun retryAfterSeconds(response: HttpResponse): Long? {
        val header = response.headers.entries
            .firstOrNull { it.key.equals("Retry-After", true) }?.value?.trim() ?: return null
        return header.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun retryAfterMs(response: HttpResponse): Long? {
        val header = response.headers.entries
            .firstOrNull { it.key.equals("Retry-After", true) }?.value?.trim() ?: return null
        header.toLongOrNull()?.takeIf { it >= 0 }?.let { return it * 1000 }
        return runCatching {
            java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli().minus(now()).takeIf { it > 0 }
        }.getOrNull()
    }

    /** Next midnight in America/New_York — the daily quota reset. */
    internal fun nextQuotaResetMs(fromMs: Long = now()): Long {
        val zone = ZoneId.of("America/New_York")
        return Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private suspend fun ensureFreshAccessToken() {
        val held = tokens() ?: throw SimklAuthException()
        if (held.accessExpiresAtMs - now() < PROACTIVE_REFRESH_MS) refreshAccessToken()
    }

    /** Single-flight refresh: concurrent callers wait on the one
     *  in-flight refresh instead of each refreshing. A refresh that
     *  already happened while waiting is reused. */
    private suspend fun refreshAccessToken() {
        val failing = tokens()?.accessToken
        refreshMutex.withLock {
            val current = tokens()
            if (current != null && current.accessToken != failing) return
            val refresh = current?.refreshToken ?: throw SimklAuthException()
            val response = postFormRaw(
                "/oauth2/token",
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId(),
                    "refresh_token" to refresh,
                ),
            )
            when (response.status) {
                in 200..299 -> {
                    val json = JsonParser.parseString(response.body).asJsonObject
                    val access = json.optString("access_token") ?: throw SimklAuthException()
                    val expiresIn = json.optLong("expires_in") ?: (ACCESS_TTL_FALLBACK_MS / 1000)
                    // The refresh token is non-rotating: the response
                    // repeats the one sent, and its window slides.
                    onTokens(
                        SimklTokens(
                            access,
                            json.optString("refresh_token") ?: refresh,
                            now() + expiresIn * 1000, now() + REFRESH_TTL_MS,
                        ),
                    )
                }
                429 -> throw SimklRateLimited(retryAfterMs(response))
                else -> throw SimklAuthException()
            }
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private fun parseAllItems(json: JsonObject, type: SimklType): List<SimklEntry> =
        json.getAsJsonArray(type.responseKey)?.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            parseEntry(item, type)
        }.orEmpty()

    private fun parseEntry(item: JsonObject, type: SimklType): SimklEntry? {
        // Rewatch rows never appear without ?allow_rewatch=yes; skip
        // them defensively so a session can never shadow the canonical row.
        if (item.optBooleanOrNull("is_rewatch") == true) return null
        val status = SimklStatus.fromApi(item.optString("status")) ?: return null
        val block = item.getAsJsonObject(type.mediaKey) ?: return null
        val ids = block.getAsJsonObject("ids") ?: return null
        val simklId = ids.optInt("simkl") ?: return null
        val nextInfo = item.getAsJsonObject("next_to_watch_info")
        val (nextSeason, nextEpisode) = when {
            nextInfo != null -> nextInfo.optInt("season") to nextInfo.optInt("episode")
            else -> parseNextToWatch(item.optString("next_to_watch"))
        }
        return SimklEntry(
            simklId = simklId,
            type = type,
            status = status,
            title = block.optString("title")?.takeIf { it.isNotBlank() },
            imdbId = ids.optString("imdb")?.takeIf { it.matches(imdbIdPattern) },
            tmdbId = ids.optString("tmdb")?.toIntOrNull(),
            slug = ids.optString("slug")?.takeIf { it.isNotBlank() },
            watchedEpisodes = item.optInt("watched_episodes_count"),
            totalEpisodes = item.optInt("total_episodes_count"),
            nextSeason = nextSeason,
            nextEpisode = nextEpisode,
            nextTitle = nextInfo?.optString("title")?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseNextToWatch(value: String?): Pair<Int?, Int?> {
        val match = value?.let(nextToWatchPattern::matchEntire) ?: return null to null
        return match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
    }
}
