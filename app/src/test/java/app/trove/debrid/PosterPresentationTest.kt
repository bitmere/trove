package app.trove.debrid

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.trove.debrid.core.MediaMetadata
import app.trove.debrid.ui.downloads.DownloadsUiState
import app.trove.debrid.ui.downloads.PosterSlot
import app.trove.debrid.ui.downloads.RowPosterSize
import app.trove.debrid.ui.downloads.awaitPoster
import app.trove.debrid.ui.downloads.detailsPosterSize
import app.trove.debrid.ui.downloads.posterPrefetchPlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PosterPresentationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a mounted failed slot picks up the sheet bitmap for the same URL`() = runTest {
        val url = "https://example.invalid/poster.jpg"
        val generation = MutableStateFlow(0L)
        val cache = mutableMapOf<String, String>()
        var row = PosterSlot<String>()
        var visibleRequests = 0
        var failures = 0
        val mountedLoad = launch {
            val bitmap = awaitPoster(
                generation,
                force = false,
                load = { force ->
                    assertFalse(force)
                    cache[url] ?: run {
                        visibleRequests++
                        null
                    }
                },
                onFailure = { failures++ },
            )
            row = row.afterLoad(url, bitmap)
        }

        runCurrent()
        assertNull(row.bitmap)
        assertEquals(1, failures)
        assertTrue(mountedLoad.isActive)

        generation.value++ // A different URL finished while this URL is still missed.
        runCurrent()
        assertEquals(1, failures)
        assertEquals(2, visibleRequests)
        assertTrue(mountedLoad.isActive)

        cache[url] = "sheet bitmap"
        generation.value++
        runCurrent()
        assertEquals("sheet bitmap", row.bitmap)
        assertEquals(url, row.decodedUrl)
        assertEquals(2, visibleRequests)
        assertEquals(1, failures)
        assertTrue(mountedLoad.isCompleted)
    }

    @Test fun `the poster slot stays visible across missing metadata and failed replacement`() {
        val legacyUrl = "https://example.invalid/legacy.jpg"
        val replacementUrl = "https://example.invalid/replacement.jpg"
        val empty = PosterSlot<String>()
        assertFalse(empty.hasPoster(null))
        assertTrue(empty.hasPoster(legacyUrl))
        val displayed = empty.afterLoad(legacyUrl, "legacy bitmap")
        assertTrue(displayed.hasPoster(null))
        assertSame(displayed, displayed.afterLoad(replacementUrl, null))
        val updated = displayed.afterLoad(replacementUrl, "replacement bitmap")
        assertEquals("replacement bitmap", updated.bitmap)
        assertEquals(replacementUrl, updated.decodedUrl)
    }

    @Test fun `sheet keeps one responsive size while row keeps its reserved slot`() {
        assertEquals(DpSize(48.dp, 72.dp), RowPosterSize)
        assertEquals(DpSize(80.dp, 120.dp), detailsPosterSize(312.dp, 1f))
        assertEquals(DpSize(64.dp, 96.dp), detailsPosterSize(272.dp, 1f))
        assertEquals(DpSize(64.dp, 96.dp), detailsPosterSize(400.dp, 1.3f))
    }

    @Test fun `prefetch needs settled viewport metadata and selects only the next three rows`() {
        val rows = List(7) { sampleDownload(id = it.toString()) }
        val metadata = rows.associate { row ->
            (row.kind to row.id) to MediaMetadata("Title", null, null, "https://example.invalid/${row.id}.jpg")
        }
        val state = DownloadsUiState(metadata = metadata)
        val pending = state.copy(metadata = metadata - (rows[1].kind to rows[1].id))
        assertNull(posterPrefetchPlan(pending, rows, listOf(1, 2)))
        val plan = posterPrefetchPlan(state, rows, listOf(1, 2))!!
        assertEquals(listOf("1.jpg", "2.jpg"), plan.visible.map { it.substringAfterLast('/') })
        assertEquals(listOf("3.jpg", "4.jpg", "5.jpg"), plan.next.map { it.substringAfterLast('/') })
    }
}
