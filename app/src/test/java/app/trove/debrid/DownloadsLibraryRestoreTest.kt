package app.trove.debrid

import app.trove.debrid.core.DebridProvider
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.LibrarySnapshot
import app.trove.debrid.core.MediaMetadata
import app.trove.debrid.core.MetadataLookup
import app.trove.debrid.data.DownloadPage
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.downloads.ScrollAnchor
import app.trove.debrid.ui.downloads.posterUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsLibraryRestoreTest {
    private val dispatcher = StandardTestDispatcher()
    private val torrent = DownloadKind.TORRENT
    private val cached = List(2) { sampleDownload(id = "cached-$it", name = "Cached $it", fileCount = 0) }
    private val live = sampleDownload(id = "live")
    private val snapshot = LibrarySnapshot(DebridService.TORBOX, 123, mapOf(
        torrent to LibrarySnapshot.Tab(cached, endReached = true, nextOffset = 2),
    ))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun cachedRowsSurviveInFlightPageZero() = runTest(dispatcher) {
        val repo = Repository(snapshot)
        val vm = DownloadsViewModel(repo, dispatcher)

        runCurrent()

        assertEquals(listOf(0), repo.offsets)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.refreshing)
        assertEquals(cached, vm.visibleDownloads(vm.state.value))
        with(vm.state.value.pagedByTab.getValue(torrent)) {
            assertTrue(visited)
            assertTrue(endReached)
            assertEquals(2, nextOffset)
        }

        repo.page.complete(Result.success(DownloadPage(listOf(live), endReached = true)))
        runCurrent()
        assertEquals(listOf(live), vm.state.value.downloads)
        assertFalse(vm.state.value.refreshing)
        advanceTimeBy(500)
        runCurrent()
    }

    @Test fun absentSnapshotUsesInitialLoadingFrame() = runTest(dispatcher) {
        checkUncachedStartup(null)
    }

    @Test fun restoredRowsEnrichEvenWhenPageZeroFails() = runTest(dispatcher) {
        val repo = Repository(snapshot)
        val vm = DownloadsViewModel(repo, dispatcher)
        runCurrent()

        assertEquals(cached.map { it.name }.toSet(), repo.lookups.toSet())
        assertFalse(repo.page.isCompleted)

        repo.page.complete(Result.failure(IllegalStateException("Offline")))
        runCurrent()

        assertNotNull(vm.state.value.loadError)
        val rows = vm.visibleDownloads(vm.state.value)
        assertEquals(cached, rows)
        assertTrue(rows.all { vm.state.value.posterUrl(it.kind to it.id) == repo.artwork })
        assertEquals(cached.size, repo.lookups.size)
    }

    @Test fun differentProviderSnapshotUsesInitialLoadingFrame() = runTest(dispatcher) {
        checkUncachedStartup(snapshot.copy(service = DebridService.OFFCLOUD))
    }

    @Test fun savedAnchorResolvesAgainstCachedRowsBeforeTheNetworkCompletes() = runTest(dispatcher) {
        val repo = Repository(snapshot)
        val vm = DownloadsViewModel(repo, dispatcher)
        runCurrent()
        val index = 1
        val target = vm.visibleDownloads(vm.state.value)[index]
        val anchor = ScrollAnchor(torrent, target.id, index, DownloadSort.NEWEST, "")

        vm.setPendingAnchor(anchor, DebridService.TORBOX.name)

        assertEquals(anchor, vm.state.value.pendingAnchor)
        assertEquals(index, vm.anchorRowIndex(anchor))
        assertFalse(repo.page.isCompleted)

        repo.page.complete(Result.failure(IllegalStateException("Offline")))
        runCurrent()
        assertEquals(cached, vm.state.value.downloads)
        assertNotNull(vm.state.value.loadError)
        assertEquals(index, vm.anchorRowIndex(anchor))
    }

    @Test fun visitingACachedTabRevalidatesPageZeroBeforeUsingItsOffset() = runTest(dispatcher) {
        val web = DownloadKind.WEB
        val cachedWebRow = sampleDownload(id = "cached-web", name = "Cached Web", kind = web, fileCount = 0)
        val freshWebRow = sampleDownload(id = "fresh-web", name = "Fresh Web", kind = web)
        val repo = Repository(snapshot.copy(tabs = snapshot.tabs + (
            web to LibrarySnapshot.Tab(listOf(cachedWebRow), endReached = true, nextOffset = 240)
        )))
        val webPage = CompletableDeferred<DownloadPage>()
        repo.webPage = webPage
        val vm = DownloadsViewModel(repo, dispatcher)
        runCurrent()
        assertEquals(listOf(cachedWebRow), vm.visibleDownloads(vm.state.value, web))

        vm.setTab(web)
        runCurrent()

        assertEquals(listOf(0, 0), repo.offsets)
        assertEquals(listOf(cachedWebRow), vm.visibleDownloads(vm.state.value, web))
        assertEquals(repo.artwork, vm.state.value.posterUrl(web to cachedWebRow.id))
        assertTrue(cachedWebRow.name in repo.lookups)

        webPage.complete(DownloadPage(listOf(freshWebRow), endReached = true))
        runCurrent()
        assertEquals(listOf(freshWebRow), vm.visibleDownloads(vm.state.value, web))
        repo.page.complete(Result.success(DownloadPage(listOf(live), endReached = true)))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(setOf(torrent, web), repo.writes.single().tabs.keys)
    }

    @Test fun successfulPagesDebounceOneWriteOfTheLatestUnion() = runTest(dispatcher) {
        val repo = Repository(null)
        val vm = DownloadsViewModel(repo, dispatcher)
        val second = sampleDownload(id = "next")
        repo.nextPage = DownloadPage(listOf(second), endReached = true)
        repo.page.complete(Result.success(DownloadPage(listOf(live), endReached = false)))
        runCurrent()
        assertEquals(listOf(live), vm.state.value.downloads)
        advanceTimeBy(300)

        vm.loadNextPage()
        runCurrent()
        assertEquals(listOf(live, second), vm.state.value.downloads)
        advanceTimeBy(499)
        runCurrent()
        assertTrue(repo.writes.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            LibrarySnapshot.Tab(listOf(live, second), endReached = true, nextOffset = 2),
            repo.writes.single().tabs.getValue(torrent),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.checkUncachedStartup(candidate: LibrarySnapshot?) {
        val repo = Repository(candidate)
        val vm = DownloadsViewModel(repo, dispatcher)
        runCurrent()

        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.refreshing)
        assertTrue(vm.state.value.downloads.isEmpty())
        assertEquals(listOf(0), repo.offsets)
        repo.page.complete(Result.success(DownloadPage(listOf(live), endReached = true)))
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals(listOf(live), vm.state.value.downloads)
        advanceTimeBy(500)
        runCurrent()
    }

    private class Repository(private val snapshot: LibrarySnapshot?) : TroveRepository by FakeRepository() {
        val page = CompletableDeferred<Result<DownloadPage>>()
        var webPage: CompletableDeferred<DownloadPage>? = null
        var nextPage = DownloadPage(emptyList(), endReached = true)
        val offsets = mutableListOf<Int>()
        val writes = mutableListOf<LibrarySnapshot>()
        val lookups = mutableListOf<String>()
        val artwork = "https://example.invalid/restored.jpg"

        override suspend fun lookupMetadataForRelease(
            name: String, bypassMiss: Boolean, onPoster: suspend (String) -> Unit,
        ): MetadataLookup {
            lookups += name
            return MetadataLookup.from(MediaMetadata(name, null, null, artwork))
        }

        override suspend fun loadLibrarySnapshot() = snapshot

        override suspend fun saveLibrarySnapshot(snapshot: LibrarySnapshot) {
            writes += snapshot
        }

        override suspend fun listDownloadPage(
            provider: DebridProvider,
            kind: DownloadKind,
            offset: Int,
            limit: Int,
            bypassCache: Boolean,
        ): Result<DownloadPage> {
            offsets += offset
            return when {
                kind == DownloadKind.WEB -> Result.success(webPage?.await() ?: DownloadPage(emptyList(), endReached = true))
                offset == 0 -> page.await()
                else -> Result.success(nextPage)
            }
        }
    }
}
