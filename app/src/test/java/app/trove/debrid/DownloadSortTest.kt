package app.trove.debrid

import app.trove.debrid.core.DownloadSort
import app.trove.debrid.core.sortDownloads
import app.trove.debrid.core.InMemoryCredentialStore
import app.trove.debrid.data.DebridRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadSortTest {
    private val rows = listOf(
        sampleDownload(id = "a", name = "alpha").copy(createdAtEpochMs = 20, sizeBytes = 100, progress = .4f),
        sampleDownload(id = "b", name = "Beta").copy(createdAtEpochMs = 10, sizeBytes = 300, progress = .8f),
        sampleDownload(id = "c", name = "charlie").copy(createdAtEpochMs = null, sizeBytes = null, progress = 0f),
    )

    private fun assertOrder(sort: DownloadSort, vararg ids: String) =
        assertEquals(ids.toList(), sortDownloads(rows, sort).map { it.id })

    @Test fun `newest first uses descending created date with missing dates last`() =
        assertOrder(DownloadSort.NEWEST, "a", "b", "c")

    @Test fun `oldest first uses ascending created date with missing dates last`() =
        assertOrder(DownloadSort.OLDEST, "b", "a", "c")

    @Test fun `name A to Z compares names without case`() =
        assertOrder(DownloadSort.NAME_ASC, "a", "b", "c")

    @Test fun `name Z to A compares names without case`() =
        assertOrder(DownloadSort.NAME_DESC, "c", "b", "a")

    @Test fun `largest first uses descending size with missing sizes last`() =
        assertOrder(DownloadSort.LARGEST, "b", "a", "c")

    @Test fun `smallest first uses ascending size with missing sizes last`() =
        assertOrder(DownloadSort.SMALLEST, "a", "b", "c")

    @Test fun `progress highest first uses descending progress`() =
        assertOrder(DownloadSort.PROGRESS, "b", "a", "c")

    @Test fun `zero progress preserves provider order including negative zero`() {
        val input = listOf(rows[0].copy(progress = -0f), rows[2])
        assertEquals(input, sortDownloads(input, DownloadSort.PROGRESS))
    }

    @Test fun `both date directions keep multiple missing dates last and preserve ties`() {
        val input = rows + rows[2].copy(id = "d") + rows[0].copy(id = "e")
        assertEquals(listOf("a", "e", "b", "c", "d"), sortDownloads(input, DownloadSort.NEWEST).map { it.id })
        assertEquals(listOf("b", "a", "e", "c", "d"), sortDownloads(input, DownloadSort.OLDEST).map { it.id })
    }

    @Test fun `persisted choices round trip through a new repository and unknown choice defaults`() = runTest {
        val prefs = FakeSharedPreferences()
        fun repository() = DebridRepository(prefs, InMemoryCredentialStore(), FakeHttpTransport(), Dispatchers.Unconfined)
        assertEquals(DownloadSort.NEWEST, repository().loadDownloadSort())
        DownloadSort.entries.forEach { sort ->
            repository().saveDownloadSort(sort)
            assertEquals(sort, repository().loadDownloadSort())
        }
        prefs.edit().putString("download_sort", "future-option").apply()
        assertEquals(DownloadSort.NEWEST, repository().loadDownloadSort())
    }
}
