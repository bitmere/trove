package app.trove.debrid.ui.downloads

import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.posterCacheKey
import app.trove.debrid.core.publicPosterUrl

internal fun DownloadsUiState.posterUrl(key: Pair<DownloadKind, String>): String? =
    metadata[key]?.posterUrl ?: pendingPosters[key]

internal data class PosterPrefetchPlan(val visible: List<String>, val next: List<String>)

internal fun posterPrefetchPlan(
    state: DownloadsUiState,
    rows: List<DebridDownload>,
    visibleIndices: List<Int>,
): PosterPrefetchPlan? {
    val last = visibleIndices.maxOrNull() ?: return null
    val visible = visibleIndices.mapNotNull { index ->
        val row = rows.getOrNull(index) ?: return null
        val key = row.kind to row.id
        if (key !in state.enrichment && key !in state.metadata) return null
        publicPosterUrl(state.posterUrl(key))
    }
    val visibleKeys = visible.mapTo(HashSet(), ::posterCacheKey)
    val next = rows.subList(last + 1, minOf(rows.size, last + 4)).mapNotNull { row ->
        publicPosterUrl(state.metadata[row.kind to row.id]?.posterUrl)
    }.distinctBy(::posterCacheKey).filterNot { posterCacheKey(it) in visibleKeys }
    return if (next.isEmpty()) null else PosterPrefetchPlan(visible, next)
}
