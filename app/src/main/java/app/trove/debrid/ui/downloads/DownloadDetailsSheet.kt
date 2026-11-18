package app.trove.debrid.ui.downloads

import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.trove.debrid.core.*
import app.trove.debrid.ui.theme.TroveSpace

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DownloadDetailsSheet(
    download: DebridDownload,
    state: DownloadsUiState,
    sheetState: SheetState,
    dismissing: Boolean,
    onClose: () -> Unit,
    onPlay: (String) -> Unit,
    onPlayWith: (String) -> Unit,
    onDelete: () -> Unit,
    showClutterFiles: Boolean = false,
) {
    val visibleFiles = remember(download.files, showClutterFiles) {
        if (showClutterFiles) download.files else download.files.filterNot { isClutterFile(it.name) }
    }
    val busy = state.resolvingDownload != null || state.playUrl != null || dismissing
    val canClose = !busy || (state.resolvingDownload != null && download.files.isEmpty() && !dismissing)
    val ready = download.status == DownloadStatus.READY && visibleFiles.isNotEmpty()
    ModalBottomSheet(
        onDismissRequest = { if (canClose) onClose() }, sheetState = sheetState,
        sheetGesturesEnabled = canClose,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = TroveSpace.large, end = TroveSpace.large, bottom = TroveSpace.section),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.medium),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
                    Text(download.name, style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.details_metadata, kindWord(download.kind), formatSize(download.sizeBytes), statusWord(download.status)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DownloadTransfer(download)
                    state.error?.let { app.trove.debrid.ui.Feedback(it, error = true) }
                    if (busy && download.files.isEmpty()) {
                        val preparing = stringResource(R.string.preparing)
                        LinearProgressIndicator(Modifier.fillMaxWidth().semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = preparing
                        })
                    } else if (!ready) Text(
                        if (visibleFiles.isEmpty() && download.files.isNotEmpty()) stringResource(R.string.no_files)
                        else if (download.status != DownloadStatus.READY) stringResource(R.string.play_when_ready)
                        else stringResource(R.string.no_files),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
                    Button(onClick = { visibleFiles.firstOrNull()?.let { onPlay(it.id) } }, enabled = ready && !busy) {
                        if (busy) PlaybackSpinner() else Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(TroveSpace.small))
                        Text(if (busy) stringResource(R.string.preparing) else if (visibleFiles.size > 1) stringResource(R.string.play_first) else stringResource(R.string.play))
                    }
                    TextButton(onClick = { visibleFiles.firstOrNull()?.let { onPlayWith(it.id) } }, enabled = ready && !busy) {
                        Text(stringResource(R.string.play_with))
                    }
                    TextButton(onClick = onDelete, enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) }
                    TextButton(onClick = onClose, enabled = canClose) { Text(stringResource(R.string.close)) }
                }
            }
            if (visibleFiles.isNotEmpty()) item {
                Text(stringResource(R.string.files_count, visibleFiles.size), style = MaterialTheme.typography.titleMedium)
            }
            items(visibleFiles, key = { it.id }) { file ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(TroveSpace.medium), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TroveSpace.compact)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TroveSpace.micro)) {
                            Text(baseFileName(file.name), style = MaterialTheme.typography.bodyLarge)
                            Text(formatSize(file.sizeBytes), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalIconButton(onClick = { onPlay(file.id) }, enabled = ready && !busy,
                            modifier = Modifier.size(48.dp)) {
                            val playFile = stringResource(R.string.play_file, baseFileName(file.name))
                            if (state.resolvingFileId == file.id) {
                                val preparing = stringResource(R.string.preparing_player)
                                PlaybackSpinner(Modifier.semantics {
                                    contentDescription = playFile
                                    liveRegion = LiveRegionMode.Polite
                                    stateDescription = preparing
                                })
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = playFile)
                            }
                        }
                        Box {
                            var showActions by remember { mutableStateOf(false) }
                            IconButton(onClick = { showActions = true }, enabled = ready && !busy) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.file_actions, baseFileName(file.name)))
                            }
                            DropdownMenu(expanded = showActions && !busy, onDismissRequest = { showActions = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.play_with)) }, onClick = {
                                    showActions = false
                                    onPlayWith(file.id)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
