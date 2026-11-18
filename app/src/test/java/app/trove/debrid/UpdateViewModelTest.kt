package app.trove.debrid

import app.trove.debrid.core.update.LatestRelease
import app.trove.debrid.core.update.UpdateChannel
import app.trove.debrid.ui.settings.*
import app.trove.debrid.update.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    private val release = LatestRelease("0.2.0", 21, "https://github.com/bitmere/trove/releases/download/v0.2.0/Trove-v0.2.0.apk", "a".repeat(64), "notes", 2)
    private inner class Backend : UpdateBackend {
        override val installedName = "installed"
        var granted = false
        var settingsAvailable = true
        var checks = 0
        val channels = mutableListOf<UpdateChannel>()
        var downloads = 0
        var installs = 0
        var operation: String? = null
        var checkDelay = 0L
        var downloadDelay = 0L
        var result: UpdateCheckResult = UpdateCheckResult.Available(release)
        var outcome: InstallOutcome = InstallOutcome.Error(UpdateIssue.INSTALL)
        var downloadFailure: Exception? = null
        var downloadCancelled = false
        val file = temporary.newFile()
        override fun canInstall() = granted
        override fun openSettings() = settingsAvailable
        override suspend fun check(channel: UpdateChannel): UpdateCheckResult { checks++; channels += channel; delay(checkDelay); return result }
        override suspend fun download(release: LatestRelease, progress: (Long, Long?) -> Unit): File {
            downloads++
            try {
                progress(1, 2)
                delay(downloadDelay)
                downloadFailure?.let { throw it }
                return file
            } catch (e: CancellationException) { downloadCancelled = true; throw e }
        }
        override suspend fun install(file: File, release: LatestRelease, operation: String): InstallOutcome {
            if (!granted) return InstallOutcome.NeedsUnknownSources
            installs++; this.operation = operation
            return outcome
        }
    }
    private fun model(backend: Backend): UpdateViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(backend, dispatcher) as T
        }
        return ViewModelProvider(store, factory)[UpdateViewModel::class.java]
    }
    @Test fun manualOnlyAndOneCheckAtATime() = runTest(dispatcher) {
        val backend = Backend().apply { checkDelay = 1000 }
        val vm = model(backend)
        vm.onResume(); advanceUntilIdle()
        assertEquals(0, backend.checks); assertEquals(UpdateUiState.Idle, vm.state.value)
        vm.check(); vm.check(); runCurrent()
        assertEquals(1, backend.checks); assertEquals(UpdateUiState.Checking, vm.state.value)
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateUiState.Available)
    }
    @Test fun channelChangeClearsOfferAndCancelsStaleCheck() = runTest(dispatcher) {
        val backend = Backend().apply { checkDelay = 1000 }
        val vm = model(backend)
        assertEquals(UpdateChannel.STABLE, vm.channel.value)
        vm.check()
        runCurrent()
        vm.selectChannel(UpdateChannel.BETA)
        advanceUntilIdle()
        assertEquals(UpdateUiState.Idle, vm.state.value)
        assertEquals(listOf(UpdateChannel.STABLE), backend.channels)
        vm.check()
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateUiState.Available)
        assertEquals(listOf(UpdateChannel.STABLE, UpdateChannel.BETA), backend.channels)
        vm.selectChannel(UpdateChannel.STABLE)
        assertEquals(UpdateUiState.Idle, vm.state.value)
    }
    @Test fun permissionReturnOffersInstallAndRetryRetriesInstallOnly() = runTest(dispatcher) {
        val backend = Backend()
        val vm = model(backend)
        vm.check(); advanceUntilIdle(); vm.download(); advanceUntilIdle(); vm.install(); advanceUntilIdle()
        assertTrue(vm.state.value is UpdateUiState.NeedsUnknownSources)
        vm.onResume(); advanceUntilIdle()
        assertTrue(vm.state.value is UpdateUiState.NeedsUnknownSources)
        backend.granted = true
        vm.onResume(); advanceUntilIdle()
        assertTrue(vm.state.value is UpdateUiState.ReadyToInstall)
        assertEquals(0, backend.installs)
        vm.install(); advanceUntilIdle()
        assertEquals(release to backend.file, (vm.state.value as UpdateUiState.Error).retryInstall)
        vm.retry(); advanceUntilIdle()
        assertEquals(2, backend.installs); assertEquals(1, backend.downloads); assertEquals(1, backend.checks)
    }
    @Test fun cancellationAndClearingCancelOwnedJob() = runTest(dispatcher) {
        val backend = Backend().apply { downloadDelay = 10000 }
        val vm = model(backend)
        vm.check(); advanceUntilIdle(); vm.download(); runCurrent()
        assertTrue(vm.state.value is UpdateUiState.Downloading)
        vm.check(); vm.download()
        vm.cancelDownload(); advanceUntilIdle()
        assertTrue(backend.downloadCancelled); assertTrue(vm.state.value is UpdateUiState.Available)
        vm.download(); runCurrent(); stores.last().clear(); advanceUntilIdle()
        assertTrue(backend.downloadCancelled); assertEquals(1, backend.checks)
    }
    @Test fun cancellingBeforeDownloadStartsRestoresAvailableState() = runTest(dispatcher) {
        val backend = Backend()
        val vm = model(backend)
        vm.check(); advanceUntilIdle()
        vm.download()
        vm.cancelDownload()
        advanceUntilIdle()
        assertEquals(UpdateUiState.Available(release), vm.state.value)
        assertEquals(0, backend.downloads)
    }
    @Test fun errorsAreErrorsAndSettingsFailurePreservesInstallRetry() = runTest(dispatcher) {
        val backend = Backend()
        val vm = model(backend)
        backend.result = UpdateCheckResult.Error(UpdateIssue.MANIFEST)
        vm.check(); advanceUntilIdle()
        assertEquals(UpdateIssue.MANIFEST, (vm.state.value as UpdateUiState.Error).issue)
        backend.result = UpdateCheckResult.UpToDate
        vm.retry(); advanceUntilIdle(); assertEquals(UpdateUiState.UpToDate, vm.state.value)
        backend.result = UpdateCheckResult.Available(release)
        vm.check(); advanceUntilIdle(); vm.download(); advanceUntilIdle(); vm.install(); advanceUntilIdle()
        backend.settingsAvailable = false
        vm.openUnknownSourcesSettings(); advanceUntilIdle()
        assertEquals(UpdateIssue.SETTINGS, (vm.state.value as UpdateUiState.Error).issue)
        assertNotNull((vm.state.value as UpdateUiState.Error).retryInstall)
    }
    @Test fun correlatedReceiverEventsIgnoreStaleAttemptAndCannotOverwriteTerminalResult() = runTest(dispatcher) {
        val backend = Backend().apply { granted = true; outcome = InstallOutcome.SessionStarted(7) }
        val vm = model(backend)
        vm.check(); advanceUntilIdle(); vm.download(); advanceUntilIdle(); vm.install(); advanceUntilIdle()
        assertEquals(UpdateUiState.Installing, vm.state.value)
        vm.check(); advanceUntilIdle(); assertEquals(1, backend.checks)
        UpdateInstallEvents.publish("stale", UpdateInstallResult.Installed); runCurrent()
        assertEquals(UpdateUiState.Installing, vm.state.value)
        val operation = requireNotNull(backend.operation)
        UpdateInstallEvents.publish(operation, UpdateInstallResult.WaitingForConfirmation); runCurrent()
        assertTrue(vm.state.value is UpdateUiState.WaitingForConfirmation)
        UpdateInstallEvents.publish(operation, UpdateInstallResult.Installed); runCurrent()
        assertEquals(UpdateUiState.Installed, vm.state.value)
        UpdateInstallEvents.publish(operation, UpdateInstallResult.Failed()); runCurrent()
        assertEquals(UpdateUiState.Installed, vm.state.value)
    }
    @Test fun verificationFailureNeverOffersInstall() = runTest(dispatcher) {
        val backend = Backend().apply { downloadFailure = UpdateException(UpdateIssue.VERIFICATION) }
        val vm = model(backend)
        vm.check(); advanceUntilIdle(); vm.download(); advanceUntilIdle()
        assertEquals(UpdateIssue.VERIFICATION, (vm.state.value as UpdateUiState.Error).issue)
        vm.install(); advanceUntilIdle(); assertEquals(0, backend.installs)
    }
    @Test fun installOwnershipErrorSurfacesDiagnosticsAndNeverOffersInstall() = runTest(dispatcher) {
        val diagnostics = InstallDiagnostics(
            InstallCheck.FAIL, InstallCheck.NOT_CHECKED, InstallCheck.NOT_CHECKED,
            3, null, "a".repeat(64), null, null,
        )
        val backend = Backend().apply { granted = true; outcome = InstallOutcome.Error(UpdateIssue.OWNERSHIP, diagnostics) }
        val vm = model(backend)
        vm.check(); advanceUntilIdle(); vm.download(); advanceUntilIdle(); vm.install(); advanceUntilIdle()
        val error = vm.state.value as UpdateUiState.Error
        assertEquals(UpdateIssue.OWNERSHIP, error.issue)
        assertEquals(diagnostics.toString(), error.detail)
        assertNull(error.retryInstall)
        vm.install(); advanceUntilIdle()
        assertEquals(1, backend.installs)
    }

    @Test fun downloadIssuesExposeOnlyTypedDiagnosticsAndNeverOfferInstall() = runTest(dispatcher) {
        for (issue in listOf(UpdateIssue.EMPTY, UpdateIssue.TRUNCATED, UpdateIssue.DIGEST_MISMATCH)) {
            val diagnostics = UpdateDiagnostics(2, 3, "a".repeat(64), UpdateFraming.CONTENT_LENGTH, 200, 3)
            val backend = Backend().apply { downloadFailure = UpdateException(issue, diagnostics) }
            val vm = model(backend)
            vm.check(); advanceUntilIdle(); vm.download(); advanceUntilIdle()
            val error = vm.state.value as UpdateUiState.Error
            assertEquals(issue, error.issue)
            assertEquals(diagnostics.toString(), error.detail)
            assertNull(error.retryInstall)
            vm.install(); advanceUntilIdle()
            assertEquals(0, backend.installs)
        }
    }

}
