package apap.domain.model.capability

import apap.domain.model.vo.CapabilityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CapabilityDefinitionTest {
    private fun definition(status: CapabilityDefinitionStatus = CapabilityDefinitionStatus.DRAFT) =
        CapabilityDefinition(
            capabilityId = CapabilityId("chat"),
            name = "Chat",
            inputSchema = "{}",
            outputSchema = "{}",
            streamable = true,
            status = status,
        )

    @Test
    fun `rejects blank schemas or name`() {
        assertThrows(IllegalArgumentException::class.java) { definition().copy(name = " ") }
        assertThrows(IllegalArgumentException::class.java) { definition().copy(inputSchema = " ") }
        assertThrows(IllegalArgumentException::class.java) { definition().copy(outputSchema = " ") }
    }

    @Test
    fun `DRAFT allows unrestricted schema revision`() {
        val revised = definition().reviseSchema("{\"a\":1}", "{\"b\":1}", confirmedBackwardCompatible = false)
        assertEquals("{\"a\":1}", revised.inputSchema)
    }

    @Test
    fun `published schema revision requires confirmed backward compatibility`() {
        val ga = definition().publish()
        assertThrows(SchemaBackwardCompatibilityRequiredException::class.java) {
            ga.reviseSchema("{}", "{}", confirmedBackwardCompatible = false)
        }
        ga.reviseSchema("{}", "{}", confirmedBackwardCompatible = true)
    }

    @Test
    fun `publish and deprecate change status`() {
        assertEquals(CapabilityDefinitionStatus.GA, definition().publish().status)
        assertEquals(CapabilityDefinitionStatus.DEPRECATED, definition().deprecate().status)
    }
}
