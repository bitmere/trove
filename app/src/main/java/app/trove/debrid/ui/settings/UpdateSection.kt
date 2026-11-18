package app.trove.debrid.ui.settings

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.trove.debrid.R
import app.trove.debrid.ui.Feedback
import app.trove.debrid.core.update.UpdateChannel
import app.trove.debrid.ui.theme.TroveSpace
import app.trove.debrid.update.UpdateIssue

@Composable
fun UpdateSection(viewModel: UpdateViewModel, channel: UpdateChannel) {
    val state by viewModel.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    UpdateSection(state, viewModel.installedName, viewModel::check, viewModel::download,
        viewModel::cancelDownload, viewModel::install, viewModel::retry, viewModel::openUnknownSourcesSettings, channel, viewModel::selectChannel)
}

/** Thin rendering boundary also used by device tests; all actions belong to the ViewModel. */
@Composable
fun UpdateSection(
    state: UpdateUiState,
    currentVersion: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    channel: UpdateChannel = UpdateChannel.STABLE,
    onChannel: (UpdateChannel) -> Unit = {},
) {
    val release = when (state) {
        is UpdateUiState.Available -> state.release
        is UpdateUiState.Downloading -> state.release
        is UpdateUiState.ReadyToInstall -> state.release
        is UpdateUiState.NeedsUnknownSources -> state.release
        is UpdateUiState.WaitingForConfirmation -> state.release
        is UpdateUiState.Error -> state.retryInstall?.first
        else -> null
    }
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    var cleanOpen by rememberSaveable { mutableStateOf(false) }
    val notesScroll = rememberScrollState()
    val notesButton = remember { FocusRequester() }
    val notesContent = remember { FocusRequester() }
    val cleanButton = remember { FocusRequester() }
    val installButton = remember { FocusRequester() }
    var restoreNotes by remember { mutableStateOf(false) }
    var restoreClean by remember { mutableStateOf(false) }
    val showCleanHelp = state is UpdateUiState.Error && state.issue == UpdateIssue.SIGNER
    var permissionPending by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is UpdateUiState.NeedsUnknownSources) permissionPending = true
        if (state is UpdateUiState.ReadyToInstall && permissionPending) {
            permissionPending = false
            installButton.requestFocus()
        }
    }
    LaunchedEffect(notesOpen, release) {
        if (release == null) {
            notesOpen = false
            restoreNotes = false
        } else if (notesOpen) {
            notesContent.requestFocus()
            restoreNotes = true
        } else if (restoreNotes) {
            notesButton.requestFocus()
            restoreNotes = false
        }
    }
    LaunchedEffect(cleanOpen, showCleanHelp) {
        if (!showCleanHelp) {
            cleanOpen = false
            restoreClean = false
        } else if (cleanOpen) {
            restoreClean = true
        } else if (restoreClean) {
            cleanButton.requestFocus()
            restoreClean = false
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(TroveSpace.medium), verticalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.update_title)) },
                supportingContent = { Text(stringResource(R.string.version, currentVersion)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            Text(stringResource(R.string.update_channel))
            Row {
                UpdateChannel.entries.forEach { candidate ->
                    val label = if (candidate == UpdateChannel.STABLE) R.string.update_stable else R.string.update_beta
                    val enabled = state !is UpdateUiState.Downloading && state !is UpdateUiState.Installing &&
                        state !is UpdateUiState.WaitingForConfirmation
                    FilterChip(
                        selected = candidate == channel,
                        onClick = { onChannel(candidate) },
                        enabled = enabled,
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(TroveSpace.small)) {
                when (state) {
                    UpdateUiState.Idle -> Text(stringResource(R.string.update_manual))
                    UpdateUiState.Checking -> {
                        Text(stringResource(R.string.update_checking))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    UpdateUiState.NoReleases -> Feedback(stringResource(R.string.update_no_releases), error = false)
                    UpdateUiState.UpToDate -> Feedback(stringResource(R.string.update_current), error = false)
                    is UpdateUiState.Available -> Text(stringResource(R.string.update_available, state.release.versionName))
                    is UpdateUiState.Downloading -> {
                        Text(stringResource(R.string.update_downloading, state.downloaded / 1024))
                        val total = state.total
                        if (total != null && total > 0) LinearProgressIndicator(
                            progress = { (state.downloaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is UpdateUiState.ReadyToInstall -> Text(stringResource(R.string.update_ready, state.release.versionName))
                    UpdateUiState.Installing -> { Text(stringResource(R.string.update_installing)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is UpdateUiState.WaitingForConfirmation -> Text(stringResource(R.string.update_waiting))
                    UpdateUiState.Installed -> Feedback(stringResource(R.string.update_installed), error = false)
                    is UpdateUiState.NeedsUnknownSources -> Text(stringResource(R.string.update_permission))
                    is UpdateUiState.Error -> {
                        Feedback(stringResource(issueString(state.issue)), error = true)
                        state.detail?.takeIf { state.issue != UpdateIssue.SIGNER }?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                        if (state.issue == UpdateIssue.SIGNER) TextButton(onClick = { cleanOpen = true }, modifier = Modifier.focusRequester(cleanButton)) {
                            Text(stringResource(R.string.update_clean_title))
                        }
                    }
                }
            }
            if (release != null) OutlinedButton(onClick = { notesOpen = true }, modifier = Modifier.focusRequester(notesButton)) {
                Text(stringResource(R.string.update_notes, release.versionName))
            }
            when (state) {
                UpdateUiState.Idle, UpdateUiState.NoReleases, UpdateUiState.UpToDate, UpdateUiState.Installed -> Button(onClick = onCheck) { Text(stringResource(R.string.update_check)) }
                is UpdateUiState.Available -> Button(onClick = onDownload) { Text(stringResource(R.string.update_download)) }
                is UpdateUiState.Downloading -> OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                is UpdateUiState.ReadyToInstall -> Button(onClick = onInstall, modifier = Modifier.focusRequester(installButton)) { Text(stringResource(R.string.update_install)) }
                is UpdateUiState.NeedsUnknownSources -> {
                    Button(onClick = onSettings) { Text(stringResource(R.string.update_allow)) }
                    OutlinedButton(onClick = onInstall) { Text(stringResource(R.string.update_install_again)) }
                }
                is UpdateUiState.WaitingForConfirmation -> OutlinedButton(onClick = onInstall) { Text(stringResource(R.string.update_install_again)) }
                is UpdateUiState.Error -> if (state.issue != UpdateIssue.SIGNER) Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                    else OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.update_check)) }
                UpdateUiState.Checking, UpdateUiState.Installing -> Unit
            }
        }
    }
    if (notesOpen && release != null) AlertDialog(
        onDismissRequest = { notesOpen = false },
        title = { Text(stringResource(R.string.update_notes, release.versionName)) },
        text = {
            Text(release.notes.ifEmpty { stringResource(R.string.update_no_notes) }, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("update_notes_scroll")
                    .verticalScroll(notesScroll).focusRequester(notesContent).focusable())
        },
        confirmButton = { TextButton(onClick = { notesOpen = false }) { Text(stringResource(R.string.update_close)) } },
    )
    if (cleanOpen && showCleanHelp) AlertDialog(
        onDismissRequest = { cleanOpen = false },
        title = { Text(stringResource(R.string.update_clean_title)) },
        text = { Text(stringResource(R.string.update_clean_body), Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { cleanOpen = false }) { Text(stringResource(R.string.update_close)) } },
    )
}

private fun issueString(issue: UpdateIssue): Int = when (issue) {
    UpdateIssue.UNAVAILABLE -> R.string.update_unavailable
    UpdateIssue.RATE_LIMIT -> R.string.update_rate_limit
    UpdateIssue.ASSETS -> R.string.update_invalid_assets
    UpdateIssue.NETWORK -> R.string.update_error_network
    UpdateIssue.MANIFEST -> R.string.update_error_manifest
    UpdateIssue.DOWNLOAD -> R.string.update_error_download
    UpdateIssue.EMPTY -> R.string.update_error_empty
    UpdateIssue.TRUNCATED -> R.string.update_error_truncated
    UpdateIssue.DIGEST_MISMATCH -> R.string.update_error_digest
    UpdateIssue.VERIFICATION -> R.string.update_error_hash
    UpdateIssue.PACKAGE -> R.string.update_error_package
    UpdateIssue.SIGNER -> R.string.update_error_signer
    UpdateIssue.INSTALL -> R.string.update_error_install
    UpdateIssue.SETTINGS -> R.string.update_error_settings
    UpdateIssue.PREVIOUS_SESSION -> R.string.update_error_previous
}
