package apap.routing

import apap.domain.event.EventMetadata
import apap.domain.event.ProviderDraining
import apap.domain.event.ProviderEnabled
import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.Provider
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.OptimizeFor
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.RoutingPreferences
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryAliasRepository
import apap.testkit.inmemory.InMemoryCircuitBreakerStateRepository
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryHealthLatencyStatsRepository
import apap.testkit.inmemory.InMemoryModelRepository
import apap.testkit.inmemory.InMemoryPolicyRepository
import apap.testkit.inmemory.InMemoryPriceBookRepository
import apap.testkit.inmemory.InMemoryProviderRepository
import apap.testkit.inmemory.InMemoryQuotaSnapshotRepository
import apap.testkit.inmemory.InMemoryTenantEntitlementRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class RoutingEngineTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val capabilityId = CapabilityId("chat")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FAW")

    private val providerRepository = InMemoryProviderRepository()
    private val modelRepository = InMemoryModelRepository()
    private val aliasRepository = InMemoryAliasRepository()
    private val policyRepository = InMemoryPolicyRepository()
    private val clock = InMemoryClock()
    private val cache = RoutingCandidateCache()
    private val eventPublisher = InMemoryDomainEventPublisher().apply { subscribe { cache.apply(it) } }

    private val candidateFactory =
        CandidateFactory(
            providerRepository,
            modelRepository,
            aliasRepository,
            InMemoryCircuitBreakerStateRepository(),
            InMemoryHealthLatencyStatsRepository(),
            InMemoryQuotaSnapshotRepository(),
            InMemoryTenantEntitlementRepository(),
            ZeroCostEstimator(),
            cache,
            clock,
        )
    private val routingEngine = RoutingEngine(candidateFactory, policyRepository, randomSource = { 0.0 })

    private fun provider(
        id: ProviderId,
        priority: Int = 50,
    ) = Provider(
        providerId = id,
        name = "provider-${id.value}",
        adapterPluginId = "plugin-a",
        spiVersion = SemVer(1, 0, 0),
        endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
        authType = "api_key",
        credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.ACTIVE)),
        rateLimits = RateLimits(rpm = 60, tpm = 1000, concurrent = 10),
        priority = priority,
        regions = setOf(region),
    )

    private fun model(
        id: ModelId,
        providerId: ProviderId,
        status: ModelStatus,
    ) = Model(
        modelId = id,
        providerId = providerId,
        modelName = "model-${id.value}",
        version = "1.0",
        capabilities = listOf(ModelCapability(capabilityId)),
        contextWindow = 8000,
        maxOutputTokens = 1000,
        regions = setOf(region),
        status = status,
        priority = 50,
    )

    private fun enableProvider(id: ProviderId) {
        providerRepository.save(provider(id))
        val meta = EventMetadata("evt-enable-${id.value}", clock.now(), "trace", null, id.value, 0)
        eventPublisher.publish(ProviderEnabled(meta, id, "manual"))
    }

    @Test
    fun `route throws NoCandidateAvailableException when no candidate survives hard filters`() {
        assertThrows(NoCandidateAvailableException::class.java) {
            routingEngine.route(RoutingRequest(capabilityId, tenantId), requestId)
        }
    }

    @Test
    fun `RoutingDecision reports costEstimationStubbed when wired with ZeroCostEstimator`() {
        val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FAX")
        val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FAY")
        enableProvider(providerId)
        modelRepository.save(model(modelId, providerId, ModelStatus.ACTIVE))

        val decision = routingEngine.route(RoutingRequest(capabilityId, tenantId), requestId)

        assertTrue(decision.costEstimationStubbed)
        assertTrue(decision.toAuditSummary().contains("cost-estimation-stubbed"))
    }

    @Test
    fun `DRAINING excludes the provider from new routing but an already-returned decision is unaffected`() {
        val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FAX")
        val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FAY")
        enableProvider(providerId)
        modelRepository.save(model(modelId, providerId, ModelStatus.ACTIVE))
        cache.apply(
            apap.domain.event.ProviderHealthChanged(
                EventMetadata("evt-health-1", clock.now(), "trace", null, providerId.value, 0),
                providerId,
                from = apap.domain.model.provider.ProviderHealthStatus.UP,
                to = apap.domain.model.provider.ProviderHealthStatus.UP,
                evidence = "seed",
            ),
        )

        val decisionBeforeDraining = routingEngine.route(RoutingRequest(capabilityId, tenantId), requestId)
        assertEquals(
            providerId,
            decisionBeforeDraining.chain.candidates
                .first()
                .providerId,
        )

        val drainMeta = EventMetadata("evt-drain-1", clock.now(), "trace", null, providerId.value, 0)
        eventPublisher.publish(ProviderDraining(drainMeta, providerId, "maintenance"))

        assertThrows(NoCandidateAvailableException::class.java) {
            routingEngine.route(RoutingRequest(capabilityId, tenantId), requestId)
        }
        // 既に返却済みのRoutingDecisionはイミュータブルなデータクラスであり、その後のイベントで変化しない
        // （実行中リクエストへ影響を与えないことに相当する）。
        assertEquals(
            providerId,
            decisionBeforeDraining.chain.candidates
                .first()
                .providerId,
        )
    }

    @Test
    fun `alias canary weight change is reflected immediately for the same requestId`() {
        val oldProviderId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
        val newProviderId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
        val oldModelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
        val newModelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA3")
        enableProvider(oldProviderId)
        enableProvider(newProviderId)
        modelRepository.save(model(oldModelId, oldProviderId, ModelStatus.ACTIVE))
        modelRepository.save(model(newModelId, newProviderId, ModelStatus.TESTING))
        val aliasId = AliasId("01ARZ3NDEKTSV4RRFFQ69G5FA4")
        aliasRepository.save(
            ModelAlias(aliasId, "chat-standard", listOf(AliasTarget(oldModelId, 100), AliasTarget(newModelId, 0))),
        )

        val beforeCanary =
            routingEngine.route(RoutingRequest(capabilityId, tenantId, modelAlias = "chat-standard"), requestId)
        assertEquals(
            oldModelId,
            beforeCanary.chain.candidates
                .first()
                .modelId,
        )

        aliasRepository.save(
            ModelAlias(aliasId, "chat-standard", listOf(AliasTarget(oldModelId, 0), AliasTarget(newModelId, 100))),
        )

        val afterCanary =
            routingEngine.route(RoutingRequest(capabilityId, tenantId, modelAlias = "chat-standard"), requestId)
        assertEquals(
            newModelId,
            afterCanary.chain.candidates
                .first()
                .modelId,
        )
    }

    @Test
    fun `optimize_for=cost picks the cheaper candidate once RealCostEstimator is wired`() {
        val cheapProviderId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FB0")
        val expensiveProviderId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FB1")
        val cheapModelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FB2")
        val expensiveModelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FB3")
        enableProvider(cheapProviderId)
        enableProvider(expensiveProviderId)
        modelRepository.save(model(cheapModelId, cheapProviderId, ModelStatus.ACTIVE))
        modelRepository.save(model(expensiveModelId, expensiveProviderId, ModelStatus.ACTIVE))

        val priceBookRepository = InMemoryPriceBookRepository()
        val period = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
        priceBookRepository.save(
            PriceBook(
                "book-1",
                listOf(
                    PriceEntry(
                        cheapModelId,
                        Money(BigDecimal("0.10"), "USD"),
                        Money(BigDecimal("0.10"), "USD"),
                        period,
                    ),
                    PriceEntry(
                        expensiveModelId,
                        Money(BigDecimal("10.00"), "USD"),
                        Money(BigDecimal("10.00"), "USD"),
                        period,
                    ),
                ),
            ),
        )

        val costAwareFactory =
            CandidateFactory(
                providerRepository,
                modelRepository,
                aliasRepository,
                InMemoryCircuitBreakerStateRepository(),
                InMemoryHealthLatencyStatsRepository(),
                InMemoryQuotaSnapshotRepository(),
                InMemoryTenantEntitlementRepository(),
                RealCostEstimator(priceBookRepository, clock),
                cache,
                clock,
            )
        val costAwareEngine = RoutingEngine(costAwareFactory, policyRepository, randomSource = { 0.0 })

        val preferences = RoutingPreferences(optimizeFor = OptimizeFor.COST)
        val decision =
            costAwareEngine.route(RoutingRequest(capabilityId, tenantId, preferences = preferences), requestId)

        assertEquals(
            cheapModelId,
            decision.chain.candidates
                .first()
                .modelId,
        )
        assertTrue(!decision.costEstimationStubbed)
    }
}
