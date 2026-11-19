package app.trove.debrid

import app.trove.debrid.core.DebridService
import app.trove.debrid.ui.settings.ProviderAccountState
import app.trove.debrid.ui.settings.SAVED_CREDENTIAL_MASK
import app.trove.debrid.ui.settings.SettingsUiState
import app.trove.debrid.ui.settings.SettingsViewModel
import app.trove.debrid.ui.settings.providerSectionDescriptors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun `backup picker gates save and sign out for every provider`() = runTest {
        val repo = FakeRepository()
        val inputs = listOf(
            DebridService.TORBOX to SettingsViewModel::setKeyField,
            DebridService.REAL_DEBRID to SettingsViewModel::setRdTokenField,
            DebridService.ALLDEBRID to SettingsViewModel::setAdKeyField,
            DebridService.PREMIUMIZE to SettingsViewModel::setPmKeyField,
            DebridService.OFFCLOUD to SettingsViewModel::setOcKeyField,
            DebridService.DEBRID_LINK to SettingsViewModel::setDlKeyField,
        )
        for ((service) in inputs) repo.saveTestCredential(service, "good").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.chooseRestoreSource())
        for ((service, setField) in inputs) {
            setField(vm, "rejected-edit")
            vm.signOut(service)
            vm.testAndSave(service)
        }
        advanceUntilIdle()
        assertEquals(inputs.map { it.first }, repo.accounts().map { it.service })
        assertEquals(1, repo.validateCalls)
        assertFalse(vm.accountChanged.value)
        assertNull(vm.state.value.message)
        assertNull(vm.state.value.messageResource)
        assertEquals("good", repo.savedKey)
        vm.backupPickerUnavailable()
    }

    @Test
    fun `provider sections retain their saved masks and help lines`() {
        val expectedHelp = mapOf(
            DebridService.TORBOX to listOf(R.string.key_help),
            DebridService.REAL_DEBRID to listOf(R.string.rd_token_help, R.string.rd_token_note),
            DebridService.ALLDEBRID to listOf(R.string.ad_key_help, R.string.ad_key_note),
            DebridService.PREMIUMIZE to listOf(R.string.pm_key_help),
            DebridService.OFFCLOUD to listOf(R.string.oc_key_help),
            DebridService.DEBRID_LINK to listOf(R.string.dl_key_help),
        )
        val expectedSavedText = mapOf(
            DebridService.TORBOX to R.string.saved_key,
            DebridService.REAL_DEBRID to R.string.rd_saved_token,
            DebridService.ALLDEBRID to R.string.ad_saved_key,
            DebridService.PREMIUMIZE to R.string.pm_saved_key,
            DebridService.OFFCLOUD to R.string.oc_saved_key,
            DebridService.DEBRID_LINK to R.string.dl_saved_key,
        )
        assertEquals(expectedHelp, providerSectionDescriptors.mapValues { it.value.helpLines })
        assertEquals(expectedSavedText, providerSectionDescriptors.mapValues { it.value.savedCredential })
        val state = SettingsUiState(torbox = ProviderAccountState(maskedKey = "••••", keyField = "typed-secret"))
        val masks = providerSectionDescriptors.mapValues { it.value.maskedCredential(state) }
        assertEquals("••••", masks.getValue(DebridService.TORBOX))
        assertEquals(List(5) { SAVED_CREDENTIAL_MASK }, masks.filterKeys { it != DebridService.TORBOX }.values.toList())
    }

    @Test
    fun `valid key connects and bad key errors`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        assertFalse(vm.state.value.torbox.hasKey)

        vm.setKeyField("good-key")
        vm.testAndSave(DebridService.TORBOX)
        advanceUntilIdle()
        assertTrue(vm.state.value.torbox.hasKey)
        assertEquals("TorBox (Essential)", vm.state.value.torbox.accountLabel)
        assertTrue(vm.state.value.message?.contains("connected") == true)

        vm.setKeyField("bad-key")
        vm.testAndSave(DebridService.TORBOX)
        advanceUntilIdle()
        assertTrue(vm.state.value.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", vm.state.value.message)
    }

    @Test
    fun `sign out clears the key`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        vm.setKeyField("good-key")
        vm.testAndSave(DebridService.TORBOX)
        advanceUntilIdle()
        assertTrue(vm.state.value.torbox.hasKey)
        vm.signOut(DebridService.TORBOX)
        assertTrue(repo.hasTorboxKey())
        advanceUntilIdle()
        assertFalse(vm.state.value.torbox.hasKey)
    }

    @Test
    fun `a throwing repository shows an error instead of crashing`() = runTest {
        val repo = FakeRepository(throwOnValidate = true)
        val vm = SettingsViewModel(repo)
        vm.setKeyField("good-key")
        vm.testAndSave(DebridService.TORBOX)
        advanceUntilIdle()
        // No exception escaped the coroutine: the app stays alive,
        // the error is visible, and the user can retry.
        assertFalse(vm.state.value.torbox.saving)
        assertTrue(vm.state.value.messageIsError)
        assertEquals("good-key", vm.state.value.torbox.keyField)
        assertFalse(vm.state.value.torbox.hasKey)

        // Retry works once the repository stops throwing.
        repo.throwOnValidate = false
        vm.testAndSave(DebridService.TORBOX)
        advanceUntilIdle()
        assertTrue(vm.state.value.torbox.hasKey)
    }

    @Test
    fun `a second tap while saving is ignored`() = runTest {
        val repo = FakeRepository(validateDelayMs = 1_000L)
        val vm = SettingsViewModel(repo)
        vm.setKeyField("good-key")
        vm.testAndSave(DebridService.TORBOX)
        vm.testAndSave(DebridService.TORBOX)
        vm.testAndSave(DebridService.TORBOX)
        advanceUntilIdle()
        assertEquals(1, repo.validateCalls)
        assertTrue(vm.state.value.torbox.hasKey)
        assertFalse(vm.state.value.torbox.saving)
    }

    @Test
    fun `account change is signalled on connect and sign out`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        assertFalse(vm.accountChanged.value)
        vm.setKeyField("good-key")
        vm.testAndSave(DebridService.TORBOX)
        advanceUntilIdle()
        assertTrue(vm.accountChanged.value)
        vm.consumeAccountChanged()
        assertFalse(vm.accountChanged.value)
        vm.signOut(DebridService.TORBOX)
        assertFalse(vm.accountChanged.value)
        advanceUntilIdle()
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `per-service records round-trip through the state and descriptors`() {
        val state = SettingsUiState(
            torbox = ProviderAccountState(
                hasKey = true, accountLabel = "TorBox (Essential)", maskedKey = "••••",
                keyField = "typed", showKey = true, saving = true,
            ),
            realDebrid = ProviderAccountState(
                hasKey = true, accountLabel = "Real-Debrid (Premium)", keyField = "token",
                showKey = true, saving = true, feedback = true,
            ),
            allDebrid = ProviderAccountState(hasKey = true, accountLabel = "AllDebrid (Premium)", keyField = "key"),
            premiumize = ProviderAccountState(hasKey = true, accountLabel = "Premiumize (Premium)"),
            offcloud = ProviderAccountState(hasKey = true, accountLabel = "Offcloud"),
            debridLink = ProviderAccountState(hasKey = true, accountLabel = "Debrid-Link (Premium)", feedback = true),
        )
        for (service in DebridService.entries) {
            val descriptor = providerSectionDescriptors.getValue(service)
            val record = state.providerAccount(service)
            assertEquals(record.hasKey, descriptor.hasKey(state))
            assertEquals(record.accountLabel, descriptor.savedLabel(state))
            assertEquals(record.keyField, descriptor.field(state))
            assertEquals(record.showKey, descriptor.showCredential(state))
            assertEquals(record.saving, descriptor.saving(state))
            assertEquals(record.feedback, descriptor.showFeedback(state))
        }
    }

    @Test
    fun `feedback is claimed by exactly one provider section`() {
        val empty = SettingsUiState()
        for (service in DebridService.entries) {
            val descriptor = providerSectionDescriptors.getValue(service)
            val claimed = descriptor.feedback(empty, resource = 1)
            val claiming = DebridService.entries.filter {
                providerSectionDescriptors.getValue(it).showFeedback(claimed)
            }
            assertEquals(listOf(service), claiming)
        }
        // TorBox has no record flag of its own: it shows the message
        // exactly when no other section claims it.
        val rdClaimed = providerSectionDescriptors.getValue(DebridService.REAL_DEBRID)
            .feedback(empty, resource = 1)
        assertFalse(providerSectionDescriptors.getValue(DebridService.TORBOX).showFeedback(rdClaimed))
        assertTrue(providerSectionDescriptors.getValue(DebridService.TORBOX).showFeedback(empty))
    }

    @Test
    fun `backup is blocked by a mid-edit record for every service`() {
        val idle = SettingsUiState(
            loadingPlayers = false, loadingClutterFiles = false, loadingClipboardOffers = false,
            loadingReleaseWords = false, loadingTheme = false,
        )
        assertTrue(idle.canStartBackup)
        val savingStates = listOf(
            idle.copy(torbox = idle.torbox.copy(saving = true)),
            idle.copy(realDebrid = idle.realDebrid.copy(saving = true)),
            idle.copy(allDebrid = idle.allDebrid.copy(saving = true)),
            idle.copy(premiumize = idle.premiumize.copy(saving = true)),
            idle.copy(offcloud = idle.offcloud.copy(saving = true)),
            idle.copy(debridLink = idle.debridLink.copy(saving = true)),
        )
        assertTrue(savingStates.all { it.anyProviderSaving })
        assertTrue(savingStates.none { it.canStartBackup })
        // A save in one record leaves the other records untouched.
        val one = savingStates[1]
        assertFalse(one.torbox.saving)
        assertEquals("", one.torbox.keyField)
    }

    @Test
    fun `backup stays blocked while a provider save is mid-flight`() = runTest {
        val repo = FakeRepository(validateDelayMs = 1_000L)
        val vm = SettingsViewModel(repo)
        vm.setKeyField("good-key")
        vm.testAndSave(DebridService.TORBOX)
        assertTrue(vm.state.value.torbox.saving)
        assertTrue(vm.state.value.anyProviderSaving)
        assertFalse(vm.state.value.canStartBackup)
        assertFalse(vm.chooseRestoreSource())
        advanceUntilIdle()
        assertFalse(vm.state.value.torbox.saving)
        assertTrue(vm.state.value.canStartBackup)
    }

    @Test
    fun `a grouped refresh does not drop the account-changed signal`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        vm.setKeyField("good-key")
        vm.testAndSave(DebridService.TORBOX)
        advanceUntilIdle()
        assertTrue(vm.accountChanged.value)
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.accountChanged.value)
        assertTrue(vm.state.value.torbox.hasKey)
    }
    @Test
    fun `valid Real-Debrid token connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setRdTokenField("good-token")
        vm.toggleShowRdToken()
        vm.testAndSave(DebridService.REAL_DEBRID)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.realDebrid.hasKey)
        assertFalse(state.torbox.hasKey)
        assertEquals(DebridService.REAL_DEBRID, state.activeService)
        assertTrue(state.realDebrid.accountLabel?.contains("Real-Debrid") == true)
        assertEquals(R.string.rd_connected, state.messageResource)
        assertEquals(state.realDebrid.accountLabel, state.messageArgument)
        assertEquals("", state.realDebrid.keyField)
        assertFalse(state.realDebrid.showKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad Real-Debrid token shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setRdTokenField("bad-token")
        vm.testAndSave(DebridService.REAL_DEBRID)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.realDebrid.hasKey)
    }

    @Test
    fun `Real-Debrid sign out clears token and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "good-key").getOrThrow()
        repo.saveTestCredential(DebridService.REAL_DEBRID, "good-token").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.REAL_DEBRID, vm.state.value.activeService)

        vm.setRdTokenField("replacement-token")
        vm.toggleShowRdToken()
        vm.signOut(DebridService.REAL_DEBRID)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.realDebrid.hasKey)
        assertNull(state.realDebrid.accountLabel)
        assertEquals("", state.realDebrid.keyField)
        assertFalse(state.realDebrid.showKey)
        assertTrue(state.torbox.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.rd_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `valid AllDebrid key connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setAdKeyField("good-key")
        vm.toggleShowAdKey()
        vm.testAndSave(DebridService.ALLDEBRID)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.allDebrid.hasKey)
        assertFalse(state.torbox.hasKey)
        assertEquals(DebridService.ALLDEBRID, state.activeService)
        assertTrue(state.allDebrid.accountLabel?.contains("AllDebrid") == true)
        assertTrue(state.allDebrid.feedback)
        assertFalse(state.realDebrid.feedback)
        assertEquals(R.string.ad_connected, state.messageResource)
        assertEquals(state.allDebrid.accountLabel, state.messageArgument)
        assertEquals("", state.allDebrid.keyField)
        assertFalse(state.allDebrid.showKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad AllDebrid key shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setAdKeyField("bad-key")
        vm.testAndSave(DebridService.ALLDEBRID)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.allDebrid.hasKey)
    }

    @Test
    fun `AllDebrid sign out clears key and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "good-key").getOrThrow()
        repo.saveTestCredential(DebridService.ALLDEBRID, "good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.ALLDEBRID, vm.state.value.activeService)

        vm.setAdKeyField("replacement-key")
        vm.toggleShowAdKey()
        vm.signOut(DebridService.ALLDEBRID)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.allDebrid.hasKey)
        assertNull(state.allDebrid.accountLabel)
        assertEquals("", state.allDebrid.keyField)
        assertFalse(state.allDebrid.showKey)
        assertTrue(state.torbox.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.ad_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `Settings switches to a saved AllDebrid account`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "torbox-key").getOrThrow()
        repo.saveTestCredential(DebridService.ALLDEBRID, "ad-key").getOrThrow()
        repo.setActiveService(DebridService.TORBOX)
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.ALLDEBRID)
        advanceUntilIdle()

        assertEquals(DebridService.ALLDEBRID, vm.state.value.activeService)
        assertTrue(vm.state.value.torbox.hasKey)
        assertTrue(vm.state.value.allDebrid.hasKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `valid Premiumize key connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setPmKeyField("good-key")
        vm.toggleShowPmKey()
        vm.testAndSave(DebridService.PREMIUMIZE)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.premiumize.hasKey)
        assertFalse(state.torbox.hasKey)
        assertEquals(DebridService.PREMIUMIZE, state.activeService)
        assertTrue(state.premiumize.accountLabel?.contains("Premiumize") == true)
        assertTrue(state.premiumize.feedback)
        assertFalse(state.allDebrid.feedback)
        assertFalse(state.realDebrid.feedback)
        assertEquals(R.string.pm_connected, state.messageResource)
        assertEquals(state.premiumize.accountLabel, state.messageArgument)
        assertEquals("", state.premiumize.keyField)
        assertFalse(state.premiumize.showKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad Premiumize key shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setPmKeyField("bad-key")
        vm.testAndSave(DebridService.PREMIUMIZE)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.premiumize.hasKey)
    }

    @Test
    fun `Premiumize sign out clears key and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "good-key").getOrThrow()
        repo.saveTestCredential(DebridService.PREMIUMIZE, "good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.PREMIUMIZE, vm.state.value.activeService)

        vm.setPmKeyField("replacement-key")
        vm.toggleShowPmKey()
        vm.signOut(DebridService.PREMIUMIZE)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.premiumize.hasKey)
        assertNull(state.premiumize.accountLabel)
        assertEquals("", state.premiumize.keyField)
        assertFalse(state.premiumize.showKey)
        assertTrue(state.torbox.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.pm_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `Settings switches to a saved Premiumize account`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "torbox-key").getOrThrow()
        repo.saveTestCredential(DebridService.PREMIUMIZE, "pm-key").getOrThrow()
        repo.setActiveService(DebridService.TORBOX)
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.PREMIUMIZE)
        advanceUntilIdle()

        assertEquals(DebridService.PREMIUMIZE, vm.state.value.activeService)
        assertTrue(vm.state.value.torbox.hasKey)
        assertTrue(vm.state.value.premiumize.hasKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `valid Offcloud key connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setOcKeyField("good-key")
        vm.toggleShowOcKey()
        vm.testAndSave(DebridService.OFFCLOUD)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.offcloud.hasKey)
        assertFalse(state.torbox.hasKey)
        assertEquals(DebridService.OFFCLOUD, state.activeService)
        assertEquals("Offcloud", state.offcloud.accountLabel)
        assertFalse(state.premiumize.feedback)
        assertTrue(state.offcloud.feedback)
        assertFalse(state.allDebrid.feedback)
        assertFalse(state.realDebrid.feedback)
        assertEquals(R.string.oc_connected, state.messageResource)
        assertEquals(state.offcloud.accountLabel, state.messageArgument)
        assertEquals("", state.offcloud.keyField)
        assertFalse(state.offcloud.showKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad Offcloud key shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setOcKeyField("bad-key")
        vm.testAndSave(DebridService.OFFCLOUD)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.offcloud.hasKey)
    }

    @Test
    fun `Offcloud sign out clears key and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "good-key").getOrThrow()
        repo.saveTestCredential(DebridService.OFFCLOUD, "good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.OFFCLOUD, vm.state.value.activeService)

        vm.setOcKeyField("replacement-key")
        vm.toggleShowOcKey()
        vm.signOut(DebridService.OFFCLOUD)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.offcloud.hasKey)
        assertNull(state.offcloud.accountLabel)
        assertEquals("", state.offcloud.keyField)
        assertFalse(state.offcloud.showKey)
        assertTrue(state.torbox.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.oc_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `Settings switches to a saved Offcloud account`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "torbox-key").getOrThrow()
        repo.saveTestCredential(DebridService.OFFCLOUD, "oc-key").getOrThrow()
        repo.setActiveService(DebridService.TORBOX)
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.OFFCLOUD)
        advanceUntilIdle()

        assertEquals(DebridService.OFFCLOUD, vm.state.value.activeService)
        assertTrue(vm.state.value.torbox.hasKey)
        assertTrue(vm.state.value.offcloud.hasKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `valid Debrid-Link key connects and becomes active`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setDlKeyField("good-key")
        vm.toggleShowDlKey()
        vm.testAndSave(DebridService.DEBRID_LINK)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.debridLink.hasKey)
        assertFalse(state.torbox.hasKey)
        assertEquals(DebridService.DEBRID_LINK, state.activeService)
        assertEquals("Debrid-Link (Premium)", state.debridLink.accountLabel)
        assertFalse(state.premiumize.feedback)
        assertFalse(state.offcloud.feedback)
        assertTrue(state.debridLink.feedback)
        assertFalse(state.allDebrid.feedback)
        assertFalse(state.realDebrid.feedback)
        assertEquals(R.string.dl_connected, state.messageResource)
        assertEquals(state.debridLink.accountLabel, state.messageArgument)
        assertEquals("", state.debridLink.keyField)
        assertFalse(state.debridLink.showKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `bad Debrid-Link key shows the authentication error`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        vm.setDlKeyField("bad-key")
        vm.testAndSave(DebridService.DEBRID_LINK)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messageIsError)
        assertEquals("Key/token rejected — re-authenticate", state.message)
        assertFalse(state.debridLink.hasKey)
    }

    @Test
    fun `Debrid-Link sign out clears key and falls back to TorBox`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "good-key").getOrThrow()
        repo.saveTestCredential(DebridService.DEBRID_LINK, "good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertEquals(DebridService.DEBRID_LINK, vm.state.value.activeService)

        vm.setDlKeyField("replacement-key")
        vm.toggleShowDlKey()
        vm.signOut(DebridService.DEBRID_LINK)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.debridLink.hasKey)
        assertNull(state.debridLink.accountLabel)
        assertEquals("", state.debridLink.keyField)
        assertFalse(state.debridLink.showKey)
        assertTrue(state.torbox.hasKey)
        assertEquals(DebridService.TORBOX, state.activeService)
        assertEquals(R.string.dl_signed_out, state.messageResource)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `Settings switches to a saved Debrid-Link account`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "torbox-key").getOrThrow()
        repo.saveTestCredential(DebridService.DEBRID_LINK, "dl-key").getOrThrow()
        repo.setActiveService(DebridService.TORBOX)
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.DEBRID_LINK)
        advanceUntilIdle()

        assertEquals(DebridService.DEBRID_LINK, vm.state.value.activeService)
        assertTrue(vm.state.value.torbox.hasKey)
        assertTrue(vm.state.value.debridLink.hasKey)
        assertTrue(vm.accountChanged.value)
    }

    @Test
    fun `provider switch requires a saved credential`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(DebridService.TORBOX, "good-key").getOrThrow()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setActiveProvider(DebridService.REAL_DEBRID)
        advanceUntilIdle()
        assertEquals(DebridService.TORBOX, vm.state.value.activeService)
        assertFalse(vm.accountChanged.value)

        repo.saveTestCredential(DebridService.REAL_DEBRID, "good-token").getOrThrow()
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
        assertEquals("TorBox (Essential)", vm.state.value.torbox.accountLabel)
        assertTrue(vm.accountChanged.value)
    }

    @Test fun `account state diagnostics never contain the key`() {
        val secret = "private-test-value"
        assertFalse(SettingsUiState(torbox = ProviderAccountState(keyField = secret, showKey = true)).toString().contains(secret))
    }
}
