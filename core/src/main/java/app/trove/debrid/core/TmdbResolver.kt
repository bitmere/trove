package app.trove.debrid.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Opt-in TMDB metadata and artwork. The per-user token is sent only in the
 * `Authorization: Bearer` header. It never appears in a URL or in logs.
 * With a missing or blank token, metadata lookups silently skip TMDB and
 * make zero TMDB requests. [CinemetaResolver] tries TMDB first only with a
 * configured token and falls back to Cinemeta if the primary lookup fails
 * or finds no match.
 *
 * A lookup with a known IMDb ID uses `/3/find`. Without one, TMDB searches
 * by title. The primary title search requires a single match and an exact
 * year when the query supplies one. A full match fetches details and
 * best-effort credits. The primary detail request appends `external_ids`
 * to obtain the IMDb ID for Cinemeta backfill.
 *
 * Poster URLs use the HTTPS base URL and sizes from `/3/configuration`,
 * with no hardcoded CDN host. TMDB's `vote_average` is deliberately left
 * out of [MediaMetadata.imdbRating]. TMDB TV results carry no episode list
 * because fetching it requires a call per season. Cinemeta backfill supplies
 * episodes and the IMDb rating when available.
 *
 * TMDB-sourced entries use the same 180-day disk-cache freshness TTL as
 * Cinemeta entries, within the six-month cap in section 1.C of the TMDB
 * API Terms of Use. An HTTP 429 honors `Retry-After` and sets a cooldown
 * shared by all requests through this resolver. Primary lookups fall back
 * to Cinemeta during the cooldown, avoiding repeated TMDB requests.
 */
/**
 * A hand-entered TMDB link from the match picker: a TMDB id
 * (from a themoviedb.org link or a bare number), a title to
 * search, or input that cannot be used. */
sealed interface ManualTmdbLink {
    data class ById(val tmdbId: Int, val type: String) : ManualTmdbLink
    data class ByTitle(val title: String) : ManualTmdbLink
    data object Invalid : ManualTmdbLink
}

/** Outcome of a manual TMDB search: candidates to show, nothing
 *  matching, or input that cannot be used. */
sealed interface ManualTmdbSearch {
    data class Found(val candidates: List<MediaCandidate>) : ManualTmdbSearch
    data object NoMatch : ManualTmdbSearch
    data object Invalid : ManualTmdbSearch
}

class TmdbResolver(
    private val transport: HttpTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val apiKey: () -> String?,
) {
    /** Title matches for the picker when Cinemeta lists nothing.
     *  Key required: no key means an empty list and zero TMDB
     *  requests. Failures (including the 429 cooldown) mean an
     *  empty list, never an exception. Results are in TMDB's
     *  returned order (movies, then series) and capped at the
     *  picker's [MAX_PICKER_CANDIDATES]. No year filter: the
     *  picker shows all years and the user chooses. The automatic
     *  picker uses exact normalized-title matches; [allowPartial]
     *  permits manual title substrings of 2+ normalized characters. */
    suspend fun searchCandidates(query: MediaQuery, allowPartial: Boolean = false): List<MediaCandidate> {
        val key = configuredKey() ?: return emptyList()
        if (query.title.isBlank() || query.title.length > 160) return emptyList()
        return try {
            val matches = searchMatches(query, key, allowPartial).take(MAX_PICKER_CANDIDATES)
            val config = if (matches.isEmpty()) null else imageConfig(key)
            matches.mapNotNull { match ->
                val title = match.displayTitle.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MediaCandidate(null, title, match.year, match.hit.type, tmdbId = match.hit.tmdbId,
                    posterUrl = posterUrl(config, match.hit.posterPath), overview = match.overview)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Detail for a known TMDB id, as picked from the picker list.
     *  Fetches with `primary = true` so `external_ids` supplies a
     *  verified IMDb id when TMDB knows one. Null when no key is
     *  configured (no network call), for an invalid id/type, for a
     *  detail id that does not match, or when any TMDB call fails. */
    suspend fun resolveByTmdbId(tmdbId: Int, type: String): MediaMetadata? {
        val key = configuredKey() ?: return null
        if (tmdbId <= 0 || type !in listOf("movie", "series")) return null
        return try {
            detail(TmdbHit(tmdbId, type, null), null, imageConfig(key, primary = true), key, primary = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Parses hand-entered picker input without a network call.
     *  Accepts a themoviedb.org link (`/movie/<id>` or `/tv/<id>`),
     *  a bare numeric id when [fallbackType] is known, or a title to search
     *  (including untyped digits). Blank or over-long input is invalid. */
    fun parseManualLink(input: String, fallbackType: String?): ManualTmdbLink {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > 160) return ManualTmdbLink.Invalid
        val link = tmdbLinkPattern.matchEntire(trimmed)
        if (link != null) {
            val id = link.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
                ?: return ManualTmdbLink.Invalid
            val type = if (link.groupValues[1].equals("tv", ignoreCase = true)) "series" else "movie"
            return ManualTmdbLink.ById(id, type)
        }
        if (trimmed.all { it.isDigit() }) {
            val type = fallbackType?.takeIf { it == "movie" || it == "series" }
                ?: return ManualTmdbLink.ByTitle(trimmed)
            val id = trimmed.toIntOrNull()?.takeIf { it > 0 } ?: return ManualTmdbLink.Invalid
            return ManualTmdbLink.ById(id, type)
        }
        return ManualTmdbLink.ByTitle(trimmed)
    }

    /** Manual TMDB search for the picker: an id resolves its detail
     *  to a single candidate, a title reuses [searchCandidates]
     *  with substring matching for 2+ normalized characters and the
     *  same cap as the picker. The automatic picker stays exact. No key
     *  or any TMDB failure means [ManualTmdbSearch.NoMatch], never
     *  an exception; invalid input makes no request. */
    suspend fun searchManualLink(input: String, fallbackType: String?): ManualTmdbSearch {
        return when (val parsed = parseManualLink(input, fallbackType)) {
            ManualTmdbLink.Invalid -> ManualTmdbSearch.Invalid
            is ManualTmdbLink.ById -> {
                val metadata = resolveByTmdbId(parsed.tmdbId, parsed.type)
                    ?: return ManualTmdbSearch.NoMatch
                ManualTmdbSearch.Found(listOf(
                    MediaCandidate(null, metadata.title, metadata.year, parsed.type, tmdbId = parsed.tmdbId,
                        posterUrl = metadata.posterUrl, overview = metadata.overview)))
            }
            is ManualTmdbLink.ByTitle -> {
                val type = fallbackType?.takeIf { it == "movie" || it == "series" }
                val found = searchCandidates(MediaQuery(parsed.title, null, type), allowPartial = true)
                if (found.isEmpty()) ManualTmdbSearch.NoMatch else ManualTmdbSearch.Found(found)
            }
        }
    }

    private companion object {
        const val BASE_URL = "https://api.themoviedb.org"
        val imdbIdPattern = Regex("tt\\d+")
        /** themoviedb.org item link, tolerant of scheme, www, and
         *  anything after the id. */
        val tmdbLinkPattern =
            Regex("^(?:https?://)?(?:www\\.)?themoviedb\\.org/(movie|tv)/(\\d+)(?:[-/?#].*)?$",
                RegexOption.IGNORE_CASE)
        /** Same cap as the Cinemeta picker's MAX_CANDIDATES. */
        const val MAX_PICKER_CANDIDATES = 5
    }

    private data class ImageConfig(val baseUrl: String, val posterSizes: List<String>)
    private data class TmdbHit(val tmdbId: Int, val type: String, val posterPath: String?)

    private data class SearchMatch(
        val hit: TmdbHit,
        val year: Int?,
        val title: String,
        val originalTitle: String,
        val language: String?,
        val popularity: Double?,
        val localizedTitleMatches: Boolean,
        /** Display title as TMDB returned it (never the normalized form). */
        val displayTitle: String,
        val overview: String?,
    )

    private val configMutex = Mutex()
    private var imageConfig: ImageConfig? = null

    @Volatile private var blockedUntil = 0L
    private val rateLimitLock = Any()

    internal fun configuredKey(): String? = apiKey()?.takeIf { it.isNotBlank() }
    internal fun cooldownUntil(): Long = blockedUntil

    /** A rate limit anywhere in primary enrichment sends the lookup to Cinemeta. */
    internal suspend fun resolvePrimary(query: MediaQuery, key: String): MediaMetadata? {
        val hit = searchHit(query, key, strict = true) ?: return null
        val metadata = detail(hit, null, imageConfig(key, primary = true), key, primary = true)
        return metadata?.takeIf { it.year == null || query.year == null || it.year == query.year }
    }

    /** Key check without resolving: a 2xx from /3/configuration with
     *  the given key means the key works. The key is a parameter, not
     *  the stored one, so a key can be checked before it is saved. */
    suspend fun validateKey(key: String): Boolean {
        if (key.isBlank()) return false
        return try {
            get("/3/configuration", key)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Title search, or `/find` when [imdbId] is supplied. Null when
     *  no key is configured (no network call), when nothing matches
     *  strictly, or when any TMDB call fails. */
    suspend fun resolve(query: MediaQuery, imdbId: String? = null): MediaMetadata? {
        val key = configuredKey() ?: return null
        if (query.title.isBlank() || query.title.length > 160) return null
        return try {
            val hit = if (imdbId?.matches(imdbIdPattern) == true) {
                findHit(imdbId, query.type, key)
            } else {
                searchHit(query, key)
            } ?: return null
            detail(hit, imdbId?.takeIf { it.matches(imdbIdPattern) }, imageConfig(key), key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Find a poster without fetching the detail and credits used for a full match. */
    suspend fun resolvePoster(query: MediaQuery, imdbId: String?): String? {
        val key = configuredKey() ?: return null
        return try {
            val hit = if (imdbId?.matches(imdbIdPattern) == true) {
                findHit(imdbId, query.type, key)
            } else {
                if (query.title.isBlank() || query.title.length > 160) return null
                searchHit(query, key)
            } ?: return null
            val path = hit.posterPath?.takeIf { it.startsWith("/") } ?: return null
            posterUrl(imageConfig(key), path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Detail for a known IMDb id via `/find`. The type must be
     *  "movie" or "series"; anything else returns null without a
     *  network call. */
    suspend fun resolveById(imdbId: String, type: String): MediaMetadata? {
        val key = configuredKey() ?: return null
        if (!imdbId.matches(imdbIdPattern) || type !in listOf("movie", "series")) return null
        return try {
            val config = imageConfig(key)
            val hit = findHit(imdbId, type, key) ?: return null
            detail(hit, imdbId, config, key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findHit(imdbId: String, type: String?, key: String): TmdbHit? {
        val found = get("/3/find/$imdbId?external_source=imdb_id", key)
        fun hit(array: String, type: String): TmdbHit? =
            found.results(array).firstNotNullOfOrNull { (id, obj) ->
                id?.let { TmdbHit(it, type, obj.optString("poster_path")) }
            }
        return when (type) {
            "movie" -> hit("movie_results", "movie")
            "series" -> hit("tv_results", "series")
            else -> hit("movie_results", "movie") ?: hit("tv_results", "series")
        }
    }

    /** Title matches in TMDB's returned order, across the query's
     *  type (or movie + series when the type is unknown). The
     *  caller decides what a match means — the strict single-hit
     *  gate picks from these, the picker lists them. */
    private suspend fun searchMatches(query: MediaQuery, key: String, allowPartial: Boolean = false): List<SearchMatch> {
        val encoded = URLEncoder.encode(query.title, "UTF-8").replace("+", "%20")
        val titleKey = normalizedTitle(query.title)
        val types = query.type?.let { listOf(it) } ?: listOf("movie", "series")
        return types.flatMap { type ->
            val series = type == "series"
            val endpoint = if (series) "tv" else "movie"
            get("/3/search/$endpoint?query=$encoded", key)
                .results("results").mapNotNull { (id, obj) ->
                    if (id == null) return@mapNotNull null
                    val title = normalizedTitle(obj.optString(if (series) "name" else "title").orEmpty())
                    val original = normalizedTitle(obj.optString(if (series) "original_name" else "original_title").orEmpty())
                    val exact = title == titleKey || original == titleKey
                    val partial = !exact && allowPartial && titleKey.length >= 2 &&
                        (title.contains(titleKey) || original.contains(titleKey))
                    if (!exact && !partial) return@mapNotNull null
                    SearchMatch(
                        TmdbHit(id, type, obj.optString("poster_path")), releaseYear(obj, type), title, original,
                        obj.optString("original_language")?.takeIf { it.isNotBlank() },
                        obj.optString("popularity")?.toDoubleOrNull()?.takeIf { it.isFinite() },
                        localizedTitleMatches = title == titleKey,
                        overview = obj.optString("overview")?.takeIf { it.isNotBlank() },
                        displayTitle = obj.optString(if (series) "name" else "title")
                            ?.takeIf { it.isNotBlank() }
                            ?: obj.optString(if (series) "original_name" else "original_title").orEmpty(),
                    )
                }
        }.distinctBy { it.hit.tmdbId to it.hit.type }
    }

    private suspend fun searchHit(query: MediaQuery, key: String, strict: Boolean = false): TmdbHit? {
        val hits = searchMatches(query, key)
        if (query.year == null) return hits.singleOrNull()?.hit
        if (strict) return hits.singleOrNull { it.year == query.year }?.hit
        val eligible = hits.filter { matchesYear(it.year, query.year, tolerance = 1) }
        val titleMatches = eligible.filter { it.localizedTitleMatches }.ifEmpty { eligible }
        val ranked = titleMatches.filter { it.year == query.year }
        if (ranked.isEmpty()) return titleMatches.singleOrNull()?.hit
        val first = ranked.first()
        if (ranked.size == 1) return first.hit
        // A shared localized title can hide unrelated titles from different markets.
        // Use popularity only with the same known language, type, and original title.
        if (first.language == null || first.originalTitle.isEmpty() || ranked.any {
            it.language != first.language || it.title != first.title || it.originalTitle != first.originalTitle ||
                it.hit.type != first.hit.type || it.popularity == null
        }) return null
        val highest = ranked.maxOf { it.popularity!! }
        return ranked.singleOrNull { it.popularity == highest }?.hit
    }

    private suspend fun detail(
        hit: TmdbHit,
        imdbId: String?,
        config: ImageConfig?,
        key: String,
        primary: Boolean = false,
    ): MediaMetadata? {
        val endpoint = if (hit.type == "series") "tv" else "movie"
        val append = if (primary) "?append_to_response=external_ids" else ""
        val obj = get("/3/$endpoint/${hit.tmdbId}$append", key)
        if (obj.optInt("id") != hit.tmdbId) return null
        val title = obj.optString(if (hit.type == "series") "name" else "title")
            ?.takeIf { it.isNotBlank() } ?: return null
        val credits = try {
            get("/3/$endpoint/${hit.tmdbId}/credits", key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (primary && e is RateLimited) throw e
            null
        }
        val cast = credits?.results("cast")
            ?.mapNotNull { it.second.optString("name")?.takeIf(String::isNotBlank) }
            ?.take(5).orEmpty()
        val director = credits?.results("crew")
            ?.firstNotNullOfOrNull { (_, member) ->
                member.optString("name")?.takeIf { member.optString("job") == "Director" }
            } ?: obj.results("created_by")
            .firstNotNullOfOrNull { it.second.optString("name") }
        val runtimeMinutes = obj.optInt("runtime")
            ?: obj.get("episode_run_time")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.firstOrNull()?.let { runCatching { it.asInt }.getOrNull() }
        return MediaMetadata(
            title = title,
            year = releaseYear(obj, hit.type),
            type = hit.type,
            posterUrl = posterUrl(config, obj.optString("poster_path") ?: hit.posterPath),
            imdbId = imdbId ?: if (primary) {
                val external = obj.get("external_ids")?.takeIf { it.isJsonObject }?.asJsonObject
                (external?.optString("imdb_id") ?: obj.optString("imdb_id"))
                    ?.takeIf { it.matches(imdbIdPattern) }
            } else null,
            overview = obj.optString("overview")?.takeIf { it.isNotBlank() },
            genres = obj.results("genres")
                .mapNotNull { it.second.optString("name")?.takeIf(String::isNotBlank) }
                .distinct().take(5),
            cast = cast,
            director = director,
            runtime = runtimeMinutes?.let { "$it min" },
            provenance = EnrichmentProvenance.TMDB,
        )
    }

    /** Configuration is key-independent, so one fetch serves every
     *  key. A failure leaves the poster out, never the title. */
    private suspend fun imageConfig(key: String, primary: Boolean = false): ImageConfig? {
        imageConfig?.let { return it }
        return configMutex.withLock {
            imageConfig?.let { return it }
            val parsed: ImageConfig? = try {
                val images = get("/3/configuration", key).getAsJsonObject("images")
                val base = images?.optString("secure_base_url")?.takeIf { it.isNotBlank() }
                if (base == null) null else ImageConfig(base, images.stringResults("poster_sizes"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (primary && e is RateLimited) throw e
                null
            }
            if (parsed != null) imageConfig = parsed
            parsed
        }
    }

    private fun posterUrl(config: ImageConfig?, path: String?): String? {
        val base = config?.baseUrl?.takeIf { it.startsWith("https://") } ?: return null
        val cleanPath = path?.takeIf { it.startsWith("/") } ?: return null
        val size = config.posterSizes.firstOrNull { it == "w185" }
            ?: config.posterSizes.firstOrNull { it == "w342" }
            ?: config.posterSizes.firstOrNull { it == "w500" }
            ?: config.posterSizes.firstOrNull { it != "original" }
            ?: config.posterSizes.firstOrNull()
            ?: return null
        return publicPosterUrl("$base$size$cleanPath")
    }

    private class RateLimited : Exception("TMDB rate limited")

    private suspend fun get(path: String, key: String): JsonObject {
        if (now() < blockedUntil) throw RateLimited()
        val response = transport.execute(
            HttpRequest("GET", "$BASE_URL$path", mapOf("Authorization" to "Bearer $key")),
        )
        if (response.status == 429) {
            val currentTime = now()
            val header = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value
            val seconds = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            val httpDate = if (seconds == null && header != null) runCatching {
                java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli()
            }.getOrNull() else null
            val deadline = when {
                seconds != null -> currentTime + seconds.coerceAtMost((Long.MAX_VALUE - currentTime) / 1000) * 1000
                httpDate != null -> httpDate
                else -> currentTime + 60_000
            }
            synchronized(rateLimitLock) {
                blockedUntil = maxOf(blockedUntil, deadline, currentTime + 1_000)
            }
            throw RateLimited()
        }
        check(response.status in 200..299)
        return JsonParser.parseString(response.body).asJsonObject
    }

    private fun releaseYear(obj: JsonObject, type: String): Int? {
        val date = obj.optString(if (type == "series") "first_air_date" else "release_date")
        return date?.take(4)?.toIntOrNull()
    }

    private fun matchesYear(year: Int?, queryYear: Int?, tolerance: Int): Boolean =
        queryYear == null || year?.let { abs(it.toLong() - queryYear) <= tolerance } == true

    private fun JsonObject.results(name: String): List<Pair<Int?, JsonObject>> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            ?.map { obj -> obj.optInt("id") to obj }.orEmpty()

    private fun JsonObject.stringResults(name: String): List<String> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element ->
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.takeIf { it.isNotBlank() }
            }.orEmpty()
}
