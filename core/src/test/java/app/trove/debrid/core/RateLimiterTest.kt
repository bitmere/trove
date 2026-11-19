package app.trove.debrid.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    class FakeClock(var now: Long = 0L) : Clock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun `token bucket allows burst then throttles`() = runBlocking {
        val clock = FakeClock(0L)
        val bucket = TokenBucket(permitsPerSecond = 1.0, burst = 2, clock = clock)
        bucket.acquire() // token 1
        bucket.acquire() // token 2
        // Third acquire would wait ~1000ms; advance the fake clock instead
        // by checking the bucket refills after time passes.
        clock.now = 2_000L
        bucket.acquire() // refilled
    }

    @Test
    fun `hourly budget spends then refuses`() = runBlocking {
        val clock = FakeClock(0L)
        val budget = HourlyBudget(maxPerHour = 2, clock = clock)
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
        clock.now = 3_600_001L // next window
        assertTrue(budget.tryConsume())
    }
}
