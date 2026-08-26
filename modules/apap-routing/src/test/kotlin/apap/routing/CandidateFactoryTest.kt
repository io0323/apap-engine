package apap.routing

import apap.domain.model.execution.CbState
import apap.domain.model.execution.CircuitBreakerState
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.domain.service.routing.RoutingHardFilters
import apap.testkit.inmemory.InMemoryAliasRepository
import apap.testkit.inmemory.InMemoryCircuitBreakerStateRepository
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryHealthLatencyStatsRepository
import apap.testkit.inmemory.InMemoryModelRepository
import apap.testkit.inmemory.InMemoryProviderRepository
import apap.testkit.inmemory.InMemoryQuotaSnapshotRepository
import apap.testkit.inmemory.InMemoryTenantEntitlementRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 02_システム仕様.md 2.5.2 ハードフィルタ(a〜g)の各条件が単独で候補を除外することを検証する。
 * 判定ロジック自体は[RoutingHardFilters]（apap-domain）にあるため、ここでは[CandidateFactory]が
 * 組み立てたCandidateに対しRoutingHardFiltersを適用し、周辺データ収集が正しく効くことを確認する。
 */
class CandidateFactoryTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val capabilityId = CapabilityId("chat")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FAW")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FAX")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FAY")

    private val providerRepository = InMemoryProviderRepository()
    private val modelRepository = InMemoryModelRepository()
    private val aliasRepository = InMemoryAliasRepository()
    private val circuitBreakerStateRepository = InMemoryCircuitBreakerStateRepository()
    private val healthLatencyStatsRepository = InMemoryHealthLatencyStatsRepository()
    private val quotaSnapshotRepository = InMemoryQuotaSnapshotRepository()
    private val tenantEntitlementRepository = InMemoryTenantEntitlementRepository()
    private val cache = RoutingCandidateCache()
    private val clock = InMemoryClock()

    private val factory =
        CandidateFactory(
            providerRepository,
            modelRepository,
            aliasRepository,
            circuitBreakerStateRepository,
            healthLatencyStatsRepository,
            quotaSnapshotRepository,
            tenantEntitlementRepository,
            ZeroCostEstimator(),
            cache,
            clock,
        )

    private fun seedActiveProviderAndModel() {
        providerRepository.save(
            Provider(
                providerId = providerId,
                name = "provider-a",
                adapterPluginId = "plugin-a",
                spiVersion = SemVer(1, 0, 0),
                endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                authType = "api_key",
                credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.ACTIVE)),
                rateLimits = RateLimits(rpm = 60, tpm = 1000, concurrent = 10),
                priority = 50,
                regions = setOf(region),
                status = ProviderStatus.ACTIVE,
            ),
        )
        modelRepository.save(
            Model(
                modelId = modelId,
                providerId = providerId,
                modelName = "model-a",
                version = "1.0",
                capabilities = listOf(ModelCapability(capabilityId)),
                contextWindow = 8000,
                maxOutputTokens = 1000,
                regions = setOf(region),
                status = ModelStatus.ACTIVE,
                priority = 50,
            ),
        )
        healthLatencyStatsRepository.recordOutcome(
            providerId,
            modelId,
            success = true,
            latencyMs = 100,
            at = clock.now(),
        )
        cache.apply(healthChangedEvent(providerId, apap.domain.model.provider.ProviderHealthStatus.UP))
    }

    private fun buildCandidates() = factory.build(capabilityId, null, tenantId, requestId)

    private fun assertExcluded() {
        val candidates = buildCandidates()
        val filtered = RoutingHardFilters.apply(candidates, regionRequirement = null)
        assertTrue(filtered.isEmpty(), "expected the candidate to be excluded but it passed: $candidates")
    }

    @Test
    fun `baseline candidate passes all hard filters when everything is favorable`() {
        seedActiveProviderAndModel()

        val filtered = RoutingHardFilters.apply(buildCandidates(), regionRequirement = region)

        assertEquals(1, filtered.size)
    }

    @Test
    fun `a - excluded when Provider is not ACTIVE`() {
        seedActiveProviderAndModel()
        providerRepository.save(providerRepository.findById(providerId)!!.copy(status = ProviderStatus.DRAINING))

        assertExcluded()
    }

    @Test
    fun `b - excluded when Circuit Breaker is OPEN`() {
        seedActiveProviderAndModel()
        // 直接OPENへ構築できないため（CircuitBreakerStateはtransitionToでのみ状態遷移する不変オブジェクト）、
        // CLOSED->OPENという許容遷移でOPEN状態を作る。
        val opened = CircuitBreakerState(CbKey(providerId, modelId)).transitionTo(CbState.OPEN, clock.now())
        circuitBreakerStateRepository.put(opened)

        assertExcluded()
    }

    @Test
    fun `c - excluded when Provider Health is DOWN`() {
        seedActiveProviderAndModel()
        cache.apply(healthChangedEvent(providerId, apap.domain.model.provider.ProviderHealthStatus.DOWN))

        assertExcluded()
    }

    @Test
    fun `d - excluded when region constraint is not satisfied by the model`() {
        seedActiveProviderAndModel()
        val otherRegion = Region.of("us-east", RegionCodeTable(setOf("jp-east", "us-east")))

        val candidates = buildCandidates()
        val filtered = RoutingHardFilters.apply(candidates, regionRequirement = otherRegion)

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `e - excluded when a Policy DENY rule matches`() {
        seedActiveProviderAndModel()

        val candidates = buildCandidates()
        val filtered = RoutingHardFilters.apply(candidates, regionRequirement = null, isDenied = { true })

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `f - excluded when the tenant lacks permission`() {
        seedActiveProviderAndModel()
        tenantEntitlementRepository.deny(tenantId, capabilityId, modelId)

        assertExcluded()
    }

    @Test
    fun `g - excluded when quota remaining is zero`() {
        seedActiveProviderAndModel()
        quotaSnapshotRepository.setRemaining(tenantId, providerId, modelId, 0)

        assertExcluded()
    }

    /** ADR-0021: 単価未登録のModelはCandidate自体が組み立てられず除外される（ハードフィルタa〜g以前）。 */
    @Test
    fun `excluded when the model has no registered PriceEntry`() {
        seedActiveProviderAndModel()
        val unpricedFactory =
            CandidateFactory(
                providerRepository,
                modelRepository,
                aliasRepository,
                circuitBreakerStateRepository,
                healthLatencyStatsRepository,
                quotaSnapshotRepository,
                tenantEntitlementRepository,
                RealCostEstimator(apap.testkit.inmemory.InMemoryPriceBookRepository(), clock),
                cache,
                clock,
            )

        val candidates = unpricedFactory.build(capabilityId, null, tenantId, requestId)

        assertTrue(candidates.isEmpty(), "expected the unpriced candidate to be excluded but it was: $candidates")
    }

    private fun healthChangedEvent(
        providerId: ProviderId,
        to: apap.domain.model.provider.ProviderHealthStatus,
    ) = apap.domain.event.ProviderHealthChanged(
        apap.domain.event.EventMetadata(
            eventId = "evt-${providerId.value}-$to",
            occurredAt = clock.now(),
            traceId = "trace",
            tenantId = null,
            aggregateId = providerId.value,
            version = 0,
        ),
        providerId,
        from = apap.domain.model.provider.ProviderHealthStatus.UP,
        to = to,
        evidence = "test",
    )
}
