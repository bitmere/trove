package app.trove.debrid.ui.downloads

import app.trove.debrid.core.DebridError
import app.trove.debrid.core.publicPosterUrl
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

internal const val POSTER_MISS_TTL_MS = 60_000L
internal const val TRANSIENT_POSTER_TTL_MS = 1_000L

internal sealed interface PosterLoad<out T> {
    data class Ready<T>(val value: T) : PosterLoad<T>
    data class Failed(val retryAt: Long, val transient: Boolean) : PosterLoad<Nothing>
}

/** Guarded by the loader's mutex. Failed entries never outlive the bounded LRU. */
internal class PosterMissCache(private val now: () -> Long) {
    private val entries = object : LinkedHashMap<String, PosterLoad.Failed>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PosterLoad.Failed>?) =
            size > 128
    }

    fun get(key: String): PosterLoad.Failed? = entries[key]?.takeIf { it.retryAt > now() }

    fun record(key: String, transient: Boolean): PosterLoad.Failed {
        val cooldown = if (transient) TRANSIENT_POSTER_TTL_MS else POSTER_MISS_TTL_MS
        return PosterLoad.Failed(now() + cooldown, transient).also { entries[key] = it }
    }

    fun clear(key: String) {
        entries.remove(key)
    }
}

internal fun isTransientPosterFailure(error: Exception): Boolean =
    error is DebridError.Network ||
        (error is DebridError.Provider && error.code.removePrefix("http").toIntOrNull()?.let { it in 500..599 } == true)

/** Each generation offers another attempt without cancelling a visible load already in flight. */
internal suspend fun <T : Any> awaitPoster(
    loadGeneration: StateFlow<Long>,
    force: Boolean,
    load: suspend (force: Boolean) -> T?,
    onFailure: () -> Unit,
): T {
    var forceNext = force
    var reportedFailure = false
    return loadGeneration.mapNotNull {
        val bitmap = load(forceNext)
        forceNext = false
        if (bitmap == null && !reportedFailure) {
            reportedFailure = true
            onFailure()
        }
        bitmap
    }.first()
}

internal suspend fun <T : Any> loadPosterWithRetry(
    url: String?,
    now: () -> Long,
    pause: suspend (Long) -> Unit,
    load: suspend (String) -> PosterLoad<T>,
): T? {
    val validUrl = publicPosterUrl(url) ?: return null
    for (attempt in 0..2) {
        when (val result = load(validUrl)) {
            is PosterLoad.Ready -> return result.value
            is PosterLoad.Failed -> {
                if (!result.transient || attempt == 2) return null
                pause(maxOf(result.retryAt - now(), TRANSIENT_POSTER_TTL_MS * (attempt + 1)))
            }
        }
    }
    return null
}
