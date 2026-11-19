package app.trove.debrid.ui.downloads

import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.core.MediaMetadata
import java.util.Locale

fun formatCount(count: Int): String = count.toString()

fun downloadCoverSubtitle(
    identity: MediaMetadata,
    localizedType: String?,
    episode: String?,
    quality: String?,
): String = listOfNotNull(identity.year?.toString(), localizedType, episode, quality).joinToString(" · ")

data class DownloadProgress(
    val fraction: Float,
    val percentage: String,
    val speed: String?,
    val eta: String?,
    val seeds: Int?,
    val peers: Int?,
)

fun downloadProgress(download: DebridDownload): DownloadProgress? {
    if (download.status != DownloadStatus.DOWNLOADING) return null
    val fraction = download.progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    return DownloadProgress(
        fraction = fraction,
        percentage = "${(fraction * 100).toInt()}%",
        speed = download.downloadSpeedBytesPerSec?.takeIf { it > 0 }?.let { formatSize(it) + "/s" },
        eta = formatEta(download.etaSeconds),
        seeds = download.seeds?.takeIf { it >= 0 },
        peers = download.peers?.takeIf { it >= 0 },
    )
}

fun formatEta(seconds: Long?): String? {
    if (seconds == null || seconds <= 0) return null
    val units = listOf(
        seconds / 86400 to "d",
        seconds % 86400 / 3600 to "h",
        seconds % 3600 / 60 to "m",
        seconds % 60 to "s",
    )
    return units.filter { it.first > 0 }.take(2)
        .joinToString(" ") { (value, unit) -> "$value$unit" } + " left"
}

fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "Size unavailable"
    return when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

fun baseFileName(name: String): String = name.replace('\\', '/').substringAfterLast('/').ifBlank { "Unnamed file" }


private val addedDateFormatter =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)

fun addedDate(epochMs: Long?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()): String? = epochMs?.let {
    addedDateFormatter.withLocale(locale).withZone(zone).format(java.time.Instant.ofEpochMilli(it))
}

/** Local identity remains useful while offline or when a lookup is ambiguous. */
fun downloadIdentity(download: DebridDownload, metadata: app.trove.debrid.core.MediaMetadata?): app.trove.debrid.core.MediaMetadata {
    val parsed = app.trove.debrid.core.parseMediaName(download.name)
    return metadata ?: app.trove.debrid.core.MediaMetadata(
        parsed.title.ifBlank { baseFileName(download.name) }, parsed.year, parsed.type)
}
