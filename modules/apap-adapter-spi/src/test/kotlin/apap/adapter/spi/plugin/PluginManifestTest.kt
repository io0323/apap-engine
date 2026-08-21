package apap.adapter.spi.plugin

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.SemVer
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PluginManifestTest {
    private fun manifest(
        pluginId: String = "adapter-example-a",
        entryPoint: String = "example.Entry",
        authTypes: Set<String> = setOf("api_key"),
        signature: String = "sig",
    ) = PluginManifest(
        pluginId = pluginId,
        version = SemVer(1, 0, 0),
        spiVersionRange = SemVerRange.parse(">=1.0 <2.0"),
        entryPoint = entryPoint,
        capabilities = setOf(CapabilityId("chat")),
        authTypes = authTypes,
        signature = signature,
    )

    @Test
    fun `rejects a blank plugin_id`() {
        assertThrows(IllegalArgumentException::class.java) { manifest(pluginId = " ") }
    }

    @Test
    fun `rejects a blank entry_point`() {
        assertThrows(IllegalArgumentException::class.java) { manifest(entryPoint = " ") }
    }

    @Test
    fun `rejects an empty auth_types set`() {
        assertThrows(IllegalArgumentException::class.java) { manifest(authTypes = emptySet()) }
    }

    @Test
    fun `rejects a blank signature`() {
        assertThrows(IllegalArgumentException::class.java) { manifest(signature = " ") }
    }
}
