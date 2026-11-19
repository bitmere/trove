package app.trove.debrid

import app.trove.debrid.core.DebridError
import app.trove.debrid.core.DebridService
import app.trove.debrid.ui.add.AddViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The per-service "add rejected asks for a new key/token" cases, as one
 * parameterized test x 5 services (JUnit4 @Parameterized — the repo's first
 * parameterized test). Each parameter set still runs as its own test and the
 * parameter name is the service, so a Real-Debrid failure is still named
 * Real-Debrid. The class is separate from [AddViewModelTest] because a
 * @Parameterized runner re-runs every test in its class once per parameter.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AddAuthRejectionTest(
    // Display name only: JUnit's @Parameters(name = "{0}") uses the first
    // value for the per-case test name, so it is not stored as a property.
    serviceName: String,
    private val service: DebridService,
    private val credential: String,
    private val expectedErrorResource: Int?,
    private val expectedError: String?,
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun services(): List<Array<Any?>> = listOf(
            arrayOf(
                "Real-Debrid", DebridService.REAL_DEBRID, "good-token", null,
                "Real-Debrid rejected your API token. Open Settings and re-enter it.",
            ),
            arrayOf("AllDebrid", DebridService.ALLDEBRID, "good-key", R.string.ad_add_auth_invalid, null),
            arrayOf("Premiumize", DebridService.PREMIUMIZE, "good-key", R.string.pm_add_auth_invalid, null),
            arrayOf("Offcloud", DebridService.OFFCLOUD, "good-key", R.string.oc_add_auth_invalid, null),
            arrayOf("Debrid-Link", DebridService.DEBRID_LINK, "good-key", R.string.dl_add_auth_invalid, null),
        )
    }

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun providerFor(repo: FakeRepository): FakeProvider = when (service) {
        DebridService.TORBOX -> repo.provider!!
        DebridService.REAL_DEBRID -> repo.realDebridProvider
        DebridService.ALLDEBRID -> repo.allDebridProvider
        DebridService.PREMIUMIZE -> repo.premiumizeProvider
        DebridService.OFFCLOUD -> repo.offcloudProvider
        DebridService.DEBRID_LINK -> repo.debridlinkProvider
    }

    @Test
    fun `add rejected asks for a new key or token`() = runTest {
        val repo = FakeRepository()
        repo.saveTestCredential(service, credential).getOrThrow()
        val provider = providerFor(repo)
        provider.addFailure = DebridError.AuthInvalid()
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd&dn=Dune")
        advanceUntilIdle()
        vm.add()
        advanceUntilIdle()

        assertEquals(1, provider.addedInputs.size)
        if (expectedErrorResource != null) {
            assertEquals(expectedErrorResource, vm.state.value.errorResource)
        } else {
            assertEquals(expectedError, vm.state.value.error)
        }
    }
}
