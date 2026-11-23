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
    fun `newest comes first then preference score then input order`() {
        val newest = download("new", "Film", date = 30)
        val oldPreferred = download("old", "Film.x265", date = 10)
        val preferred = download("preferred", "Film.REMUX", date = 20)
        val tied = preferred.copy(id = "tie")
        val mostPreferred = download("most", "Film.x265.REMUX", date = 5)
        val rows = listOf(newest, oldPreferred, preferred, tied, mostPreferred)
        assertEquals(
            listOf(newest, preferred, tied, oldPreferred, mostPreferred),
            sortDownloads(rows, DownloadSort.NEWEST, listOf("x265", "REMUX")),
        )
    }

    @Test
    fun `dates outrank default and custom preferences with nulls last and stable ties`() {
        for (words in listOf(listOf("x265", "REMUX"), listOf("custom"))) {
            val plain = download("plain", "Film", 30)
            val old = download("old", "Film.${words.joinToString(".")}", 10)
            val tiedPlain = download("tie-plain", "Film", 10)
            val tiedPreferred = old.copy(id = "tie-preferred")
            val unknown = download("unknown", "Film")
            val unknownPreferred = old.copy(id = "unknown-preferred", createdAtEpochMs = null)
            val rows = listOf(unknown, tiedPlain, plain, old, tiedPreferred, unknownPreferred)
            assertEquals(listOf(plain, old, tiedPreferred, tiedPlain, unknownPreferred, unknown),
                sortDownloads(rows, DownloadSort.NEWEST, words))
            assertEquals(listOf(old, tiedPreferred, tiedPlain, plain, unknownPreferred, unknown),
                sortDownloads(rows, DownloadSort.OLDEST, words))
        }
    }

    @Test
    fun `non chronological sorts keep preference first`() {
        val preferred = download("preferred", "Middle.x265").copy(sizeBytes = 20, progress = 0.5f)
        val low = download("low", "Alpha").copy(sizeBytes = 10, progress = 0f)
        val high = download("high", "Zebra").copy(sizeBytes = 30, progress = 1f)
        for (sort in DownloadSort.entries.filter { it != DownloadSort.NEWEST && it != DownloadSort.OLDEST }) {
            assertEquals(sort.name, preferred,
                sortDownloads(listOf(low, high, preferred), sort, listOf("x265")).first())
        }
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
