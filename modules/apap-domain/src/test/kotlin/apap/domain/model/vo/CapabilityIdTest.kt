package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CapabilityIdTest {
    @Test
    fun `accepts lowercase snake_case within length bounds`() {
        CapabilityId("chat")
        CapabilityId("structured_output")
    }

    @Test
    fun `rejects uppercase`() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityId("Chat") }
    }

    @Test
    fun `rejects too short value`() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityId("ab") }
    }

    @Test
    fun `rejects value exceeding 40 characters`() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityId("a".repeat(41)) }
    }

    @Test
    fun `rejects digits and hyphens`() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityId("chat2") }
        assertThrows(IllegalArgumentException::class.java) { CapabilityId("chat-standard") }
    }
}
