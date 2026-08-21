package apap.domain.model.plugin

import apap.domain.model.vo.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PluginRegistrationTest {
    private fun registration(signatureVerified: Boolean = false) =
        PluginRegistration(
            pluginId = "plugin-1",
            version = SemVer(1, 0, 0),
            spiVersion = SemVer(1, 0, 0),
            signature = "sig",
            signatureVerified = signatureVerified,
        )

    @Test
    fun `rejects blank signature`() {
        assertThrows(IllegalArgumentException::class.java) { registration().copy(signature = " ") }
    }

    @Test
    fun `load requires a verified signature`() {
        assertThrows(PluginSignatureNotVerifiedException::class.java) { registration(signatureVerified = false).load() }
        assertEquals(PluginRegistrationStatus.LOADED, registration(signatureVerified = true).load().status)
    }

    @Test
    fun `unload and quarantine change status`() {
        val loaded = registration(signatureVerified = true).load()
        assertEquals(PluginRegistrationStatus.UNLOADED, loaded.unload().status)
        assertEquals(PluginRegistrationStatus.QUARANTINED, loaded.quarantine().status)
    }
}
