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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.trove.debrid.core.*
import app.trove.debrid.ui.theme.TroveSpace
import app.trove.debrid.ui.theme.SpaceGrotesk
import androidx.compose.ui.semantics.*

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
    refreshing: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Column(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = onOpen,
                    onLongClick = onLongPress,
                    onClickLabel = stringResource(R.string.view_details),
                    onLongClickLabel = stringResource(R.string.delete_download),
                ).padding(vertical = TroveSpace.small),
                verticalArrangement = Arrangement.spacedBy(TroveSpace.compact),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TroveSpace.small),
                ) {
                    DownloadCover(download, Modifier.weight(1f), metadata, refreshing)
                    if (download.status == DownloadStatus.READY) {
                        val play = stringResource(R.string.play)
                        val preparing = stringResource(R.string.preparing_player)
                        IconButton(
                            onClick = onPlay,
                            enabled = playEnabled,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = play },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            if (resolving) {
                                PlaybackSpinner(Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    stateDescription = preparing
                                })
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            }
                        }
                    }
                }
                DownloadTransfer(download)
                if (resolving && download.status != DownloadStatus.READY) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            HorizontalDivider()
        }
    }
}

/** Poster, media identity and compact download status. */
@Composable
internal fun DownloadCover(
    download: DebridDownload,
    modifier: Modifier = Modifier,
    metadata: MediaMetadata? = null,
    refreshing: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val identity = remember(download.name, metadata) { metadata ?: downloadIdentity(download, null) }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TroveSpace.medium),
    ) {
        MetadataPoster(identity.posterUrl, metadata, refreshing)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TroveSpace.micro)) {
            Text(identity.title, style = MaterialTheme.typography.titleMedium, fontFamily = SpaceGrotesk,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val type = when (identity.type) {
                "movie" -> stringResource(R.string.media_movie)
                "series" -> stringResource(R.string.media_series)
                else -> null
            }
            val (episode, quality) = remember(download.name) { parseReleaseDetails(download.name) }
            val subtitle = downloadCoverSubtitle(identity, type, episode, quality)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${statusWord(download.status)} · ${formatSize(download.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(progress.percentage, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
            listOfNotNull(progress.speed, progress.eta,
                progress.seeds?.let { stringResource(R.string.seeds, it) }, progress.peers?.let { stringResource(R.string.peers, it) }).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (download.status == DownloadStatus.ERROR) {
        app.trove.debrid.ui.Feedback(
            stringResource(R.string.download_attention), error = true,
            contentColor = MaterialTheme.colorScheme.primary,
            containerColor = MaterialTheme.colorScheme.surface,
        )
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
