package app.trove.debrid

import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.MediaMetadata
import app.trove.debrid.core.parseMediaName
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsLibraryPerformanceTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `batching emits a few updates for many rows, not one per row`() = runTest {
        val provider = FakeProvider(downloads = metadataRows())
        var calls = 0
        val repo = object : TroveRepository by FakeRepository(provider = provider) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata {
                calls++
                // Let the collector observe each publication without StateFlow conflation.
                yield()
                return parseMediaName(name).let { MediaMetadata(it.title, it.year, it.type) }
            }
        }
        val vm = DownloadsViewModel(repo, StandardTestDispatcher(testScheduler))
        val sizes = mutableListOf(0)
        backgroundScope.launch { vm.state.collect { sizes += it.metadata.size } }
        advanceUntilIdle()
        runCurrent()

        assertEquals(25, vm.state.value.metadata.size)
        assertEquals(25, calls)
        assertEquals(25, sizes.last())
        assertTrue(sizes.zipWithNext().count { (before, after) -> after > before } <= 4)
    }

    @Test fun `stale batch after refresh is dropped`() = runTest {
        val provider = FakeProvider(downloads = metadataRows())
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repo = object : TroveRepository by FakeRepository(provider = provider) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata {
                if (++calls > 10) gate.await()
                return parseMediaName(name).let { MediaMetadata(it.title, it.year, it.type) }
            }
        }
        val vm = DownloadsViewModel(repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(10, vm.state.value.metadata.size)

        provider.downloads.clear()
        vm.refresh()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.state.value.downloads.isEmpty())
        assertTrue(vm.state.value.metadata.isEmpty())
    }

    @Test fun `poster arrival order is preserved`() = runTest {
        val rows = metadataRows()
        val provider = FakeProvider(downloads = rows)
        val repo = object : TroveRepository by FakeRepository(provider = provider) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean) =
                parseMediaName(name).let { MediaMetadata(it.title, it.year, it.type) }
        }
        val vm = DownloadsViewModel(repo, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        assertEquals(25, vm.state.value.metadata.size)
        assertEquals(rows.map { it.kind to it.id }, vm.state.value.metadata.keys.toList())
    }

    @Test fun `viewport rows resolve first with at most four active lookups`() = runTest {
        val rows = metadataRows()
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val repo = object : TroveRepository by FakeRepository(provider = FakeProvider(downloads = rows)) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata {
                started += name
                gate.await()
                return MediaMetadata(name, null, null)
            }
        }
        val vm = DownloadsViewModel(repo, StandardTestDispatcher(testScheduler))
        val viewport = rows.takeLast(4)
        vm.updateVisibleRows(DownloadKind.TORRENT, viewport.mapTo(HashSet()) { "${it.kind}:${it.id}" })
        runCurrent()

        assertEquals(viewport.map { it.name }, started)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(rows.size, started.size)
        assertEquals(rows.size, vm.state.value.metadata.size)
    }

    @Test fun `typing debounces results and settles after the delay`() = runTest {
        val rows = searchRows()
        val vm = library(rows)
        advanceUntilIdle()

        vm.setQuery("dune")
        runCurrent()
        assertEquals("dune", vm.state.value.query)
        assertEquals(rows, vm.visibleDownloads(vm.state.value))
        advanceTimeBy(100)
        assertEquals(rows, vm.visibleDownloads(vm.state.value))
        advanceTimeBy(200)
        assertEquals(rows.take(2), vm.visibleDownloads(vm.state.value))
        assertEquals(rows.take(2), vm.state.value.visibleByTab[DownloadKind.TORRENT])
    }

    @Test fun `clearing the query applies immediately`() = runTest {
        val rows = searchRows()
        val vm = library(rows)
        advanceUntilIdle()
        vm.setQuery("dune")
        advanceUntilIdle()
        assertEquals(rows.take(2), vm.visibleDownloads(vm.state.value))

        vm.setQuery("")
        assertEquals(rows, vm.visibleDownloads(vm.state.value))
    }

    @Test fun `reset applies immediately`() = runTest {
        val rows = searchRows()
        val vm = library(rows)
        advanceUntilIdle()
        vm.setSort(DownloadSort.OLDEST)
        vm.setQuery("dune")
        advanceUntilIdle()

        vm.resetSearch()
        assertEquals("", vm.state.value.query)
        assertEquals(DownloadSort.NEWEST, vm.state.value.sort)
        assertEquals(rows, vm.visibleDownloads(vm.state.value))
    }

    @Test fun `stable ties keep provider order`() = runTest {
        val rows = listOf(
            sampleDownload(id = "missing-a").copy(createdAtEpochMs = null),
            sampleDownload(id = "old").copy(createdAtEpochMs = 100),
            sampleDownload(id = "new-a").copy(createdAtEpochMs = 300),
            sampleDownload(id = "new-b").copy(createdAtEpochMs = 300),
            sampleDownload(id = "missing-b").copy(createdAtEpochMs = null),
        )
        val vm = library(rows)
        advanceUntilIdle()

        assertEquals(listOf("new-a", "new-b", "old", "missing-a", "missing-b"),
            vm.visibleDownloads(vm.state.value).map { it.id })
    }

    @Test fun `missing dates and sizes order last`() = runTest {
        val rows = listOf(
            sampleDownload(id = "negative").copy(sizeBytes = -1, createdAtEpochMs = null),
            sampleDownload(id = "small").copy(sizeBytes = 1, createdAtEpochMs = 200),
            sampleDownload(id = "unknown").copy(sizeBytes = null, createdAtEpochMs = null),
            sampleDownload(id = "large").copy(sizeBytes = 10, createdAtEpochMs = 100),
        )
        val vm = library(rows)
        advanceUntilIdle()
        assertEquals(listOf("small", "large", "negative", "unknown"),
            vm.visibleDownloads(vm.state.value).map { it.id })

        vm.setSort(DownloadSort.LARGEST)
        advanceUntilIdle()
        assertEquals(listOf("large", "small", "negative", "unknown"),
            vm.visibleDownloads(vm.state.value).map { it.id })
    }

    @Test fun `counts and offscreen tabs load on first visit`() = runTest {
        val web = sampleDownload(id = "web", kind = DownloadKind.WEB)
        val rows = searchRows() + web + sampleDownload(id = "usenet", kind = DownloadKind.USENET)
        val vm = library(rows)
        advanceUntilIdle()

        assertEquals(DownloadKind.TORRENT, vm.state.value.tab)
        // Only the visible tab is loaded eagerly; the others load
        // page 0 on first visit.
        assertEquals(mapOf(DownloadKind.TORRENT to 3),
            vm.state.value.visibleByTab.filterValues { it.isNotEmpty() }.mapValues { it.value.size })
        assertTrue(vm.visibleDownloads(vm.state.value, DownloadKind.WEB).isEmpty())

        vm.setTab(DownloadKind.WEB)
        advanceUntilIdle()
        vm.setTab(DownloadKind.USENET)
        advanceUntilIdle()
        assertEquals(mapOf(DownloadKind.TORRENT to 3, DownloadKind.WEB to 1, DownloadKind.USENET to 1),
            vm.state.value.visibleByTab.mapValues { it.value.size })
        assertEquals(listOf(web), vm.visibleDownloads(vm.state.value, DownloadKind.WEB))
    }

    @Test fun `prefer words still rank first with precomputed keys`() = runTest {
        val rows = listOf(
            sampleDownload(id = "1080p", name = "Dune 1080p"),
            sampleDownload(id = "x265", name = "Dune x265"),
            sampleDownload(id = "banana", name = "banana"),
            sampleDownload(id = "apple", name = "Apple"),
        )
        val vm = library(rows)
        advanceUntilIdle()
        assertEquals(listOf("x265", "1080p", "banana", "apple"),
            vm.visibleDownloads(vm.state.value).map { it.id })

        vm.setSort(DownloadSort.NAME_ASC)
        advanceUntilIdle()
        assertEquals(listOf("x265", "apple", "banana", "1080p"),
            vm.visibleDownloads(vm.state.value).map { it.id })
    }

    private fun TestScope.library(rows: List<DebridDownload>) = DownloadsViewModel(
        FakeRepository(provider = FakeProvider(downloads = rows.toMutableList())),
        StandardTestDispatcher(testScheduler),
    )

    private fun metadataRows() = MutableList(25) { index ->
        sampleDownload(id = "$index", name = "Movie $index")
    }

    private fun searchRows() = listOf(
        sampleDownload(id = "part-two", name = "Dune Part Two").copy(createdAtEpochMs = 300),
        sampleDownload(id = "1984", name = "Dune 1984").copy(createdAtEpochMs = 200),
        sampleDownload(id = "other", name = "Other").copy(createdAtEpochMs = 100),
    )
}
