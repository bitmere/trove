package app.trove.debrid
import app.trove.debrid.data.SavedConnection
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DebridProvider
import app.trove.debrid.data.TroveRepository
import kotlinx.coroutines.delay
import org.junit.Assert.assertNotNull

import app.trove.debrid.core.CacheState
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridService
import app.trove.debrid.ui.add.AddViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class AddViewModelTest {

    private val hash = "aabbccddeeff00112233445566778899aabbccdd"
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Dune"
    private val hit = CachedInfo(true, "Dune Part Two 2024", 1_000_000L)

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
    fun `magnet text is classified and cache-checked`() = runTest {
        val repo = FakeRepository()
        repo.provider!!.cached = mapOf(hash to hit)
        val vm = AddViewModel(repo)
        vm.setText(magnet)
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state.classified is ClassifiedShare.Magnet)
        assertEquals("aabbccddeeff00112233445566778899aabbccdd", (state.classified as ClassifiedShare.Magnet).infoHashHex)
        assertEquals("Dune Part Two 2024", state.cached?.name)
        assertEquals(1, state.cacheResults.size)
        assertNull(state.selectedService)
        assertFalse(state.showServicePicker)
        assertFalse(state.checking)
    }

    private suspend fun signedInToTorBoxAndPremiumize(): FakeRepository = FakeRepository().apply {
        saveTestCredential(DebridService.TORBOX, "torbox-key").getOrThrow()
        saveTestCredential(DebridService.PREMIUMIZE, "premiumize-key").getOrThrow()
        setActiveService(DebridService.TORBOX)
        provider!!.cached = emptyMap()
        premiumizeProvider.cached = emptyMap()
    }

    @Test
    fun `both services cached preselects the active service`() = runTest {
        val repo = signedInToTorBoxAndPremiumize()
        repo.provider!!.cached = mapOf(hash to hit)
        val premiumizeHit = hit.copy(name = "Premiumize cache hit")
        repo.premiumizeProvider.cached = mapOf(hash to premiumizeHit)
        repo.setActiveService(DebridService.PREMIUMIZE)
        val vm = AddViewModel(repo)
        vm.setText(magnet)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(CacheState.CACHED, CacheState.CACHED), state.cacheResults.map { it.state })
        assertTrue(state.showServicePicker)
        assertEquals(DebridService.PREMIUMIZE, state.selectedService)
        assertEquals(premiumizeHit, state.cached)
    }

    @Test
    fun `only Premiumize cached preselects Premiumize`() = runTest {
        val repo = signedInToTorBoxAndPremiumize()
        repo.premiumizeProvider.cached = mapOf(hash to hit)
        val vm = AddViewModel(repo)
        vm.setText(magnet)
        advanceUntilIdle()

        assertEquals(DebridService.PREMIUMIZE, vm.state.value.selectedService)
    }

    @Test
    fun `with an uncached active service the first cached service in Settings order wins`() = runTest {
        val repo = signedInToTorBoxAndPremiumize()
        repo.saveTestCredential(DebridService.OFFCLOUD, "offcloud-key").getOrThrow()
        repo.setActiveService(DebridService.TORBOX)
        repo.premiumizeProvider.cached = mapOf(hash to hit)
        repo.offcloudProvider.cached = mapOf(hash to hit)
        val vm = AddViewModel(repo)
        vm.setText(magnet)
        advanceUntilIdle()

        assertEquals(
            listOf(DebridService.TORBOX, DebridService.PREMIUMIZE, DebridService.OFFCLOUD),
            vm.state.value.cacheResults.map { it.service },
        )
        assertEquals(DebridService.PREMIUMIZE, vm.state.value.selectedService)
    }

    @Test
    fun `with no cached services Add uses the active service`() = runTest {
        val repo = signedInToTorBoxAndPremiumize()
        val vm = AddViewModel(repo)
        vm.setText(magnet)
        advanceUntilIdle()

        assertEquals(DebridService.TORBOX, vm.state.value.selectedService)
        vm.add()
        advanceUntilIdle()

        assertEquals(1, repo.provider!!.addedInputs.size)
        assertTrue(vm.state.value.added)
        assertNotNull(vm.state.value.addedDownload)
    }

    @Test
    fun `Add honors the user's service choice`() = runTest {
        val repo = signedInToTorBoxAndPremiumize()
        val vm = AddViewModel(repo)
        vm.setText(magnet)
        advanceUntilIdle()

        assertEquals(DebridService.TORBOX, vm.state.value.selectedService)
        vm.selectService(DebridService.PREMIUMIZE)
        vm.add()
        advanceUntilIdle()

        assertEquals(1, repo.premiumizeProvider.addedInputs.size)
        assertEquals(0, repo.provider!!.addedInputs.size)
        assertTrue(vm.state.value.added)
        assertNotNull(vm.state.value.addedDownload)
    }

    @Test
    fun `unknown cache state does not prevent adding with the cached service`() = runTest {
        val repo = signedInToTorBoxAndPremiumize()
        repo.provider!!.cached = null
        repo.premiumizeProvider.cached = mapOf(hash to hit)
        val vm = AddViewModel(repo)
        vm.setText(magnet)
        advanceUntilIdle()

        assertEquals(listOf(CacheState.UNKNOWN, CacheState.CACHED), vm.state.value.cacheResults.map { it.state })
        assertEquals(DebridService.PREMIUMIZE, vm.state.value.selectedService)
        vm.add()
        advanceUntilIdle()

        assertEquals(1, repo.premiumizeProvider.addedInputs.size)
        assertTrue(vm.state.value.added)
        assertNotNull(vm.state.value.addedDownload)
    }

    @Test
    fun `magnet stays usable when the optional cache connection read throws`() = runTest {
        val repo = object : app.trove.debrid.data.TroveRepository by FakeRepository() {
            override suspend fun loadConnection(): app.trove.debrid.data.SavedConnection =
                error("Fixture: connection read failed")
        }
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:${"a".repeat(40)}")
        advanceUntilIdle()
        assertTrue(vm.state.value.classified is ClassifiedShare.Magnet)
        assertFalse(vm.state.value.checking)
    }

    @Test
    fun `plain url is a hoster link and can be added`() = runTest {
        val repo = FakeRepository()
        val vm = AddViewModel(repo)
        vm.setText("https://example.com/file/abc")
        advanceUntilIdle()
        // No hoster set is known to the fake, so any http(s) URL is a
        // hoster link here; the real app checks supportsUrl live.
        assertTrue(vm.state.value.classified is ClassifiedShare.HosterLink)
        vm.add()
        advanceUntilIdle()
        assertTrue(vm.state.value.added)
        assertNotNull(vm.state.value.addedDownload)
        assertEquals(1, repo.addedClassified.size)
    }

    @Test
    fun `add sends the classified input and reports success`() = runTest {
        val repo = FakeRepository()
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbcc")
        advanceUntilIdle()
        vm.add()
        advanceUntilIdle()
        assertTrue(vm.state.value.added)
        assertNotNull(vm.state.value.addedDownload)
        assertEquals(1, repo.provider?.addedInputs?.size)
    }

    @Test
    fun `torrent refusal reaches the sheet as TorBox detail`() = runTest {
        val detail = "The torrent sent is not a valid torrent."
        val repo = FakeRepository().apply {
            provider!!.addFailure = app.trove.debrid.core.DebridError.Provider("BOZO_TORRENT", detail)
        }
        val vm = AddViewModel(repo)
        vm.setText("Browser share: magnet:?xt=urn:btih:aabbcc\nShared from browser")
        advanceUntilIdle()
        vm.add()
        advanceUntilIdle()
        assertEquals(detail, vm.state.value.error)
        assertFalse(vm.state.value.adding)
        assertFalse(vm.state.value.added)
        assertEquals("magnet:?xt=urn:btih:aabbcc", (repo.provider!!.addedInputs.single() as app.trove.debrid.core.AddInput.Magnet).magnetUri)
    }






    @Test
    fun `add without a provider explains the missing key`() = runTest {
        val repo = FakeRepository(provider = null)
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbcc")
        advanceUntilIdle()
        vm.add()
        // The provider is now loaded inside the add coroutine (off Main),
        // so the missing-key error lands after the dispatcher advances.
        advanceUntilIdle()
        assertEquals("Add your TorBox API key in Settings first", vm.state.value.error)
        assertFalse(vm.state.value.added)
    }
    @Test
    fun `changing input or closing the sheet cannot publish an old cache result`() = runTest {
        val repo = FakeRepository().apply { provider!!.cacheDelayMs = 1_000 }
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd")
        advanceTimeBy(300)
        assertTrue(vm.state.value.checking)
        vm.setFile(byteArrayOf(1), "new.nzb", "application/x-nzb")
        advanceUntilIdle()
        assertTrue(vm.state.value.classified is ClassifiedShare.Nzb)
        assertNull(vm.state.value.cached)
        assertFalse(vm.state.value.checking)
        vm.setText("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd")
        advanceTimeBy(300)
        vm.reset()
        advanceUntilIdle()
        assertNull(vm.state.value.classified)
        assertNull(vm.state.value.cached)
        assertFalse(vm.state.value.checking)
    }

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
        assertNotNull(vm.state.value.addedDownload)
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

    @Test fun `add and cache lookup use suspending connection seam not synchronous credential access`() = runTest {
        val provider = FakeProvider()
        val base = FakeRepository(provider = provider)
        var connectionReads = 0
        val repository = object : TroveRepository by base {
            override fun provider(): app.trove.debrid.core.DebridProvider? =
                error("Synchronous credential access on the caller")
            override suspend fun loadConnection(): SavedConnection {
                connectionReads++
                return SavedConnection(null, true, provider)
            }
        }
        val model = AddViewModel(repository)
        model.setText("magnet:?xt=urn:btih:0123456789012345678901234567890123456789")
        advanceUntilIdle()
        assertEquals(1, connectionReads)
        model.add()
        advanceUntilIdle()
        assertEquals(2, connectionReads)
        assertTrue(model.state.value.added)
        assertEquals(provider.downloads.single(), model.state.value.addedDownload)
        assertFalse(model.state.value.adding)
    }
}
