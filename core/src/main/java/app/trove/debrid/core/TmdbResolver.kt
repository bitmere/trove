package app.trove.debrid.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import kotlin.math.abs

/**
 * TMDB as an opt-in fallback behind Cinemeta, never a primary source.
 *
 * The caller supplies a per-user API Read Access Token (v4 auth) via
 * [apiKey]; there is no shared or shipped key. A missing or blank key
 * means TMDB is silently skipped — [resolve] and [resolveById] return
 * null without touching the network. The token travels only as an
 * `Authorization: Bearer` header, never in a URL, and is never logged:
 * [HttpRequest.toString] redacts request URLs and headers are not
 * printed at all.
 *
 * Lookup order mirrors the resolver it backs: `/3/find/{imdbId}` first
 * when an IMDb id is known (one call bridges the stored id), title
 * search second (`/3/search/movie`, `/3/search/tv`) with exact normalized
 * localized or original titles and a strict year gate. A match is then expanded with a detail call
 * (genres, runtime, overview) and a best-effort credits call (cast,
 * director), because search and find responses carry neither.
 *
 * Poster URLs are built from `GET /3/configuration` (image base URL +
 * sizes), fetched once per resolver and cached in memory. The CDN host
 * is never hardcoded: when configuration cannot be read, the title
 * still resolves and the poster is left null.
 *
 * TMDB-sourced entries are cached by the caller with the same 180-day
 * disk TTL as Cinemeta entries, inside the 6-month cap in the TMDB API
 * Terms of Use (§1.C: "Cache, for longer than 6 months, any
 * information obtained through or from TMDB or the TMDB APIs.").
 * This resolver keeps no disk cache of its own.
 *
 * Deliberate gaps, not oversights: the TMDB `vote_average` is NOT
 * mapped onto [MediaMetadata.imdbRating] — the details sheet renders
 * that field as an IMDb rating, and a TMDB score is not one. TV results
 * carry no episode list (that needs one call per season), so episodes
 * stay empty and keep coming from Cinemeta.
 */
class TmdbResolver(
    private val transport: HttpTransport,
    private val apiKey: () -> String?,
) {
    private companion object {
        const val BASE_URL = "https://api.themoviedb.org"
        val imdbIdPattern = Regex("tt\\d+")
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
    )

    private val configMutex = Mutex()
    private var imageConfig: ImageConfig? = null

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
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
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
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
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
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
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

    private suspend fun searchHit(query: MediaQuery, key: String): TmdbHit? {
        val encoded = URLEncoder.encode(query.title, "UTF-8").replace("+", "%20")
        val titleKey = normalizedTitle(query.title)
        val types = query.type?.let { listOf(it) } ?: listOf("movie", "series")
        val hits = types.flatMap { type ->
            val series = type == "series"
            val endpoint = if (series) "tv" else "movie"
            get("/3/search/$endpoint?query=$encoded", key)
                .results("results").mapNotNull { (id, obj) ->
                    if (id == null) return@mapNotNull null
                    val title = normalizedTitle(obj.optString(if (series) "name" else "title").orEmpty())
                    val original = normalizedTitle(obj.optString(if (series) "original_name" else "original_title").orEmpty())
                    if (title != titleKey && original != titleKey) return@mapNotNull null
                    SearchMatch(
                        TmdbHit(id, type, obj.optString("poster_path")), releaseYear(obj, type), title, original,
                        obj.optString("original_language")?.takeIf { it.isNotBlank() },
                        obj.optString("popularity")?.toDoubleOrNull()?.takeIf { it.isFinite() },
                        localizedTitleMatches = title == titleKey,
                    )
                }
        }.distinctBy { it.hit.tmdbId to it.hit.type }
        if (query.year == null) return hits.singleOrNull()?.hit
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
    ): MediaMetadata? {
        val endpoint = if (hit.type == "series") "tv" else "movie"
        val obj = get("/3/$endpoint/${hit.tmdbId}", key)
        if (obj.optInt("id") != hit.tmdbId) return null
        val title = obj.optString(if (hit.type == "series") "name" else "title")
            ?.takeIf { it.isNotBlank() } ?: return null
        val credits = try {
            get("/3/$endpoint/${hit.tmdbId}/credits", key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
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
            posterUrl = posterUrl(config, obj.optString("poster_path")),
            imdbId = imdbId,
            overview = obj.optString("overview")?.takeIf { it.isNotBlank() },
            genres = obj.results("genres")
                .mapNotNull { it.second.optString("name")?.takeIf(String::isNotBlank) }
                .distinct().take(5),
            cast = cast,
            director = director,
            runtime = runtimeMinutes?.let { "$it min" },
        )
    }

    /** Configuration is key-independent, so one fetch serves every
     *  key. A failure leaves the poster out, never the title. */
    private suspend fun imageConfig(key: String): ImageConfig? {
        imageConfig?.let { return it }
        return configMutex.withLock {
            imageConfig?.let { return it }
            val parsed: ImageConfig? = try {
                val images = get("/3/configuration", key).getAsJsonObject("images")
                val base = images?.optString("secure_base_url")?.takeIf { it.isNotBlank() }
                if (base == null) null else ImageConfig(base, images.stringResults("poster_sizes"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (parsed != null) imageConfig = parsed
            parsed
        }
    }

    private fun posterUrl(config: ImageConfig?, path: String?): String? {
        val base = config?.baseUrl?.takeIf { it.startsWith("https://") } ?: return null
        val cleanPath = path?.takeIf { it.startsWith("/") } ?: return null
        val size = config.posterSizes.firstOrNull { it == "w342" }
            ?: config.posterSizes.firstOrNull { it == "w500" }
            ?: config.posterSizes.firstOrNull { it != "original" }
            ?: config.posterSizes.firstOrNull()
            ?: return null
        return publicPosterUrl("$base$size$cleanPath")
    }

    private suspend fun get(path: String, key: String): JsonObject {
        val response = transport.execute(
            HttpRequest("GET", "$BASE_URL$path", mapOf("Authorization" to "Bearer $key")),
        )
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
