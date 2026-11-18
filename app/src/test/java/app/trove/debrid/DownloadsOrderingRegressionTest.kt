package app.trove.debrid

import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.data.TroveRepository
import kotlinx.coroutines.CompletableDeferred
import app.trove.debrid.ui.downloads.DownloadsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsOrderingRegressionTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `newest first survives filtering and missing dates preserve provider order`() = runTest {
        val rows = listOf(
            sampleDownload(id = "unknown-a", name = "Match A").copy(createdAtEpochMs = null),
            sampleDownload(id = "old", name = "Match old").copy(createdAtEpochMs = 100),
            sampleDownload(id = "web", kind = DownloadKind.WEB).copy(createdAtEpochMs = 500),
            sampleDownload(id = "new", name = "Match new").copy(createdAtEpochMs = 300),
            sampleDownload(id = "tie", name = "Match tie").copy(createdAtEpochMs = 300),
            sampleDownload(id = "unknown-b", name = "Match B").copy(createdAtEpochMs = null),
        )
        val vm = DownloadsViewModel(FakeRepository(provider = FakeProvider(downloads = rows.toMutableList())))
        advanceUntilIdle()
        vm.setQuery("match")
        assertEquals(listOf("new", "tie", "old", "unknown-a", "unknown-b"),
            vm.visibleDownloads(vm.state.value).map { it.id })
        vm.setQuery("")
        assertEquals(listOf("web"), vm.visibleDownloads(vm.state.value, DownloadKind.WEB).map { it.id })
    }

    @Test fun `chosen sort survives refresh and applies after tab and query filters`() = runTest {
        val provider = FakeProvider(downloads = mutableListOf(
            sampleDownload(id = "a", name = "Match A").copy(sizeBytes = 10),
            sampleDownload(id = "b", name = "Match B").copy(sizeBytes = 20),
            sampleDownload(id = "hidden", name = "Other").copy(sizeBytes = 30),
            sampleDownload(id = "web", name = "Match Web", kind = DownloadKind.WEB).copy(sizeBytes = 40),
        ))
        var saved = DownloadSort.NEWEST
        val repo = object : TroveRepository by FakeRepository(provider = provider) {
            override suspend fun loadDownloadSort() = saved
            override suspend fun saveDownloadSort(sort: DownloadSort) { saved = sort }
        }
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        vm.setQuery("Match")
        vm.setSort(DownloadSort.LARGEST)
        advanceUntilIdle()
        assertEquals(listOf("b", "a"), vm.visibleDownloads(vm.state.value).map { it.id })
        vm.refresh()
        advanceUntilIdle()
        assertEquals(DownloadSort.LARGEST, vm.state.value.sort)
        assertEquals(listOf("b", "a"), vm.visibleDownloads(vm.state.value).map { it.id })
        val restarted = DownloadsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DownloadSort.LARGEST, restarted.state.value.sort)
        assertEquals(listOf("web"), vm.visibleDownloads(vm.state.value, DownloadKind.WEB).map { it.id })
    }

    @Test fun `late preference read cannot override a new user choice and rapid writes stay ordered`() = runTest {
        val gate = CompletableDeferred<DownloadSort>()
        val writes = mutableListOf<DownloadSort>()
        val repo = object : TroveRepository by FakeRepository(provider = FakeProvider()) {
            override suspend fun loadDownloadSort() = gate.await()
            override suspend fun saveDownloadSort(sort: DownloadSort) { writes += sort }
        }
        val vm = DownloadsViewModel(repo)
        runCurrent()
        vm.setSort(DownloadSort.OLDEST)
        vm.setSort(DownloadSort.NAME_DESC)
        gate.complete(DownloadSort.LARGEST)
        advanceUntilIdle()
        assertEquals(DownloadSort.NAME_DESC, vm.state.value.sort)
        assertEquals(listOf(DownloadSort.OLDEST, DownloadSort.NAME_DESC), writes)
    }

}
