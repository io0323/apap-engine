package apap.adapter.spi.plugin

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PluginManifestParserTest {
    private val validYaml =
        """
        plugin_id: adapter-example-a
        version: 1.0.0
        spi_version: ">=1.2 <2.0"
        entry_point: example.a.ExampleAdapter
        capabilities: [chat, embedding, streaming, tool_calling]
        auth_types: [api_key]
        signature: deadbeef
        """.trimIndent()

    @Test
    fun `parses a well-formed plugin manifest`() {
        val manifest = PluginManifestParser.parse(validYaml)
        assertEquals("adapter-example-a", manifest.pluginId)
        assertEquals(SemVer(1, 0, 0), manifest.version)
        assertEquals("example.a.ExampleAdapter", manifest.entryPoint)
        val expectedCapabilities =
            setOf(
                CapabilityId("chat"),
                CapabilityId("embedding"),
                CapabilityId("streaming"),
                CapabilityId("tool_calling"),
            )
        assertEquals(expectedCapabilities, manifest.capabilities)
        assertEquals(setOf("api_key"), manifest.authTypes)
        assertEquals("deadbeef", manifest.signature)
        assertEquals(true, manifest.spiVersionRange.contains(SemVer(1, 5, 0)))
    }

    @Test
    fun `ignores comment lines and blank lines`() {
        val yamlWithComments =
            """
            # this is a comment
            plugin_id: adapter-example-a

            version: 1.0.0 # trailing comment
            spi_version: ">=1.0 <2.0"
            entry_point: example.Entry
            capabilities: [chat]
            auth_types: [api_key]
            signature: sig
            """.trimIndent()
        val manifest = PluginManifestParser.parse(yamlWithComments)
        assertEquals("adapter-example-a", manifest.pluginId)
        assertEquals(SemVer(1, 0, 0), manifest.version)
    }

    @Test
    fun `unquotes single and double quoted scalar values`() {
        val yaml =
            """
            plugin_id: 'adapter-example-a'
            version: "1.0.0"
            spi_version: ">=1.0 <2.0"
            entry_point: example.Entry
            capabilities: [chat]
            auth_types: [api_key]
            signature: sig
            """.trimIndent()
        val manifest = PluginManifestParser.parse(yaml)
        assertEquals("adapter-example-a", manifest.pluginId)
        assertEquals(SemVer(1, 0, 0), manifest.version)
    }

    @Test
    fun `rejects a manifest missing a required field`() {
        val missingSignature =
            """
            plugin_id: adapter-example-a
            version: 1.0.0
            spi_version: ">=1.0 <2.0"
            entry_point: example.Entry
            capabilities: [chat]
            auth_types: [api_key]
            """.trimIndent()
        assertThrows(PluginManifestParseException::class.java) { PluginManifestParser.parse(missingSignature) }
    }

    @Test
    fun `rejects a line without a colon separator`() {
        assertThrows(PluginManifestParseException::class.java) { PluginManifestParser.parse("not-a-key-value-line") }
    }

    @Test
    fun `rejects a non flow-sequence value for a list field`() {
        val yaml =
            """
            plugin_id: adapter-example-a
            version: 1.0.0
            spi_version: ">=1.0 <2.0"
            entry_point: example.Entry
            capabilities: chat
            auth_types: [api_key]
            signature: sig
            """.trimIndent()
        assertThrows(PluginManifestParseException::class.java) { PluginManifestParser.parse(yaml) }
    }
}
