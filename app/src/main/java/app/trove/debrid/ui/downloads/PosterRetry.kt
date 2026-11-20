package app.trove.debrid.ui.downloads

import app.trove.debrid.core.publicPosterUrl

internal const val POSTER_MISS_TTL_MS = 60_000L
private const val MAX_POSTER_RETRIES = 2

/** Wait out the shared negative cache before trying again while the poster is visible. */
internal suspend fun <T : Any> loadPosterWithRetry(
    url: String?,
    pause: suspend (Long) -> Unit,
    load: suspend (String) -> T?,
): T? {
    val validUrl = publicPosterUrl(url) ?: return null
    repeat(MAX_POSTER_RETRIES) {
        load(validUrl)?.let { return it }
        pause(POSTER_MISS_TTL_MS)
    }
    return load(validUrl)
}
