package app.trove.debrid.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
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
    val provenance: EnrichmentProvenance? = null,
    val alternatePosterUrl: String? = null,
)

enum class EnrichmentProvenance(internal val diskValue: String) {
    CINEMETA("cinemeta"),
    TMDB("tmdb-full"),
    CINEMETA_TMDB_POSTER("cinemeta-tmdb-poster"),
    TMDB_CINEMETA_BACKFILL("tmdb-cinemeta-backfill"),
    TMDB_CINEMETA_POSTER("tmdb-cinemeta-poster"),
    LEGACY_TMDB("tmdb"),
    NO_MATCH("no-match"),
    UNAVAILABLE("unavailable"),
}

/** The result available now, with an optional background update and cooldown deadline. */
data class MetadataLookup(
    val metadata: MediaMetadata?,
    val provenance: EnrichmentProvenance,
    val retryAfter: Long = 0,
    val revalidation: Deferred<MetadataLookup?>? = null,
) {
    companion object {
        fun from(metadata: MediaMetadata?): MetadataLookup = MetadataLookup(
            metadata, metadata?.provenance ?: if (metadata == null) {
                EnrichmentProvenance.NO_MATCH
            } else EnrichmentProvenance.CINEMETA,
        )

        val Unavailable = MetadataLookup(null, EnrichmentProvenance.UNAVAILABLE)
    }
}

data class MediaEpisode(val season: Int, val episode: Int, val title: String?, val released: String?)
data class MediaQuery(val title: String, val year: Int?, val type: String?)
data class ReleaseDetails(val episode: String?, val quality: String?)

/** One title the user can pick when the automatic match fails. The
 *  automatic gate stays strict: a candidate list is never auto-applied.
 *  Exactly one of [imdbId] / [tmdbId] is present; [tmdbId] set means
 *  the candidate came from TMDB, otherwise from Cinemeta. */
data class MediaCandidate(val imdbId: String?, val title: String, val year: Int?, val type: String, val tmdbId: Int? = null,
    val posterUrl: String? = null, val overview: String? = null,
)

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
    /** Optional primary enrichment and fallback artwork when configured. */
    private val tmdbResolver: TmdbResolver? = null,
) {
    constructor(transport: HttpTransport, clock: () -> Long) : this(transport, now = clock)

    private companion object {
        const val SUCCESS_TTL_MS = 24 * 60 * 60 * 1000L
        const val MISS_TTL_MS = 60 * 60 * 1000L
        const val FAILURE_TTL_MS = 60_000L
        const val MAX_CANDIDATES = 5
    }

    private data class CacheEntry(
        val result: MetadataLookup,
        val expires: Long,
        val tmdbFirst: Boolean,
        val miss: Boolean = false,
    )
    private val cacheMutex = Mutex()
    private val lookupLanes = Array(32) { Mutex() }
    private val network = Semaphore(4)

    private fun lookupLane(key: MediaQuery): Mutex = lookupLanes[(key.hashCode() and Int.MAX_VALUE) % lookupLanes.size]
    private val refreshing = ConcurrentHashMap<Pair<MediaQuery, String?>, MetadataLookup>()
    private val cache = object : LinkedHashMap<MediaQuery, CacheEntry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaQuery, CacheEntry>?): Boolean = size > 256
    }

    suspend fun resolve(query: MediaQuery, bypassMiss: Boolean = false): MediaMetadata? =
        resolve(listOf(query), bypassMiss)

    suspend fun resolve(queries: List<MediaQuery>, bypassMiss: Boolean = false): MediaMetadata? =
        resolveOutcome(queries, bypassMiss).metadata

    suspend fun resolveOutcome(
        queries: List<MediaQuery>,
        bypassMiss: Boolean = false,
        onPoster: suspend (String) -> Unit = {},
    ): MetadataLookup {
        val query = queries.firstOrNull()?.takeIf(::validQuery) ?: return MetadataLookup.from(null)
        val key = query.copy(title = normalizedTitle(query.title))
        if (refreshing.isNotEmpty()) refreshing[key to tmdbResolver?.configuredKey()]?.let { return it }
        return lookupLane(key).withLock {
            val token = tmdbResolver?.configuredKey()
            val tmdbFirst = token != null
            val refreshKey = key to token
            refreshing[refreshKey]?.let { return@withLock it }
            val cached = cacheMutex.withLock {
                cache[key]?.takeIf { it.tmdbFirst == tmdbFirst && it.expires > now() && !(bypassMiss && it.miss) }
            }
            if (cached?.result?.metadata?.posterUrl != null) return@withLock cached.result

            val stored = diskCache?.get(query)
            val diskEntry = stored?.takeIf { it.tmdbFirst == tmdbFirst }
            val stale = diskEntry?.metadata
            val legacy = stored?.takeIf {
                diskEntry == null && it.isFresh(now()) && publicPosterUrl(it.metadata.posterUrl) != null
            }?.metadata
            val background = legacy ?: stale?.takeIf {
                diskEntry?.isFresh(now()) == false && revalidationScope != null
            }
            if (diskEntry?.isFresh(now()) == true && stale?.posterUrl != null) {
                val result = MetadataLookup.from(stale)
                cacheMutex.withLock { cache[key] = CacheEntry(result, now() + SUCCESS_TTL_MS, tmdbFirst) }
                return@withLock result
            }
            if (cached != null && background?.posterUrl == null) return@withLock cached.result
            if (background != null) {
                val served = MetadataLookup.from(background).copy(retryAfter = cached?.result?.retryAfter ?: 0)
                if (cached != null || revalidationScope == null) return@withLock served
                val revalidation = revalidationScope.async(start = CoroutineStart.LAZY) {
                    // Register under the query lane, then release it so unrelated
                    // rows sharing the lane can still read their warm caches.
                    network.withPermit {
                        if (tmdbResolver?.configuredKey() != token) return@withPermit null
                        lookupAndStore(queries, key, token, background, background = true)
                    }
                }
                val initial = served.copy(revalidation = revalidation)
                refreshing[refreshKey] = initial
                revalidation.invokeOnCompletion { refreshing.remove(refreshKey, initial) }
                revalidation.start()
                return@withLock initial
            }
            network.withPermit {
                val incomplete = stale?.takeIf {
                    diskEntry?.isFresh(now()) == true && it.provenance == EnrichmentProvenance.CINEMETA
                }
                lookupAndStore(queries, key, token, stale, incomplete = incomplete, onPoster = onPoster)
            }
        }
    }

    private fun validQuery(query: MediaQuery): Boolean = query.title.isNotBlank() && query.title.length <= 160

    /** Whether a TMDB key is configured, so the sheet can hint at
     *  it when no candidate was found. */
    fun isTmdbConfigured(): Boolean = tmdbResolver?.configuredKey() != null

    private suspend fun lookupAndStore(
        queries: List<MediaQuery>,
        key: MediaQuery,
        token: String?,
        stale: MediaMetadata?,
        background: Boolean = false,
        incomplete: MediaMetadata? = null,
        onPoster: suspend (String) -> Unit = {},
    ): MetadataLookup {
        val query = queries.first()
        val tmdb = tmdbResolver
        var failed = false
        suspend fun lookupSafely(block: suspend () -> MediaMetadata?): MediaMetadata? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            null
        }

        val primary = if (incomplete == null && tmdb != null && token != null) lookupSafely {
            queries.firstNotNullOfOrNull { variant ->
                if (validQuery(variant) && variant.year == query.year) tmdb.resolvePrimary(variant, token) else null
            }
        } else null

        val resolved = if (primary != null) {
            if (primary.imdbId != null && primary.type != null) primary.posterUrl?.let { onPoster(it) }
            mergeTmdbPrimary(primary, query) { block -> lookupSafely(block) }
        } else {
            val cinemeta = incomplete ?: lookupSafely {
                queries.firstNotNullOfOrNull { if (validQuery(it)) lookup(it) else null }
            }
            cinemeta?.let { match ->
                val poster = if (token != null && match.posterUrl == null && !failed) {
                    tmdb?.resolvePoster(MediaQuery(match.title, match.year, match.type), match.imdbId)
                } else null
                when {
                    poster != null -> match.copy(
                        posterUrl = poster, provenance = EnrichmentProvenance.CINEMETA_TMDB_POSTER,
                    )
                    incomplete != null -> {
                        val id = incomplete.imdbId
                        val type = incomplete.type
                        val refreshed = lookupSafely {
                            if (id != null && type != null) lookupById(id, type)
                            else queries.firstNotNullOfOrNull { if (validQuery(it)) lookup(it) else null }
                        }
                        match.copy(posterUrl = refreshed?.posterUrl)
                    }
                    else -> match
                }
            }
        }
        coroutineContext.ensureActive()

        val current = when {
            resolved != null -> MetadataLookup.from(resolved)
            failed -> MetadataLookup.Unavailable
            else -> MetadataLookup.from(null)
        }
        val tmdbFirst = token != null
        val cooldown = if (token == null) 0L else tmdb?.cooldownUntil() ?: 0L
        val retryAfter = cooldown.takeIf { it > now() } ?: 0L
        val retrySoon = failed || retryAfter > 0
        // Keep legacy data out of current-order memory, even when it is served
        // again while a failed or artwork-free revalidation is cached.
        val memoryResult = if (!background && resolved == null && stale != null) {
            MetadataLookup.from(stale)
        } else current
        val ttl = when {
            retrySoon || resolved == null && stale != null -> FAILURE_TTL_MS
            resolved?.posterUrl != null -> SUCCESS_TTL_MS
            else -> MISS_TTL_MS
        }
        val expires = if (retryAfter > 0) minOf(now() + ttl, retryAfter) else now() + ttl
        // A failed background attempt cannot supersede the disk poster,
        // even if another provider returned artwork during the cooldown.
        val cached = if (background && retrySoon) MetadataLookup.Unavailable else memoryResult
        cacheMutex.withLock {
            coroutineContext.ensureActive()
            if (tmdb?.configuredKey() != token) return@withLock
            if (resolved != null && !retrySoon) diskCache?.put(query, resolved, tmdbFirst)
            cache[key] = CacheEntry(cached.copy(retryAfter = retryAfter), expires, tmdbFirst,
                miss = !retrySoon && cached.metadata?.posterUrl == null)
        }
        val served = if (background && (retrySoon || resolved?.posterUrl == null) ||
            !background && resolved == null && stale != null
        ) MetadataLookup.from(stale) else current
        return served.copy(retryAfter = retryAfter)
    }

    /** Called after the visible image loader exhausts its normal retries. */
    suspend fun replaceFailedPoster(query: MediaQuery, metadata: MediaMetadata): MediaMetadata? {
        val failedUrl = metadata.posterUrl ?: return null
        if (!validQuery(query) || (metadata.provenance != EnrichmentProvenance.TMDB &&
                metadata.provenance != EnrichmentProvenance.TMDB_CINEMETA_BACKFILL)) return null
        val key = query.copy(title = normalizedTitle(query.title))
        return lookupLane(key).withLock lookup@{
            val tmdb = tmdbResolver ?: return@lookup null
            val token = tmdb.configuredKey() ?: return@lookup null
            val alternate = metadata.alternatePosterUrl ?: network.withPermit {
                val id = metadata.imdbId
                val type = metadata.type
                if (id != null && type != null) lookupById(id, type)?.posterUrl
                else lookup(artworkQuery(query, metadata), exactYear = true)?.posterUrl
            }
            if (alternate == null || alternate == failedUrl) return@lookup null
            val provenance = if (metadata.provenance == EnrichmentProvenance.TMDB_CINEMETA_BACKFILL) {
                EnrichmentProvenance.TMDB_CINEMETA_BACKFILL
            } else EnrichmentProvenance.TMDB_CINEMETA_POSTER
            val replacement = metadata.copy(
                posterUrl = alternate, alternatePosterUrl = null, provenance = provenance,
            )
            cacheMutex.withLock {
                coroutineContext.ensureActive()
                if (tmdb.configuredKey() != token) return@lookup null
                if (tmdb.cooldownUntil() <= now()) diskCache?.replacePoster(query, metadata, replacement)
                val entry = cache[key]
                if (entry?.tmdbFirst == true && entry.result.metadata == metadata) {
                    cache[key] = entry.copy(result = entry.result.copy(
                        metadata = replacement, provenance = provenance,
                    ))
                }
            }
            replacement
        }
    }

    private fun artworkQuery(query: MediaQuery, primary: MediaMetadata): MediaQuery =
        query.copy(type = primary.type ?: query.type)

    private suspend fun get(path: String): JsonObject {
        val response = transport.execute(HttpRequest("GET", "https://v3-cinemeta.strem.io/$path"))
        check(response.status in 200..299)
        return JsonParser.parseString(response.body).asJsonObject
    }

    /** Title matches for the user to pick from when the automatic match
     *  fails (ambiguous or no-match). Reuses the catalog fetch the
     *  resolver already does — no new network call per candidate — and
     *  never resolves anything itself. When Cinemeta lists nothing
     *  and a TMDB key is configured, TMDB is asked for the same
     *  title instead; a Cinemeta transport failure still propagates
     *  as today and is never masked by the fallback. */
    suspend fun candidates(query: MediaQuery): List<MediaCandidate> = network.withPermit {
        if (query.title.isBlank() || query.title.length > 160) return@withPermit emptyList()
        val cinemeta = fetchTitleMatches(query).take(MAX_CANDIDATES).mapNotNull { obj ->
            val id = obj.optString("id")?.takeIf { it.matches(imdbIdPattern) } ?: return@mapNotNull null
            val title = obj.optString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = obj.optString("type") ?: return@mapNotNull null
            MediaCandidate(id, title, mediaYear(obj), type,
                posterUrl = publicPosterUrl(obj.optString("poster")),
                overview = obj.nonBlankStringField("description"))
        }
        if (cinemeta.isNotEmpty()) return@withPermit cinemeta
        tmdbResolver?.searchCandidates(query).orEmpty()
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
            if (poster == null) metadata else metadata.copy(
                posterUrl = poster, provenance = EnrichmentProvenance.CINEMETA_TMDB_POSTER,
            )
        }

    /** Detail for a user-picked TMDB candidate. Same per-download
     *  rule as [resolveById]: never stored in the shared disk
     *  cache. With a verified IMDb id the TMDB primary is backfilled
     *  from Cinemeta exactly as the automatic TMDB-first path does;
     *  without one the TMDB metadata is served as-is. */
    suspend fun resolveByTmdbId(tmdbId: Int, type: String): MediaMetadata? =
        network.withPermit {
            val tmdb = tmdbResolver ?: return@withPermit null
            val primary = tmdb.resolveByTmdbId(tmdbId, type) ?: return@withPermit null
            mergeTmdbPrimary(primary, MediaQuery(primary.title, primary.year, type)) { block ->
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }

    /** TMDB-primary + Cinemeta-backfill merge, shared by the
     *  automatic TMDB-first path and a user-picked TMDB candidate
     *  so the two cannot drift. [lookupSafely] swallows a failed
     *  backfill/artwork lookup; the automatic path also records it
     *  for its retry TTL. */
    private suspend fun mergeTmdbPrimary(
        primary: MediaMetadata,
        query: MediaQuery,
        lookupSafely: suspend (suspend () -> MediaMetadata?) -> MediaMetadata?,
    ): MediaMetadata {
        val primaryId = primary.imdbId
        val primaryType = primary.type
        val backfill = if (primaryId != null && primaryType != null) {
            lookupSafely { lookupById(primaryId, primaryType) }
        } else null
        val enriched = if (backfill == null) {
            primary
        } else {
            val combined = primary.copy(
                year = primary.year ?: backfill.year,
                posterUrl = primary.posterUrl ?: backfill.posterUrl,
                alternatePosterUrl = backfill.posterUrl?.takeIf {
                    primary.posterUrl != null && primary.posterUrl != it
                },
                overview = primary.overview ?: backfill.overview,
                genres = primary.genres.ifEmpty { backfill.genres },
                cast = primary.cast.ifEmpty { backfill.cast },
                director = primary.director ?: backfill.director,
                runtime = primary.runtime ?: backfill.runtime,
                imdbRating = backfill.imdbRating,
                episodes = backfill.episodes,
            )
            if (combined.copy(alternatePosterUrl = null) == primary) combined else combined.copy(
                provenance = EnrichmentProvenance.TMDB_CINEMETA_BACKFILL,
            )
        }
        val artwork = if (enriched.posterUrl == null) {
            lookupSafely { lookup(artworkQuery(query, primary), exactYear = true) }?.posterUrl
        } else null
        return if (artwork == null) enriched else enriched.copy(
            posterUrl = artwork, provenance = EnrichmentProvenance.TMDB_CINEMETA_POSTER,
        )
    }

    private suspend fun lookupById(imdbId: String, type: String): MediaMetadata? {
        if (!imdbId.matches(imdbIdPattern) || type !in listOf("movie", "series")) return null
        val detail = get("meta/$type/$imdbId.json").getAsJsonObject("meta") ?: return null
        return parseDetail(detail, imdbId, type)
    }

    private suspend fun lookup(query: MediaQuery, exactYear: Boolean = false): MediaMetadata? {
        val candidates = fetchTitleMatches(query)
        val exact = candidates.filter { matchesYear(it, query.year, tolerance = 0) }
        if (exact.size > 1) return null
        val tolerance = if (!exactYear && exact.isEmpty() && query.year != null) 1 else 0
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
            title = detail.optString("name")?.takeIf { it.isNotBlank() } ?: return null,
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
            provenance = EnrichmentProvenance.CINEMETA,
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
