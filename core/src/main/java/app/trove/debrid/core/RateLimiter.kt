package app.trove.debrid.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Clock abstraction so rate limiting is testable. */
fun interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Token bucket: sustained [permitsPerSecond], bursting up to [burst].
 *  [acquire] suspends until a permit is available. */
class TokenBucket(
    private val permitsPerSecond: Double,
    private val burst: Int,
    private val clock: Clock = SystemClock,
) {
    private val mutex = Mutex()
    private var tokens: Double = burst.toDouble()
    private var lastRefillMs: Long = clock.nowMillis()

    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                val now = clock.nowMillis()
                val elapsed = (now - lastRefillMs).coerceAtLeast(0)
                tokens = (tokens + elapsed * permitsPerSecond / 1000.0).coerceAtMost(burst.toDouble())
                lastRefillMs = now
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    ((1.0 - tokens) * 1000.0 / permitsPerSecond).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }
}

/** Fixed-window hourly budget (e.g. TorBox createtorrent 50/hour).
 *  Returns false when the budget is spent, without waiting. */
class HourlyBudget(
    private val maxPerHour: Int,
    private val clock: Clock = SystemClock,
) {
    private val mutex = Mutex()
    private var windowStartMs: Long = clock.nowMillis()
    private var used: Int = 0

    suspend fun tryConsume(): Boolean = mutex.withLock {
        val now = clock.nowMillis()
        if (now - windowStartMs >= 3_600_000L) {
            windowStartMs = now
            used = 0
        }
        if (used >= maxPerHour) return false
        used++
        true
    }
}
