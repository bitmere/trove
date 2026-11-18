package app.trove.debrid.ui.add

import app.trove.debrid.R
import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trove.debrid.ui.theme.TroveSpace
import app.trove.debrid.core.CacheState
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridService
import app.trove.debrid.ui.downloads.formatSize
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import app.trove.debrid.readSharedFile
import app.trove.debrid.ui.Feedback
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddSheet(
    viewModel: AddViewModel,
    initialText: String?,
    initialFile: Triple<ByteArray, String, String?>?,
    onDismiss: () -> Unit,
    onAdded: (app.trove.debrid.core.DownloadKind) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val fileReadFailed = stringResource(R.string.file_read_failed)
    val scope = rememberCoroutineScope()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var readingFile by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!state.adding && !readingFile && !closing) {
            closing = true
            scope.launch { sheetState.hide(); onDismiss() }
        }
    }

    LaunchedEffect(initialText, initialFile) {
        if (initialText != null) viewModel.setText(initialText)
        if (initialFile != null) {
            viewModel.setFile(initialFile.first, initialFile.second, initialFile.third)
        }
    }
    LaunchedEffect(state.added) {
        if (state.added) {
            closing = true
            sheetState.hide()
            state.addedKind?.let(onAdded)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            readingFile = true
            try {
                val file = readSharedFile(context.contentResolver, uri)
                viewModel.setFile(file.bytes!!, file.fileName!!, file.mimeType)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { viewModel.inputError(fileReadFailed) }
            finally { readingFile = false }
        }
    }

    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState, sheetGesturesEnabled = !state.adding && !readingFile && !closing) {
        Column(
            modifier = Modifier
                .fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(start = TroveSpace.page, end = TroveSpace.page, bottom = TroveSpace.section),
            verticalArrangement = Arrangement.spacedBy(TroveSpace.medium),
        ) {
            Text(
                text = stringResource(R.string.add_download),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(stringResource(R.string.add_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.inputText,
                enabled = !state.adding && !readingFile && !closing,
                onValueChange = viewModel::setText,
                placeholder = { Text(stringResource(R.string.paste_link), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                shape = MaterialTheme.shapes.medium,
                label = { Text(stringResource(R.string.link_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
                FilledTonalButton(enabled = !state.adding && !readingFile && !closing, onClick = { filePicker.launch("application/x-bittorrent") }) {
                    Text(stringResource(R.string.choose_torrent))
                }
                FilledTonalButton(enabled = !state.adding && !readingFile && !closing, onClick = { filePicker.launch("application/x-nzb") }) {
                    Text(stringResource(R.string.choose_nzb))
                }
            }

            // Detection line: what we understood, before anything is sent.
            state.classified?.let { classified ->
                Text(text = detectionLine(classified, state), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (readingFile) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            val cached = state.cached
            val cacheStatus = when {
                state.checking -> stringResource(R.string.checking_cache)
                cached != null && !state.showServicePicker -> stringResource(R.string.cached_result, cached.name.orEmpty(), formatSize(cached.sizeBytes))
                else -> null
            }
            if (cacheStatus != null) {
                Text(
                    cacheStatus,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (state.checking) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
            if (state.showServicePicker) {
                Column(verticalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
                    Text(stringResource(R.string.add_choose_service), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
                        state.cacheResults.forEach { result ->
                            val service = stringResource(result.service.nameResource)
                            val status = stringResource(when (result.state) {
                                CacheState.CACHED -> R.string.cache_state_cached
                                CacheState.NOT_CACHED -> R.string.cache_state_not_cached
                                CacheState.UNKNOWN -> R.string.cache_state_unknown
                            })
                            FilterChip(
                                selected = state.selectedService == result.service,
                                onClick = { viewModel.selectService(result.service) },
                                label = { Text("$service · $status") },
                                enabled = !state.adding && !readingFile && !closing,
                            )
                        }
                    }
                }
            }
            (state.errorResource?.let { stringResource(it) } ?: state.error)?.let { error ->
                Feedback(error, error = true)
            }

            Button(
                onClick = viewModel::add,
                enabled = state.classified?.isAddable == true && !state.adding && !readingFile && !closing,
                modifier = Modifier.fillMaxWidth().padding(top = TroveSpace.small),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            ) {
                Box(Modifier.padding(end = TroveSpace.small).size(TroveSpace.page), contentAlignment = Alignment.Center) {
                    if (state.adding) CircularProgressIndicator(
                        color = LocalContentColor.current,
                        modifier = Modifier.size(TroveSpace.page),
                        strokeWidth = 2.dp,
                    )
                }
                Text(text = if (state.adding) stringResource(R.string.adding) else stringResource(R.string.add_download))
            }
            TextButton(onClick = close, enabled = !state.adding && !readingFile && !closing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}

private val DebridService.nameResource: Int
    get() = when (this) {
        DebridService.TORBOX -> R.string.torbox
        DebridService.REAL_DEBRID -> R.string.real_debrid
        DebridService.ALLDEBRID -> R.string.all_debrid
        DebridService.PREMIUMIZE -> R.string.premiumize
        DebridService.OFFCLOUD -> R.string.offcloud
        DebridService.DEBRID_LINK -> R.string.debrid_link
    }

private val ClassifiedShare.isAddable: Boolean
    get() = when (this) {
        is ClassifiedShare.Magnet -> true
        is ClassifiedShare.TorrentFile -> true
        is ClassifiedShare.Nzb -> true
        is ClassifiedShare.HosterLink -> true
        is ClassifiedShare.TorrentUrl -> true
        is ClassifiedShare.NzbUrl -> true
        is ClassifiedShare.UnsupportedHoster -> false
        is ClassifiedShare.NotShareable -> false
    }

@Composable
private fun detectionLine(classified: ClassifiedShare, state: AddUiState): String = when (classified) {
    is ClassifiedShare.Magnet -> stringResource(R.string.detected_magnet) +
        (if (state.cached != null && !state.showServicePicker) stringResource(R.string.cached_suffix) else "") +
        (if (classified.infoHashHex == null) stringResource(R.string.unreadable_hash) else "")
    is ClassifiedShare.TorrentFile -> stringResource(R.string.detected_torrent, classified.fileName)
    is ClassifiedShare.Nzb -> stringResource(R.string.detected_nzb, classified.fileName)
    is ClassifiedShare.HosterLink -> stringResource(R.string.detected_web, classified.url)
    is ClassifiedShare.TorrentUrl -> stringResource(R.string.detected_torrent_link)
    is ClassifiedShare.NzbUrl -> stringResource(R.string.detected_nzb_link)
    is ClassifiedShare.UnsupportedHoster -> stringResource(R.string.unsupported_hoster)
    is ClassifiedShare.NotShareable -> stringResource(R.string.nothing_to_add)
}
