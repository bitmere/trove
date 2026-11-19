package app.trove.debrid

import app.trove.debrid.core.PlayerComponent
import app.trove.debrid.core.VideoPlayer
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val player = PlayerComponent("org.example.player", "org.example.player.VideoActivity")
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // Preparation must run on the runTest scheduler: the default
    // Dispatchers.Default escapes virtual time, and its resume onto Main
    // can land after resetMain(), when Main is again the unmocked-Looper
    // MissingMain dispatcher. That resume fails fatally on the Default
    // worker and the shared ExceptionCollector charges it to the next test
    // in the fork (UncaughtExceptionsBeforeTest), even in a class that
    // never builds a ViewModel. Injecting the test dispatcher keeps
    // preparation, the 250 ms debounce and the return to Main on the
    // drainable scheduler.
    private fun TestScope.viewModel(repo: TroveRepository) =
        DownloadsViewModel(repo, StandardTestDispatcher(testScheduler))

    @Test fun `settings discovers players loads default and can choose then clear`() = runTest {
        val repo = FakeRepository()
        val players = listOf(VideoPlayer(player, "Video player"))
        val vm = SettingsViewModel(repo, PlayerDiscovery { mime ->
            assertEquals("video/mp4", mime)
            players
        })
        advanceUntilIdle()
        assertNull(vm.state.value.defaultPlayer)
        assertEquals(players, vm.state.value.players)
        vm.setDefaultPlayer(player)
        advanceUntilIdle()
        assertEquals(player, vm.state.value.defaultPlayer)
        assertEquals(player, repo.savedPlayer)
        assertFalse(vm.accountChanged.value)
        vm.setDefaultPlayer(null)
        advanceUntilIdle()
        assertNull(repo.savedPlayer)
        assertNull(vm.state.value.defaultPlayer)
    }

    @Test fun `stale saved choice stays visible when discovery returns no players`() = runTest {
        val repo = FakeRepository().apply { savedPlayer = player }
        val vm = SettingsViewModel(repo, PlayerDiscovery { emptyList() })
        advanceUntilIdle()
        assertEquals(player, vm.state.value.defaultPlayer)
        assertEquals(player, repo.savedPlayer)
    }

    @Test fun `cancelled discovery cannot enable the setting while its replacement is loading`() = runTest {
        val first = CompletableDeferred<List<VideoPlayer>>()
        val second = CompletableDeferred<List<VideoPlayer>>()
        var calls = 0
        val vm = SettingsViewModel(FakeRepository(), PlayerDiscovery {
            if (++calls == 1) first.await() else second.await()
        })
        runCurrent()
        vm.refreshPlayers()
        runCurrent()
        assertTrue(vm.state.value.loadingPlayers)
        vm.setDefaultPlayer(player)
        assertFalse(vm.state.value.savingPlayer)
        second.complete(listOf(VideoPlayer(player, "Video player")))
        advanceUntilIdle()
        assertFalse(vm.state.value.loadingPlayers)
    }

    @Test fun `play with is one event and never changes saved default`() = runTest {
        val repo = FakeRepository().apply { savedPlayer = player }
        val vm = viewModel(repo)
        advanceUntilIdle()
        val download = sampleDownload()
        vm.playFile(download, download.files.first().id, askEveryTime = true)
        advanceUntilIdle()
        assertNotNull(vm.state.value.playUrl)
        assertTrue(vm.state.value.askEveryTime)
        assertEquals(player, repo.savedPlayer)
        vm.consumePlayUrl()
        assertFalse(vm.state.value.askEveryTime)
        vm.playFile(download, download.files.first().id)
        advanceUntilIdle()
        assertNotNull(vm.state.value.playUrl)
        assertFalse(vm.state.value.askEveryTime)
        assertEquals(player, repo.savedPlayer)
    }
}
