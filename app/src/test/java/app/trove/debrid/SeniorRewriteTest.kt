package app.trove.debrid

import app.trove.debrid.core.*
import app.trove.debrid.data.*
import app.trove.debrid.ui.add.AddViewModel
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.downloads.downloadFormat
import app.trove.debrid.ui.settings.SettingsUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SeniorRewriteTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `throwing add is recoverable and a duplicate submission is suppressed`() = runTest {
        var attempts = 0
        val base = FakeRepository()
        val repo = object : TroveRepository by base {
            override suspend fun addClassified(provider: DebridProvider, classified: ClassifiedShare): Result<DebridDownload> {
                attempts++
                delay(100)
                if (attempts == 1) throw IllegalStateException("transport failure")
                return Result.success(sampleDownload())
            }
        }
        val vm = AddViewModel(repo)
        vm.setText("https://example.com/file")
        advanceUntilIdle()
        vm.add(); vm.add()
        advanceUntilIdle()
        assertEquals(1, attempts)
        assertFalse(vm.state.value.adding)
        assertNotNull(vm.state.value.error)
        vm.add()
        advanceUntilIdle()
        assertTrue(vm.state.value.added)
        vm.add()
        assertEquals(2, attempts)
    }

    @Test fun `real authentication failure in Add points to Settings`() = runTest {
        val repo = FakeRepository().apply { provider!!.addFailure = DebridError.AuthInvalid() }
        val vm = AddViewModel(repo)
        vm.setText("https://example.com/file"); advanceUntilIdle()
        vm.add(); advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("Settings"))
        assertFalse(vm.state.value.adding)
    }

    @Test fun `details load has a synchronous busy guard`() = runTest {
        val fresh = sampleDownload()
        val vm = DownloadsViewModel(FakeRepository(provider = FakeProvider(downloads = mutableListOf(fresh))))
        advanceUntilIdle()
        vm.openDownload(fresh.copy(files = emptyList()))
        assertEquals(fresh.kind to fresh.id, vm.state.value.resolvingDownload)
        vm.openDownload(sampleDownload(id = "different", fileCount = 2))
        assertEquals(fresh.id, vm.state.value.filePicker?.id)
        advanceUntilIdle()
        assertNull(vm.state.value.resolvingDownload)
        assertEquals(fresh, vm.state.value.filePicker)
    }

    @Test fun `sign out cannot be overwritten by a late refresh`() = runTest {
        val base = FakeRepository()
        val repo = object : TroveRepository by base {
            override suspend fun listDownloads(provider: DebridProvider): Result<List<DebridDownload>> {
                withContext(NonCancellable) { delay(1000) }
                return Result.success(listOf(sampleDownload()))
            }
        }
        val vm = DownloadsViewModel(repo)
        runCurrent()
        base.provider = null
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.signedOut)
        assertTrue(vm.state.value.downloads.isEmpty())
    }

    @Test fun `download kind reaches the deletion repository boundary`() = runTest {
        var deleted: DebridDownload? = null
        val repo = object : TroveRepository by FakeRepository() {
            override suspend fun delete(provider: DebridProvider, download: DebridDownload): Result<Unit> {
                deleted = download
                return Result.success(Unit)
            }
        }
        val vm = DownloadsViewModel(repo); advanceUntilIdle()
        val web = sampleDownload(kind = DownloadKind.WEB)
        vm.requestDelete(web); vm.confirmDelete(); advanceUntilIdle()
        assertEquals(web, deleted)
    }

    @Test fun `cover format uses actual file metadata and handles mixed or missing formats`() {
        val file = sampleDownload()
        assertEquals("MKV", downloadFormat(file))
        assertEquals("FILE", downloadFormat(file.copy(files = emptyList())))
        assertEquals("MIXED", downloadFormat(file.copy(files = file.files + DebridFile("2", "folder/subtitles.srt", 10))))
        assertEquals("FILE", downloadFormat(file.copy(files = listOf(DebridFile("1", "no extension", 0)))))
    }

    @Test fun `account state diagnostics never contain the key`() {
        val secret = "private-test-value"
        assertFalse(SettingsUiState(keyField = secret, showKey = true).toString().contains(secret))
    }

    @Test fun `provider lifetime retains budgets until credential changes`() {
        val store = InMemoryCredentialStore()
        val repo = DebridRepository(FakeSharedPreferences(), store, FakeHttpTransport(), Dispatchers.Unconfined)
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "first")
        val first = repo.provider()
        assertSame(first, repo.provider())
        store.save(DebridRepository.TORBOX_ACCOUNT_ID, "second")
        assertNotSame(first, repo.provider())
        repo.signOutTorbox()
        assertNull(repo.provider())
    }

    @Test fun `URL file download failure is a Result instead of a thrown exception`() = runTest {
        val repo = DebridRepository(FakeSharedPreferences(), InMemoryCredentialStore(),
            FakeHttpTransport { throw java.io.IOException("offline") }, Dispatchers.Unconfined)
        val result = repo.addClassified(FakeProvider(), ClassifiedShare.TorrentUrl("https://example.com/a.torrent"))
        assertTrue(result.isFailure)
    }
}
