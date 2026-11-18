package app.trove.debrid

import app.trove.debrid.core.ClassifiedShare
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
        val vm = AddViewModel(repo)
        vm.setText("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd&dn=Dune")
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state.classified is ClassifiedShare.Magnet)
        assertEquals("aabbccddeeff00112233445566778899aabbccdd", (state.classified as ClassifiedShare.Magnet).infoHashHex)
        assertEquals("Dune Part Two 2024", state.cached?.name)
        assertFalse(state.checking)
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
        val repo = FakeRepository().apply { cacheDelayMs = 1_000 }
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
}
