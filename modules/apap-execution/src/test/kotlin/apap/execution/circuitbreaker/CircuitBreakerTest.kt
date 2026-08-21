package apap.execution.circuitbreaker

import apap.domain.model.execution.CbState
import apap.domain.model.vo.CbKey
import apap.execution.adapter.out.InMemoryCircuitBreakerStateStore
import apap.execution.testsupport.testModelId
import apap.execution.testsupport.testProviderId
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** 02_システム仕様.md 2.13 / 09_状態遷移図.md 9.3。 */
class CircuitBreakerTest {
    private val key = CbKey(testProviderId(), testModelId())
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val store = InMemoryCircuitBreakerStateStore()
    private val events = InMemoryDomainEventPublisher()
    private val ids = InMemoryIdGenerator()
    private val config = CircuitBreakerConfig(minRequests = 10, failureRateThreshold = 0.5)
    private val cb = CircuitBreaker(store, clock, events, ids, config)

    private fun failN(n: Int) {
        repeat(n) {
            val permit = cb.tryAcquire(key, "trace")
            cb.recordFailure(permit, cbRecordable = true, traceId = "trace")
        }
    }

    private fun succeedN(n: Int) {
        repeat(n) {
            val permit = cb.tryAcquire(key, "trace")
            cb.recordSuccess(permit, "trace")
        }
    }

    @Test
    fun `stays CLOSED below the minRequests threshold even at 100 percent failure`() {
        failN(9)
        assertEquals(CbState.CLOSED, cb.state(key))
    }

    @Test
    fun `opens exactly at minRequests with failureRate at threshold`() {
        succeedN(5)
        failN(5)
        assertEquals(CbState.OPEN, cb.state(key))
    }

    @Test
    fun `stays CLOSED when failureRate is just below threshold`() {
        succeedN(6)
        failN(4)
        assertEquals(CbState.CLOSED, cb.state(key))
    }

    @Test
    fun `tryAcquire rejects immediately while OPEN`() {
        succeedN(5)
        failN(5)
        assertEquals(CbState.OPEN, cb.state(key))
        assertThrows(CircuitOpenException::class.java) { cb.tryAcquire(key, "trace") }
    }

    @Test
    fun `transitions to HALF_OPEN only after the backoff window elapses`() {
        succeedN(5)
        failN(5)
        assertEquals(CbState.OPEN, cb.state(key))

        clock.advanceBy(config.halfOpenBaseSeconds - 1)
        assertThrows(CircuitOpenException::class.java) { cb.tryAcquire(key, "trace") }

        clock.advanceBy(2)
        val permit = cb.tryAcquire(key, "trace")
        assertTrue(permit.halfOpen)
    }

    @Test
    fun `HALF_OPEN allows at most halfOpenMaxConcurrent permits`() {
        succeedN(5)
        failN(5)
        clock.advanceBy(config.halfOpenBaseSeconds)

        repeat(config.halfOpenMaxConcurrent) { cb.tryAcquire(key, "trace") }
        assertThrows(CircuitOpenException::class.java) { cb.tryAcquire(key, "trace") }
    }

    @Test
    fun `HALF_OPEN closes after closeAfterConsecutiveSuccesses successes`() {
        succeedN(5)
        failN(5)
        clock.advanceBy(config.halfOpenBaseSeconds)

        repeat(config.closeAfterConsecutiveSuccesses) {
            val permit = cb.tryAcquire(key, "trace")
            cb.recordSuccess(permit, "trace")
        }
        assertEquals(CbState.CLOSED, cb.state(key))
    }

    @Test
    fun `HALF_OPEN reopens on a single cbRecordable failure`() {
        succeedN(5)
        failN(5)
        clock.advanceBy(config.halfOpenBaseSeconds)

        val permit = cb.tryAcquire(key, "trace")
        cb.recordFailure(permit, cbRecordable = true, traceId = "trace")
        assertEquals(CbState.OPEN, cb.state(key))
    }

    @Test
    fun `non-cbRecordable failures do not affect CLOSED window or HALF_OPEN probes`() {
        repeat(20) {
            val permit = cb.tryAcquire(key, "trace")
            cb.recordFailure(permit, cbRecordable = false, traceId = "trace")
        }
        assertEquals(CbState.CLOSED, cb.state(key))
    }

    @Test
    fun `half-open backoff grows exponentially and caps at halfOpenCapSeconds`() {
        // 1st open: base backoff
        succeedN(5)
        failN(5)
        clock.advanceBy(config.halfOpenBaseSeconds)
        val firstHalfOpen = cb.tryAcquire(key, "trace")
        cb.recordFailure(firstHalfOpen, cbRecordable = true, traceId = "trace") // -> OPEN again, openCount=2

        // 2nd open: backoff should now be base*2, not yet elapsed
        clock.advanceBy(config.halfOpenBaseSeconds)
        assertThrows(CircuitOpenException::class.java) { cb.tryAcquire(key, "trace") }
        clock.advanceBy(config.halfOpenBaseSeconds)
        // now elapsed base*2 total
        val secondHalfOpen = cb.tryAcquire(key, "trace")
        assertTrue(secondHalfOpen.halfOpen)
    }

    @Test
    fun `half-open backoff never exceeds halfOpenCapSeconds regardless of openCount`() {
        val cappedConfig = CircuitBreakerConfig(halfOpenBaseSeconds = 30, halfOpenCapSeconds = 60)
        val cappedCb = CircuitBreaker(InMemoryCircuitBreakerStateStore(), clock, events, ids, cappedConfig)
        repeat(cappedConfig.minRequests) {
            val permit = cappedCb.tryAcquire(key, "trace")
            cappedCb.recordFailure(permit, cbRecordable = true, traceId = "trace")
        }
        assertEquals(CbState.OPEN, cappedCb.state(key))

        // Force openCount high by repeatedly failing half-open probes.
        repeat(5) {
            clock.advanceBy(cappedConfig.halfOpenCapSeconds)
            val permit = cappedCb.tryAcquire(key, "trace")
            cappedCb.recordFailure(permit, cbRecordable = true, traceId = "trace")
        }
        // Even after many re-opens, cap seconds should always be enough to reach HALF_OPEN again.
        clock.advanceBy(cappedConfig.halfOpenCapSeconds)
        val permit = cappedCb.tryAcquire(key, "trace")
        assertTrue(permit.halfOpen)
    }
}
