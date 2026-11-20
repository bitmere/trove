package app.trove.debrid

import app.trove.debrid.core.DebridError
import app.trove.debrid.ui.downloads.POSTER_MISS_TTL_MS
import app.trove.debrid.ui.downloads.PosterLoad
import app.trove.debrid.ui.downloads.PosterMissCache
import app.trove.debrid.ui.downloads.isTransientPosterFailure
import app.trove.debrid.ui.downloads.loadPosterWithRetry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class PosterRetryTest {
    private val posterUrl = "https://example.com/poster.jpg"

    @Test fun `transient retries wait until shared eligibility and back off`() = runTest {
        val waits = mutableListOf<Long>()
        var time = 500L
        var attempts = 0
        val poster = loadPosterWithRetry(posterUrl, { time }, {
            waits += it
            time += it
        }) {
            assertEquals(posterUrl, it)
            when (++attempts) {
                1 -> PosterLoad.Failed(2_000, transient = true)
                2 -> PosterLoad.Failed(time + 1_000, transient = true)
                else -> PosterLoad.Ready("poster")
            }
        }

        assertEquals("poster", poster)
        assertEquals(3, attempts)
        assertEquals(listOf(1_500L, 2_000L), waits)
    }

    @Test fun `persistent failures stop automatic retries and a new trigger can load immediately`() = runTest {
        var loads = 0
        val first = loadPosterWithRetry<String>(posterUrl, { 0 }, { error("Unexpected wait") }) {
            loads++
            PosterLoad.Failed(POSTER_MISS_TTL_MS, transient = false)
        }
        val manuallyRetried = loadPosterWithRetry(posterUrl, { 0 }, { error("Unexpected wait") }) {
            loads++
            PosterLoad.Ready("poster")
        }
        assertNull(first)
        assertEquals("poster", manuallyRetried)
        assertEquals(2, loads)
    }

    @Test fun `shared misses expire at their deadline and a tap clears them before expiry`() {
        var time = 10L
        val cache = PosterMissCache { time }
        val transient = cache.record(posterUrl, transient = true)
        time = transient.retryAt - 1
        assertEquals(transient, cache.get(posterUrl))
        time++
        assertNull(cache.get(posterUrl))

        val persistent = cache.record(posterUrl, transient = false)
        assertEquals(time + POSTER_MISS_TTL_MS, persistent.retryAt)
        assertEquals(persistent, cache.get(posterUrl))
        cache.clear(posterUrl)
        assertNull(cache.get(posterUrl))
    }

    @Test fun `transient failures stop after three attempts`() = runTest {
        var attempts = 0
        assertNull(loadPosterWithRetry<String>(posterUrl, { 0 }, {}) {
            attempts++
            PosterLoad.Failed(1_000, transient = true)
        })
        assertEquals(3, attempts)
    }

    @Test fun `network and server failures are transient but missing and invalid images are persistent`() {
        assertTrue(isTransientPosterFailure(DebridError.Network(SocketTimeoutException())))
        assertTrue(isTransientPosterFailure(DebridError.Provider("http503", "")))
        assertFalse(isTransientPosterFailure(DebridError.Provider("http404", "")))
        assertFalse(isTransientPosterFailure(DebridError.Provider("bodyTooLarge", "")))
        assertFalse(isTransientPosterFailure(IllegalArgumentException()))
    }

    @Test fun `validation rejection never loads or waits`() = runTest {
        for (url in listOf(null, "http://example.com/poster.jpg", "$posterUrl?token=private")) {
            assertNull(loadPosterWithRetry<String>(url, { 0 }, { error("Unexpected wait") }) {
                error("Unexpected load")
            })
        }
    }
}
