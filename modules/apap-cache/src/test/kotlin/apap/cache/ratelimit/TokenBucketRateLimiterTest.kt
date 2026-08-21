package apap.cache.ratelimit

import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

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
    fun `acquire throws and publishes RateLimitExceeded when the bucket is empty`() {
        val limiter = TokenBucketRateLimiter(clock, events, ids, RateLimiterConfig(defaultCapacity = 1))
        limiter.acquire(scope, "trace-1")
        assertThrows(RateLimitExceededException::class.java) { limiter.acquire(scope, "trace-2") }
        assertEquals(1, events.publishedEvents.size)
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
        val providerScope =
            RateLimitScope.ProviderScope(
                apap.domain.model.vo
                    .ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1"),
            )
        assertTrue(limiter.tryAcquire(scope))
        assertTrue(limiter.tryAcquire(providerScope))
    }
}
