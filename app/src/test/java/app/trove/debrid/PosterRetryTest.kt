package app.trove.debrid

import app.trove.debrid.ui.downloads.loadPosterWithRetry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterRetryTest {
    private val posterUrl = "https://example.com/poster.jpg"

    @Test fun `network miss retries after the miss window and stops on success`() = runTest {
        val waits = mutableListOf<Long>()
        var attempts = 0
        val poster = loadPosterWithRetry(posterUrl, pause = { waits.add(it) }) {
            assertEquals(posterUrl, it)
            if (++attempts == 2) "poster" else null
        }

        assertEquals("poster", poster)
        assertEquals(2, attempts)
        assertEquals(listOf(60_000L), waits)
    }

    @Test fun `persistent miss stops after two retries and a new trigger has a fresh budget`() = runTest {
        val waits = mutableListOf<Long>()
        var attempts = 0
        suspend fun load() = loadPosterWithRetry<String>(posterUrl, pause = { waits.add(it) }) {
            attempts++
            null
        }

        assertNull(load())
        assertEquals(3, attempts)
        assertEquals(listOf(60_000L, 60_000L), waits)
        assertNull(load())
        assertEquals(6, attempts)
    }

    @Test fun `validation rejection never loads or waits`() = runTest {
        for (url in listOf(null, "http://example.com/poster.jpg", "$posterUrl?token=private")) {
            val poster = loadPosterWithRetry<String>(url, pause = { error("Unexpected wait") }) {
                error("Unexpected load")
            }
            assertNull(poster)
        }
    }
}
