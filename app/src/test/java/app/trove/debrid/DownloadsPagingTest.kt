package app.trove.debrid

import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.data.DownloadPage
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.downloads.ScrollAnchor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsPagingTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(repo: TroveRepository) =
        DownloadsViewModel(repo, StandardTestDispatcher(testScheduler))

    private fun manyRows(
        count: Int,
        kind: DownloadKind = DownloadKind.TORRENT,
        name: (Int) -> String = { "Movie $it" },
    ): MutableList<DebridDownload> = (0 until count).map { i ->
        sampleDownload(id = "$i", name = name(i), kind = kind)
            .copy(createdAtEpochMs = 1000L + i, sizeBytes = (i + 1) * 1000L)
    }.toMutableList()

    @Test
    fun `only the visible tab loads eagerly and other tabs load page 0 on first visit`() = runTest {
        val rows = mutableListOf(
            sampleDownload(id = "t1"),
            sampleDownload(id = "t2", name = "Other"),
            sampleDownload(id = "w1", name = "Web row", kind = DownloadKind.WEB),
            sampleDownload(id = "u1", name = "Usenet row", kind = DownloadKind.USENET),
        )
        val vm = viewModel(FakeRepository(provider = FakeProvider(downloads = rows)))
        advanceUntilIdle()

        assertEquals(listOf("t1", "t2"), vm.state.value.downloads.map { it.id })
        val torrent = vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT)
        assertTrue(torrent.visited)
        assertEquals(2, torrent.loaded.size)
        assertTrue(torrent.endReached)
        assertFalse(vm.state.value.pagedByTab.getValue(DownloadKind.WEB).visited)
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.WEB).loaded.isEmpty())
        assertFalse(vm.state.value.pagedByTab.getValue(DownloadKind.USENET).visited)

        vm.setTab(DownloadKind.WEB)
        advanceUntilIdle()
        assertEquals(listOf("t1", "t2", "w1"), vm.state.value.downloads.map { it.id })
        assertEquals(1, vm.state.value.pagedByTab.getValue(DownloadKind.WEB).loaded.size)

        vm.setTab(DownloadKind.USENET)
        advanceUntilIdle()
        assertEquals(4, vm.state.value.downloads.size)
    }

    @Test
    fun `appending loads the next page and visible order follows newest first`() = runTest {
        val vm = viewModel(FakeRepository(provider = FakeProvider(downloads = manyRows(250))))
        advanceUntilIdle()

        val page0 = vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT)
        assertEquals(100, page0.loaded.size)
        assertEquals(100, page0.nextOffset)
        assertFalse(page0.endReached)
        assertEquals(listOf("99", "98", "97"), vm.visibleDownloads(vm.state.value).take(3).map { it.id })

        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(200, vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).loaded.size)
        assertEquals("199", vm.visibleDownloads(vm.state.value).first().id)

        vm.loadNextPage()
        advanceUntilIdle()
        val done = vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT)
        assertEquals(250, done.loaded.size)
        assertTrue(done.endReached)
        assertEquals("249", vm.visibleDownloads(vm.state.value).first().id)

        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(250, vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).loaded.size)
    }

    @Test
    fun `append ordering follows name and size sorts within loaded rows`() = runTest {
        val vm = viewModel(FakeRepository(provider = FakeProvider(downloads = manyRows(250))))
        advanceUntilIdle()

        vm.setSort(DownloadSort.NAME_ASC)
        advanceUntilIdle()
        val names = vm.visibleDownloads(vm.state.value).map { it.name.lowercase() }
        assertEquals(names.sorted(), names)
        assertEquals("0", vm.visibleDownloads(vm.state.value).first().id)
        vm.loadNextPage()
        advanceUntilIdle()
        val namesAfter = vm.visibleDownloads(vm.state.value).map { it.name.lowercase() }
        assertEquals(namesAfter.sorted(), namesAfter)

        vm.setSort(DownloadSort.LARGEST)
        advanceUntilIdle()
        vm.loadNextPage()
        advanceUntilIdle()
        val sizes = vm.visibleDownloads(vm.state.value).map { it.sizeBytes ?: 0L }
        assertEquals(sizes.sortedDescending(), sizes)
        assertEquals("199", vm.visibleDownloads(vm.state.value).first().id)
    }

    @Test
    fun `page error keeps loaded rows and retry loads only that page`() = runTest {
        var failOffset100 = true
        val repo = object : TroveRepository by FakeRepository(
            provider = FakeProvider(downloads = manyRows(250)),
        ) {
            override suspend fun listDownloadPage(
                provider: app.trove.debrid.core.DebridProvider,
                kind: DownloadKind,
                offset: Int,
                limit: Int,
                bypassCache: Boolean,
            ): Result<DownloadPage> =
                if (offset == 100 && failOffset100) Result.failure(IllegalStateException("offline"))
                else super.listDownloadPage(provider, kind, offset, limit, bypassCache)
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(100, vm.state.value.downloads.size)

        vm.loadNextPage()
        advanceUntilIdle()
        val failed = vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT)
        assertEquals(100, failed.loaded.size)
        assertEquals(100, failed.nextOffset)
        assertNotNull(failed.pageError)
        assertNull(vm.state.value.loadError)
        assertEquals((0 until 100).map { "$it" }, failed.loaded.map { it.id })

        failOffset100 = false
        vm.retryPage()
        advanceUntilIdle()
        val retried = vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT)
        assertEquals(200, retried.loaded.size)
        assertEquals(200, retried.nextOffset)
        assertNull(retried.pageError)
    }

    @Test
    fun `usenet page error is surfaced not swallowed`() = runTest {
        val repo = object : TroveRepository by FakeRepository(
            provider = FakeProvider(downloads = mutableListOf(sampleDownload(id = "t1"))),
        ) {
            override suspend fun listDownloadPage(
                provider: app.trove.debrid.core.DebridProvider,
                kind: DownloadKind,
                offset: Int,
                limit: Int,
                bypassCache: Boolean,
            ): Result<DownloadPage> =
                if (kind == DownloadKind.USENET) Result.failure(IllegalStateException("Pro only"))
                else super.listDownloadPage(provider, kind, offset, limit, bypassCache)
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(listOf("t1"), vm.state.value.downloads.map { it.id })

        vm.setTab(DownloadKind.USENET)
        advanceUntilIdle()
        val usenet = vm.state.value.pagedByTab.getValue(DownloadKind.USENET)
        assertNotNull(usenet.pageError)
        assertTrue(usenet.loaded.isEmpty())
        assertEquals(listOf("t1"), vm.state.value.downloads.map { it.id })
    }

    @Test
    fun `page 0 failure stops loading with a load error and a later refresh recovers`() = runTest {
        var fail = true
        val repo = object : TroveRepository by FakeRepository(
            provider = FakeProvider(downloads = mutableListOf(sampleDownload(id = "t1"))),
        ) {
            override suspend fun listDownloadPage(
                provider: app.trove.debrid.core.DebridProvider,
                kind: DownloadKind,
                offset: Int,
                limit: Int,
                bypassCache: Boolean,
            ): Result<DownloadPage> =
                if (fail) Result.failure(IllegalStateException("offline"))
                else super.listDownloadPage(provider, kind, offset, limit, bypassCache)
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.signedOut)
        assertNotNull(vm.state.value.loadError)
        assertNotNull(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).pageError)
        assertTrue(vm.state.value.downloads.isEmpty())

        fail = false
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.loadError)
        assertEquals(listOf("t1"), vm.state.value.downloads.map { it.id })
    }

    @Test
    fun `refresh resets pagination to page 0 and keeps rows until it lands`() = runTest {
        val vm = viewModel(FakeRepository(provider = FakeProvider(downloads = manyRows(250))))
        advanceUntilIdle()
        vm.loadNextPage()
        advanceUntilIdle()
        vm.loadNextPage()
        advanceUntilIdle()
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).endReached)

        vm.refresh()
        assertEquals(250, vm.state.value.downloads.size)
        advanceUntilIdle()
        val reset = vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT)
        assertEquals(100, reset.loaded.size)
        assertEquals(100, reset.nextOffset)
        assertFalse(reset.endReached)
    }

    @Test
    fun `sort change resets pagination to page 0 for every tab`() = runTest {
        val rows = (manyRows(250) + sampleDownload(id = "w1", name = "Web row", kind = DownloadKind.WEB)).toMutableList()
        val vm = viewModel(FakeRepository(provider = FakeProvider(downloads = rows)))
        advanceUntilIdle()
        vm.setTab(DownloadKind.WEB)
        advanceUntilIdle()
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.WEB).visited)
        vm.setTab(DownloadKind.TORRENT)
        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(200, vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).loaded.size)

        vm.setSort(DownloadSort.NAME_ASC)
        advanceUntilIdle()
        val reset = vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT)
        assertEquals(100, reset.loaded.size)
        assertEquals(100, reset.nextOffset)
        assertFalse(reset.endReached)
        assertFalse(vm.state.value.pagedByTab.getValue(DownloadKind.WEB).visited)
        val names = vm.visibleDownloads(vm.state.value).map { it.name.lowercase() }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `provider switch resets all three tab states to top`() = runTest {
        val first = FakeProvider(
            downloads = mutableListOf(
                sampleDownload(id = "t1"),
                sampleDownload(id = "w1", name = "Web row", kind = DownloadKind.WEB),
            ),
        )
        val repo = FakeRepository(provider = first)
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.setTab(DownloadKind.WEB)
        advanceUntilIdle()
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.WEB).visited)

        repo.provider = FakeProvider(downloads = mutableListOf(sampleDownload(id = "new", name = "New account")))
        vm.refresh()
        advanceUntilIdle()
        // The visible tab is WEB: its page 0 loads empty for the new
        // account, and TORRENT stays unvisited until first visit
        // (lazy first load). Only the visible tab loads eagerly.
        assertTrue(vm.state.value.downloads.isEmpty())
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.WEB).visited)
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.WEB).loaded.isEmpty())
        assertFalse(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).visited)
        assertFalse(vm.state.value.pagedByTab.getValue(DownloadKind.USENET).visited)
        vm.setTab(DownloadKind.TORRENT)
        advanceUntilIdle()
        assertEquals(listOf("new"), vm.state.value.downloads.map { it.id })
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).visited)
    }

    @Test
    fun `search triggers a full sync before matching`() = runTest {
        val rows = manyRows(250) { if (it == 249) "Zebra 249" else "Movie $it" }
        val vm = viewModel(FakeRepository(provider = FakeProvider(downloads = rows)))
        advanceUntilIdle()
        assertEquals(100, vm.state.value.downloads.size)
        assertTrue(vm.state.value.downloads.none { it.id == "249" })

        vm.setQuery("zebra")
        advanceUntilIdle()
        assertEquals(250, vm.state.value.downloads.size)
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).endReached)
        assertEquals(listOf("249"), vm.visibleDownloads(vm.state.value).map { it.id })

        vm.setQuery("")
        assertEquals(250, vm.visibleDownloads(vm.state.value).size)
    }

    @Test
    fun `anchor restores only once its row is loaded and is dropped on a criteria change`() = runTest {
        val vm = viewModel(FakeRepository(provider = FakeProvider(downloads = manyRows(250))))
        advanceUntilIdle()

        val anchor = vm.captureAnchor(DownloadKind.TORRENT, 5)
        assertNotNull(anchor)
        // Newest first among loaded 0..99: index 5 is id 94.
        assertEquals("94", anchor!!.id)
        assertEquals(5, vm.anchorRowIndex(anchor))

        val deep = ScrollAnchor(DownloadKind.TORRENT, "200", 49, DownloadSort.NEWEST, "")
        assertNull(vm.anchorRowIndex(deep))
        vm.loadNextPage()
        advanceUntilIdle()
        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(49, vm.anchorRowIndex(deep))

        val wrongCriteria = deep.copy(sort = DownloadSort.OLDEST)
        assertNull(vm.anchorRowIndex(wrongCriteria))

        // A foreign account's anchor is ignored, never restored.
        vm.setPendingAnchor(deep, "OTHER")
        assertNull(vm.state.value.pendingAnchor)
        vm.setPendingAnchor(deep, vm.currentAccountIdentity())
        assertEquals(deep, vm.state.value.pendingAnchor)
        vm.consumePendingAnchor()
        assertNull(vm.state.value.pendingAnchor)
    }

    @Test
    fun `stale page is dropped after refresh`() = runTest {
        val marker = sampleDownload(id = "stale-marker", name = "Stale Marker")
        val gate = CompletableDeferred<Unit>()
        val repo = object : TroveRepository by FakeRepository(
            provider = FakeProvider(downloads = manyRows(250)),
        ) {
            override suspend fun listDownloadPage(
                provider: app.trove.debrid.core.DebridProvider,
                kind: DownloadKind,
                offset: Int,
                limit: Int,
                bypassCache: Boolean,
            ): Result<DownloadPage> {
                if (offset == 100) {
                    // Non-cancellable on purpose: cancellation alone
                    // must not be what drops this page — the version
                    // guard must.
                    withContext(NonCancellable) { gate.await() }
                    return Result.success(DownloadPage(listOf(marker), true))
                }
                return super.listDownloadPage(provider, kind, offset, limit, bypassCache)
            }
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(100, vm.state.value.downloads.size)

        vm.loadNextPage()
        runCurrent()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(100, vm.state.value.downloads.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.downloads.none { it.id == "stale-marker" })
        assertEquals(100, vm.state.value.downloads.size)
    }
    private fun pagingRepo(
        rows: MutableList<DebridDownload> = manyRows(250),
        page: suspend (Int, Boolean, () -> Result<DownloadPage>) -> Result<DownloadPage>,
    ): TroveRepository = object : TroveRepository by FakeRepository(provider = FakeProvider(downloads = rows)) {
        override suspend fun listDownloadPage(
            provider: app.trove.debrid.core.DebridProvider, kind: DownloadKind,
            offset: Int, limit: Int, bypassCache: Boolean,
        ): Result<DownloadPage> {
            val result = super.listDownloadPage(provider, kind, offset, limit, bypassCache)
            return page(offset, bypassCache) { result }
        }
    }

    @Test
    fun `fresh refresh reaches newest late page across 2401 rows with bypass`() = runTest {
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val vm = viewModel(pagingRepo(manyRows(2401)) { offset, bypass, result ->
            calls += offset to bypass
            result()
        })
        advanceUntilIdle()
        assertEquals(listOf(0 to false), calls)
        calls.clear()
        vm.refreshFromUser()
        runCurrent()
        assertTrue(vm.state.value.refreshing)
        advanceUntilIdle()
        assertEquals((0..24).map { it * 100 to true }, calls)
        assertEquals(2401, vm.state.value.downloads.size)
        assertEquals("2400", vm.visibleDownloads(vm.state.value).first().id)
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).endReached)
        assertFalse(vm.state.value.refreshing)
        assertFalse(vm.state.value.pagedByTab.getValue(DownloadKind.WEB).visited)
    }

    @Test
    fun `raw cursor survives fewer accepted rows and nonfresh search keeps cache mode`() = runTest {
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val vm = viewModel(pagingRepo { offset, bypass, result ->
            calls += offset to bypass
            if (offset == 0) Result.success(DownloadPage(manyRows(98), false, 100)) else result()
        })
        advanceUntilIdle()
        vm.setQuery("Movie 249")
        advanceUntilIdle()
        assertEquals(listOf(0 to false, 100 to false, 200 to false), calls)
        assertEquals(listOf("249"), vm.visibleDownloads(vm.state.value).map { it.id })
    }

    @Test
    fun `failed head surfaces detail with zero matches and retry replaces using bypass`() = runTest {
        var fail = false
        var updated = false
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val vm = viewModel(pagingRepo(manyRows(1)) { offset, bypass, result ->
            calls += offset to bypass
            when {
                fail -> Result.failure(app.trove.debrid.core.DebridError.Provider("busy", "Please try later"))
                updated -> Result.success(DownloadPage(listOf(sampleDownload(id = "0", name = "Updated")), true))
                else -> result()
            }
        })
        advanceUntilIdle()
        vm.setQuery("no match")
        fail = true
        vm.refreshFromUser()
        advanceUntilIdle()
        assertEquals("Please try later", vm.state.value.error)
        assertEquals("Please try later", vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).pageError)
        assertTrue(vm.visibleDownloads(vm.state.value).isEmpty())
        assertEquals(1, vm.state.value.downloads.size)
        fail = false
        updated = true
        vm.retryPage()
        advanceUntilIdle()
        assertEquals(0 to true, calls.last())
        assertEquals("Updated", vm.state.value.downloads.single().name)
        assertNull(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).pageError)
    }

    @Test
    fun `cancelled search fetch releases loading and a continuation can resume`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var block = true
        val vm = viewModel(pagingRepo { offset, _, result ->
            if (offset == 100 && block) gate.await()
            result()
        })
        advanceUntilIdle()
        vm.setQuery("Movie")
        advanceUntilIdle()
        assertTrue(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).pageLoading)
        vm.setQuery("")
        runCurrent()
        assertFalse(vm.state.value.pagedByTab.getValue(DownloadKind.TORRENT).pageLoading)
        block = false
        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(200, vm.state.value.downloads.size)
    }

    @Test
    fun `fresh continuation failure retries its offset and freshness`() = runTest {
        var fail = true
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val vm = viewModel(pagingRepo { offset, bypass, result ->
            calls += offset to bypass
            if (offset == 100 && fail) Result.failure(IllegalStateException()) else result()
        })
        advanceUntilIdle()
        vm.refreshFromUser()
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.refreshing)
        fail = false
        calls.clear()
        vm.retryPage()
        advanceUntilIdle()
        assertEquals(listOf(100 to true, 200 to true), calls)
        assertEquals(250, vm.state.value.downloads.size)
    }

    @Test
    fun `foreground revalidation is head only and bounded per tab`() = runTest {
        var time = 0L
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val repo = pagingRepo { offset, bypass, result -> calls += offset to bypass; result() }
        val vm = DownloadsViewModel(repo, StandardTestDispatcher(testScheduler), now = { time })
        advanceUntilIdle()
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(1, calls.size)
        time += DownloadsViewModel.HEAD_REVALIDATION_INTERVAL_MS
        vm.onForeground()
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(listOf(0 to false, 0 to true), calls)
        vm.setTab(DownloadKind.TORRENT)
        advanceUntilIdle()
        assertEquals(2, calls.size)
        assertEquals(100, vm.state.value.downloads.size)
    }

}
