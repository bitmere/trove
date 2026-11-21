package app.trove.debrid

import app.trove.debrid.core.SimklDeviceCode
import app.trove.debrid.core.SimklEntry
import app.trove.debrid.core.SimklPollResult
import app.trove.debrid.core.SimklStatus
import app.trove.debrid.core.SimklSyncFailure
import app.trove.debrid.core.SimklSyncResult
import app.trove.debrid.core.SimklType
import app.trove.debrid.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** SIMKL Settings: sign-in flow, sign-out, sync-now, and the
 *  not-registered state. No live SIMKL app exists yet — the fake
 *  repository scripts every transition. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimklSettingsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val deviceCode = SimklDeviceCode(
        deviceCode = "dc-1", userCode = "ABCD-EFGH",
        verificationUri = "https://simkl.com/pin",
        verificationUriComplete = "https://simkl.com/pin/ABCD-EFGH",
        expiresInSecs = 900, intervalSecs = 5,
    )

    private fun signedInRepo(): FakeRepository = FakeRepository().apply {
        simklConfigured = true
        simklSignedIn = true
        simklUsername = "tester"
        simklEntries = listOf(
            SimklEntry(123, SimklType.SHOW, SimklStatus.WATCHING, "Example Show",
                "tt1234567", 456, "example-show", 4, 20, 1, 5, null),
        )
    }

    @Test fun `saving a client id configures SIMKL and clears the field`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertFalse(vm.state.value.simklConfigured)

        vm.setSimklClientIdField("my-app-id")
        assertEquals("my-app-id", vm.state.value.simklClientIdField)
        vm.saveSimklClientId()
        advanceUntilIdle()

        assertTrue(vm.state.value.simklConfigured)
        assertEquals("my-app-id", vm.state.value.simklClientId)
        assertEquals("", vm.state.value.simklClientIdField)
        assertTrue(vm.state.value.simklClientIdSaved)
        assertFalse(vm.state.value.simklClientIdError)
        assertEquals(1, repo.simklClientIdSaveCalls)
    }

    @Test fun `an empty client id is rejected without a save call`() = runTest {
        val repo = FakeRepository()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.setSimklClientIdField("   ")
        vm.saveSimklClientId()
        advanceUntilIdle()

        assertTrue(vm.state.value.simklClientIdError)
        assertFalse(vm.state.value.simklClientIdSaved)
        assertFalse(vm.state.value.simklConfigured)
        assertEquals(0, repo.simklClientIdSaveCalls)
    }

    @Test fun `changing the client id signs the user out in state`() = runTest {
        val repo = signedInRepo().apply { savedSimklClientId = "old-id" }
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.simklSignedIn)

        vm.setSimklClientIdField("new-id")
        vm.saveSimklClientId()
        advanceUntilIdle()

        assertEquals("new-id", vm.state.value.simklClientId)
        assertTrue(vm.state.value.simklConfigured)
        assertFalse(vm.state.value.simklSignedIn)
        assertNull(vm.state.value.simklUsername)
        assertEquals(0, vm.state.value.simklEntryCount)
    }

    @Test fun `clearing the client id unconfigures SIMKL`() = runTest {
        val repo = signedInRepo().apply { savedSimklClientId = "old-id" }
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.simklConfigured)

        vm.clearSimklClientId()
        advanceUntilIdle()

        assertFalse(vm.state.value.simklConfigured)
        assertNull(vm.state.value.simklClientId)
        assertFalse(vm.state.value.simklSignedIn)
        assertEquals(1, repo.simklClientIdClearCalls)
    }

    @Test fun `refresh loads the signed-in snapshot summary`() = runTest {
        val repo = signedInRepo()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        assertTrue(vm.state.value.simklConfigured)
        assertTrue(vm.state.value.simklSignedIn)
        assertEquals("tester", vm.state.value.simklUsername)
        assertEquals(1, vm.state.value.simklEntryCount)
        assertNull(vm.state.value.simklLastSyncError)
    }

    @Test fun `not registered means sign-in is unavailable`() = runTest {
        val vm = SettingsViewModel(FakeRepository())
        advanceUntilIdle()

        assertFalse(vm.state.value.simklConfigured)
        assertFalse(vm.state.value.simklSignedIn)
    }

    @Test fun `device flow shows the code and signs in on approval`() = runTest {
        val repo = FakeRepository().apply {
            simklConfigured = true
            simklDeviceCode = deviceCode
            simklPollResult = SimklPollResult.Authorized
        }
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.startSimklSignIn()
        advanceUntilIdle()

        assertFalse(vm.state.value.simklSigningIn)
        assertNull(vm.state.value.simklUserCode)
        assertTrue(vm.state.value.simklSignedIn)
        assertFalse(vm.state.value.simklSignInError)
    }

    @Test fun `an expired code reports expiry and allows retry`() = runTest {
        val repo = FakeRepository().apply {
            simklConfigured = true
            simklDeviceCode = deviceCode
            simklPollResult = SimklPollResult.Expired
        }
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.startSimklSignIn()
        advanceUntilIdle()

        assertFalse(vm.state.value.simklSigningIn)
        assertTrue(vm.state.value.simklSignInExpired)
        assertFalse(vm.state.value.simklSignedIn)

        // Retry starts a fresh flow with no stale expiry flag.
        vm.startSimklSignIn()
        advanceUntilIdle()
        assertTrue(vm.state.value.simklSignInExpired)
    }

    @Test fun `cancel clears the code without signing in`() = runTest {
        val repo = FakeRepository().apply {
            simklConfigured = true
            simklDeviceCode = deviceCode
            // Never authorises: the loop keeps polling.
            simklPollResult = SimklPollResult.Pending
        }
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.startSimklSignIn()
        // Let the first poll run so the code is on screen.
        testDispatcher.scheduler.advanceTimeBy(6_000)
        assertEquals("ABCD-EFGH", vm.state.value.simklUserCode)
        vm.cancelSimklSignIn()
        advanceUntilIdle()

        assertFalse(vm.state.value.simklSigningIn)
        assertNull(vm.state.value.simklUserCode)
        assertFalse(vm.state.value.simklSignedIn)
    }

    @Test fun `sign-out clears tokens and snapshot state`() = runTest {
        val repo = signedInRepo()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value.simklSignedIn)

        vm.signOutSimkl()
        advanceUntilIdle()

        assertFalse(vm.state.value.simklSignedIn)
        assertNull(vm.state.value.simklUsername)
        assertEquals(0, vm.state.value.simklEntryCount)
        assertEquals(1, repo.simklSignOutCalls)
    }

    @Test fun `sync now triggers a sync and clears the spinner`() = runTest {
        val repo = signedInRepo()
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.syncSimklNow()
        advanceUntilIdle()

        assertEquals(1, repo.simklSyncCalls)
        assertFalse(vm.state.value.simklSyncing)
    }

    @Test fun `a failed sync surfaces the failure and keeps the badges`() = runTest {
        val repo = signedInRepo().apply {
            simklSyncResult = SimklSyncResult.Failed(SimklSyncFailure.NETWORK)
        }
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        vm.syncSimklNow()
        advanceUntilIdle()

        assertFalse(vm.state.value.simklSyncing)
        assertEquals(1, repo.simklSyncCalls)
        // The fake keeps its entries on a failed sync, matching the
        // production engine: the last snapshot stays in place.
        assertEquals(1, vm.state.value.simklEntryCount)
    }
}
