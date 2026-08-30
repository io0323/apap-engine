package apap.observability.health

import apap.domain.event.EventMetadata
import apap.domain.event.ProviderHealthChanged
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.infrastructure.eventbus.SynchronousEventBus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ProviderHealthAggregatorTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAA")
    private val providerA = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FBB")
    private val providerB = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FCC")
    private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun meta(eventId: String) = EventMetadata(eventId, occurredAt, "trace-1", tenantId, providerA.value, 0)

    @Test
    fun `check is UP before any ProviderHealthChanged event arrives`() {
        val bus = SynchronousEventBus()
        val aggregator = ProviderHealthAggregator(bus)
        assertEquals(HealthState.UP, aggregator.check().state)
        assertTrue(aggregator.snapshot().isEmpty())
    }

    @Test
    fun `snapshot reflects the latest status per provider`() {
        val bus = SynchronousEventBus()
        val aggregator = ProviderHealthAggregator(bus)

        bus.publish(
            ProviderHealthChanged(
                meta("e1"),
                providerA,
                ProviderHealthStatus.UP,
                ProviderHealthStatus.DEGRADED,
                "slow",
            ),
        )
        bus.publish(
            ProviderHealthChanged(
                meta("e2"),
                providerA,
                ProviderHealthStatus.DEGRADED,
                ProviderHealthStatus.UP,
                "recovered",
            ),
        )

        assertEquals(ProviderHealthStatus.UP, aggregator.snapshot()[providerA])
    }

    @Test
    fun `check reflects the worst status across all providers`() {
        val bus = SynchronousEventBus()
        val aggregator = ProviderHealthAggregator(bus)

        bus.publish(
            ProviderHealthChanged(
                meta("e1"),
                providerA,
                ProviderHealthStatus.UP,
                ProviderHealthStatus.DEGRADED,
                "slow",
            ),
        )
        bus.publish(
            ProviderHealthChanged(
                meta("e2"),
                providerB,
                ProviderHealthStatus.UP,
                ProviderHealthStatus.DOWN,
                "outage",
            ),
        )

        val result = aggregator.check()
        assertEquals(HealthState.DOWN, result.state)
        assertEquals("DEGRADED", result.details[providerA.value])
        assertEquals("DOWN", result.details[providerB.value])
    }

    @Test
    fun `a duplicate eventId delivery does not corrupt the snapshot`() {
        val bus = SynchronousEventBus()
        val aggregator = ProviderHealthAggregator(bus)

        val event =
            ProviderHealthChanged(meta("e1"), providerA, ProviderHealthStatus.UP, ProviderHealthStatus.DOWN, "outage")
        bus.publish(event)
        bus.publish(event)

        assertEquals(1, aggregator.snapshot().size)
        assertEquals(ProviderHealthStatus.DOWN, aggregator.snapshot()[providerA])
    }
}
