package app.trove.debrid

import app.trove.debrid.core.*
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
        val vm = DownloadsViewModel(repo)
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
        val vm = DownloadsViewModel(repo)
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
        val vm = DownloadsViewModel(repo)
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
        val vm = DownloadsViewModel(repo)
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, calls)
    }

}
