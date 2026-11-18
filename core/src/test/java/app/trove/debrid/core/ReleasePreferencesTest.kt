package app.trove.debrid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePreferencesTest {
    @Test
    fun `excluded words match only whole tokens regardless of case`() {
        val words = listOf(" CAM ", "TS", " ")
        for (name in listOf("Film.CaM.1080p", "Film_[ts]", "Film-(cam)", "Film {TS}")) {
            assertTrue(name, isExcludedRelease(name, words))
        }
        assertFalse(isExcludedRelease("Camera.Parts.1080p", words))
        assertFalse(isExcludedRelease("Film.CAM", emptyList()))
        assertFalse(isExcludedRelease("Film.CAM", listOf("", " ")))
    }

    @Test
    fun `preference score counts distinct matching words`() {
        val words = listOf("x265", " REMUX ", "X265", "", " ")
        assertEquals(2, preferScore("Film-[X265]_Remux.x265", words))
        assertEquals(1, preferScore("Film.x265.x265", words))
        assertEquals(0, preferScore("Film.x2650.Remuxed", words))
        assertEquals(0, preferScore("Film.x265", emptyList()))
    }

    @Test
    fun `filter retains provider order for releases that remain`() {
        val first = download("1", "Zebra")
        val last = download("4", "Camera")
        val rows = listOf(first, download("2", "Film.CAM"), download("3", "Film.TS"), last)
        assertEquals(listOf(first, last), filterExcluded(rows, listOf("cam", "ts")))
        assertEquals(rows, filterExcluded(rows, emptyList()))
    }

    @Test
    fun `preference score comes first then newest then input order`() {
        val newest = download("new", "Film", date = 30)
        val oldPreferred = download("old", "Film.x265", date = 10)
        val preferred = download("preferred", "Film.REMUX", date = 20)
        val tied = preferred.copy(id = "tie")
        val mostPreferred = download("most", "Film.x265.REMUX", date = 5)
        val rows = listOf(newest, oldPreferred, preferred, tied, mostPreferred)
        assertEquals(
            listOf(mostPreferred, preferred, tied, oldPreferred, newest),
            sortDownloads(rows, DownloadSort.NEWEST, listOf("x265", "REMUX")),
        )
    }

    @Test
    fun `empty preferences retain the existing order for every sort including ties`() {
        val alpha = download("a", "Alpha", date = 2).copy(sizeBytes = 1, progress = 0.5f)
        val beta = download("b", "Beta", date = 1).copy(sizeBytes = 2, progress = 1f)
        val tie = alpha.copy(id = "tie")
        val unknown = download("u", "Unknown")
        val rows = listOf(unknown, alpha, beta, tie)
        val expected = mapOf(
            DownloadSort.NEWEST to listOf(alpha, tie, beta, unknown),
            DownloadSort.OLDEST to listOf(beta, alpha, tie, unknown),
            DownloadSort.NAME_ASC to listOf(alpha, tie, beta, unknown),
            DownloadSort.NAME_DESC to listOf(unknown, beta, alpha, tie),
            DownloadSort.LARGEST to listOf(beta, alpha, tie, unknown),
            DownloadSort.SMALLEST to listOf(alpha, tie, beta, unknown),
            DownloadSort.PROGRESS to listOf(beta, alpha, tie, unknown),
        )
        for (sort in DownloadSort.entries) {
            val original = sortDownloads(rows, sort)
            assertEquals(sort.name, expected.getValue(sort), original)
            assertEquals(sort.name, original, sortDownloads(rows, sort, emptyList()))
        }
    }

    private fun download(id: String, name: String, date: Long? = null) = DebridDownload(
        id = id,
        service = DebridService.TORBOX,
        name = name,
        sizeBytes = null,
        status = DownloadStatus.READY,
        progress = 0f,
        kind = DownloadKind.TORRENT,
        createdAtEpochMs = date,
    )
}
