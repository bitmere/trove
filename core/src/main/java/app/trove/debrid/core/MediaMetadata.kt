package app.trove.debrid.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
private val releaseTag = Regex("(?i)\\b(?:2160p|1080[pi]|720p|480p|4k|web[ -]?(?:rip|dl)|bluray|blu[ -]ray|b[dr]rip|dvdrip|hdtv|remux|hevc|avc|[xh][ .-]?26[45]|hdr10?\\+?|dv|aac|dts|ddp|ac3|multi|dual(?![ -]+audio)|proper|repack)\\b")

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

private val parenthetical = Regex("\\([^()]*\\)")
private val editionTag = Regex("(?i)\\b(?:extended|unrated|director['’]?s[ -]+cut|remastered|imax|3d)\\b")
private val dubTag = Regex(
    "(?i)\\b(?:(?:(?:hindi|english|tamil|telugu|malayalam|kannada)[ -]+)?dub(?:bed)?|dual[ -]+audio)\\b",
)

fun parseMediaName(name: String): MediaQuery = parseMediaRelease(name, clean = true)

fun mediaQueryVariants(name: String): List<MediaQuery> {
    val query = parseMediaName(name)
    if (query.title.isBlank()) return listOf(query)
    val editionStripped = cleanTitle(query.title.replace(editionTag, " "))
    return buildList {
        add(query)
        add(query.copy(year = null))
        if (editionStripped.isNotBlank()) add(query.copy(title = editionStripped))
        val title = editionStripped.ifBlank { query.title }
        when {
            title.startsWith("The ", ignoreCase = true) -> {
                val rest = title.drop(4)
                add(query.copy(title = rest))
                add(query.copy(title = "$rest, The"))
            }
            title.endsWith(", The", ignoreCase = true) -> {
                val rest = title.dropLast(5).trimEnd()
                add(query.copy(title = rest))
                add(query.copy(title = "The $rest"))
            }
        }
        add(parseMediaRelease(name, clean = false))
    }.distinctBy { it.copy(title = normalizedTitle(it.title)) }
}

private fun cleanTitle(title: String): String = title.replace(Regex("[(){}\\[\\]]"), " ")
    .replace(Regex("\\s+"), " ").trim(' ', '-')

private fun parseMediaRelease(name: String, clean: Boolean): MediaQuery {
    // URLs and magnets must never become third-party search requests.
    if (name.contains("://") || name.startsWith("magnet:", true)) return MediaQuery("", null, null)
    val raw = name.replace('\\', '/').substringAfterLast('/').replace(extension, "")
        .replace(Regex("\\[(?!(?:19|20)\\d{2}])[^]]*]"), " ").replace('.', ' ').replace('_', ' ')
    val base = (if (clean) raw.replace(parenthetical, " ") else raw).trim()
    val ep = episode.find(base)
    val tag = releaseTag.find(base)
    val boundary = listOfNotNull(ep?.range?.first, tag?.range?.first).minOrNull() ?: base.length
    val latestYear = Year.now().value + 1
    val year = yearToken.findAll(base.take(boundary))
        .lastOrNull { it.range.first > 0 && it.value.toInt() <= latestYear }
    val end = minOf(boundary, year?.range?.first ?: base.length)
    var title = cleanTitle(base.take(end))
    if (clean) {
        // Strip trailing release qualifiers while preserving words inside a title.
        while (true) {
            val tag = sequenceOf(editionTag, dubTag).firstNotNullOfOrNull { pattern ->
                pattern.findAll(title).lastOrNull()?.takeIf { it.range.last == title.lastIndex }
            } ?: break
            val stripped = cleanTitle(title.take(tag.range.first))
            if (stripped.isBlank()) break
            title = stripped
        }
    }
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

/** Bounded session and optional disk cache with independent lookup lanes. */
class CinemetaResolver(
    private val transport: HttpTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val diskCache: MetadataDiskCache? = null,
    private val revalidationScope: CoroutineScope? = null,
    /** Optional fallback for missing Cinemeta matches and posters. */
    private val tmdbResolver: TmdbResolver? = null,
) {
    constructor(transport: HttpTransport, clock: () -> Long) : this(transport, now = clock)

    private companion object {
        const val SUCCESS_TTL_MS = 24 * 60 * 60 * 1000L
        const val MISS_TTL_MS = 60 * 60 * 1000L
        const val FAILURE_TTL_MS = 60_000L
        const val MAX_CANDIDATES = 5
    }

    private data class CacheEntry(val value: MediaMetadata?, val expires: Long, val miss: Boolean = false)
    private val cacheMutex = Mutex()
    private val lookupLanes = Array(32) { Mutex() }
    private val network = Semaphore(4)

    private fun lookupLane(key: MediaQuery): Mutex = lookupLanes[(key.hashCode() and Int.MAX_VALUE) % lookupLanes.size]
    private val refreshing = ConcurrentHashMap<MediaQuery, Unit>()
    private val cache = object : LinkedHashMap<MediaQuery, CacheEntry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaQuery, CacheEntry>?): Boolean = size > 256
    }

    suspend fun resolve(query: MediaQuery, bypassMiss: Boolean = false): MediaMetadata? =
        resolve(listOf(query), bypassMiss)

    suspend fun resolve(queries: List<MediaQuery>, bypassMiss: Boolean = false): MediaMetadata? {
        val query = queries.firstOrNull() ?: return null
        if (query.title.isBlank() || query.title.length > 160) return null
        val key = query.copy(title = normalizedTitle(query.title))
        return lookupLane(key).withLock {
            val cached = cacheMutex.withLock {
                cache[key]?.takeIf { it.expires > now() && !(bypassMiss && it.miss) }
            }
            if (cached != null) return@withLock cached.value

            val diskEntry = diskCache?.get(query)
            if (diskEntry?.isFresh(now()) == true) {
                val metadata = diskEntry.metadata
                if (metadata.posterUrl != null) {
                    cacheMutex.withLock { cache[key] = CacheEntry(metadata, now() + SUCCESS_TTL_MS) }
                    return@withLock metadata
                }
                return@withLock network.withPermit { lookupAndStore(queries, key, metadata, diskEntry.source) }
            }
            val stale = diskEntry?.metadata
            if (stale != null && revalidationScope != null) {
                if (refreshing.putIfAbsent(key, Unit) == null) {
                    revalidationScope.launch {
                        lookupLane(key).withLock {
                            network.withPermit { lookupAndStore(queries, key, stale, background = true) }
                        }
                    }.invokeOnCompletion { refreshing.remove(key) }
                }
                return@withLock stale
            }
            network.withPermit { lookupAndStore(queries, key, stale) }
        }
    }

    private suspend fun lookupAndStore(
        queries: List<MediaQuery>,
        key: MediaQuery,
        stale: MediaMetadata?,
        incompleteSource: String? = null,
        background: Boolean = false,
    ): MediaMetadata? {
        val query = queries.first()
        var ttl = MISS_TTL_MS
        val result = if (incompleteSource != null) stale else try {
            queries.firstNotNullOfOrNull { variant ->
                if (variant.title.isBlank() || variant.title.length > 160) null else lookup(variant)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ttl = FAILURE_TTL_MS
            null
        }
        // For incomplete disk entries, keep the cached identity and text.
        val poster = result?.takeIf { it.posterUrl == null }?.let { metadata ->
            tmdbResolver?.resolvePoster(
                MediaQuery(metadata.title, metadata.year, metadata.type),
                metadata.imdbId,
            )
        }
        val refreshedPoster = if (poster == null && incompleteSource == "cinemeta") {
            try {
                val id = result?.imdbId
                val type = result?.type
                if (id != null && type != null) lookupById(id, type)?.posterUrl
                else queries.firstNotNullOfOrNull { lookup(it) }?.posterUrl
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else null
        val tmdbResult = if (result == null) tmdbResolver?.let { tmdb ->
            queries.firstNotNullOfOrNull { variant -> tmdb.resolve(variant) }
        } else null
        val resolved = result?.let { metadata ->
            (poster ?: refreshedPoster)?.let { metadata.copy(posterUrl = it) } ?: metadata
        } ?: tmdbResult
        coroutineContext.ensureActive()

        if (resolved != null) {
            if (incompleteSource == null || resolved.posterUrl != null) {
                val source = if (poster != null || tmdbResult != null) "tmdb" else incompleteSource ?: "cinemeta"
                diskCache?.put(query, resolved, source)
            }
            val successTtl = if (resolved.posterUrl == null) MISS_TTL_MS else SUCCESS_TTL_MS
            // A posterless success expires on the miss schedule and is
            // re-checked on an explicit refresh, like a miss.
            cacheMutex.withLock { cache[key] = CacheEntry(resolved, now() + successTtl, miss = resolved.posterUrl == null) }
        } else if (!background) {
            cacheMutex.withLock {
                cache[key] = CacheEntry(stale, now() + if (stale == null) ttl else FAILURE_TTL_MS,
                    miss = stale == null && ttl == MISS_TTL_MS)
            }
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
    suspend fun candidates(query: MediaQuery): List<MediaCandidate> = network.withPermit {
        if (query.title.isBlank() || query.title.length > 160) return@withPermit emptyList()
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
    suspend fun resolveById(imdbId: String, type: String): MediaMetadata? =
        network.withPermit {
            val metadata = lookupById(imdbId, type) ?: return@withPermit null
            if (metadata.posterUrl != null) return@withPermit metadata
            val poster = tmdbResolver?.resolvePoster(MediaQuery(metadata.title, metadata.year, type), imdbId)
            if (poster == null) metadata else metadata.copy(posterUrl = poster)
        }

    private suspend fun lookupById(imdbId: String, type: String): MediaMetadata? {
        if (!imdbId.matches(imdbIdPattern) || type !in listOf("movie", "series")) return null
        val detail = get("meta/$type/$imdbId.json").getAsJsonObject("meta") ?: return null
        return parseDetail(detail, imdbId, type)
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
