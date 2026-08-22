package apap.cache.ratelimit

import apap.domain.event.RateLimitExceeded
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** タスク要件item3: RateLimiterの有界待機（02_システム仕様.md 2.8 step8b / 2.19の action(wait/reject)）。 */
class TokenBucketRateLimiterTest {
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val events = InMemoryDomainEventPublisher()
    private val ids = InMemoryIdGenerator()
    private val scope = RateLimitScope.TenantScope(TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0"))

    @Test
    fun `allows consumption up to capacity then rejects`() {
        val limiter = TokenBucketRateLimiter(clock, events, ids, RateLimiterConfig(defaultCapacity = 3))
        assertTrue(limiter.tryAcquire(scope))
        assertTrue(limiter.tryAcquire(scope))
        assertTrue(limiter.tryAcquire(scope))
        assertFalse(limiter.tryAcquire(scope))
    }

    @Test
    fun `acquire with zero maxWait behaves like an immediate accept-or-reject check`() =
        runBlocking {
            val limiter = TokenBucketRateLimiter(clock, events, ids, RateLimiterConfig(defaultCapacity = 1))
            val first = limiter.acquire(scope, "trace-1", Duration.ZERO)
            assertTrue(first is AcquireResult.Acquired)
            assertEquals(0L, (first as AcquireResult.Acquired).waitedMillis)

            val second = limiter.acquire(scope, "trace-2", Duration.ZERO)
            assertTrue(second is AcquireResult.Rejected)
            // 14.3 RateLimitExceeded must still fire on the reject path (unchanged by the
            // Permit -> AcquireResult return-type change).
            assertEquals(1, events.publishedEvents.size)
            assertTrue(events.publishedEvents.single() is RateLimitExceeded)
        }

    @Test
    fun `acquire waits up to maxWait and succeeds once the bucket refills`() =
        runBlocking {
            // capacity=1, refill 20 tokens/sec -> the 2nd token needs ~50ms of real wait inside acquire().
            val config = RateLimiterConfig(defaultCapacity = 1, defaultRefillPerSecond = 20.0)
            val limiter = TokenBucketRateLimiter(clock, events, ids, config)
            limiter.acquire(scope, "trace-1", Duration.ZERO)

            // Bucket refill is driven by the injected Clock, not wall-clock time, so a plain real
            // delay() inside acquire() would never actually refill it under a frozen InMemoryClock.
            // Advance the injected clock concurrently with acquire()'s internal real-time delay to
            // simulate wall-clock time genuinely passing (same technique as StreamingEngineTest's
            // idle-timeout test).
            val clockAdvancer =
                launch {
                    delay(10)
                    clock.advanceBy(1)
                }
            val result =
                withTimeout(2_000) {
                    limiter.acquire(scope, "trace-2", Duration.ofMillis(500))
                }
            clockAdvancer.join()
            // The wait succeeded: no RateLimitExceeded should have been published, and the outcome
            // carries the wait duration it actually took (not reconstructed from a log string).
            assertTrue(events.publishedEvents.isEmpty())
            assertTrue(result is AcquireResult.Acquired)
            assertTrue((result as AcquireResult.Acquired).waitedMillis > 0L)
        }

    @Test
    fun `acquire rejects promptly, without waiting the full deficit, when it exceeds maxWait`() =
        runBlocking {
            // capacity=1, refill 1 token/sec -> the 2nd token needs ~1000ms, far more than maxWait below.
            val config = RateLimiterConfig(defaultCapacity = 1, defaultRefillPerSecond = 1.0)
            val limiter = TokenBucketRateLimiter(clock, events, ids, config)
            limiter.acquire(scope, "trace-1", Duration.ZERO)

            // If acquire actually waited out the ~1000ms deficit before giving up, this would time out.
            val result =
                withTimeout(500) {
                    limiter.acquire(scope, "trace-2", Duration.ofMillis(10))
                }
            assertTrue(result is AcquireResult.Rejected)
            assertEquals(10L, (result as AcquireResult.Rejected).maxWaitMillis)
            assertEquals(1, events.publishedEvents.size)
            assertTrue(events.publishedEvents.single() is RateLimitExceeded)
        }

    @Test
    fun `refills over time up to capacity`() {
        val config = RateLimiterConfig(defaultCapacity = 2, defaultRefillPerSecond = 1.0)
        val limiter = TokenBucketRateLimiter(clock, events, ids, config)
        assertTrue(limiter.tryAcquire(scope))
        assertTrue(limiter.tryAcquire(scope))
        assertFalse(limiter.tryAcquire(scope))

        clock.advanceBy(1)
        assertTrue(limiter.tryAcquire(scope))
        assertFalse(limiter.tryAcquire(scope))
    }

    @Test
    fun `refill never exceeds configured capacity`() {
        val config = RateLimiterConfig(defaultCapacity = 2, defaultRefillPerSecond = 100.0)
        val limiter = TokenBucketRateLimiter(clock, events, ids, config)
        assertTrue(limiter.tryAcquire(scope))
        clock.advanceBy(1000)
        // Even though refill math would exceed capacity, only 2 tokens should ever be consumable at once.
        assertTrue(limiter.tryAcquire(scope))
        assertTrue(limiter.tryAcquire(scope))
        assertFalse(limiter.tryAcquire(scope))
    }

    @Test
    fun `configure overrides the default bucket size per scope`() {
        val limiter = TokenBucketRateLimiter(clock, events, ids, RateLimiterConfig(defaultCapacity = 1))
        limiter.configure(scope, capacity = 5, refillPerSecond = 1.0)
        repeat(5) { assertTrue(limiter.tryAcquire(scope)) }
        assertFalse(limiter.tryAcquire(scope))
    }

    @Test
    fun `tenant and provider scopes are tracked independently`() {
        val limiter = TokenBucketRateLimiter(clock, events, ids, RateLimiterConfig(defaultCapacity = 1))
        val providerScope = RateLimitScope.ProviderScope(ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1"))
        assertTrue(limiter.tryAcquire(scope))
        assertTrue(limiter.tryAcquire(providerScope))
    }
}
