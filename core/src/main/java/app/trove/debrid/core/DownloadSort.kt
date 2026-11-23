package app.trove.debrid.core

import java.util.Locale

/** Persist names rather than ordinals so adding an option cannot change a saved choice. */
enum class DownloadSort {
    NEWEST, OLDEST, NAME_ASC, NAME_DESC, LARGEST, SMALLEST, PROGRESS;

    companion object {
        fun fromStored(value: String?): DownloadSort = entries.firstOrNull { it.name == value } ?: NEWEST
    }
}

/** Stable sorting retains input order for ties; unavailable dates and sizes always come last. */
fun sortDownloads(downloads: List<DebridDownload>, sort: DownloadSort): List<DebridDownload> =
    sortDownloads(downloads, sort, emptyList())

fun sortDownloads(
    downloads: List<DebridDownload>,
    sort: DownloadSort,
    preferWords: List<String> = emptyList(),
): List<DebridDownload> {
    val comparator: Comparator<DebridDownload> = when (sort) {
        DownloadSort.NEWEST -> compareBy<DebridDownload, Long?>(nullsLast(reverseOrder())) { it.createdAtEpochMs }
        DownloadSort.OLDEST -> compareBy<DebridDownload, Long?>(nullsLast(naturalOrder())) { it.createdAtEpochMs }
        DownloadSort.NAME_ASC -> compareBy { it.name.lowercase(Locale.ROOT) }
        DownloadSort.NAME_DESC -> compareByDescending { it.name.lowercase(Locale.ROOT) }
        DownloadSort.LARGEST -> compareBy<DebridDownload, Long?>(nullsLast(reverseOrder())) { it.sizeBytes?.takeIf { size -> size >= 0 } }
        DownloadSort.SMALLEST -> compareBy<DebridDownload, Long?>(nullsLast(naturalOrder())) { it.sizeBytes?.takeIf { size -> size >= 0 } }
        DownloadSort.PROGRESS -> compareByDescending {
            it.progress.takeIf { progress -> progress.isFinite() && progress > 0f }?.coerceAtMost(1f) ?: 0f
        }
    }
    if (preferWords.isEmpty()) return downloads.sortedWith(comparator)
    val preference = compareByDescending<Pair<DebridDownload, Int>> { it.second }
    val selected = Comparator<Pair<DebridDownload, Int>> { first, second ->
        comparator.compare(first.first, second.first)
    }
    val combined = if (sort == DownloadSort.NEWEST || sort == DownloadSort.OLDEST) {
        selected.then(preference)
    } else {
        preference.then(selected)
    }
    return downloads.map { it to preferScore(it.name, preferWords) }
        .sortedWith(combined)
        .map { it.first }
}
