package app.trove.debrid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trove.debrid.core.update.LatestRelease
import app.trove.debrid.update.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import app.trove.debrid.core.update.UpdateChannel
import android.content.SharedPreferences
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object NoReleases : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val release: LatestRelease) : UpdateUiState
    data class Downloading(val release: LatestRelease, val downloaded: Long, val total: Long?) : UpdateUiState
    data class ReadyToInstall(val release: LatestRelease, val apkFile: File) : UpdateUiState
    data object Installing : UpdateUiState
    data class WaitingForConfirmation(val release: LatestRelease, val apkFile: File) : UpdateUiState
    data object Installed : UpdateUiState
    data class NeedsUnknownSources(val release: LatestRelease, val apkFile: File) : UpdateUiState
    data class Error(val issue: UpdateIssue, val retryInstall: Pair<LatestRelease, File>? = null, val detail: String? = null) : UpdateUiState
}

/** Activity-owned state and operation owner. Nothing polls at construction or on resume. */
class UpdateViewModel(
    private val backend: UpdateBackend,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val preferences: SharedPreferences? = null,
) : ViewModel() {
    val installedName = backend.installedName
    private val mutable = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = mutable.asStateFlow()
    private val selectedChannel = MutableStateFlow(
        if (preferences?.getString("channel", null) == UpdateChannel.BETA.name) UpdateChannel.BETA else UpdateChannel.STABLE,
    )
    val channel = selectedChannel.asStateFlow()

    fun selectChannel(value: UpdateChannel) {
        if (value == selectedChannel.value || (busy() && mutable.value != UpdateUiState.Checking)) return
        job?.cancel()
        job = null
        preferences?.edit()?.putString("channel", value.name)?.apply()
        selectedChannel.value = value
        mutable.value = UpdateUiState.Idle
    }

    private var job: Job? = null
    private var operation: String? = null
    private var downloadGeneration: Any? = null
    private var installPair: Pair<LatestRelease, File>? = null

    init {
        viewModelScope.launch {
            UpdateInstallEvents.events.collect { event ->
                if (event.operation != operation) return@collect
                val pair = installPair ?: return@collect
                mutable.value = when (val result = event.result) {
                    UpdateInstallResult.Installed -> UpdateUiState.Installed
                    UpdateInstallResult.WaitingForConfirmation -> UpdateUiState.WaitingForConfirmation(pair.first, pair.second)
                    is UpdateInstallResult.Failed -> UpdateUiState.Error(result.issue, pair, result.message)
                }
                if (event.result !is UpdateInstallResult.WaitingForConfirmation) operation = null
            }
        }
    }
    private fun busy() = job?.isActive == true || mutable.value is UpdateUiState.Installing || mutable.value is UpdateUiState.WaitingForConfirmation

    fun check() {
        if (busy()) return
        val checkedChannel = selectedChannel.value
        mutable.value = UpdateUiState.Checking
        job = viewModelScope.launch {
            try {
                val result = withContext(io) { backend.check(checkedChannel) }
                currentCoroutineContext().ensureActive()
                mutable.value = when (result) {
                    is UpdateCheckResult.Available -> UpdateUiState.Available(result.release)
                    UpdateCheckResult.NoReleases -> UpdateUiState.NoReleases
                    UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                    is UpdateCheckResult.Error -> UpdateUiState.Error(result.issue)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (checkedChannel == selectedChannel.value && currentCoroutineContext()[Job]?.isActive == true) {
                    mutable.value = UpdateUiState.Error(UpdateIssue.NETWORK)
                }
            }
        }
    }
    fun download() {
        val release = (mutable.value as? UpdateUiState.Available)?.release ?: return
        if (busy()) return
        val generation = Any().also { downloadGeneration = it }
        mutable.value = UpdateUiState.Downloading(release, 0, null)
        job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var owned: File? = null
            try {
                val file = withContext(io) {
                    var lastPercent = -1L
                    var lastBytes = 0L
                    var lastTotal: Long? = null
                    backend.download(release) { bytes, total ->
                        val percent = total?.takeIf { it > 0 }?.let { bytes * 100 / it }
                        if (total != lastTotal || bytes < lastBytes ||
                            (percent != null && percent != lastPercent) || bytes - lastBytes >= 512 * 1024
                        ) {
                            lastPercent = percent ?: -1
                            lastBytes = bytes
                            lastTotal = total
                            viewModelScope.launch {
                                if (downloadGeneration === generation && mutable.value is UpdateUiState.Downloading) mutable.value = UpdateUiState.Downloading(release, bytes, total)
                            }
                        }
                    }.also { owned = it }
                }
                currentCoroutineContext().ensureActive()
                mutable.value = UpdateUiState.ReadyToInstall(release, file)
                owned = null
            } catch (e: CancellationException) {
                mutable.value = UpdateUiState.Available(release)
                throw e
            } catch (e: UpdateException) { mutable.value = UpdateUiState.Error(e.issue, detail = e.diagnostics?.toString()) }
            catch (_: Exception) { mutable.value = UpdateUiState.Error(UpdateIssue.DOWNLOAD) }
            finally {
                if (downloadGeneration === generation) downloadGeneration = null
                owned?.let { file -> withContext(NonCancellable + io) { file.delete() } }
            }
        }
    }
    fun cancelDownload() { if (mutable.value is UpdateUiState.Downloading) job?.cancel() }

    fun install() {
        if (job?.isActive == true) return
        val pair = when (val current = mutable.value) {
            is UpdateUiState.ReadyToInstall -> current.release to current.apkFile
            is UpdateUiState.NeedsUnknownSources -> current.release to current.apkFile
            is UpdateUiState.Error -> current.retryInstall ?: return
            is UpdateUiState.WaitingForConfirmation -> current.release to current.apkFile
            else -> return
        }
        val op = UUID.randomUUID().toString()
        operation = op
        installPair = pair
        mutable.value = UpdateUiState.Installing
        job = viewModelScope.launch {
            val outcome = try { withContext(io) { backend.install(pair.second, pair.first, op) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { InstallOutcome.Error(UpdateIssue.INSTALL) }
            // A terminal receiver event can beat the return from commit.
            if (operation != op) return@launch
            when (outcome) {
                is InstallOutcome.SessionStarted -> Unit
                InstallOutcome.SystemInstallerLaunched -> { operation = null; mutable.value = UpdateUiState.WaitingForConfirmation(pair.first, pair.second) }
                InstallOutcome.NeedsUnknownSources -> { operation = null; mutable.value = UpdateUiState.NeedsUnknownSources(pair.first, pair.second) }
                is InstallOutcome.Error -> {
                    operation = null
                    val retry = pair.takeIf { outcome.issue in setOf(UpdateIssue.INSTALL, UpdateIssue.PREVIOUS_SESSION, UpdateIssue.SETTINGS) }
                    mutable.value = UpdateUiState.Error(outcome.issue, retry)
                }
            }
        }
    }
    fun retry() {
        if ((mutable.value as? UpdateUiState.Error)?.retryInstall != null) install() else check()
    }
    fun openUnknownSourcesSettings() {
        val pending = mutable.value as? UpdateUiState.NeedsUnknownSources ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val opened = try { withContext(io) { backend.openSettings() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            if (!opened) mutable.value = UpdateUiState.Error(UpdateIssue.SETTINGS, pending.release to pending.apkFile)
        }
    }
    /** Permission returns only re-offer Install; no automatic install, download or network check. */
    fun onResume() {
        val pending = mutable.value as? UpdateUiState.NeedsUnknownSources ?: return
        val previous = job
        job = viewModelScope.launch {
            // A fast permission return can arrive before openSettings has returned on IO.
            previous?.join()
            if (mutable.value != pending) return@launch
            try {
                if (withContext(io) { backend.canInstall() }) mutable.value = UpdateUiState.ReadyToInstall(pending.release, pending.apkFile)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = UpdateUiState.Error(UpdateIssue.SETTINGS, pending.release to pending.apkFile) }
        }
    }
    override fun onCleared() {
        operation = null
        job?.cancel()
        super.onCleared()
    }
}
