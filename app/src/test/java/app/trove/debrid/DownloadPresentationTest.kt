package app.trove.debrid

import app.trove.debrid.ui.downloads.baseFileName
import app.trove.debrid.ui.downloads.formatSize
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPresentationTest {
    @Test fun `header identity uses parsed fallback or resolved metadata`() {
        val row = sampleDownload().copy(name = "Baththa.2026.1080p.WEB-Rip.HEVC-GROUP.mkv")
        assertEquals(app.trove.debrid.core.MediaMetadata("Baththa", 2026, null),
            app.trove.debrid.ui.downloads.downloadIdentity(row, null))
        val resolved = app.trove.debrid.core.MediaMetadata("Synthetic Title", 2026, "movie")
        assertEquals(resolved, app.trove.debrid.ui.downloads.downloadIdentity(row, resolved))
    }

    @Test
    fun `file names strip folder prefixes including repeated download name`() {
        assertEquals("movie.mkv", baseFileName("Movie release/Movie release/movie.mkv"))
        assertEquals("movie.mkv", baseFileName("folder\\movie.mkv"))
        assertEquals("movie.mkv", baseFileName("movie.mkv"))
    }

    @Test
    fun `sizes use decimal provider bytes with a readable missing value`() {
        assertEquals("3.6 GB", formatSize(3_600_000_000L))
        assertEquals("3.5 GB", formatSize(3_500_000_000L))
        assertEquals("800 MB", formatSize(800_000_000L))
        assertEquals("12 KB", formatSize(12_000L))
        assertEquals("0 B", formatSize(0L))
        assertEquals("Size unavailable", formatSize(null))
    }
    @Test
    fun `tab counts retain all digits including zero`() {
        for (count in listOf(0, 75, 1000, 10000)) {
            assertEquals(count.toString(), app.trove.debrid.ui.downloads.formatCount(count))
        }
    }

    @Test
    fun `downloading presentation includes percent speed ETA and available swarm counts`() {
        val row = sampleDownload().copy(status = app.trove.debrid.core.DownloadStatus.DOWNLOADING,
            progress = 0.5f, downloadSpeedBytesPerSec = 12000, etaSeconds = 150, seeds = 8, peers = 0)
        val display = app.trove.debrid.ui.downloads.downloadProgress(row)!!
        assertEquals("50%", display.percentage)
        assertEquals(0.5f, display.fraction)
        assertEquals("12 KB/s", display.speed)
        assertEquals("2m 30s left", display.eta)
        assertEquals(8, display.seeds)
        assertEquals(0, display.peers)
    }

    @Test
    fun `every other state hides all progress even with stale stats`() {
        for (state in app.trove.debrid.core.DownloadStatus.entries.filter { it != app.trove.debrid.core.DownloadStatus.DOWNLOADING }) {
            org.junit.Assert.assertNull(app.trove.debrid.ui.downloads.downloadProgress(
                sampleDownload().copy(status = state, progress = 0.5f, downloadSpeedBytesPerSec = 100, etaSeconds = 150)))
        }
    }

    @Test
    fun `zero negative and absent speed and ETA are not displayed`() {
        for (value in listOf(null, 0L, -1L)) {
            val display = app.trove.debrid.ui.downloads.downloadProgress(sampleDownload().copy(
                status = app.trove.debrid.core.DownloadStatus.DOWNLOADING,
                downloadSpeedBytesPerSec = value, etaSeconds = value))!!
            org.junit.Assert.assertNull(display.speed)
            org.junit.Assert.assertNull(display.eta)
        }
        assertEquals("1h 1m left", app.trove.debrid.ui.downloads.formatEta(3660))
        assertEquals("1d 1h left", app.trove.debrid.ui.downloads.formatEta(90000))
        assertEquals("1s left", app.trove.debrid.ui.downloads.formatEta(1))
    }

    @Test
    fun `added date is compact localized and absent when unknown`() {
        val zone = java.time.ZoneId.of("UTC")
        val epoch = java.time.Instant.parse("2026-11-17T10:00:00Z").toEpochMilli()
        assertEquals("Nov 17, 2026", app.trove.debrid.ui.downloads.addedDate(epoch, zone, java.util.Locale.US))
        org.junit.Assert.assertNull(app.trove.debrid.ui.downloads.addedDate(null, zone, java.util.Locale.US))
        assertEquals("Nov 16, 2026", app.trove.debrid.ui.downloads.addedDate(
            java.time.Instant.parse("2026-11-17T00:30:00Z").toEpochMilli(),
            java.time.ZoneId.of("America/Los_Angeles"), java.util.Locale.US))
    }

}
