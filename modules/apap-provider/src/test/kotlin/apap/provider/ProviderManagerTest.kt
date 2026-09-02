package apap.provider

import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryProviderRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FR-PRV-001/002/006: ProviderManagerの登録/検証/有効化/DRAINING/無効化/削除ユースケース。 */
class ProviderManagerTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val providerRepository = InMemoryProviderRepository()
    private val eventPublisher = InMemoryDomainEventPublisher()
    private val clock = InMemoryClock()
    private val idGenerator = InMemoryIdGenerator()
    private val adapterRegistry = InMemoryAdapterRegistry()
    private val manager = ProviderManager(providerRepository, eventPublisher, clock, idGenerator, adapterRegistry)

    private fun registerCommand() =
        RegisterProviderCommand(
            name = "test-provider",
            adapterPluginId = "plugin-a",
            spiVersion = SemVer(1, 0, 0),
            endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
            authType = "api_key",
            credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.STANDBY)),
            rateLimits = RateLimits(rpm = 60, tpm = 1000, concurrent = 10),
            priority = 50,
            regions = setOf(region),
        )

    private fun manifest(capabilities: Set<CapabilityId> = setOf(CapabilityId("chat"))) =
        PluginManifest(
            pluginId = "plugin-a",
            version = SemVer(1, 0, 0),
            spiVersionRange = SemVerRange.parse(">=1.0"),
            entryPoint = "test.Entry",
            capabilities = capabilities,
            authTypes = setOf("api_key"),
            signature = "sig",
        )

    @Test
    fun `register creates a REGISTERED provider and publishes ProviderRegistered`() {
        val provider = manager.register(registerCommand())

        assertEquals(ProviderStatus.REGISTERED, provider.status)
        assertEquals(1, eventPublisher.publishedEvents.size)
        assertTrue(eventPublisher.publishedEvents.first() is apap.domain.event.ProviderRegistered)
    }

    @Test
    fun `full lifecycle from register to enable succeeds when validation passes`(): Unit =
        runBlocking {
            adapterRegistry.register("plugin-a", manifest(), FakeProviderAdapter())
            val provider = manager.register(registerCommand())

            manager.beginValidation(provider.providerId)
            val outcome = manager.completeValidation(provider.providerId)
            assertTrue(outcome is ValidationOutcome.Passed)
            assertEquals(ProviderStatus.VALIDATING, outcome.provider.status)

            val enabled = manager.enable(provider.providerId, "manual")
            assertEquals(ProviderStatus.ACTIVE, enabled.status)
        }

    @Test
    fun `completeValidation reverts to REGISTERED and never reaches ACTIVE when credential validation fails`(): Unit =
        runBlocking {
            val failingAdapter =
                FakeProviderAdapter(
                    credentialValidationResult = apap.adapter.spi.ValidationResult(valid = false, detail = "bad key"),
                )
            adapterRegistry.register("plugin-a", manifest(), failingAdapter)
            val provider = manager.register(registerCommand())
            manager.beginValidation(provider.providerId)

            val outcome = manager.completeValidation(provider.providerId)

            assertTrue(outcome is ValidationOutcome.Failed)
            assertEquals(ProviderStatus.REGISTERED, outcome.provider.status)
            assertTrue(
                eventPublisher.publishedEvents.any { it is apap.domain.event.CredentialValidationFailed },
            )
            // ACTIVEへ遷移するにはVALIDATINGが必須のため、REGISTEREDからの直接enable()は不正遷移として拒否される。
            org.junit.jupiter.api.Assertions.assertThrows(
                apap.domain.model.provider.IllegalProviderStateTransitionException::class.java,
            ) {
                manager.enable(provider.providerId, "manual")
            }
        }

    @Test
    fun `completeValidation fails when declared capabilities do not match plugin manifest`(): Unit =
        runBlocking {
            adapterRegistry.register(
                "plugin-a",
                manifest(capabilities = setOf(CapabilityId("chat"))),
                FakeProviderAdapter(supportedCapabilities = setOf(CapabilityId("embedding"))),
            )
            val provider = manager.register(registerCommand())
            manager.beginValidation(provider.providerId)

            val outcome = manager.completeValidation(provider.providerId)

            assertTrue(outcome is ValidationOutcome.Failed)
            assertEquals(ProviderStatus.REGISTERED, outcome.provider.status)
        }

    @Test
    fun `drain records drainStartedAt and enforceDrainTimeout completes draining only after timeout elapses`() {
        adapterRegistry.register("plugin-a", manifest(), FakeProviderAdapter())
        val provider = manager.register(registerCommand())
        manager.beginValidation(provider.providerId)
        manager.enable(provider.providerId, "manual")
        val draining = manager.drain(provider.providerId, "maintenance")
        assertEquals(ProviderStatus.DRAINING, draining.status)
        assertEquals(clock.now(), draining.drainStartedAt)

        val tooSoon = manager.enforceDrainTimeout(provider.providerId)
        assertNull(tooSoon)

        clock.advanceBy(301)
        val completed = manager.enforceDrainTimeout(provider.providerId)
        assertEquals(ProviderStatus.DISABLED, completed?.status)
    }

    @Test
    fun `delete requires DISABLED and produces a logical delete`() {
        adapterRegistry.register("plugin-a", manifest(), FakeProviderAdapter())
        val provider = manager.register(registerCommand())
        manager.beginValidation(provider.providerId)
        manager.enable(provider.providerId, "manual")
        manager.drain(provider.providerId, "maintenance")
        manager.completeDraining(provider.providerId, "drain_complete")

        val deleted = manager.delete(provider.providerId)

        assertEquals(ProviderStatus.DELETED, deleted.status)
    }
}
