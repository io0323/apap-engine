package apap.infrastructure.eventbus

import apap.domain.event.EventMetadata
import apap.domain.event.PluginUnloaded
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class IdempotentEventHandlerTest {
    private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun event(eventId: String): PluginUnloaded {
        val meta = EventMetadata(eventId, occurredAt, "trace", null, "plugin-a", 0)
        return PluginUnloaded(meta, "plugin-a")
    }

    @Test
    fun `invokes the delegate once per distinct eventId and skips repeats`() {
        var calls = 0
        val handler = IdempotentEventHandler { calls++ }

        handler(event("evt-1"))
        handler(event("evt-1"))
        handler(event("evt-2"))
        handler(event("evt-1"))

        assertEquals(2, calls)
    }
}
