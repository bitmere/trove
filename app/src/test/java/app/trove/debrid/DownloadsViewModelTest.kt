package app.trove.debrid

import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.data.defaultPreferWords
import app.trove.debrid.ui.downloads.DownloadsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signed out when no provider`() = runTest {
        val repo = FakeRepository(provider = null)
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.signedOut)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `loads downloads and filters by tab and query`() = runTest {
        val repo = FakeRepository(
            provider = FakeProvider(
                downloads = mutableListOf(
                    sampleDownload(id = "1", name = "Dune Part Two 2024"),
                    sampleDownload(id = "2", name = "The Last of Us S02E05", kind = DownloadKind.WEB),
                    sampleDownload(id = "3", name = "Dune 1984", status = DownloadStatus.DOWNLOADING),
                ),
            ),
        )
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        assertFalse(vm.state.value.signedOut)
        assertEquals(3, vm.state.value.downloads.size)

        // Default tab is Torrents: 2 rows.
        assertEquals(2, vm.visibleDownloads(vm.state.value).size)
        vm.setTab(DownloadKind.WEB)
        assertEquals(1, vm.visibleDownloads(vm.state.value).size)
        assertEquals("2", vm.visibleDownloads(vm.state.value).first().id)

        vm.setTab(DownloadKind.TORRENT)
        vm.setQuery("dune 1984")
        assertEquals(1, vm.visibleDownloads(vm.state.value).size)
        vm.setQuery("zzz")
        assertEquals(0, vm.visibleDownloads(vm.state.value).size)
    }

    @Test
    fun `pager pages filter independently of selection and retain query after refresh`() = runTest {
        val rows = DownloadKind.entries.map { kind ->
            sampleDownload(id = "same-id", name = "Match ${kind.name}", kind = kind)
        }
        val provider = FakeProvider(downloads = rows.toMutableList())
        val vm = DownloadsViewModel(FakeRepository(provider = provider))
        advanceUntilIdle()
        vm.setQuery("match")

        for (selected in DownloadKind.entries + DownloadKind.TORRENT) {
            vm.setTab(selected)
            assertEquals(selected, vm.state.value.tab)
            // Adjacent pages must show their own rows, even before becoming selected.
            for (page in DownloadKind.entries) {
                assertEquals(listOf(rows[page.ordinal]), vm.visibleDownloads(vm.state.value, page))
            }
        }
        vm.setTab(DownloadKind.WEB)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(DownloadKind.WEB, vm.state.value.tab)
        assertEquals("match", vm.state.value.query)
        assertEquals(listOf(rows[1]), vm.visibleDownloads(vm.state.value))
        vm.setQuery("missing")
        DownloadKind.entries.forEach { page ->
            assertTrue(vm.visibleDownloads(vm.state.value, page).isEmpty())
        }
    }

    @Test
    fun `play with one file emits the stream url`() = runTest {
        val repo = FakeRepository(
            provider = FakeProvider(downloads = mutableListOf(sampleDownload(fileCount = 1))),
        )
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        vm.play(vm.state.value.downloads.first())
        advanceUntilIdle()
        assertEquals("https://example.com/play/abc123", vm.state.value.playUrl)
        assertNull(vm.state.value.filePicker)
    }

    @Test
    fun `failed play shows an error and can be retried`() = runTest {
        val provider = FakeProvider(downloads = mutableListOf(sampleDownload()))
        provider.playFailure = app.trove.debrid.core.DebridError.Provider("stream", "No stream link")
        val vm = DownloadsViewModel(FakeRepository(provider = provider))
        advanceUntilIdle()
        vm.play(vm.state.value.downloads.first())
        advanceUntilIdle()
        assertEquals("Provider error stream: No stream link", vm.state.value.error)
        assertNull(vm.state.value.playUrl)
        provider.playFailure = null
        vm.play(vm.state.value.downloads.first())
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(provider.playUrl, vm.state.value.playUrl)
    }

    @Test
    fun `blank or malformed stream links show an error`() = runTest {
        for (url in listOf("", "not a url", "file:///tmp/video", "https://")) {
            val vm = DownloadsViewModel(FakeRepository(provider = FakeProvider(playUrl = url)))
            advanceUntilIdle()
            vm.play(sampleDownload())
            advanceUntilIdle()
            assertNull(vm.state.value.playUrl)
            assertFalse(vm.state.value.error.isNullOrBlank())
        }
    }

    @Test
    fun `missing provider and thrown play failure show errors`() = runTest {
        val repo = FakeRepository(provider = FakeProvider())
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        repo.provider!!.throwOnPlay = true
        vm.play(sampleDownload())
        advanceUntilIdle()
        assertFalse(vm.state.value.error.isNullOrBlank())
        vm.clearError()
        repo.provider = null
        vm.play(sampleDownload())
        advanceUntilIdle()
        assertFalse(vm.state.value.error.isNullOrBlank())
        assertNull(vm.state.value.playUrl)
    }

    @Test
    fun `player launch error is visible after consuming the url`() = runTest {
        val vm = DownloadsViewModel(FakeRepository())
        advanceUntilIdle()
        vm.play(sampleDownload())
        advanceUntilIdle()
        vm.playbackError("Couldn't open the video player")
        vm.consumePlayUrl()
        assertEquals("Couldn't open the video player", vm.state.value.error)
        assertNull(vm.state.value.playUrl)
    }

    @Test
    fun `play with several files opens the file picker`() = runTest {
        val repo = FakeRepository(
            provider = FakeProvider(downloads = mutableListOf(sampleDownload(fileCount = 3))),
        )
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        vm.play(vm.state.value.downloads.first())
        assertEquals("11", vm.state.value.filePicker?.id)
        assertNull(vm.state.value.playUrl)
        vm.playFile(vm.state.value.downloads.first(), "2")
        advanceUntilIdle()
        assertEquals("https://example.com/play/abc123", vm.state.value.playUrl)
        assertEquals("11", vm.state.value.filePicker?.id)
        vm.dismissFilePicker()
        assertNull(vm.state.value.filePicker)
    }

    @Test
    fun `play does nothing for a download that is not ready`() = runTest {
        val repo = FakeRepository(
            provider = FakeProvider(
                downloads = mutableListOf(sampleDownload(status = DownloadStatus.DOWNLOADING)),
            ),
        )
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        vm.play(vm.state.value.downloads.first())
        advanceUntilIdle()
        assertNull(vm.state.value.playUrl)
        assertNull(vm.state.value.filePicker)
    }

    @Test
    fun `delete asks first, then removes and refreshes`() = runTest {
        val fake = FakeProvider(
            downloads = mutableListOf(
                sampleDownload(id = "1"),
                sampleDownload(id = "2", name = "Other"),
            ),
        )
        val repo = FakeRepository(provider = fake)
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        vm.requestDelete(vm.state.value.downloads.first())
        assertEquals("1", vm.state.value.deleteConfirm?.id)
        assertEquals(2, vm.state.value.downloads.size)
        vm.confirmDelete()
        advanceUntilIdle()
        assertNull(vm.state.value.deleteConfirm)
        assertEquals(1, vm.state.value.downloads.size)
        assertEquals(listOf("1"), fake.deletedIds)
    }

    @Test
    fun `account changes cannot play or delete an old row in the replacement account`() = runTest {
        val old = FakeProvider(downloads = mutableListOf(sampleDownload(id = "1", name = "Old account")))
        val replacement = FakeProvider(downloads = mutableListOf(sampleDownload(id = "1", name = "New account")))
        val repo = FakeRepository(provider = old)
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        val row = vm.state.value.downloads.single()
        vm.requestDelete(row)
        repo.provider = replacement
        vm.confirmDelete()
        vm.play(row)
        advanceUntilIdle()
        assertTrue(replacement.deletedIds.isEmpty())
        assertTrue(replacement.playedFileIds.isEmpty())
        assertNull(vm.state.value.playUrl)
        vm.refresh()
        advanceUntilIdle()
        assertEquals("New account", vm.state.value.downloads.single().name)
        assertFalse(vm.isCurrentDownload(row))
    }

    @Test
    fun `closing details during file lookup leaves the sheet closed`() = runTest {
        val details = sampleDownload()
        val gate = CompletableDeferred<Unit>()
        val provider = object : app.trove.debrid.core.DebridProvider by FakeProvider() {
            override suspend fun getDownload(id: String): Result<app.trove.debrid.core.DebridDownload> {
                gate.await()
                return Result.success(details)
            }
        }
        val repo = object : app.trove.debrid.data.TroveRepository by FakeRepository() {
            override suspend fun loadConnection() = app.trove.debrid.data.SavedConnection(null, true, provider)
        }
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        vm.openDownload(details.copy(files = emptyList()))
        runCurrent()
        vm.dismissFilePicker()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.state.value.filePicker)
        assertNull(vm.state.value.resolvingDownload)
    }

    @Test
    fun `refresh of a loaded list keeps rows and uses the pull indicator`() = runTest {
        val repo = FakeRepository(
            provider = FakeProvider(
                downloads = mutableListOf(sampleDownload(id = "1")),
            ),
        )
        repo.savedKey = "key"
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(1, vm.state.value.downloads.size)
        assertEquals("Essential", vm.state.value.accountLabel)

        vm.refresh()
        // Synchronous part: rows stay, no full-screen spinner.
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.refreshing)
        assertEquals(1, vm.state.value.downloads.size)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
        assertEquals(1, vm.state.value.downloads.size)
    }
    @Test
    fun `card opens files without playing and preserves long press deletion`() = runTest {
        val vm = DownloadsViewModel(FakeRepository())
        advanceUntilIdle()
        for (count in listOf(1, 3)) {
            val download = sampleDownload(fileCount = count)
            vm.openDownload(download)
            assertEquals(download, vm.state.value.filePicker)
            assertNull(vm.state.value.playUrl)
            vm.dismissFilePicker()
            assertNull(vm.state.value.filePicker)
            vm.requestDelete(download)
            assertEquals(download, vm.state.value.deleteConfirm)
            vm.dismissDelete()
        }
    }

    @Test
    fun `empty file list is refreshed before play instead of guessing file zero`() = runTest {
        val fresh = sampleDownload(fileCount = 3)
        val vm = DownloadsViewModel(FakeRepository(provider = FakeProvider(downloads = mutableListOf(fresh))))
        advanceUntilIdle()
        vm.play(fresh.copy(files = emptyList()))
        advanceUntilIdle()
        assertEquals(fresh, vm.state.value.filePicker)
        assertNull(vm.state.value.playUrl)
    }

    @Test
    fun `card can show incomplete download but cannot play its files`() = runTest {
        val vm = DownloadsViewModel(FakeRepository())
        advanceUntilIdle()
        val download = sampleDownload(status = DownloadStatus.DOWNLOADING)
        vm.openDownload(download)
        assertEquals(download, vm.state.value.filePicker)
        vm.playFile(download, "1")
        advanceUntilIdle()
        assertNull(vm.state.value.playUrl)
    }

    @Test
    fun `missing list files resolve to the real file ID or show no files`() = runTest {
        for (count in listOf(0, 1)) {
            val fresh = sampleDownload(fileCount = count).let { download ->
                download.copy(files = download.files.map { it.copy(id = "7") })
            }
            val provider = FakeProvider(downloads = mutableListOf(fresh))
            val vm = DownloadsViewModel(FakeRepository(provider = provider))
            advanceUntilIdle()
            vm.play(fresh.copy(files = emptyList()))
            advanceUntilIdle()
            assertEquals(if (count == 1) listOf("7") else emptyList<String>(), provider.playedFileIds)
            if (count == 0) {
                assertTrue(vm.state.value.error!!.contains("No files"))
                assertNull(vm.state.value.playUrl)
            } else assertEquals(provider.playUrl, vm.state.value.playUrl)
        }
    }

    @Test
    fun `rejected playback key offers Settings and never emits a URL`() = runTest {
        val provider = FakeProvider()
        provider.playFailure = app.trove.debrid.core.DebridError.AuthInvalid()
        val vm = DownloadsViewModel(FakeRepository(provider = provider))
        advanceUntilIdle()
        vm.play(sampleDownload())
        advanceUntilIdle()
        assertNull(vm.state.value.playUrl)
        assertTrue(vm.state.value.authenticationRequired)
        assertTrue(vm.state.value.error!!.contains("Settings"))
        assertTrue(vm.state.value.error!!.contains("re-enter"))
        vm.clearError()
        assertFalse(vm.state.value.authenticationRequired)
    }

    @Test
    fun `bearer playback link is transient and redacted from state diagnostics`() = runTest {
        val provider = FakeProvider()
        val link = "https://example.com/requestdl?token=secret"
        provider.playUrl = link
        val vm = DownloadsViewModel(FakeRepository(provider = provider))
        advanceUntilIdle()
        vm.play(sampleDownload())
        advanceUntilIdle()
        assertEquals(link, vm.state.value.playUrl)
        assertFalse(vm.state.value.toString().contains(link))
        assertNull(vm.state.value.error)
        vm.consumePlayUrl()
        assertNull(vm.state.value.playUrl)
    }

    @Test
    fun `reset clears query, restores newest and persists it`() = runTest {
        val repo = FakeRepository(
            provider = FakeProvider(downloads = mutableListOf(sampleDownload(id = "1"))),
        )
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        vm.setSort(DownloadSort.OLDEST)
        vm.setQuery("dune")
        advanceUntilIdle()
        assertEquals(DownloadSort.OLDEST, vm.state.value.sort)
        assertEquals(DownloadSort.OLDEST, repo.savedSort)

        vm.resetSearch()
        advanceUntilIdle()
        assertEquals("", vm.state.value.query)
        assertEquals(DownloadSort.NEWEST, vm.state.value.sort)
        assertEquals(DownloadSort.NEWEST, repo.savedSort)
    }

    @Test
    fun `reset is a no-op when search is already clean`() = runTest {
        val repo = FakeRepository()
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        vm.resetSearch()
        advanceUntilIdle()
        assertEquals("", vm.state.value.query)
        assertEquals(DownloadSort.NEWEST, vm.state.value.sort)
        assertEquals(DownloadSort.NEWEST, repo.savedSort)
    }

    @Test
    fun `prefer words rank matching downloads first`() = runTest {
        val repo = FakeRepository(
            provider = FakeProvider(downloads = mutableListOf(
                sampleDownload(id = "1", name = "Dune Part Two 2024 1080p"),
                sampleDownload(id = "2", name = "Dune Part Two 2024 x265"),
            )),
        )
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        assertEquals(defaultPreferWords, vm.state.value.preferWords)
        // Same date on both rows, so only the prefer word decides the order.
        assertEquals(listOf("2", "1"), vm.visibleDownloads(vm.state.value).map { it.id })
    }

    @Test
    fun `show clutter files loads into state and follows settings changes`() = runTest {
        val repo = FakeRepository()
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        assertFalse(vm.state.value.showClutterFiles)

        repo.savedShowClutterFiles = true
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.showClutterFiles)
    }

    @Test
    fun `play keeps details visible while resolving and suppresses duplicate taps`() = runTest {
        val provider = FakeProvider()
        val vm = DownloadsViewModel(FakeRepository(provider = provider))
        advanceUntilIdle()
        val download = sampleDownload()
        vm.openDownload(download)
        vm.playFile(download, download.files.first().id)
        assertEquals(download.kind to download.id, vm.state.value.resolvingDownload)
        assertEquals(download, vm.state.value.filePicker)
        vm.playFile(download, download.files.first().id)
        advanceUntilIdle()
        assertEquals(1, provider.playedFileIds.size)
        assertNull(vm.state.value.resolvingDownload)
        assertEquals(download, vm.state.value.filePicker)
        assertEquals(download.files.first().name, vm.state.value.playFileName)
        vm.dismissFilePicker()
        vm.consumePlayUrl()
        assertNull(vm.state.value.playFileName)
    }

}
