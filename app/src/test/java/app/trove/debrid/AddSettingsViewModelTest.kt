package app.trove.debrid

import app.trove.debrid.core.CacheState
import app.trove.debrid.core.CachedInfo
import app.trove.debrid.core.ClassifiedShare
import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridService
import app.trove.debrid.ui.add.AddViewModel
import app.trove.debrid.ui.settings.SettingsViewModel
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
        validateAndSaveTorboxKey("torbox-key").getOrThrow()
        validateAndSavePremiumizeKey("premiumize-key").getOrThrow()
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
        repo.validateAndSaveOffcloudKey("offcloud-key").getOrThrow()
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
    fun `add rejected by Real-Debrid asks for a new token`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveRealDebridToken("good-token").getOrThrow()
        repo.realDebridProvider.addFailure = DebridError.AuthInvalid()
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd&dn=Dune")
        advanceUntilIdle()
        vm.add()
        advanceUntilIdle()

        assertEquals(1, repo.realDebridProvider.addedInputs.size)
        assertEquals("Real-Debrid rejected your API token. Open Settings and re-enter it.", vm.state.value.error)
    }

    @Test
    fun `add rejected by AllDebrid asks for a new key`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveAllDebridKey("good-key").getOrThrow()
        repo.allDebridProvider.addFailure = DebridError.AuthInvalid()
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd&dn=Dune")
        advanceUntilIdle()
        vm.add()
        advanceUntilIdle()

        assertEquals(1, repo.allDebridProvider.addedInputs.size)
        assertEquals(R.string.ad_add_auth_invalid, vm.state.value.errorResource)
    }

    @Test
    fun `add rejected by Premiumize asks for a new key`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSavePremiumizeKey("good-key").getOrThrow()
        repo.premiumizeProvider.addFailure = DebridError.AuthInvalid()
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd&dn=Dune")
        advanceUntilIdle()
        vm.add()
        advanceUntilIdle()

        assertEquals(1, repo.premiumizeProvider.addedInputs.size)
        assertEquals(R.string.pm_add_auth_invalid, vm.state.value.errorResource)
    }

    @Test
    fun `add rejected by Offcloud asks for a new key`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveOffcloudKey("good-key").getOrThrow()
        repo.offcloudProvider.addFailure = DebridError.AuthInvalid()
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd&dn=Dune")
        advanceUntilIdle()
        vm.add()
        advanceUntilIdle()

        assertEquals(1, repo.offcloudProvider.addedInputs.size)
        assertEquals(R.string.oc_add_auth_invalid, vm.state.value.errorResource)
    }

    @Test
    fun `add rejected by Debrid-Link asks for a new key`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveDebridLinkKey("good-key").getOrThrow()
        repo.debridlinkProvider.addFailure = DebridError.AuthInvalid()
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd&dn=Dune")
        advanceUntilIdle()
        vm.add()
        advanceUntilIdle()

        assertEquals(1, repo.debridlinkProvider.addedInputs.size)
        assertEquals(R.string.dl_add_auth_invalid, vm.state.value.errorResource)
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

}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

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
    fun `valid key connects and bad key errors`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        assertFalse(vm.state.value.hasKey)

        vm.setKeyField("good-key")
        vm.testAndSave()
        advanceUntilIdle()
        assertTrue(vm.state.value.hasKey)
        assertEquals("TorBox (Essential)", vm.state.value.accountLabel)
        assertTrue(vm.state.value.message?.contains("connected") == true)

        vm.setKeyField("bad-key")
        vm.testAndSave()
        advanceUntilIdle()
        assertTrue(vm.state.value.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", vm.state.value.message)
    }

    @Test
    fun `sign out clears the key`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        vm.setKeyField("good-key")
        vm.testAndSave()
        advanceUntilIdle()
        assertTrue(vm.state.value.hasKey)
        vm.signOut()
        assertFalse(vm.state.value.hasKey)
    }

    @Test
    fun `a throwing repository shows an error instead of crashing`() = runTest {
        val repo = FakeRepository(throwOnValidate = true)
        val vm = SettingsViewModel(repo)
        vm.setKeyField("good-key")
        vm.testAndSave()
        advanceUntilIdle()
        // No exception escaped the coroutine: the app stays alive,
        // the error is visible, and the user can retry.
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.messageIsError)
        assertEquals("good-key", vm.state.value.keyField)
        assertFalse(vm.state.value.hasKey)

        // Retry works once the repository stops throwing.
        repo.throwOnValidate = false
        vm.testAndSave()
        advanceUntilIdle()
        assertTrue(vm.state.value.hasKey)
    }

    @Test
    fun `a second tap while saving is ignored`() = runTest {
        val repo = FakeRepository(validateDelayMs = 1_000L)
        val vm = SettingsViewModel(repo)
        vm.setKeyField("good-key")
        vm.testAndSave()
        vm.testAndSave()
        vm.testAndSave()
        advanceUntilIdle()
        assertEquals(1, repo.validateCalls)
        assertTrue(vm.state.value.hasKey)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun `account change is signalled on connect and sign out`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        assertFalse(vm.accountChanged.value)
        vm.setKeyField("good-key")
        vm.testAndSave()
        advanceUntilIdle()
        assertTrue(vm.accountChanged.value)
        vm.consumeAccountChanged()
        assertFalse(vm.accountChanged.value)
        vm.signOut()
        assertTrue(vm.accountChanged.value)
    }
    @Test
    fun `valid Real-Debrid token connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setRdTokenField("good-token")
        vm.toggleShowRdToken()
        vm.testAndSaveRealDebrid()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.rdHasKey)
        assertFalse(state.hasKey)
        assertEquals(DebridService.REAL_DEBRID, state.activeService)
        assertTrue(state.rdAccountLabel?.contains("Real-Debrid") == true)
        assertEquals(R.string.rd_connected, state.messageResource)
        assertEquals(state.rdAccountLabel, state.messageArgument)
        assertEquals("", state.rdTokenField)
        assertFalse(state.rdShowToken)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad Real-Debrid token shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setRdTokenField("bad-token")
        vm.testAndSaveRealDebrid()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.rdHasKey)
    }

    @Test
    fun `Real-Debrid sign out clears token and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("good-key").getOrThrow()
        repo.validateAndSaveRealDebridToken("good-token").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.REAL_DEBRID, vm.state.value.activeService)

        vm.setRdTokenField("replacement-token")
        vm.toggleShowRdToken()
        vm.signOutRealDebrid()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.rdHasKey)
        assertNull(state.rdAccountLabel)
        assertEquals("", state.rdTokenField)
        assertFalse(state.rdShowToken)
        assertTrue(state.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.rd_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `valid AllDebrid key connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setAdKeyField("good-key")
        vm.toggleShowAdKey()
        vm.testAndSaveAllDebrid()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.adHasKey)
        assertFalse(state.hasKey)
        assertEquals(DebridService.ALLDEBRID, state.activeService)
        assertTrue(state.adAccountLabel?.contains("AllDebrid") == true)
        assertTrue(state.adFeedback)
        assertFalse(state.rdFeedback)
        assertEquals(R.string.ad_connected, state.messageResource)
        assertEquals(state.adAccountLabel, state.messageArgument)
        assertEquals("", state.adKeyField)
        assertFalse(state.adShowKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad AllDebrid key shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setAdKeyField("bad-key")
        vm.testAndSaveAllDebrid()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.adHasKey)
    }

    @Test
    fun `AllDebrid sign out clears key and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("good-key").getOrThrow()
        repo.validateAndSaveAllDebridKey("good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.ALLDEBRID, vm.state.value.activeService)

        vm.setAdKeyField("replacement-key")
        vm.toggleShowAdKey()
        vm.signOutAllDebrid()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.adHasKey)
        assertNull(state.adAccountLabel)
        assertEquals("", state.adKeyField)
        assertFalse(state.adShowKey)
        assertTrue(state.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.ad_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `Settings switches to a saved AllDebrid account`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        repo.validateAndSaveAllDebridKey("ad-key").getOrThrow()
        repo.setActiveService(DebridService.TORBOX)
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.ALLDEBRID)
        advanceUntilIdle()

        assertEquals(DebridService.ALLDEBRID, vm.state.value.activeService)
        assertTrue(vm.state.value.hasKey)
        assertTrue(vm.state.value.adHasKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `valid Premiumize key connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setPmKeyField("good-key")
        vm.toggleShowPmKey()
        vm.testAndSavePremiumize()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.pmHasKey)
        assertFalse(state.hasKey)
        assertEquals(DebridService.PREMIUMIZE, state.activeService)
        assertTrue(state.pmAccountLabel?.contains("Premiumize") == true)
        assertTrue(state.pmFeedback)
        assertFalse(state.adFeedback)
        assertFalse(state.rdFeedback)
        assertEquals(R.string.pm_connected, state.messageResource)
        assertEquals(state.pmAccountLabel, state.messageArgument)
        assertEquals("", state.pmKeyField)
        assertFalse(state.pmShowKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad Premiumize key shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setPmKeyField("bad-key")
        vm.testAndSavePremiumize()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.pmHasKey)
    }

    @Test
    fun `Premiumize sign out clears key and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("good-key").getOrThrow()
        repo.validateAndSavePremiumizeKey("good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.PREMIUMIZE, vm.state.value.activeService)

        vm.setPmKeyField("replacement-key")
        vm.toggleShowPmKey()
        vm.signOutPremiumize()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.pmHasKey)
        assertNull(state.pmAccountLabel)
        assertEquals("", state.pmKeyField)
        assertFalse(state.pmShowKey)
        assertTrue(state.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.pm_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `Settings switches to a saved Premiumize account`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        repo.validateAndSavePremiumizeKey("pm-key").getOrThrow()
        repo.setActiveService(DebridService.TORBOX)
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.PREMIUMIZE)
        advanceUntilIdle()

        assertEquals(DebridService.PREMIUMIZE, vm.state.value.activeService)
        assertTrue(vm.state.value.hasKey)
        assertTrue(vm.state.value.pmHasKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `valid Offcloud key connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setOcKeyField("good-key")
        vm.toggleShowOcKey()
        vm.testAndSaveOffcloud()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.ocHasKey)
        assertFalse(state.hasKey)
        assertEquals(DebridService.OFFCLOUD, state.activeService)
        assertEquals("Offcloud", state.ocAccountLabel)
        assertFalse(state.pmFeedback)
        assertTrue(state.ocFeedback)
        assertFalse(state.adFeedback)
        assertFalse(state.rdFeedback)
        assertEquals(R.string.oc_connected, state.messageResource)
        assertEquals(state.ocAccountLabel, state.messageArgument)
        assertEquals("", state.ocKeyField)
        assertFalse(state.ocShowKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad Offcloud key shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setOcKeyField("bad-key")
        vm.testAndSaveOffcloud()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.ocHasKey)
    }

    @Test
    fun `Offcloud sign out clears key and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("good-key").getOrThrow()
        repo.validateAndSaveOffcloudKey("good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.OFFCLOUD, vm.state.value.activeService)

        vm.setOcKeyField("replacement-key")
        vm.toggleShowOcKey()
        vm.signOutOffcloud()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.ocHasKey)
        assertNull(state.ocAccountLabel)
        assertEquals("", state.ocKeyField)
        assertFalse(state.ocShowKey)
        assertTrue(state.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.oc_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `Settings switches to a saved Offcloud account`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        repo.validateAndSaveOffcloudKey("oc-key").getOrThrow()
        repo.setActiveService(DebridService.TORBOX)
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.OFFCLOUD)
        advanceUntilIdle()

        assertEquals(DebridService.OFFCLOUD, vm.state.value.activeService)
        assertTrue(vm.state.value.hasKey)
        assertTrue(vm.state.value.ocHasKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `valid Debrid-Link key connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setDlKeyField("good-key")
        vm.toggleShowDlKey()
        vm.testAndSaveDebridLink()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.dlHasKey)
        assertFalse(state.hasKey)
        assertEquals(DebridService.DEBRID_LINK, state.activeService)
        assertEquals("Debrid-Link (Premium)", state.dlAccountLabel)
        assertFalse(state.pmFeedback)
        assertFalse(state.ocFeedback)
        assertTrue(state.dlFeedback)
        assertFalse(state.adFeedback)
        assertFalse(state.rdFeedback)
        assertEquals(R.string.dl_connected, state.messageResource)
        assertEquals(state.dlAccountLabel, state.messageArgument)
        assertEquals("", state.dlKeyField)
        assertFalse(state.dlShowKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad Debrid-Link key shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setDlKeyField("bad-key")
        vm.testAndSaveDebridLink()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.dlHasKey)
    }

    @Test
    fun `Debrid-Link sign out clears key and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("good-key").getOrThrow()
        repo.validateAndSaveDebridLinkKey("good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.DEBRID_LINK, vm.state.value.activeService)

        vm.setDlKeyField("replacement-key")
        vm.toggleShowDlKey()
        vm.signOutDebridLink()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.dlHasKey)
        assertNull(state.dlAccountLabel)
        assertEquals("", state.dlKeyField)
        assertFalse(state.dlShowKey)
        assertTrue(state.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.dl_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `Settings switches to a saved Debrid-Link account`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("torbox-key").getOrThrow()
        repo.validateAndSaveDebridLinkKey("dl-key").getOrThrow()
        repo.setActiveService(DebridService.TORBOX)
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.DEBRID_LINK)
        advanceUntilIdle()

        assertEquals(DebridService.DEBRID_LINK, vm.state.value.activeService)
        assertTrue(vm.state.value.hasKey)
        assertTrue(vm.state.value.dlHasKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `provider switch requires a saved credential`() = runTest {
        val repo = FakeRepository()
        repo.validateAndSaveTorboxKey("good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.REAL_DEBRID)
        advanceUntilIdle()
        assertEquals(DebridService.TORBOX, vm.state.value.activeService)
        assertFalse(vm.accountChanged.value)

        repo.validateAndSaveRealDebridToken("good-token").getOrThrow()
        vm.refresh()
        advanceUntilIdle()
        vm.setActiveProvider(DebridService.TORBOX)
        advanceUntilIdle()
        assertEquals(DebridService.TORBOX, vm.state.value.activeService)
        assertTrue(vm.accountChanged.value)

        vm.consumeAccountChanged()
        vm.setActiveProvider(DebridService.REAL_DEBRID)
        advanceUntilIdle()
        assertEquals(DebridService.REAL_DEBRID, vm.state.value.activeService)
        assertEquals("TorBox (Essential)", vm.state.value.accountLabel)
        assertTrue(vm.accountChanged.value)
    }

}
