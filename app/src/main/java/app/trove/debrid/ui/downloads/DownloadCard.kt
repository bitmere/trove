package app.trove.debrid.ui.downloads

import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.trove.debrid.core.*
import app.trove.debrid.ui.theme.TroveSpace
import app.trove.debrid.ui.theme.TroveElevation

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadRow(
    download: DebridDownload,
    metadata: MediaMetadata? = null,
    resolving: Boolean,
    playEnabled: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = TroveElevation.card,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress,
                onClickLabel = stringResource(R.string.view_details), onLongClickLabel = stringResource(R.string.delete_download)),
    ) {
        Column {
            DownloadCover(download, metadata)
            Column(Modifier.padding(TroveSpace.page), verticalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
                Text(download.name, style = MaterialTheme.typography.titleMedium,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.file_count_size, formatSize(download.sizeBytes), download.files.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                addedDate(download.createdAtEpochMs)?.let { date ->
                    Text(stringResource(R.string.added_date, date), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DownloadTransfer(download)
                if (download.status == DownloadStatus.READY) {
                    Button(onClick = onPlay, enabled = playEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        if (resolving) PlaybackSpinner() else Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(TroveSpace.small))
                        Text(if (resolving) stringResource(R.string.preparing_player) else stringResource(R.string.play))
                    }
                } else if (resolving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Poster and media identity; provider kind and transfer status stay visible. */
@Composable
internal fun DownloadCover(download: DebridDownload, metadata: MediaMetadata? = null) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (download.kind) {
        DownloadKind.TORRENT -> colors.primaryContainer to colors.onPrimaryContainer
        DownloadKind.WEB -> colors.secondaryContainer to colors.onSecondaryContainer
        DownloadKind.USENET -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Surface(color = background, contentColor = foreground) {
        Row(Modifier.fillMaxWidth().padding(TroveSpace.page), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TroveSpace.medium)) {
            val identity = remember(download, metadata) {
                metadata ?: downloadIdentity(download, null)
            }
            MetadataPoster(identity.posterUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
                Text(identity.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val type = when (identity.type) {
                    "movie" -> stringResource(R.string.media_movie)
                    "series" -> stringResource(R.string.media_series)
                    else -> null
                }
                val (episode, quality) = remember(download.name) { parseReleaseDetails(download.name) }
                val subtitle = downloadCoverSubtitle(identity, type, episode, quality)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
                Text("${kindWord(download.kind)} · ${statusWord(download.status)}",
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadTransfer(download: DebridDownload) {
    val progress = downloadProgress(download)
    if (progress != null) {
        val fraction by animateFloatAsState(progress.fraction, spring(), label = "Download progress")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TroveSpace.small),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.downloading), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge)
            Text(progress.percentage, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
            listOfNotNull(progress.speed, progress.eta,
                progress.seeds?.let { stringResource(R.string.seeds, it) }, progress.peers?.let { stringResource(R.string.peers, it) }).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (download.status == DownloadStatus.ERROR) {
        app.trove.debrid.ui.Feedback(stringResource(R.string.download_attention), error = true)
    }
}

@Composable
internal fun kindWord(kind: DownloadKind): String = when (kind) {
    DownloadKind.TORRENT -> stringResource(R.string.kind_torrent)
    DownloadKind.WEB -> stringResource(R.string.kind_web)
    DownloadKind.USENET -> stringResource(R.string.kind_usenet)
}

@Composable
internal fun PlaybackSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(TroveSpace.page), strokeWidth = 2.dp,
        color = LocalContentColor.current)
}
