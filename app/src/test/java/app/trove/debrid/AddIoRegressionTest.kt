package app.trove.debrid

import app.trove.debrid.data.SavedConnection
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.add.AddViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddIoRegressionTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

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
        assertFalse(model.state.value.adding)
    }
}
