package apap.provider

import apap.domain.model.vo.CapabilityId
import apap.testkit.inmemory.InMemoryCapabilityRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FR-CAP-017: 新Capabilityをスキーマ登録のみで追加可能とすること。 */
class CapabilityRegistryTest {
    private val repository = InMemoryCapabilityRepository()
    private val registry = CapabilityRegistry(repository)
    private val customCapabilityId = CapabilityId("custom_capability")
    private val requiredPromptSchema =
        """{"type":"object","required":["prompt"],"properties":{"prompt":{"type":"string"}}}"""

    private fun registerCustomCapability() =
        registry.register(
            RegisterCapabilityCommand(
                capabilityId = customCapabilityId,
                name = "Custom",
                inputSchema = requiredPromptSchema,
                outputSchema = """{"type":"object"}""",
                streamable = false,
            ),
        )

    @Test
    fun `register stores a DRAFT CapabilityDefinition`() {
        val definition = registerCustomCapability()

        assertEquals(definition, repository.findById(customCapabilityId))
    }

    @Test
    fun `validateInput accepts input satisfying required properties`() {
        registerCustomCapability()

        val result = registry.validateInput(customCapabilityId, """{"prompt":"hello"}""")

        assertTrue(result.valid)
    }

    @Test
    fun `validateInput rejects input missing a required property`() {
        registerCustomCapability()

        val result = registry.validateInput(customCapabilityId, """{}""")

        assertFalse(result.valid)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `seedInitialCapabilities registers all 20 capabilities from 2_4`() {
        val seeded = registry.seedInitialCapabilities()

        assertEquals(20, seeded.size)
        assertEquals(20, repository.listAll().size)
        assertTrue(repository.findById(CapabilityId("chat")) != null)
        assertTrue(repository.findById(CapabilityId("batch")) != null)
    }
}
