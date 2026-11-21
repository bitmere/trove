package app.trove.debrid

import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.MediaMetadata
import app.trove.debrid.core.SimklEntry
import app.trove.debrid.core.SimklStatus
import app.trove.debrid.core.SimklSyncFailure
import app.trove.debrid.core.SimklSyncResult
import app.trove.debrid.core.SimklType
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** SIMKL badges in the Downloads list: lookup by enrichment id,
 *  no badge without an id, sync triggers, and a failed sync
 *  keeping the last badges. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelSimklTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun TestScope.viewModel(repo: TroveRepository) =
        DownloadsViewModel(repo, StandardTestDispatcher(testScheduler))

    private fun simklEntry(imdbId: String? = "tt1234567", tmdbId: Int? = 456) = SimklEntry(
        simklId = 123, type = SimklType.SHOW, status = SimklStatus.WATCHING,
        title = "Example Show", imdbId = imdbId, tmdbId = tmdbId, slug = "example-show",
        watchedEpisodes = 4, totalEpisodes = 20, nextSeason = 1, nextEpisode = 5, nextTitle = null,
    )

    private fun repoWithMetadata(
        metadata: MediaMetadata?,
        simklSignedIn: Boolean = true,
        entries: List<SimklEntry> = listOf(simklEntry()),
    ): FakeRepository {
        val row = sampleDownload()
        return object : FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata? =
                metadata
        }.apply {
            this.simklSignedIn = simklSignedIn
            this.simklEntries = entries
        }
    }

    @Test fun `badge appears for the indexed IMDb id once metadata lands`() = runTest {
        val repo = repoWithMetadata(MediaMetadata("Example Show", 2026, "series", imdbId = "tt1234567"))
        val vm = viewModel(repo)
        advanceUntilIdle()
        val key = DownloadKind.TORRENT to "11"
        assertTrue(vm.state.value.simklSignedIn)
        assertEquals(123, vm.state.value.simklStatuses[key]?.simklId)
        assertEquals(SimklStatus.WATCHING, vm.state.value.simklStatuses[key]?.status)
    }

    @Test fun `badge appears by TMDB id when the override carries one`() = runTest {
        val row = sampleDownload()
        val repo = object : FakeRepository(provider = FakeProvider(downloads = mutableListOf(row))) {
            override suspend fun resolveMetadataForRelease(name: String, bypassMiss: Boolean): MediaMetadata? =
                MediaMetadata("Example Show", 2026, "series", imdbId = null)
        }.apply {
            simklSignedIn = true
            simklEntries = listOf(simklEntry(imdbId = null, tmdbId = 456))
            metadataByTmdbId = mapOf(
                (456 to "series") to MediaMetadata("Example Show", 2026, "series", imdbId = null),
            )
            savedMetadataOverrides[DownloadKind.TORRENT to "11"] =
                app.trove.debrid.data.MetadataOverride(null, "series", "Example Show", tmdbId = 456)
        }
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(123, vm.state.value.simklStatuses[DownloadKind.TORRENT to "11"]?.simklId)
    }

    @Test fun `no badge without an enrichment id`() = runTest {
        val repo = repoWithMetadata(MediaMetadata("Example Show", 2026, "series", imdbId = null))
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.simklStatuses.isEmpty())
    }

    @Test fun `no badge while signed out`() = runTest {
        val repo = repoWithMetadata(
            MediaMetadata("Example Show", 2026, "series", imdbId = "tt1234567"),
            simklSignedIn = false,
        )
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertFalse(vm.state.value.simklSignedIn)
        assertTrue(vm.state.value.simklStatuses.isEmpty())
    }

    @Test fun `sheet open and pull-to-refresh trigger an on-demand sync`() = runTest {
        val repo = repoWithMetadata(MediaMetadata("Example Show", 2026, "series", imdbId = "tt1234567"))
        val vm = viewModel(repo)
        advanceUntilIdle()
        assertEquals(0, repo.simklSyncCalls)

        vm.openDownload(sampleDownload())
        advanceUntilIdle()
        assertEquals(1, repo.simklSyncCalls)

        vm.refreshFromUser()
        advanceUntilIdle()
        assertEquals(2, repo.simklSyncCalls)
    }

    @Test fun `a failed sync keeps the last badges`() = runTest {
        val repo = repoWithMetadata(MediaMetadata("Example Show", 2026, "series", imdbId = "tt1234567"))
        val vm = viewModel(repo)
        advanceUntilIdle()
        val key = DownloadKind.TORRENT to "11"
        assertEquals(123, vm.state.value.simklStatuses[key]?.simklId)

        repo.simklSyncResult = SimklSyncResult.Failed(SimklSyncFailure.NETWORK)
        vm.openDownload(sampleDownload())
        advanceUntilIdle()
        assertEquals(123, vm.state.value.simklStatuses[key]?.simklId)
    }

    @Test fun `sign-out clears the badges`() = runTest {
        val repo = repoWithMetadata(MediaMetadata("Example Show", 2026, "series", imdbId = "tt1234567"))
        val vm = viewModel(repo)
        advanceUntilIdle()
        val key = DownloadKind.TORRENT to "11"
        assertEquals(123, vm.state.value.simklStatuses[key]?.simklId)

        repo.signOutSimkl()
        advanceUntilIdle()
        assertTrue(vm.state.value.simklStatuses.isEmpty())
        assertFalse(vm.state.value.simklSignedIn)
    }
}
