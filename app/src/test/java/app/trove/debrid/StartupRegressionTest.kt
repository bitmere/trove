package app.trove.debrid

import app.trove.debrid.core.DebridAccount
import app.trove.debrid.core.DebridService
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.data.DebridRepository
import app.trove.debrid.data.DownloadPage
import app.trove.debrid.data.SavedConnection
import app.trove.debrid.data.StoredAccounts
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.settings.SettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupRegressionTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // Preparation must run on the runTest scheduler, as in
    // DownloadsLibraryPerformanceTest: the default Dispatchers.Default
    // escapes virtual time, and its resume onto Main can land after
    // resetMain(), when Main is again the unmocked-Looper MissingMain
    // dispatcher. That resume fails fatally on the Default worker and the
    // shared ExceptionCollector charges it to the next test in the fork
    // (UncaughtExceptionsBeforeTest). Injecting the test dispatcher keeps
    // preparation and the return to Main on the drainable scheduler.
    private fun TestScope.viewModel(repo: TroveRepository) =
        DownloadsViewModel(repo, StandardTestDispatcher(testScheduler))

    private val account = DebridAccount("torbox-1", DebridService.TORBOX, "TorBox (Essential)", 123L, 456L)

    @Test fun `account JSON uses stable names and round trips without reflection`() {
        val encoded = StoredAccounts.encode(listOf(account))
        assertTrue(encoded.contains("\"service\":\"TORBOX\""))
        assertTrue(encoded.contains("\"id\":\"torbox-1\""))
        assertEquals(listOf(account), StoredAccounts.decode(encoded))
        assertEquals(listOf(account.copy(lastValidatedEpochMs = null)),
            StoredAccounts.decode(StoredAccounts.encode(listOf(account.copy(lastValidatedEpochMs = null)))))
    }

    @Test fun `reads pre-upgrade unobfuscated account JSON`() {
        val json = """[{"id":"torbox-1","service":"TORBOX","label":"TorBox (Essential)","addedAtEpochMs":123,"lastValidatedEpochMs":456}]"""
        assertEquals(listOf(account), StoredAccounts.decode(json))
    }

    @Test fun `recovers shipped R8 account fields including stripped id`() {
        // From the existing release mapping: service=a, label=b, added=c,
        // validated=d. R8 removed id because production always used torbox-1.
        val json = """[{"a":"TORBOX","b":"TorBox (Essential)","c":123,"d":456}]"""
        assertEquals(listOf(account), StoredAccounts.decode(json))
    }

    @Test fun `invalid metadata entries do not hide a valid saved account`() {
        val valid = StoredAccounts.encode(listOf(account)).removePrefix("[").removeSuffix("]")
        val json = """[null,{}, {"service":"FUTURE"}, {"service":null}, $valid]"""
        assertEquals(listOf(account), StoredAccounts.decode(json))
        listOf(null, "null", "{}", "broken", "[{}]").forEach {
            assertTrue(StoredAccounts.decode(it).isEmpty())
        }
    }

    @Test fun `damaged account metadata does not discard the saved credential or library`() = runTest {
        val prefs = FakeSharedPreferences().apply { map["accounts"] = "[{}]" }
        val store = app.trove.debrid.core.InMemoryCredentialStore().apply {
            save(DebridRepository.TORBOX_ACCOUNT_ID, "test-key")
        }
        val repo = DebridRepository(prefs, store, FakeHttpTransport(), StandardTestDispatcher(testScheduler))
        val connection = repo.loadConnection()
        assertNull(connection.account)
        assertTrue(connection.hasKey)
        assertNotNull(connection.provider)
        assertEquals("test-key", store.get(DebridRepository.TORBOX_ACCOUNT_ID))
        assertEquals("[{}]", prefs.map["accounts"]) // Reading never rewrites user data.
    }

    @Test fun `slow account access leaves startup available for a loading frame`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val base = FakeRepository()
        var reads = 0
        val repo = object : TroveRepository by base {
            override suspend fun loadConnection(): SavedConnection {
                reads++
                ready.await()
                return SavedConnection(account, true, base.provider)
            }
        }
        val downloads = viewModel(repo)
        val settings = SettingsViewModel(repo)
        assertEquals(0, reads) // Constructors can finish before storage is touched.
        runCurrent()
        assertTrue(downloads.state.value.loading)
        ready.complete(Unit)
        advanceUntilIdle()
        assertFalse(downloads.state.value.loading)
        assertTrue(settings.state.value.torbox.hasKey)
    }

    @Test fun `saved account failure is visible in both models and retry can load`() = runTest {
        var locked = true
        val base = FakeRepository(provider = FakeProvider(downloads = mutableListOf(sampleDownload())))
        val repo = object : TroveRepository by base {
            override suspend fun loadConnection(): SavedConnection {
                if (locked) throw IllegalStateException("private keystore diagnostic")
                return SavedConnection(account, true, base.provider)
            }
        }
        val downloads = viewModel(repo)
        val settings = SettingsViewModel(repo)
        advanceUntilIdle()
        assertFalse(downloads.state.value.loading)
        assertFalse(downloads.state.value.signedOut)
        assertNotNull(downloads.state.value.loadError)
        assertFalse(downloads.state.value.loadError!!.contains("private"))
        assertTrue(settings.state.value.messageIsError)
        locked = false
        downloads.refresh()
        settings.refresh()
        advanceUntilIdle()
        assertEquals(1, downloads.state.value.downloads.size)
        assertNull(downloads.state.value.loadError)
        assertTrue(settings.state.value.torbox.hasKey)
    }

    @Test fun `thrown list error stops loading and preserves rows on refresh`() = runTest {
        var fail = false
        // Override the page seam the ViewModel actually calls: a
        // `by` decorator's listDownloads override is bypassed by the
        // default listDownloadPage (same trap the TroveRepository
        // comment documents for listDownloads), so failing the list
        // must be expressed here.
        val repo = object : TroveRepository by FakeRepository() {
            override suspend fun listDownloadPage(
                provider: app.trove.debrid.core.DebridProvider,
                kind: DownloadKind,
                offset: Int,
                limit: Int,
                bypassCache: Boolean,
            ): Result<DownloadPage> {
                if (fail) throw IllegalStateException("transport failed")
                val rows = if (kind == DownloadKind.TORRENT) listOf(sampleDownload()) else emptyList()
                return Result.success(DownloadPage(rows, true))
            }
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        fail = true
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.refreshing)
        assertEquals(1, vm.state.value.downloads.size)
        assertNotNull(vm.state.value.loadError)
    }
}
