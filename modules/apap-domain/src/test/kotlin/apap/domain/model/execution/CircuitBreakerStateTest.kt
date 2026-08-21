package apap.domain.model.execution

import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class CircuitBreakerStateTest {
    private val key = CbKey(ProviderId(testUlid('A')), ModelId(testUlid('B')))
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `CLOSED to OPEN records openedAt and increments openCount`() {
        val closed = CircuitBreakerState(key)
        val open = closed.transitionTo(CbState.OPEN, now)
        assertEquals(CbState.OPEN, open.state)
        assertEquals(now, open.openedAt)
        assertEquals(1, open.openCount)
    }

    @Test
    fun `OPEN to HALF_OPEN to CLOSED clears openedAt`() {
        val open = CircuitBreakerState(key).transitionTo(CbState.OPEN, now)
        val halfOpen = open.transitionTo(CbState.HALF_OPEN, now.plusSeconds(30))
        val closed = halfOpen.transitionTo(CbState.CLOSED, now.plusSeconds(31))
        assertNull(closed.openedAt)
    }

    @Test
    fun `HALF_OPEN back to OPEN increments openCount again`() {
        val halfOpen =
            CircuitBreakerState(key)
                .transitionTo(CbState.OPEN, now)
                .transitionTo(CbState.HALF_OPEN, now.plusSeconds(30))
        val reOpened = halfOpen.transitionTo(CbState.OPEN, now.plusSeconds(31))
        assertEquals(2, reOpened.openCount)
    }

    @Test
    fun `rejects CLOSED directly to HALF_OPEN`() {
        assertThrows(IllegalCbStateTransitionException::class.java) {
            CircuitBreakerState(key).transitionTo(CbState.HALF_OPEN, now)
        }
    }

    @Test
    fun `WindowStats computes failureRate and rejects invalid combinations`() {
        assertEquals(0.5, WindowStats(10, 5).failureRate, 1e-9)
        assertEquals(0.0, WindowStats(0, 0).failureRate, 1e-9)
        assertThrows(IllegalArgumentException::class.java) { WindowStats(5, 10) }
        assertThrows(IllegalArgumentException::class.java) { WindowStats(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { WindowStats(10, -1) }
    }
}
