package apap.infrastructure.persistence.inmemory

import apap.domain.model.capability.CapabilityDefinition
import apap.domain.model.vo.CapabilityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemoryCapabilityRepositoryTest {
    private fun capability(id: String) =
        CapabilityDefinition(
            capabilityId = CapabilityId(id),
            name = id,
            inputSchema = "{}",
            outputSchema = "{}",
            streamable = false,
        )

    @Test
    fun `registers and finds a capability by id`() {
        val repo = InMemoryCapabilityRepository()
        repo.register(capability("chat"))

        assertEquals("chat", repo.findById(CapabilityId("chat"))?.capabilityId?.value)
        assertNull(repo.findById(CapabilityId("embedding")))
    }

    @Test
    fun `listAll returns every registered capability`() {
        val repo = InMemoryCapabilityRepository()
        repo.register(capability("chat"))
        repo.register(capability("embedding"))

        assertEquals(setOf("chat", "embedding"), repo.listAll().map { it.capabilityId.value }.toSet())
    }
}
