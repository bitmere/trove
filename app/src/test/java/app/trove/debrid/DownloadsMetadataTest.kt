package app.trove.debrid

import app.trove.debrid.core.*
import app.trove.debrid.data.MetadataOverride
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsMetadataTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

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

    @Test fun `rows appear before metadata and refresh cancellation cannot apply stale metadata`() = runTest {
        val row = sampleDownload().copy(name = "Baththa.2026.1080p.mkv")
        val provider = FakeProvider(downloads = mutableListOf(row))
        val gate = CompletableDeferred<MediaMetadata?>()
        val repo = object : TroveRepository by FakeRepository(provider = provider) {
            override suspend fun resolveMetadata(query: MediaQuery): MediaMetadata? {
                assertEquals(MediaQuery("Baththa", 2026, null), query)
                return gate.await()
            }
        }
        val vm = viewModel(repo)
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals(listOf(row), vm.state.value.downloads)
        assertTrue(vm.state.value.metadata.isEmpty())
        provider.downloads.clear()
        vm.refresh()
        runCurrent()
        gate.complete(MediaMetadata("Baththa", 2026, "movie"))
        advanceUntilIdle()
        assertTrue(vm.state.value.downloads.isEmpty())
        assertTrue(vm.state.value.metadata.isEmpty())
    }

    @Test fun `metadata failure leaves downloads and playback available without load error`() = runTest {
        val row = sampleDownload()
        val repo = object : TroveRepository by FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            override suspend fun resolveMetadata(query: MediaQuery): MediaMetadata? = error("Synthetic failure")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(listOf(row), vm.state.value.downloads)
        assertNull(vm.state.value.loadError)
        assertNull(vm.state.value.error)
    }
    @Test fun `unchanged refresh retains metadata without resolving again but renamed rows resolve`() = runTest {
        val provider = FakeProvider(downloads = mutableListOf(sampleDownload()))
        var calls = 0
        val repo = object : TroveRepository by FakeRepository(provider = provider) {
            override suspend fun resolveMetadata(query: MediaQuery): MediaMetadata {
                calls++
                return MediaMetadata(query.title, query.year, query.type)
            }
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        val metadata = vm.state.value.metadata
        provider.downloads[0] = provider.downloads[0].copy(progress = .5f)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(metadata, vm.state.value.metadata)
        provider.downloads[0] = provider.downloads[0].copy(name = "Different.2026.mkv")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, calls)
        provider.downloads.clear()
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.metadata.isEmpty())
    }

    @Test fun `metadata misses are retried on refresh`() = runTest {
        var calls = 0
        val repo = object : TroveRepository by FakeRepository(provider = FakeProvider(downloads = mutableListOf(sampleDownload()))) {
            override suspend fun resolveMetadata(query: MediaQuery): MediaMetadata? { calls++; return null }
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test fun `opening a download without metadata loads picker candidates`() = runTest {
        val row = sampleDownload().copy(name = "Example Film.2026.1080p.mkv")
        val candidates = listOf(
            MediaCandidate("tt0000001", "Example Film", 2026, "movie"),
            MediaCandidate("tt0000002", "Example Film", 2026, "movie"),
        )
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))).apply {
            candidatesByQuery = { candidates }
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.metadata.isEmpty())
        vm.openDownload(row)
        advanceUntilIdle()
        assertEquals(candidates, vm.state.value.candidates[DownloadKind.TORRENT to row.id])
    }

    @Test fun `no candidates load when the automatic match already resolved`() = runTest {
        val row = sampleDownload().copy(name = "Baththa.2026.1080p.mkv")
        var candidateCalls = 0
        val repo = object : TroveRepository by FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            override suspend fun resolveMetadata(query: MediaQuery) = MediaMetadata("Baththa", 2026, "movie")
            override suspend fun resolveMetadataCandidates(query: MediaQuery): List<MediaCandidate> {
                candidateCalls++
                return listOf(MediaCandidate("tt0000001", "Baththa", 2026, "movie"))
            }
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openDownload(row)
        advanceUntilIdle()
        assertEquals(0, candidateCalls)
        assertTrue(vm.state.value.candidates.isEmpty())
    }

    @Test fun `hydration applies a stored override so the card shows the picked title`() = runTest {
        val row = sampleDownload().copy(name = "Example Film.2026.1080p.mkv")
        val key = DownloadKind.TORRENT to row.id
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))).apply {
            metadataById = mapOf("tt0000002" to "movie" to MediaMetadata("Example Film", 2026, "movie", imdbId = "tt0000002"))
            savedMetadataOverrides[key] = MetadataOverride("tt0000002", "movie", "Example Film")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals("Example Film", vm.state.value.metadata[key]?.title)
        assertEquals("tt0000002", vm.state.value.metadataOverrides[key]?.imdbId)
    }

    @Test fun `picking a candidate stores the override and shows the picked title`() = runTest {
        val row = sampleDownload().copy(name = "Example Film.2026.1080p.mkv")
        val key = DownloadKind.TORRENT to row.id
        val candidate = MediaCandidate("tt0000002", "Example Film", 2026, "movie")
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))).apply {
            candidatesByQuery = { listOf(candidate) }
            metadataById = mapOf("tt0000002" to "movie" to MediaMetadata("Example Film", 2026, "movie", imdbId = "tt0000002"))
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.openDownload(row)
        advanceUntilIdle()
        vm.pickCandidate(row, candidate)
        advanceUntilIdle()
        assertEquals(MetadataOverride("tt0000002", "movie", "Example Film"), repo.savedMetadataOverrides[key])
        assertEquals("Example Film", vm.state.value.metadata[key]?.title)
        assertEquals("tt0000002", vm.state.value.metadataOverrides[key]?.imdbId)
    }

    @Test fun `clearing an override restores the parsed title and reloads candidates`() = runTest {
        val row = sampleDownload().copy(name = "Example Film.2026.1080p.mkv")
        val key = DownloadKind.TORRENT to row.id
        val candidate = MediaCandidate("tt0000002", "Example Film", 2026, "movie")
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))).apply {
            candidatesByQuery = { listOf(candidate) }
            metadataById = mapOf("tt0000002" to "movie" to MediaMetadata("Example Film", 2026, "movie", imdbId = "tt0000002"))
            savedMetadataOverrides[key] = MetadataOverride("tt0000002", "movie", "Example Film")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals("Example Film", vm.state.value.metadata[key]?.title)
        vm.openDownload(row)
        advanceUntilIdle()
        vm.clearMetadataOverride(row)
        advanceUntilIdle()
        assertNull(repo.savedMetadataOverrides[key])
        assertNull(vm.state.value.metadata[key])
        assertNull(vm.state.value.metadataOverrides[key])
        assertEquals(listOf(candidate), vm.state.value.candidates[key])
    }

}
