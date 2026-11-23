package app.trove.debrid

import androidx.lifecycle.viewModelScope
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.data.DownloadPage
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsOptimisticAddTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<DownloadsViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(repo: TroveRepository) =
        DownloadsViewModel(repo, StandardTestDispatcher(testScheduler)).also { models += it }

    private class AddRepository(rows: List<DebridDownload>) : FakeRepository(
        provider = FakeProvider(downloads = rows.toMutableList()),
    ) {
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        val calls = mutableListOf<Triple<DownloadKind, Int, Boolean>>()

        override suspend fun listDownloadPage(
            provider: DebridProvider,
            kind: DownloadKind,
            offset: Int,
            limit: Int,
            bypassCache: Boolean,
        ): Result<DownloadPage> {
            calls += Triple(kind, offset, bypassCache)
            gate?.await()
            failure?.let { return Result.failure(it) }
            return super.listDownloadPage(provider, kind, offset, limit, bypassCache)
        }
    }

    private fun DownloadsViewModel.rows(kind: DownloadKind = DownloadKind.TORRENT) =
        state.value.pagedByTab.getValue(kind).loaded

    @Test fun `add inserts immediately at top of its tab and deduplicates only that kind`() = runTest {
        val torrent = sampleDownload(id = "same")
        val web = sampleDownload(id = "same", kind = DownloadKind.WEB)
        val other = sampleDownload(id = "other", kind = DownloadKind.WEB)
        val repo = AddRepository(listOf(torrent, other, web))
        val vm = viewModel(repo)
        runCurrent()
        vm.setTab(DownloadKind.WEB)
        runCurrent()
        vm.setTab(DownloadKind.TORRENT)
        runCurrent()
        val added = web.copy(name = "Just added", status = DownloadStatus.QUEUED)
        repo.gate = CompletableDeferred()

        vm.onAdded(added)
        assertEquals(listOf(added, other), vm.rows(DownloadKind.WEB))
        assertEquals(listOf(torrent), vm.rows())
        assertEquals(DownloadKind.WEB, vm.state.value.addedConfirmation)
        assertTrue(added in vm.state.value.downloads)
        runCurrent() // Start the fetch and leave it suspended.
        assertEquals(added, vm.rows(DownloadKind.WEB).first())
        repo.gate!!.complete(Unit)
        runCurrent()
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `server head replaces optimistic fields and confirms the row`() = runTest {
        val repo = AddRepository(listOf(sampleDownload(id = "old")))
        val vm = viewModel(repo)
        runCurrent()
        val added = sampleDownload(id = "new", name = "Pending", status = DownloadStatus.QUEUED)
        val server = added.copy(name = "Server title", status = DownloadStatus.READY, progress = 1f)
        repo.provider!!.downloads.add(0, server)

        vm.onAdded(added)
        runCurrent()
        assertEquals(server, vm.rows().first())
        assertEquals(1, vm.rows().count { it.id == added.id })
        assertEquals(server, vm.state.value.downloads.first())
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `first missing head keeps optimistic row and second drops it`() = runTest {
        val old = sampleDownload(id = "old")
        val repo = AddRepository(listOf(old))
        val vm = viewModel(repo)
        runCurrent()
        val added = sampleDownload(id = "new", status = DownloadStatus.QUEUED)

        vm.onAdded(added)
        runCurrent()
        assertEquals(listOf(added, old), vm.rows())
        vm.refreshFromUser()
        runCurrent()
        assertEquals(listOf(old), vm.rows())
        assertFalse(added in vm.state.value.downloads)
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `second missing head drops the row and explains it`() = runTest {
        val old = sampleDownload(id = "old")
        val repo = AddRepository(listOf(old))
        val vm = viewModel(repo)
        runCurrent()
        val added = sampleDownload(id = "new", status = DownloadStatus.QUEUED)

        vm.onAdded(added)
        runCurrent()
        assertEquals(listOf(added, old), vm.rows())
        vm.refreshFromUser()
        runCurrent()
        assertEquals(listOf(old), vm.rows())
        assertFalse(added in vm.state.value.downloads)
        assertEquals(
            "Couldn't confirm the added download. Pull to refresh and try again.",
            vm.state.value.error,
        )
        assertNull(vm.state.value.loadError)
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `first miss survives head reconciliation with more than one hundred loaded rows`() = runTest {
        val serverRows = (0 until 250).map { sampleDownload(id = "$it", name = "Movie $it") }
        val repo = AddRepository(serverRows)
        val vm = viewModel(repo)
        runCurrent()
        vm.loadNextPage()
        runCurrent()
        assertEquals(200, vm.rows().size)
        val before = vm.rows()
        val added = sampleDownload(id = "new", status = DownloadStatus.QUEUED)
        val callsBefore = repo.calls.size

        vm.onAdded(added)
        runCurrent()
        assertEquals(listOf(added) + before, vm.rows())
        assertEquals(listOf(Triple(DownloadKind.TORRENT, 0, true)), repo.calls.drop(callsBefore))
        assertFalse(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).endReached)
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `two poll ticks do not consume misses before two user refreshes`() = runTest {
        val active = sampleDownload(id = "active", status = DownloadStatus.DOWNLOADING)
        val repo = AddRepository(listOf(active))
        val vm = viewModel(repo)
        runCurrent()
        val added = sampleDownload(id = "new", status = DownloadStatus.QUEUED)
        // Fail the add-triggered head so no successful non-poll head has counted a miss.
        repo.failure = IllegalStateException("offline")
        vm.onAdded(added)
        runCurrent()
        repo.failure = null
        vm.onForeground() // Restart polling after the failed add refresh stopped it.
        runCurrent()
        val callsBefore = repo.calls.size

        repeat(2) {
            advanceTimeBy(DownloadsViewModel.POLL_INTERVAL_MS)
            runCurrent()
            assertTrue(added in vm.rows())
        }
        assertEquals(List(2) { Triple(DownloadKind.TORRENT, 0, true) }, repo.calls.drop(callsBefore))
        vm.refreshFromUser()
        runCurrent()
        assertTrue(added in vm.rows())
        vm.refreshFromUser()
        runCurrent()
        assertFalse(added in vm.rows())
        assertEquals(listOf(active), vm.rows())
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `poll confirms server row without leaving it pending`() = runTest {
        val active = sampleDownload(id = "active", status = DownloadStatus.DOWNLOADING)
        val repo = AddRepository(listOf(active))
        val vm = viewModel(repo)
        runCurrent()
        val added = sampleDownload(id = "new", status = DownloadStatus.QUEUED)
        vm.onAdded(added)
        runCurrent() // First non-poll miss.
        val server = added.copy(name = "Server title", status = DownloadStatus.READY, progress = 1f)
        repo.provider!!.downloads.add(0, server)
        advanceTimeBy(DownloadsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(server, vm.rows().first())
        assertEquals(1, vm.rows().count { it.id == added.id })

        // A confirmed row must no longer be reinserted when the server removes it.
        repo.provider!!.downloads.remove(server)
        advanceTimeBy(DownloadsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf(active), vm.rows())
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `provider change clears pending rows before another head can restore them`() = runTest {
        val repo = AddRepository(listOf(sampleDownload(id = "old")))
        val vm = viewModel(repo)
        runCurrent()
        repo.failure = IllegalStateException("offline")
        vm.onAdded(sampleDownload(id = "pending", status = DownloadStatus.QUEUED))
        runCurrent()
        val otherAccount = sampleDownload(id = "other-account")
        repo.provider = FakeProvider(downloads = mutableListOf(otherAccount))
        repo.failure = null
        vm.refreshFromUser()
        runCurrent()
        assertEquals(listOf(otherAccount), vm.rows())
        assertEquals(listOf(otherAccount), vm.state.value.downloads)
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }
}
