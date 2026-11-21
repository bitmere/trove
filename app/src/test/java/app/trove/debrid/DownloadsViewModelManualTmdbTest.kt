package app.trove.debrid

import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.ManualTmdbSearch
import app.trove.debrid.core.MediaCandidate
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.downloads.ManualTmdbSearchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Hand-entered TMDB search in the match picker: explicit user
 *  intent replaces the shown candidates, with feedback for no match
 *  and invalid input. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelManualTmdbTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun TestScope.viewModel(repo: TroveRepository) =
        DownloadsViewModel(repo, StandardTestDispatcher(testScheduler))

    private val row = sampleDownload()
    private val key = DownloadKind.TORRENT to "11"
    private val auto = MediaCandidate("tt0000001", "Auto Film", 2024, "movie")
    private val manual = MediaCandidate(null, "Manual Film", 2026, "movie", tmdbId = 123)

    private fun repoWith(autoCandidates: List<MediaCandidate> = listOf(auto)): FakeRepository {
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(row)))
        repo.candidatesByQuery = { autoCandidates }
        repo.tmdbConfigured = true
        return repo
    }

    private fun TestScope.openSheet(vm: DownloadsViewModel) {
        vm.openDownload(row)
        advanceUntilIdle()
    }

    @Test fun `manual search replaces the candidate list even when automatic candidates exist`() = runTest {
        val repo = repoWith()
        repo.manualTmdbResult = ManualTmdbSearch.Found(listOf(manual))
        val vm = viewModel(repo)
        advanceUntilIdle()
        openSheet(vm)
        assertEquals(listOf(auto), vm.state.value.candidates[key])
        vm.searchManualTmdbLink(row, "https://themoviedb.org/movie/123")
        advanceUntilIdle()
        assertEquals(listOf(manual), vm.state.value.candidates[key])
        assertNull(vm.state.value.manualTmdbSearch[key])
        // "Dune Part Two 2024" parses with no media type, so the
        // fallback type is null.
        assertEquals(listOf("https://themoviedb.org/movie/123" to null), repo.manualTmdbInputs)
    }

    @Test fun `searching status is set while the search runs`() = runTest {
        val repo = repoWith()
        repo.manualTmdbResult = ManualTmdbSearch.Found(listOf(manual))
        val vm = viewModel(repo)
        advanceUntilIdle()
        openSheet(vm)
        vm.searchManualTmdbLink(row, "123")
        assertEquals(ManualTmdbSearchStatus.SEARCHING, vm.state.value.manualTmdbSearch[key])
        advanceUntilIdle()
        assertEquals(listOf(manual), vm.state.value.candidates[key])
        assertNull(vm.state.value.manualTmdbSearch[key])
    }

    @Test fun `invalid input sets INVALID and keeps the candidates`() = runTest {
        val repo = repoWith()
        repo.manualTmdbResult = ManualTmdbSearch.Invalid
        val vm = viewModel(repo)
        advanceUntilIdle()
        openSheet(vm)
        vm.searchManualTmdbLink(row, "   ")
        advanceUntilIdle()
        assertEquals(ManualTmdbSearchStatus.INVALID, vm.state.value.manualTmdbSearch[key])
        assertEquals(listOf(auto), vm.state.value.candidates[key])
    }

    @Test fun `no match sets NO_MATCH and keeps the candidates`() = runTest {
        val repo = repoWith()
        repo.manualTmdbResult = ManualTmdbSearch.NoMatch
        val vm = viewModel(repo)
        advanceUntilIdle()
        openSheet(vm)
        vm.searchManualTmdbLink(row, "No Such Film")
        advanceUntilIdle()
        assertEquals(ManualTmdbSearchStatus.NO_MATCH, vm.state.value.manualTmdbSearch[key])
        assertEquals(listOf(auto), vm.state.value.candidates[key])
    }

    @Test fun `manual search for a closed sheet does nothing`() = runTest {
        val repo = repoWith()
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.searchManualTmdbLink(row, "123")
        advanceUntilIdle()
        assertTrue(repo.manualTmdbInputs.isEmpty())
        assertTrue(vm.state.value.manualTmdbSearch.isEmpty())
    }
}
