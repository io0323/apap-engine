package apap.runtime

import apap.cache.ratelimit.AcquireResult
import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.RateLimiter
import apap.cache.ratelimit.TokenBucketRateLimiter
import apap.context.QueryEmbedder
import apap.domain.model.execution.CbState
import apap.domain.model.execution.CircuitBreakerState
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.execution.adapter.out.InMemoryCircuitBreakerStateStore
import apap.execution.circuitbreaker.CircuitBreaker
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** ADR-0023: Memory注入のembedding呼出をCircuitBreaker/RateLimiter経由にする。 */
class ResilientQueryEmbedderTest {
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val events = InMemoryDomainEventPublisher()
    private val ids = InMemoryIdGenerator()
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA2")

    private fun circuitBreaker(): CircuitBreaker {
        val store = InMemoryCircuitBreakerStateStore()
        return CircuitBreaker(store, clock, events, ids)
    }

    private fun openCircuitBreaker(): CircuitBreaker {
        val store = InMemoryCircuitBreakerStateStore()
        val key = CbKey(providerId, modelId)
        store.save(CircuitBreakerState(key).transitionTo(CbState.OPEN, clock.now()))
        return CircuitBreaker(store, clock, events, ids)
    }

    private fun allowingRateLimiter(): RateLimiter = TokenBucketRateLimiter(clock, events, ids)

    private class RejectingRateLimiter : RateLimiter {
        override suspend fun acquire(
            scope: RateLimitScope,
            traceId: String,
            maxWait: Duration,
            cost: Int,
        ): AcquireResult = AcquireResult.Rejected(scope, waitedMillis = 0, maxWaitMillis = maxWait.toMillis())

        override fun tryAcquire(
            scope: RateLimitScope,
            cost: Int,
        ): Boolean = false

        override fun configure(
            scope: RateLimitScope,
            capacity: Int,
            refillPerSecond: Double,
        ) = Unit
    }

    private fun embedder(
        delegate: QueryEmbedder,
        circuitBreaker: CircuitBreaker = circuitBreaker(),
        rateLimiter: RateLimiter = allowingRateLimiter(),
    ): ResilientQueryEmbedder {
        val traceId = "trace-1"
        return ResilientQueryEmbedder(delegate, circuitBreaker, rateLimiter, providerId, modelId, tenantId, traceId)
    }

    @Test
    fun `a healthy delegate's vector passes through`() =
        runBlocking {
            val delegate = QueryEmbedder { listOf(0.1, 0.2, 0.3) }
            val result = embedder(delegate).embed(listOf(ContentPart.Text("hi")))
            assertEquals(listOf(0.1, 0.2, 0.3), result)
        }

    @Test
    fun `an OPEN circuit breaker degrades to an empty vector without calling the delegate`() =
        runBlocking {
            var called = false
            val delegate =
                QueryEmbedder {
                    called = true
                    listOf(0.1)
                }
            val result = embedder(delegate, circuitBreaker = openCircuitBreaker()).embed(listOf(ContentPart.Text("hi")))
            assertTrue(result.isEmpty())
            assertTrue(!called)
        }

    @Test
    fun `a rejecting rate limiter degrades to an empty vector without calling the delegate`() =
        runBlocking {
            var called = false
            val delegate =
                QueryEmbedder {
                    called = true
                    listOf(0.1)
                }
            val result = embedder(delegate, rateLimiter = RejectingRateLimiter()).embed(listOf(ContentPart.Text("hi")))
            assertTrue(result.isEmpty())
            assertTrue(!called)
        }

    @Test
    fun `a failing delegate degrades to an empty vector and records a circuit breaker failure`() =
        runBlocking {
            val delegate = QueryEmbedder { error("embedding provider unavailable") }
            val cb = circuitBreaker()
            val result = embedder(delegate, circuitBreaker = cb).embed(listOf(ContentPart.Text("hi")))
            assertTrue(result.isEmpty())
        }
}
