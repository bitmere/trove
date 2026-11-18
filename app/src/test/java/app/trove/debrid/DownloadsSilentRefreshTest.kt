package app.trove.debrid

import app.trove.debrid.core.DownloadKind
import app.trove.debrid.ui.downloads.DownloadsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsSilentRefreshTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `successful add refresh retains rows query and tab without pull indicator`() = runTest {
        val provider = FakeProvider(downloads = mutableListOf(sampleDownload()))
        val vm = DownloadsViewModel(FakeRepository(provider = provider))
        advanceUntilIdle()
        vm.setQuery("Dune")
        val before = vm.state.value.downloads
        provider.downloads.add(sampleDownload(id = "new").copy(createdAtEpochMs = 2000))
        vm.onAdded(DownloadKind.TORRENT)
        assertEquals(before, vm.state.value.downloads)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.refreshing)
        assertEquals(DownloadKind.TORRENT, vm.state.value.addedConfirmation)
        advanceUntilIdle()
        assertEquals("new", vm.visibleDownloads(vm.state.value).first().id)
        assertEquals("Dune", vm.state.value.query)
        vm.consumeAddedConfirmation()
        assertNull(vm.state.value.addedConfirmation)
        vm.refresh()
        assertTrue(vm.state.value.refreshing)
        advanceUntilIdle()
    }
}
