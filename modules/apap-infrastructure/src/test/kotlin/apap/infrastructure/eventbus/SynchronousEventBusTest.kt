package apap.infrastructure.eventbus

import apap.domain.event.DomainEvent
import apap.domain.event.EventMetadata
import apap.domain.event.PluginUnloaded
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SynchronousEventBusTest {
    private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun event(eventId: String): PluginUnloaded {
        val meta = EventMetadata(eventId, occurredAt, "trace", null, "plugin-a", 0)
        return PluginUnloaded(meta, "plugin-a")
    }

    @Test
    fun `publish fans out synchronously to every subscriber`() {
        val bus = SynchronousEventBus()
        val seenByFirst = mutableListOf<DomainEvent>()
        val seenBySecond = mutableListOf<DomainEvent>()
        bus.subscribe { seenByFirst += it }
        bus.subscribe { seenBySecond += it }

        bus.publish(event("evt-1"))

        assertEquals(1, seenByFirst.size)
        assertEquals(1, seenBySecond.size)
        assertEquals("evt-1", seenByFirst.single().meta.eventId)
    }

    @Test
    fun `a throwing subscriber does not prevent delivery to other subscribers or to the caller`() {
        val bus = SynchronousEventBus()
        val seen = mutableListOf<DomainEvent>()
        bus.subscribe { error("boom") }
        bus.subscribe { seen += it }

        bus.publish(event("evt-1"))

        assertEquals(1, seen.size)
    }

    @Test
    fun `subscribeIdempotent delivers a duplicate eventId only once`() {
        val bus = SynchronousEventBus()
        val seen = mutableListOf<DomainEvent>()
        bus.subscribeIdempotent { seen += it }

        bus.publish(event("evt-1"))
        bus.publish(event("evt-1"))
        bus.publish(event("evt-2"))

        assertEquals(2, seen.size)
        assertEquals(setOf("evt-1", "evt-2"), seen.map { it.meta.eventId }.toSet())
    }

    @Test
    fun `plain subscribe receives duplicate eventId deliveries as-is`() {
        val bus = SynchronousEventBus()
        val seen = mutableListOf<DomainEvent>()
        bus.subscribe { seen += it }

        bus.publish(event("evt-1"))
        bus.publish(event("evt-1"))

        assertEquals(2, seen.size)
    }

    @Test
    fun `every publish is forwarded to the external forwarder`() {
        val forwarded = mutableListOf<DomainEvent>()
        val bus = SynchronousEventBus(externalForwarder = ExternalEventBusForwarder { forwarded += it })

        bus.publish(event("evt-1"))
        bus.publish(event("evt-1"))

        assertEquals(2, forwarded.size)
        assertTrue(forwarded.all { it.meta.eventId == "evt-1" })
    }
}
