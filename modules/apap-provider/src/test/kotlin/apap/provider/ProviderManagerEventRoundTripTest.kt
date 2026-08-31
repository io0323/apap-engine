package apap.provider

import apap.domain.event.CredentialValidationFailed
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.provider.applyProviderEvent
import apap.domain.model.reconstruct
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-0026 / P8後始末レビュー: `decide()`層が無く、`ProviderManager`がstate保存とイベント発行を
 * 別々に行う構造である以上、「stateは更新したがイベント発行を忘れた」経路が将来増えても
 * 気づけない。本テストは、ProviderManager経由で状態が変化する全メソッド（[ManagerStateMutationCoverageTest]
 * が機械検証する集合と同じ）を代表的な順序で呼び、最終的にrepository.findByIdで得られる状態と、
 * repository.eventsForで得られるイベント列だけを[reconstruct]した状態が一致することを検証する。
 * 不一致であれば、そのメソッドがイベント発行を怠っている（ADR-0026が要求する再構築可能性への違反）。
 *
 * VALIDATING/QUEUEDのような14章に対応イベントの無い遷移的状態は、ADR-0026が意図的に
 * 再構築対象外としているため、ここでは「次の安定状態（イベントで裏付けられる状態）に到達した後」で
 * 比較する（[ProviderReconstructionTest]と同じ設計）。
 */
class ProviderManagerEventRoundTripTest {
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

    private fun manifest() =
        apap.adapter.spi.plugin.PluginManifest(
            pluginId = "plugin-a",
            version = SemVer(1, 0, 0),
            spiVersionRange =
                apap.adapter.spi.plugin.SemVerRange
                    .parse(">=1.0"),
            entryPoint = "test.Entry",
            capabilities =
                setOf(
                    apap.domain.model.vo
                        .CapabilityId("chat"),
                ),
            authTypes = setOf("api_key"),
            signature = "sig",
        )

    private fun assertReconstructionMatchesLiveState(providerId: apap.domain.model.vo.ProviderId) {
        val live = providerRepository.findById(providerId)
        val reconstructed = reconstruct(providerRepository.eventsFor(providerId), null, ::applyProviderEvent)
        assertEquals(live, reconstructed)
    }

    @Test
    fun `register, enable, drain, completeDraining, delete round-trip through events`() {
        adapterRegistry.register("plugin-a", manifest(), FakeProviderAdapter())
        val provider = manager.register(registerCommand())
        assertReconstructionMatchesLiveState(provider.providerId)

        manager.beginValidation(provider.providerId)
        runBlocking { manager.completeValidation(provider.providerId) }
        // completeValidation(Passed)はVALIDATINGのまま。次の安定状態(ACTIVE)まで進めてから比較する。
        manager.enable(provider.providerId, "manual")
        assertReconstructionMatchesLiveState(provider.providerId)
        assertEquals(ProviderStatus.ACTIVE, providerRepository.findById(provider.providerId)?.status)
        assertEquals(
            CredentialState.ACTIVE,
            providerRepository
                .findById(provider.providerId)
                ?.credentialRefs
                ?.single()
                ?.state,
        )

        manager.drain(provider.providerId, "manual")
        assertReconstructionMatchesLiveState(provider.providerId)

        manager.completeDraining(provider.providerId, "manual")
        assertReconstructionMatchesLiveState(provider.providerId)

        manager.delete(provider.providerId)
        assertReconstructionMatchesLiveState(provider.providerId)
        assertEquals(ProviderStatus.DELETED, providerRepository.findById(provider.providerId)?.status)
    }

    @Test
    fun `failValidation with a credential failure round-trips (CredentialValidationFailed is published)`() {
        val failingAdapter =
            FakeProviderAdapter(
                credentialValidationResult = apap.adapter.spi.ValidationResult(valid = false, detail = "bad key"),
            )
        adapterRegistry.register("plugin-a", manifest(), failingAdapter)
        val provider = manager.register(registerCommand())

        manager.beginValidation(provider.providerId)
        runBlocking { manager.completeValidation(provider.providerId) }

        // failValidationはVALIDATING→REGISTEREDへ差し戻す。REGISTERED自体はreplay時の初期状態と
        // 一致する安定状態のため、ここで直接比較できる（ADR-0026: VALIDATINGは元々再構築対象外）。
        assertReconstructionMatchesLiveState(provider.providerId)
        assertEquals(ProviderStatus.REGISTERED, providerRepository.findById(provider.providerId)?.status)
        assertTrue(providerRepository.eventsFor(provider.providerId).any { it is CredentialValidationFailed })
    }

    @Test
    fun `enforceDrainTimeout delegates to completeDraining and round-trips`() {
        adapterRegistry.register("plugin-a", manifest(), FakeProviderAdapter())
        val provider = manager.register(registerCommand())
        manager.beginValidation(provider.providerId)
        runBlocking { manager.completeValidation(provider.providerId) }
        manager.enable(provider.providerId, "manual")
        manager.drain(provider.providerId, "manual")

        clock.advanceBy(seconds = ProviderManager.DEFAULT_DRAIN_TIMEOUT.seconds + 1)
        val result = manager.enforceDrainTimeout(provider.providerId)

        assertTrue(result != null)
        assertReconstructionMatchesLiveState(provider.providerId)
        assertEquals(ProviderStatus.DISABLED, providerRepository.findById(provider.providerId)?.status)
    }
}
