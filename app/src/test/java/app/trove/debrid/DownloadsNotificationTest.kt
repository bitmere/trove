package app.trove.debrid

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.trove.debrid.core.DebridDownload
import app.trove.debrid.core.DownloadKind
import app.trove.debrid.core.DownloadStatus
import app.trove.debrid.data.TroveRepository
import app.trove.debrid.ui.downloads.DownloadsViewModel
import app.trove.debrid.ui.downloads.notifications.ActiveDownload
import app.trove.debrid.ui.downloads.notifications.DownloadNotificationController
import app.trove.debrid.ui.downloads.notifications.NotificationContent
import app.trove.debrid.ui.downloads.notifications.NotificationRepostDecider
import app.trove.debrid.ui.downloads.notifications.notificationContentFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsNotificationTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<DownloadsViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private class FakeNotifications : DownloadNotificationController {
        val syncs = mutableListOf<List<ActiveDownload>>()
        var stops = 0
        override fun sync(active: List<ActiveDownload>) { syncs += active }
        override fun stop() { stops++ }
    }

    private fun TestScope.viewModel(repo: TroveRepository, notifications: FakeNotifications) =
        DownloadsViewModel(repo, StandardTestDispatcher(testScheduler), notifications = notifications)
            .also { models += it }

    // Notification content mapping (pure, no Android framework).

    @Test fun `downloading row maps to the list row text`() {
        val content = notificationContentFor(
            ActiveDownload(
                kind = DownloadKind.TORRENT, id = "a", name = "Dune",
                queued = false, percent = 42, speedText = "74 MB/s", etaText = "2m 3s left",
            ),
            queuedLabel = "Queued",
        )
        assertEquals("Dune", content.title)
        assertEquals("42% · 74 MB/s · 2m 3s left", content.text)
        assertEquals(42, content.percent)
        assertFalse(content.indeterminate)
    }

    @Test fun `queued row maps to indeterminate Queued`() {
        val content = notificationContentFor(
            ActiveDownload(
                kind = DownloadKind.TORRENT, id = "a", name = "Dune",
                queued = true, percent = 0, speedText = null, etaText = null,
            ),
            queuedLabel = "Queued",
        )
        assertEquals("Queued", content.text)
        assertEquals(0, content.percent)
        assertTrue(content.indeterminate)
    }

    @Test fun `percent is clamped to the 0 to 100 range`() {
        val over = notificationContentFor(
            ActiveDownload(DownloadKind.TORRENT, "a", "Dune", false, 150, null, null), "Queued",
        )
        assertEquals(100, over.percent)
        assertEquals("100%", over.text)
        val under = notificationContentFor(
            ActiveDownload(DownloadKind.TORRENT, "a", "Dune", false, -5, null, null), "Queued",
        )
        assertEquals(0, under.percent)
    }

    // Repost decider.

    @Test fun `decider posts first, skips a one percent move, posts a two percent move`() {
        val decider = NotificationRepostDecider()
        val base = NotificationContent("Dune", "42% · 74 MB/s", 42, false, "74 MB/s")
        assertTrue(decider.shouldPost(1, base))
        decider.recordPosted(1, base)
        val oneMore = base.copy(text = "43% · 74 MB/s", percent = 43)
        assertFalse(decider.shouldPost(1, oneMore))
        val twoMore = base.copy(text = "44% · 74 MB/s", percent = 44)
        assertTrue(decider.shouldPost(1, twoMore))
        decider.recordPosted(1, twoMore)
        assertFalse(decider.shouldPost(1, twoMore))
    }

    @Test fun `decider posts when the speed or eta text changes at the same percent`() {
        val decider = NotificationRepostDecider()
        val base = NotificationContent("Dune", "42% · 74 MB/s · 2m left", 42, false, "74 MB/s · 2m left")
        decider.recordPosted(1, base)
        val slower = base.copy(text = "42% · 12 MB/s · 9m left", details = "12 MB/s · 9m left")
        assertTrue(decider.shouldPost(1, slower))
    }

    @Test fun `decider tracks ids independently`() {
        val decider = NotificationRepostDecider()
        val content = NotificationContent("Dune", "42%", 42, false, "")
        decider.recordPosted(1, content)
        assertFalse(decider.shouldPost(1, content))
        assertTrue(decider.shouldPost(2, content))
        decider.forget(1)
        assertTrue(decider.shouldPost(1, content))
    }

    // ViewModel -> controller wiring.

    private fun downloadingRow(id: String = "a", progress: Float = 0.5f): DebridDownload =
        sampleDownload(id = id, status = DownloadStatus.DOWNLOADING).copy(
            progress = progress,
            downloadSpeedBytesPerSec = 74_000_000L,
            etaSeconds = 123L,
        )

    @Test fun `poll tick syncs the active row and an unchanged tick does not re-sync`() = runTest {
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(downloadingRow())))
        repo.savedDownloadProgressNotifications = true
        val notifications = FakeNotifications()
        val vm = viewModel(repo, notifications)
        runCurrent()
        assertEquals(1, notifications.syncs.size)
        assertEquals(listOf("a"), notifications.syncs.single().map { it.id })
        assertEquals(50, notifications.syncs.single().single().percent)
        assertEquals("74 MB/s", notifications.syncs.single().single().speedText)
        assertEquals(0, notifications.stops)

        advanceTimeBy(DownloadsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(1, notifications.syncs.size)

        repo.provider!!.downloads[0] = downloadingRow(progress = 0.51f)
        advanceTimeBy(DownloadsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, notifications.syncs.size)
        assertEquals(51, notifications.syncs.last().single().percent)
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `toggle off never syncs and stops`() = runTest {
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(downloadingRow())))
        repo.savedDownloadProgressNotifications = false
        val notifications = FakeNotifications()
        val vm = viewModel(repo, notifications)
        runCurrent()
        assertTrue(notifications.syncs.isEmpty())
        assertEquals(1, notifications.stops)
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `download finishing stops notifications`() = runTest {
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(downloadingRow())))
        repo.savedDownloadProgressNotifications = true
        val notifications = FakeNotifications()
        val vm = viewModel(repo, notifications)
        runCurrent()
        assertEquals(1, notifications.syncs.size)

        repo.provider!!.downloads[0] = sampleDownload(id = "a", status = DownloadStatus.READY)
        advanceTimeBy(DownloadsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(1, notifications.syncs.size)
        assertEquals(1, notifications.stops)
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `onCleared stops notifications`() = runTest {
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(downloadingRow())))
        repo.savedDownloadProgressNotifications = true
        val notifications = FakeNotifications()
        val vm = viewModel(repo, notifications)
        runCurrent()
        assertEquals(1, notifications.syncs.size)
        val store = ViewModelStore()
        store.put("downloads", vm)
        store.clear()
        assertEquals(1, notifications.stops)
    }

    @Test fun `optimistic queued row syncs immediately on add`() = runTest {
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(sampleDownload())))
        repo.savedDownloadProgressNotifications = true
        val notifications = FakeNotifications()
        val vm = viewModel(repo, notifications)
        runCurrent()
        assertTrue(notifications.syncs.isEmpty())

        val added = sampleDownload(id = "new", status = DownloadStatus.QUEUED)
        vm.onAdded(added)
        assertEquals(1, notifications.syncs.size)
        val synced = notifications.syncs.single().single()
        assertEquals("new", synced.id)
        assertTrue(synced.queued)
        assertEquals(0, synced.percent)
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }

    @Test fun `toggle flip off stops and flip on resumes`() = runTest {
        val repo = FakeRepository(provider = FakeProvider(downloads = mutableListOf(downloadingRow())))
        repo.savedDownloadProgressNotifications = true
        val notifications = FakeNotifications()
        val vm = viewModel(repo, notifications)
        runCurrent()
        assertEquals(1, notifications.syncs.size)

        repo.savedDownloadProgressNotifications = false
        vm.refreshNotificationPreference()
        runCurrent()
        assertEquals(1, notifications.stops)
        assertEquals(1, notifications.syncs.size)

        repo.savedDownloadProgressNotifications = true
        vm.refreshNotificationPreference()
        runCurrent()
        assertEquals(2, notifications.syncs.size)
        // The poll loop never idles; stop it so runTest can finish.
        vm.viewModelScope.cancel()
    }
}
