package app.trove.debrid.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.URLEncoder
import java.time.Year
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Media identity and details, independent of provider and playback credentials. */
data class MediaMetadata(
    val title: String,
    val year: Int?,
    val type: String?,
    val posterUrl: String? = null,
    val imdbId: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val imdbRating: Double? = null,
    val runtime: String? = null,
    val episodes: List<MediaEpisode> = emptyList(),
)

data class MediaEpisode(val season: Int, val episode: Int, val title: String?, val released: String?)
data class MediaQuery(val title: String, val year: Int?, val type: String?)
data class ReleaseDetails(val episode: String?, val quality: String?)

/** One title the user can pick when the automatic match fails. The
 *  automatic gate stays strict: a candidate list is never auto-applied. */
data class MediaCandidate(val imdbId: String, val title: String, val year: Int?, val type: String)

private val extension = Regex("\\.(mkv|mp4|avi|mov|webm|m4v|ts|torrent|nzb)$", RegexOption.IGNORE_CASE)
private val yearToken = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)")
private val episode = Regex("(?i)\\b(?:s(\\d{1,2})(?:e(\\d{1,3}))?|(\\d{1,2})x(\\d{2})|season\\s+\\d+)\\b")
private val absoluteEpisode = Regex("(?:\\s+|-+)(\\d{2,4})$")
private val imdbIdPattern = Regex("tt\\d+")
private val videoId = Regex("tt\\d+:(-?\\d+):(-?\\d+)")
private val releaseTag = Regex("(?i)\\b(?:2160p|1080[pi]|720p|480p|4k|web[ -]?(?:rip|dl)|bluray|blu[ -]ray|b[dr]rip|dvdrip|hdtv|remux|hevc|avc|[xh][ .-]?26[45]|hdr10?\\+?|dv|aac|dts|ddp|ac3|multi|dual|proper|repack)\\b")

fun parseReleaseDetails(name: String): ReleaseDetails {
    if (name.contains("://") || name.startsWith("magnet:", true)) return ReleaseDetails(null, null)
    val base = name.replace('\\', '/').substringAfterLast('/').replace(extension, "")
        .replace('.', ' ').replace('_', ' ')
    val episodeToken = episode.findAll(base).firstNotNullOfOrNull { match ->
        val (sSeason, sEpisode, xSeason, xEpisode) = match.destructured
        val season = sSeason.ifEmpty { xSeason }
        val number = sEpisode.ifEmpty { xEpisode }
        if (number.isEmpty()) null else "S${season.padStart(2, '0')}E${number.padStart(2, '0')}"
    }
    val quality = releaseTag.findAll(base).firstNotNullOfOrNull { match ->
        when (val tag = match.value.lowercase(Locale.ROOT)) {
            "4k" -> "2160p"
            "2160p", "1080p", "1080i", "720p", "480p" -> tag
            else -> null
        }
    }
    return ReleaseDetails(episodeToken, quality)
}

fun matchingEpisode(episodes: List<MediaEpisode>, episodeToken: String?): MediaEpisode? {
    val match = episodeToken?.let(episode::matchEntire) ?: return null
    val (sSeason, sEpisode, xSeason, xEpisode) = match.destructured
    val season = sSeason.ifEmpty { xSeason }.toIntOrNull() ?: return null
    val number = sEpisode.ifEmpty { xEpisode }.toIntOrNull() ?: return null
    return episodes.firstOrNull { it.season == season && it.episode == number }
}

fun parseMediaName(name: String): MediaQuery {
    // URLs and magnets must never become third-party search requests.
    if (name.contains("://") || name.startsWith("magnet:", true)) return MediaQuery("", null, null)
    val base = name.replace('\\', '/').substringAfterLast('/').replace(extension, "")
        .replace(Regex("\\[(?!(?:19|20)\\d{2}])[^]]*]"), " ").replace('.', ' ').replace('_', ' ')
    val ep = episode.find(base)
    val tag = releaseTag.find(base)
    val boundary = listOfNotNull(ep?.range?.first, tag?.range?.first).minOrNull() ?: base.length
    val latestYear = Year.now().value + 1
    val year = yearToken.findAll(base.take(boundary))
        .lastOrNull { it.range.first > 0 && it.value.toInt() <= latestYear }
    val end = minOf(boundary, year?.range?.first ?: base.length)
    val title = base.take(end).replace(Regex("[(){}\\[\\]]"), " ")
        .replace(Regex("\\s+"), " ").trim(' ', '-')
    val absolute = if (ep == null) absoluteEpisode.find(title) else null
    val seriesTitle = absolute?.takeUnless { yearToken.matches(it.groupValues[1]) }
        ?.takeIf { it.groupValues[1].length >= 3 && it.groupValues[1].toInt() > 100 }
        ?.let { title.take(it.range.first).trimEnd(' ', '-') }
        ?.takeIf { it.any(Char::isLetterOrDigit) }
    return MediaQuery(seriesTitle ?: title, year?.value?.toIntOrNull(),
        if (ep != null || seriesTitle != null) "series" else null)
}

internal fun normalizedTitle(title: String): String = java.text.Normalizer
    .normalize(title, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

/** Only public HTTPS poster URLs. Never accept credential-bearing links. */
fun publicPosterUrl(value: String?): String? = value?.takeIf {
    val uri = runCatching { URI(it) }.getOrNull()
    uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.rawQuery == null && uri.fragment == null && !it.contains("requestdl", true)
}

/** Bounded session and optional disk cache. The mutex serializes catalog traffic. */
class CinemetaResolver(
    private val transport: HttpTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val diskCache: MetadataDiskCache? = null,
    private val revalidationScope: CoroutineScope? = null,
    /** Opt-in TMDB fallback, consulted only after Cinemeta yields
     *  nothing. Null (the default) means Cinemeta is the only source;
     *  a resolver with no key configured skips itself silently. */
    private val tmdbResolver: TmdbResolver? = null,
) {
    constructor(transport: HttpTransport, clock: () -> Long) : this(transport, now = clock)

    private companion object {
        const val SUCCESS_TTL_MS = 24 * 60 * 60 * 1000L
        const val MISS_TTL_MS = 60 * 60 * 1000L
        const val FAILURE_TTL_MS = 60_000L
        const val MAX_CANDIDATES = 5
    }

    private data class CacheEntry(val value: MediaMetadata?, val expires: Long)
    private val mutex = Mutex()
    private val refreshing = ConcurrentHashMap<MediaQuery, Unit>()
    private val cache = object : LinkedHashMap<MediaQuery, CacheEntry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaQuery, CacheEntry>?): Boolean = size > 256
    }

    suspend fun resolve(query: MediaQuery): MediaMetadata? = mutex.withLock {
        if (query.title.isBlank() || query.title.length > 160) return@withLock null
        val key = query.copy(title = normalizedTitle(query.title))
        cache[key]?.takeIf { it.expires > now() }?.let { return@withLock it.value }

        val diskEntry = diskCache?.get(query)
        if (diskEntry?.isFresh(now()) == true) {
            cache[key] = CacheEntry(diskEntry.metadata, now() + SUCCESS_TTL_MS)
            return@withLock diskEntry.metadata
        }
        val stale = diskEntry?.metadata
        if (stale != null && revalidationScope != null) {
            if (refreshing.putIfAbsent(key, Unit) == null) {
                revalidationScope.launch {
                    mutex.withLock { lookupAndStore(query, key, stale, background = true) }
                }.invokeOnCompletion { refreshing.remove(key) }
            }
            return@withLock stale
        }
        lookupAndStore(query, key, stale)
    }

    private suspend fun lookupAndStore(
        query: MediaQuery,
        key: MediaQuery,
        stale: MediaMetadata?,
        background: Boolean = false,
    ): MediaMetadata? {
        var ttl = MISS_TTL_MS
        val result = try {
            lookup(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ttl = FAILURE_TTL_MS
            null
        }
        // Fallback only: Cinemeta first, TMDB only when it yielded
        // nothing. A keyless resolver returns null without a call.
        val tmdbResult = if (result == null) {
            try {
                tmdbResolver?.resolve(query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val resolved = result ?: tmdbResult
        coroutineContext.ensureActive()

        if (resolved != null) {
            // The disk entry records its source, so the 6-month cap
            // on TMDB-sourced fields stays auditable per entry.
            runCatching { diskCache?.put(query, resolved, if (tmdbResult != null) "tmdb" else "cinemeta") }
            cache[key] = CacheEntry(resolved, now() + SUCCESS_TTL_MS)
        } else if (!background) {
            cache[key] = CacheEntry(stale, now() + if (stale == null) ttl else FAILURE_TTL_MS)
        }
        return resolved ?: stale
    }

    private suspend fun get(path: String): JsonObject {
        val response = transport.execute(HttpRequest("GET", "https://v3-cinemeta.strem.io/$path"))
        check(response.status in 200..299)
        return JsonParser.parseString(response.body).asJsonObject
    }

    /** Title matches for the user to pick from when the automatic match
     *  fails (ambiguous or no-match). Reuses the catalog fetch the
     *  resolver already does — no new network call per candidate — and
     *  never resolves anything itself. */
    suspend fun candidates(query: MediaQuery): List<MediaCandidate> = mutex.withLock {
        if (query.title.isBlank() || query.title.length > 160) return@withLock emptyList()
        fetchTitleMatches(query).take(MAX_CANDIDATES).mapNotNull { obj ->
            val id = obj.optString("id")?.takeIf { it.matches(imdbIdPattern) } ?: return@mapNotNull null
            val title = obj.optString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = obj.optString("type") ?: return@mapNotNull null
            MediaCandidate(id, title, mediaYear(obj), type)
        }
    }

    /** Detail for a user-picked candidate. Deliberately NOT stored in
     *  the shared disk cache: the pick is per download, and a disk
     *  entry keyed by title would leak it to every other download
     *  with the same parsed title. */
    suspend fun resolveById(imdbId: String, type: String): MediaMetadata? = mutex.withLock {
        if (!imdbId.matches(imdbIdPattern) || type !in listOf("movie", "series")) return@withLock null
        val detail = get("meta/$type/$imdbId.json").getAsJsonObject("meta") ?: return@withLock null
        parseDetail(detail, imdbId, type)
    }

    private suspend fun lookup(query: MediaQuery): MediaMetadata? {
        val candidates = fetchTitleMatches(query)
        val exact = candidates.filter { matchesYear(it, query.year, tolerance = 0) }
        if (exact.size > 1) return null
        val tolerance = if (exact.isEmpty() && query.year != null) 1 else 0
        val match = if (tolerance == 0) exact.singleOrNull()
            else candidates.singleOrNull { matchesYear(it, query.year, tolerance) }
        if (match == null) return null
        val id = match.optString("id") ?: return null
        val type = match.optString("type") ?: return null
        val detail = get("meta/$type/$id.json").getAsJsonObject("meta") ?: return null
        if (!matches(detail, query, type, tolerance)) return null
        return parseDetail(detail, id, type)
    }

    /** Catalog entries whose normalized title matches the query, across
     *  the query's type (or movie + series when the type is unknown).
     *  Year is ignored here: the caller decides what a year means —
     *  the automatic gate filters by year, the picker shows all. */
    private suspend fun fetchTitleMatches(query: MediaQuery): List<JsonObject> {
        val encoded = URLEncoder.encode(query.title, "UTF-8").replace("+", "%20")
        val titleQuery = query.copy(year = null)
        return (query.type?.let { listOf(it) } ?: listOf("movie", "series")).flatMap { type ->
            get("catalog/$type/top/search=$encoded.json").getAsJsonArray("metas")
                ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                ?.filter { matches(it, titleQuery, type) }.orEmpty()
        }.distinctBy { it.optString("id") }
    }

    private fun parseDetail(detail: JsonObject, id: String, type: String): MediaMetadata? {
        if (detail.optString("id") != id || detail.optString("type") != type) return null
        return MediaMetadata(
            title = detail.optString("name")!!,
            year = mediaYear(detail),
            type = type,
            posterUrl = publicPosterUrl(detail.optString("poster")),
            imdbId = id,
            overview = detail.nonBlankStringField("description"),
            genres = detail.elements("genres").mapNotNull { it.nonBlankString() }.distinct().take(5).toList(),
            cast = detail.elements("cast").mapNotNull {
                if (it.isJsonObject) it.asJsonObject.nonBlankStringField("name") else it.nonBlankString()
            }.take(5).toList(),
            director = detail.nonBlankStringField("director")
                ?: detail.elements("director").firstNotNullOfOrNull { it.nonBlankString() },
            imdbRating = detail["imdbRating"]?.takeIf { it.isJsonPrimitive }?.let { element ->
                // Real Cinemeta sends this as a JSON string ("9.3"), not a number.
                runCatching { element.asDouble }.getOrNull()
                    ?: runCatching { element.asString.toDoubleOrNull() }.getOrNull()
            }?.takeIf { it.isFinite() && it in 0.0..10.0 },
            runtime = detail.nonBlankStringField("runtime"),
            episodes = if (type == "series") parseEpisodes(detail) else emptyList(),
        )
    }

    private fun matches(obj: JsonObject, query: MediaQuery, type: String, tolerance: Int = 0): Boolean =
        obj.optString("id")?.matches(imdbIdPattern) == true && obj.optString("type") == type &&
            normalizedTitle(obj.optString("name").orEmpty()) == normalizedTitle(query.title) &&
            matchesYear(obj, query.year, tolerance)

    private fun matchesYear(obj: JsonObject, year: Int?, tolerance: Int): Boolean =
        year == null || mediaYear(obj)?.let { abs(it.toLong() - year) <= tolerance } == true

    private fun parseEpisodes(detail: JsonObject): List<MediaEpisode> =
        detail.elements("videos").mapNotNull { element ->
            val video = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val coordinates = video.stringField("id")?.let(videoId::matchEntire)
            val season = video.episodeCoordinate("season", coordinates?.groupValues?.get(1))
            val number = video.episodeCoordinate("episode", coordinates?.groupValues?.get(2))
            if (season == null || number == null || season < 0 || number < 0) return@mapNotNull null
            // Real Cinemeta names the episode field "name"; accept "title" too.
            MediaEpisode(season, number, video.stringField("title") ?: video.stringField("name"), video.stringField("released"))
        }.distinctBy { it.season to it.episode }
            .sortedWith(compareBy(MediaEpisode::season, MediaEpisode::episode)).toList()

    private fun JsonObject.episodeCoordinate(name: String, fallback: String?): Int? {
        val value = get(name)
        return when {
            value == null || value.isJsonNull -> fallback?.toIntOrNull()
            value.isJsonPrimitive -> optString(name)?.toIntOrNull()
            else -> null
        }
    }

    private fun JsonObject.elements(name: String): Sequence<JsonElement> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.asSequence() ?: emptySequence()

    private fun JsonObject.stringField(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.let { optString(name) }

    private fun JsonObject.nonBlankStringField(name: String): String? =
        stringField(name)?.takeIf { it.isNotBlank() }

    private fun JsonElement.nonBlankString(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }

    private fun mediaYear(obj: JsonObject): Int? = yearToken.find(
        obj.optString("releaseInfo") ?: obj.optString("year") ?: obj.optString("released").orEmpty()
    )?.value?.toIntOrNull()
}
