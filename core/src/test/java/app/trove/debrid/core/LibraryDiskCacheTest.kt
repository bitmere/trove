package app.trove.debrid.core

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun roundTripsPagingAndRowsWithoutFileDetailsOrLiveMetrics() {
        val directory = temporary.newFolder()
        val cache = LibraryDiskCache(directory)
        val original = row("1", DownloadKind.WEB).copy(
            files = listOf(DebridFile("file-id", "private-file.mkv", 42, "private-path")),
            downloadSpeedBytesPerSec = 55,
            etaSeconds = 66,
            seeds = 7,
            peers = 8,
            errorMessage = "private-provider-diagnostic",
        )
        val tab = LibrarySnapshot.Tab(listOf(original), endReached = false, nextOffset = 15)
        val snapshot = LibrarySnapshot(DebridService.TORBOX, 123, mapOf(DownloadKind.WEB to tab))

        cache.save(snapshot)

        assertEquals(
            snapshot.copy(tabs = mapOf(DownloadKind.WEB to tab.copy(rows = listOf(row("1", DownloadKind.WEB))))),
            LibraryDiskCache(directory).load(),
        )
        val encoded = directory.listFiles()!!.single().readText()
        for (field in listOf("files", "downloadSpeedBytesPerSec", "etaSeconds", "seeds", "peers", "private-")) {
            assertFalse(field, encoded.contains(field))
        }
    }

    @Test fun capsEachTabAndTheUnion() {
        val cache = LibraryDiskCache(temporary.newFolder())
        val tabs = DownloadKind.entries.associateWith { kind ->
            LibrarySnapshot.Tab(List(250) { row(it.toString(), kind) }, endReached = true, nextOffset = 250)
        }
        cache.save(LibrarySnapshot(DebridService.TORBOX, 123, tabs))

        val restored = cache.load()!!.tabs
        assertEquals(300, restored.values.sumOf { it.rows.size })
        for ((kind, tab) in restored) {
            assertEquals(tabs.getValue(kind).rows.take(100), tab.rows)
            assertEquals(100, tab.nextOffset)
            assertFalse(tab.endReached)
        }
    }

    @Test fun keepsGenuinelyCompleteSmallTab() {
        val cache = LibraryDiskCache(temporary.newFolder())
        val tab = LibrarySnapshot.Tab(List(80) { row(it.toString(), DownloadKind.TORRENT) }, true, 80)
        cache.save(LibrarySnapshot(DebridService.TORBOX, 123, mapOf(DownloadKind.TORRENT to tab)))
        assertEquals(tab, cache.load()!!.tabs.getValue(DownloadKind.TORRENT))
    }

    @Test fun deletesCorruptUnsupportedAndOversizedFiles() {
        val directory = temporary.newFolder()
        val cache = LibraryDiskCache(directory)
        val file = File(directory, "library.json")
        for (contents in listOf("{broken", """{"schemaVersion":2}""", "x".repeat(2 * 1024 * 1024 + 1))) {
            file.writeText(contents)
            assertNull(cache.load())
            assertFalse(file.exists())
        }
    }

    private fun row(id: String, kind: DownloadKind) = DebridDownload(
        id = id,
        service = DebridService.TORBOX,
        name = "Sample $id",
        sizeBytes = 42,
        status = DownloadStatus.DOWNLOADING,
        progress = 0.25f,
        kind = kind,
        createdAtEpochMs = 100,
        active = true,
        cached = false,
        expiresAtEpochMs = 200,
    )
}
