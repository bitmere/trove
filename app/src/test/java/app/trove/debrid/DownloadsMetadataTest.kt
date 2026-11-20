package app.trove.debrid

import app.trove.debrid.core.*
import app.trove.debrid.data.MetadataOverride
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.downloads.PosterSlot
import app.trove.debrid.ui.downloads.posterUrl
import kotlinx.coroutines.flow.MutableStateFlow
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
        val repo = object : FakeRepository(provider = provider) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata? {
                assertEquals("Baththa.2026.1080p.mkv", name)
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
        val repo = object : FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata? = error("Synthetic failure")
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(listOf(row), vm.state.value.downloads)
        assertNull(vm.state.value.loadError)
        assertNull(vm.state.value.error)
        assertEquals(EnrichmentProvenance.UNAVAILABLE, vm.state.value.enrichment[row.kind to row.id])
    }
    @Test fun `unchanged refresh retains metadata without resolving again but renamed rows resolve`() = runTest {
        val provider = FakeProvider(downloads = mutableListOf(sampleDownload()))
        var calls = 0
        val repo = object : FakeRepository(provider = provider) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata {
                calls++
                val query = parseMediaName(name)
                return MediaMetadata(query.title, query.year, query.type, "https://example.invalid/poster.jpg")
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

    @Test fun `only explicit refresh bypasses the metadata miss cache`() = runTest {
        val bypasses = mutableListOf<Boolean>()
        val repo = object : FakeRepository(provider = FakeProvider(downloads = mutableListOf(sampleDownload()))) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata? {
                bypasses += bypassMiss
                return null
            }
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        vm.refreshFromUser()
        advanceUntilIdle()
        vm.onAdded(DownloadKind.TORRENT)
        advanceUntilIdle()
        assertEquals(listOf(false, false, true, false), bypasses)
    }

    @Test fun `explicit refresh retries held posterless metadata for visible and offscreen rows`() = runTest {
        val rows = List(6) { sampleDownload(id = it.toString()) }
        val attempts = mutableListOf<Boolean>()
        val artwork = "https://example.invalid/fresh.jpg"
        val repo = object : FakeRepository(provider = FakeProvider(downloads = rows.toMutableList())) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata {
                attempts += bypassMiss
                return MediaMetadata(name, null, null, artwork.takeIf { bypassMiss })
            }
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(rows.size, vm.state.value.metadata.size)
        assertTrue(vm.state.value.metadata.values.none { it.posterUrl != null })
        vm.refreshFromUser()
        advanceUntilIdle()
        assertEquals(List(rows.size) { false } + List(rows.size) { true }, attempts)
        assertTrue(vm.state.value.metadata.values.all { it.posterUrl == artwork })
    }

    @Test fun `explicit refresh retries an expired cooldown even when the row already has artwork`() = runTest {
        val row = sampleDownload()
        val key = row.kind to row.id
        val legacy = MediaMetadata("Example", null, null, "https://example.invalid/cached.jpg")
        val fresh = legacy.copy(posterUrl = "https://example.invalid/refreshed.jpg")
        val revalidation = CompletableDeferred<MetadataLookup?>()
        val repo = object : FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            var lookups = 0
            override suspend fun lookupMetadataForRelease(
                name: String, bypassMiss: Boolean, onPoster: suspend (String) -> Unit,
            ): MetadataLookup {
                lookups++
                return if (bypassMiss) MetadataLookup.from(fresh)
                else MetadataLookup.from(legacy).copy(revalidation = revalidation)
            }
        }
        var time = 1000L
        val vm = DownloadsViewModel(repo, StandardTestDispatcher(testScheduler), now = { time })
        runCurrent()
        revalidation.complete(MetadataLookup.from(legacy).copy(retryAfter = 5000))
        runCurrent()
        vm.refreshFromUser()
        advanceUntilIdle()
        assertEquals(1, repo.lookups)
        assertEquals(legacy.posterUrl, vm.state.value.posterUrl(key))
        time = 5000
        vm.refreshFromUser()
        advanceUntilIdle()
        assertEquals(2, repo.lookups)
        assertEquals(fresh.posterUrl, vm.state.value.posterUrl(key))
    }

    @Test fun `image failure updates the held poster and keeps the resolved identity`() = runTest {
        val row = sampleDownload()
        val key = row.kind to row.id
        val primary = MediaMetadata("Example", null, null, "https://example.invalid/tmdb.jpg",
            provenance = EnrichmentProvenance.TMDB)
        val alternate = primary.copy(posterUrl = "https://example.invalid/cinemeta.jpg",
            provenance = EnrichmentProvenance.TMDB_CINEMETA_POSTER)
        val repo = object : FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean) = primary
            override suspend fun replaceFailedPoster(name: String, metadata: MediaMetadata): MediaMetadata {
                assertEquals(primary, metadata)
                return alternate
            }
        }
        val vm = viewModel(repo)
        runCurrent()
        vm.onPosterFailure(row, primary.posterUrl!!)
        assertEquals(alternate, vm.state.value.metadata[key])
        assertEquals(alternate.provenance, vm.state.value.enrichment[key])
    }

    @Test fun `warm viewport exposes cached posters before revalidation and keeps their count`() = runTest {
        val rows = List(4) { sampleDownload(id = it.toString()).copy(name = "Example ${it + 1}") }
        val revalidations = rows.associate { it.name to CompletableDeferred<MetadataLookup?>() }
        val repo = object : FakeRepository(provider = FakeProvider(downloads = rows.toMutableList())) {
            override suspend fun lookupMetadataForRelease(
                name: String, bypassMiss: Boolean, onPoster: suspend (String) -> Unit,
            ): MetadataLookup = MetadataLookup.from(
                MediaMetadata(name, null, null, "https://example.invalid/${name.last()}.jpg"),
            ).copy(revalidation = revalidations.getValue(name))
        }
        val vm = viewModel(repo)
        runCurrent()
        fun posters() = rows.mapNotNull { vm.state.value.posterUrl(it.kind to it.id) }
        val launch = posters()
        assertEquals(4, launch.size)
        val replacement = MediaMetadata(rows[0].name, null, null, "https://example.invalid/new.jpg")
        revalidations.getValue(rows[0].name).complete(MetadataLookup.from(replacement))
        revalidations.getValue(rows[1].name).cancel()
        revalidations.getValue(rows[2].name).complete(null)
        revalidations.getValue(rows[3].name).complete(null)
        runCurrent()
        assertEquals(listOf(replacement.posterUrl) + launch.drop(1), posters())
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
        assertEquals(EnrichmentProvenance.NO_MATCH, vm.state.value.enrichment[row.kind to row.id])
        vm.openDownload(row)
        advanceUntilIdle()
        assertEquals(candidates, vm.state.value.candidates[DownloadKind.TORRENT to row.id])
    }

    @Test fun `no candidates load when the automatic match already resolved`() = runTest {
        val row = sampleDownload().copy(name = "Baththa.2026.1080p.mkv")
        var candidateCalls = 0
        val repo = object : FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean) = MediaMetadata("Baththa", 2026, "movie")
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
        assertEquals(EnrichmentProvenance.CINEMETA, vm.state.value.enrichment[key])
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

    @Test fun `library refresh retains a known poster while an image request can be in flight`() = runTest {
        val row = sampleDownload()
        val key = row.kind to row.id
        val poster = "https://images.example.invalid/row.jpg"
        val repo = object : FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean) =
                MediaMetadata(name, null, null, posterUrl = poster)
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(poster, vm.state.value.posterUrl(key))

        vm.refreshFromUser()
        assertEquals(poster, vm.state.value.posterUrl(key))
        runCurrent()
        advanceUntilIdle()
        assertEquals(poster, vm.state.value.posterUrl(key))
    }

    @Test fun `order changes rehydrate loaded rows without changing the library`() = runTest {
        val row = sampleDownload()
        val key = row.kind to row.id
        val order = MutableStateFlow(0)
        val gate = CompletableDeferred<Unit>()
        val repo = object : FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            override val metadataOrderChanges = order
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata {
                if (order.value == 1) gate.await()
                val source = if (order.value == 1) EnrichmentProvenance.TMDB else EnrichmentProvenance.CINEMETA
                val poster = if (order.value == 0) "https://example.invalid/cached.jpg" else null
                return MediaMetadata(name, null, null, posterUrl = poster, provenance = source)
            }
        }
        val vm = viewModel(repo)
        runCurrent()
        assertEquals(EnrichmentProvenance.CINEMETA, vm.state.value.enrichment[key])
        val displayed = PosterSlot<String>().afterLoad(vm.state.value.posterUrl(key)!!, "cached bitmap")
        order.value++
        runCurrent()
        assertEquals(listOf(row), vm.state.value.downloads)
        assertNull(vm.state.value.enrichment[key])
        assertTrue(displayed.hasPoster(vm.state.value.posterUrl(key)))
        gate.complete(Unit)
        runCurrent()
        assertEquals(EnrichmentProvenance.TMDB, vm.state.value.enrichment[key])
        assertNull(vm.state.value.posterUrl(key))
        assertTrue(displayed.hasPoster(vm.state.value.posterUrl(key)))
        order.value++
        runCurrent()
        assertEquals(EnrichmentProvenance.CINEMETA, vm.state.value.enrichment[key])
        assertTrue(displayed.hasPoster(vm.state.value.posterUrl(key)))
        order.value++
        runCurrent()
        assertEquals("cached bitmap", displayed.bitmap)
        assertTrue(displayed.hasPoster(vm.state.value.posterUrl(key)))
    }

}
