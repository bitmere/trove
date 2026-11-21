package app.trove.debrid

import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.ManualTmdbSearch
import app.trove.debrid.core.MediaCandidate
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.downloads.ManualTmdbSearchStatus
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
    private val auto = MediaCandidate("tt0000001", "Auto Film", 2024, "movie",
        posterUrl = "https://images.example.invalid/auto.jpg", overview = "Automatic synopsis")
    private val manual = MediaCandidate(null, "Manual Film", 2026, "movie", tmdbId = 123,
        posterUrl = "https://images.example.invalid/manual.jpg", overview = "Manual synopsis")

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
        assertEquals(ManualTmdbSearchStatus.FOUND, vm.state.value.manualTmdbSearch[key])
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
        assertEquals(ManualTmdbSearchStatus.FOUND, vm.state.value.manualTmdbSearch[key])
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

    @Test fun `late automatic load does not overwrite a manual search`() = runTest {
        val repo = repoWith()
        repo.manualTmdbResult = ManualTmdbSearch.Found(listOf(manual))
        val deferred = CompletableDeferred<List<MediaCandidate>>()
        repo.candidatesDeferred = deferred
        val vm = viewModel(repo)
        advanceUntilIdle()
        // Open the sheet and let the automatic load start, suspended
        // on the deferred; the manual search then takes ownership.
        vm.openDownload(row)
        runCurrent()
        vm.searchManualTmdbLink(row, "https://themoviedb.org/movie/123")
        advanceUntilIdle()
        assertEquals(listOf(manual), vm.state.value.candidates[key])
        assertEquals(ManualTmdbSearchStatus.FOUND, vm.state.value.manualTmdbSearch[key])
        // The automatic load finishing late must not clobber it.
        deferred.complete(listOf(auto))
        advanceUntilIdle()
        assertEquals(listOf(manual), vm.state.value.candidates[key])
        assertEquals(ManualTmdbSearchStatus.FOUND, vm.state.value.manualTmdbSearch[key])
    }

    @Test fun `dismissing mid-search leaves no stale searching state`() = runTest {
        val repo = repoWith()
        repo.manualTmdbResult = ManualTmdbSearch.Found(listOf(manual))
        val vm = viewModel(repo)
        advanceUntilIdle()
        openSheet(vm)
        vm.searchManualTmdbLink(row, "https://themoviedb.org/movie/123")
        assertEquals(ManualTmdbSearchStatus.SEARCHING, vm.state.value.manualTmdbSearch[key])
        vm.dismissFilePicker()
        advanceUntilIdle()
        assertNull(vm.state.value.manualTmdbSearch[key])
        // The dismissed search never ran its lookup.
        assertTrue(repo.manualTmdbInputs.isEmpty())
        // Reopening the same download starts clean: a new search is
        // accepted and its results publish.
        openSheet(vm)
        assertNull(vm.state.value.manualTmdbSearch[key])
        vm.searchManualTmdbLink(row, "https://themoviedb.org/movie/123")
        advanceUntilIdle()
        assertEquals(listOf(manual), vm.state.value.candidates[key])
        assertEquals(ManualTmdbSearchStatus.FOUND, vm.state.value.manualTmdbSearch[key])
    }

    @Test fun `manual search completing after delete clears its status`() = runTest {
        val repo = repoWith()
        repo.manualTmdbResult = ManualTmdbSearch.Found(listOf(manual))
        val vm = viewModel(repo)
        advanceUntilIdle()
        openSheet(vm)
        vm.searchManualTmdbLink(row, "https://themoviedb.org/movie/123")
        assertEquals(ManualTmdbSearchStatus.SEARCHING, vm.state.value.manualTmdbSearch[key])
        vm.requestDelete(row)
        advanceUntilIdle()
        assertNull(vm.state.value.manualTmdbSearch[key])
    }

    @Test fun `delete cancels a suspended search before reopening the same download`() = runTest {
        val repo = repoWith()
        val deferred = CompletableDeferred<ManualTmdbSearch>()
        repo.manualTmdbDeferred = deferred
        val vm = viewModel(repo)
        advanceUntilIdle()
        openSheet(vm)
        vm.searchManualTmdbLink(row, "123")
        runCurrent()
        assertEquals(1, repo.manualTmdbInputs.size)
        assertEquals(ManualTmdbSearchStatus.SEARCHING, vm.state.value.manualTmdbSearch[key])
        vm.requestDelete(row)
        assertNull(vm.state.value.manualTmdbSearch[key])
        vm.dismissDelete()
        openSheet(vm)
        assertNull(vm.state.value.manualTmdbSearch[key])
        deferred.complete(ManualTmdbSearch.Found(listOf(manual)))
        advanceUntilIdle()
        assertNull(vm.state.value.manualTmdbSearch[key])
        assertEquals(listOf(auto), vm.state.value.candidates[key])
    }

    @Test fun `override resolving state lasts until the slow resolution finishes`() = runTest {
        val repo = repoWith()
        val deferred = CompletableDeferred<app.trove.debrid.core.MediaMetadata?>()
        repo.metadataByTmdbIdDeferred = deferred
        val vm = viewModel(repo)
        advanceUntilIdle()
        openSheet(vm)
        vm.pickCandidate(row, manual)
        assertEquals(setOf(key), vm.state.value.resolvingOverrides)
        runCurrent()
        assertEquals(setOf(key), vm.state.value.resolvingOverrides)
        deferred.complete(null)
        advanceUntilIdle()
        assertTrue(vm.state.value.resolvingOverrides.isEmpty())
    }

    @Test fun `clearing manual feedback preserves found candidates`() = runTest {
        val repo = repoWith()
        repo.manualTmdbResult = ManualTmdbSearch.Found(listOf(manual))
        val vm = viewModel(repo)
        advanceUntilIdle()
        openSheet(vm)
        vm.searchManualTmdbLink(row, "123")
        advanceUntilIdle()
        assertEquals(ManualTmdbSearchStatus.FOUND, vm.state.value.manualTmdbSearch[key])
        vm.clearManualTmdbSearch(row)
        assertNull(vm.state.value.manualTmdbSearch[key])
        assertEquals(listOf(manual), vm.state.value.candidates[key])
        vm.clearManualTmdbSearch(row)
        assertNull(vm.state.value.manualTmdbSearch[key])
        assertEquals(listOf(manual), vm.state.value.candidates[key])
    }

}
