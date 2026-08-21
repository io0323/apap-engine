package apap.domain.model.provider

import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProviderTest {
    private val regionTable = RegionCodeTable(setOf("jp-east"))
    private val jpEast = Region.of("jp-east", regionTable)
    private val rateLimits = RateLimits(rpm = 60, tpm = 100_000, concurrent = 10)

    private fun provider(
        status: ProviderStatus = ProviderStatus.REGISTERED,
        credentialRefs: List<CredentialRef> = listOf(CredentialRef("secret", 1, CredentialState.STANDBY)),
        endpoints: List<Endpoint> = emptyList(),
    ) = Provider(
        providerId = ProviderId(testUlid('A')),
        name = "test-provider",
        adapterPluginId = testUlid('B'),
        spiVersion =
            SemVer(1, 0, 0),
        endpoints = endpoints,
        authType = "api_key",
        credentialRefs = credentialRefs,
        rateLimits = rateLimits,
        priority = 50,
        regions = setOf(jpEast),
        status = status,
    )

    @Test
    fun `requires at least one credentialRef`() {
        assertThrows(IllegalArgumentException::class.java) { provider(credentialRefs = emptyList()) }
    }

    @Test
    fun `rejects more than one ACTIVE credentialRef`() {
        assertThrows(IllegalArgumentException::class.java) {
            provider(
                credentialRefs =
                    listOf(
                        CredentialRef("a", 1, CredentialState.ACTIVE),
                        CredentialRef("b", 1, CredentialState.ACTIVE),
                    ),
            )
        }
    }

    @Test
    fun `rejects endpoint region outside provider regions`() {
        val euWest = Region.of("eu-west", RegionCodeTable(setOf("eu-west")))
        assertThrows(IllegalArgumentException::class.java) {
            provider(endpoints = listOf(Endpoint("ep1", euWest, "https://x", 100)))
        }
        // sanity: an endpoint whose region is one of the provider's regions is accepted
        provider(endpoints = listOf(Endpoint("ep1", jpEast, "https://x", 100)))
    }

    @Test
    fun `rejects blank name or authType`() {
        assertThrows(IllegalArgumentException::class.java) { provider().copy(name = " ") }
        assertThrows(IllegalArgumentException::class.java) { provider().copy(authType = " ") }
    }

    @Test
    fun `withCredentialRefs replaces credentialRefs and re-validates the ACTIVE-at-most-one invariant`() {
        val rotated = provider().withCredentialRefs(listOf(CredentialRef("new-secret", 1, CredentialState.ACTIVE)))
        assertEquals(CredentialState.ACTIVE, rotated.credentialRefs.single().state)
        assertThrows(IllegalArgumentException::class.java) {
            provider().withCredentialRefs(
                listOf(
                    CredentialRef("a", 1, CredentialState.ACTIVE),
                    CredentialRef("b", 1, CredentialState.ACTIVE),
                ),
            )
        }
    }

    @Test
    fun `rejects priority outside 1 to 100`() {
        assertThrows(IllegalArgumentException::class.java) { provider().copy(priority = 0) }
        assertThrows(IllegalArgumentException::class.java) { provider().copy(priority = 101) }
    }

    @Test
    fun `follows the 9_1 lifecycle for the happy path`() {
        val registered = provider()
        val validating = registered.transitionTo(ProviderStatus.VALIDATING)
        val active = validating.transitionTo(ProviderStatus.ACTIVE)
        val draining = active.transitionTo(ProviderStatus.DRAINING)
        val disabled = draining.transitionTo(ProviderStatus.DISABLED)
        val deleted = disabled.transitionTo(ProviderStatus.DELETED)
        assertEquals(ProviderStatus.DELETED, deleted.status)
    }

    @Test
    fun `allows suspend and resume`() {
        val active = provider(status = ProviderStatus.ACTIVE)
        val suspended = active.transitionTo(ProviderStatus.SUSPENDED)
        assertEquals(ProviderStatus.ACTIVE, suspended.transitionTo(ProviderStatus.ACTIVE).status)
    }

    @Test
    fun `allows re-validation after disable`() {
        val disabled = provider(status = ProviderStatus.DISABLED)
        assertEquals(ProviderStatus.VALIDATING, disabled.transitionTo(ProviderStatus.VALIDATING).status)
    }

    @Test
    fun `rejects skipping VALIDATING to reach ACTIVE directly`() {
        assertThrows(IllegalProviderStateTransitionException::class.java) {
            provider(status = ProviderStatus.REGISTERED).transitionTo(ProviderStatus.ACTIVE)
        }
    }

    @Test
    fun `rejects transition out of terminal DELETED`() {
        assertThrows(IllegalProviderStateTransitionException::class.java) {
            provider(status = ProviderStatus.DELETED).transitionTo(ProviderStatus.REGISTERED)
        }
    }

    @Test
    fun `RateLimits rejects non positive values`() {
        assertThrows(IllegalArgumentException::class.java) { RateLimits(0, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { RateLimits(1, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { RateLimits(1, 1, 0) }
    }

    @Test
    fun `Endpoint rejects a blank baseUrl or a non positive weight`() {
        assertThrows(IllegalArgumentException::class.java) { Endpoint("ep1", jpEast, " ", 100) }
        assertThrows(IllegalArgumentException::class.java) { Endpoint("ep1", jpEast, "https://x", 0) }
    }
}
