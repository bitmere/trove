package app.trove.debrid.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Media identity only, independent of provider and playback credentials. */
data class MediaMetadata(val title: String, val year: Int?, val type: String?, val posterUrl: String? = null)
data class MediaQuery(val title: String, val year: Int?, val type: String?)
data class ReleaseDetails(val episode: String?, val quality: String?)

private val extension = Regex("\\.(mkv|mp4|avi|mov|webm|m4v|ts|torrent|nzb)$", RegexOption.IGNORE_CASE)
private val yearToken = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)")
private val episode = Regex("(?i)\\b(?:s(\\d{1,2})(?:e(\\d{1,3}))?|(\\d{1,2})x(\\d{2})|season\\s+\\d+)\\b")
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

fun parseMediaName(name: String): MediaQuery {
    // URLs and magnets must never become third-party search requests.
    if (name.contains("://") || name.startsWith("magnet:", true)) return MediaQuery("", null, null)
    val base = name.replace('\\', '/').substringAfterLast('/').replace(extension, "")
        .replace(Regex("\\[(?!(?:19|20)\\d{2}])[^]]*]"), " ").replace('.', ' ').replace('_', ' ')
    val ep = episode.find(base)
    val tag = releaseTag.find(base)
    val boundary = listOfNotNull(ep?.range?.first, tag?.range?.first).minOrNull() ?: base.length
    val year = yearToken.findAll(base.take(boundary)).lastOrNull()?.takeIf { it.range.first > 0 }
    val end = minOf(boundary, year?.range?.first ?: base.length)
    val title = base.take(end).replace(Regex("[(){}\\[\\]]"), " ")
        .replace(Regex("\\s+"), " ").trim(' ', '-')
    return MediaQuery(title, year?.value?.toIntOrNull(), if (ep != null) "series" else null)
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

/** Bounded session LRU, including misses. A mutex coalesces duplicate lookups and
 * limits catalog traffic to one request at a time. Failures retry after a minute. */
class CinemetaResolver(private val transport: HttpTransport, private val now: () -> Long = System::currentTimeMillis) {
    private data class CacheEntry(val value: MediaMetadata?, val expires: Long)
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<MediaQuery, CacheEntry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaQuery, CacheEntry>?): Boolean = size > 256
    }

    suspend fun resolve(query: MediaQuery): MediaMetadata? = mutex.withLock {
        if (query.title.isBlank() || query.title.length > 160) return@withLock null
        val key = query.copy(title = normalizedTitle(query.title))
        cache[key]?.takeIf { it.expires > now() }?.let { return@withLock it.value }
        try {
            val result = lookup(query)
            cache[key] = CacheEntry(result, Long.MAX_VALUE)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            cache[key] = CacheEntry(null, now() + 60_000)
            null
        }
    }

    private suspend fun get(path: String): JsonObject {
        val response = transport.execute(HttpRequest("GET", "https://v3-cinemeta.strem.io/$path"))
        check(response.status in 200..299)
        return JsonParser.parseString(response.body).asJsonObject
    }

    private suspend fun lookup(query: MediaQuery): MediaMetadata? {
        val encoded = URLEncoder.encode(query.title, "UTF-8").replace("+", "%20")
        val candidates = (query.type?.let { listOf(it) } ?: listOf("movie", "series")).flatMap { type ->
            get("catalog/$type/top/search=$encoded.json").getAsJsonArray("metas")
                ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                ?.filter { matches(it, query, type) }.orEmpty()
        }.distinctBy { it.optString("id") }
        val match = candidates.singleOrNull() ?: return null
        val id = match.optString("id") ?: return null
        val type = match.optString("type") ?: return null
        val detail = get("meta/$type/$id.json").getAsJsonObject("meta") ?: return null
        if (detail.optString("id") != id || !matches(detail, query, type)) return null
        return MediaMetadata(detail.optString("name")!!, mediaYear(detail), type,
            publicPosterUrl(detail.optString("poster")))
    }

    private fun matches(obj: JsonObject, query: MediaQuery, type: String): Boolean =
        obj.optString("id")?.matches(Regex("tt\\d+")) == true && obj.optString("type") == type &&
            normalizedTitle(obj.optString("name").orEmpty()) == normalizedTitle(query.title) &&
            (query.year == null || query.year == mediaYear(obj))

    private fun mediaYear(obj: JsonObject): Int? = yearToken.find(
        obj.optString("releaseInfo") ?: obj.optString("year") ?: obj.optString("released").orEmpty()
    )?.value?.toIntOrNull()
}
