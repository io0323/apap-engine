package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.domain.event.RequestCompleted
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.ResolvedPlugin
import apap.testkit.inmemory.InMemoryAliasRepository
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryHealthLatencyStatsRepository
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryModelRepository
import apap.testkit.inmemory.InMemoryPolicyRepository
import apap.testkit.inmemory.InMemoryProviderRepository
import apap.testkit.inmemory.InMemoryQuotaSnapshotRepository
import apap.testkit.inmemory.InMemoryTenantEntitlementRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * apap-runtimeコンポジションルート（[ExecutionEngineComposer]）の配線が実際に組み上がり、
 * 02_システム仕様.md 2.8のRequest Flowを最小構成（adapter-mock、実Providerへは接続しない）で
 * 一気通貫に実行できることを確認するスモークテスト。
 */
class ExecutionEngineComposerTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val capabilityId = CapabilityId("chat")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA3")
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA4")

    @Suppress("LongMethod")
    @Test
    fun `wired ExecutionEngine completes a chat request end-to-end`() =
        runBlocking {
            val providerRepository = InMemoryProviderRepository()
            val modelRepository = InMemoryModelRepository()
            val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
            val ids = InMemoryIdGenerator()
            val events = InMemoryDomainEventPublisher()

            providerRepository.save(
                Provider(
                    providerId = providerId,
                    name = "provider-a",
                    adapterPluginId = "plugin-a",
                    spiVersion = SemVer(1, 0, 0),
                    endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                    authType = "api_key",
                    credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.ACTIVE)),
                    rateLimits = RateLimits(600, 100_000, 10),
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

            val adapter = MockProviderAdapter(MockAdapterConfig(supportedCapabilities = setOf(capabilityId)))
            adapter.initialize(
                AdapterConfig(
                    providerId,
                    listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                    RateLimits(600, 100_000, 10),
                    setOf(region),
                ),
                object : SecretAccessor {
                    override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
                },
            )
            val adapterRegistry =
                object : AdapterRegistry {
                    override fun resolve(pluginId: String): ResolvedPlugin {
                        if (pluginId != "plugin-a") throw PluginNotFoundException(pluginId)
                        return ResolvedPlugin(
                            adapter,
                            PluginManifest(
                                "plugin-a",
                                SemVer(1, 0, 0),
                                SemVerRange(listOf(SemVerRange.Comparator(SemVerRange.Op.GTE, SemVer(1, 0, 0)))),
                                "apap.adapter.mock.MockProviderAdapter",
                                setOf(capabilityId),
                                setOf("api_key"),
                                "sig",
                            ),
                        )
                    }
                }

            val engine =
                ExecutionEngineComposer(
                    providerRepository,
                    modelRepository,
                    InMemoryAliasRepository(),
                    InMemoryPolicyRepository(),
                    InMemoryHealthLatencyStatsRepository(),
                    InMemoryQuotaSnapshotRepository(),
                    InMemoryTenantEntitlementRepository(),
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
                    optInToStubs = true,
                ).build()

            val request =
                CanonicalRequest(
                    requestId = requestId,
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("hello")),
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            val response = engine.execute(request)

            assertTrue(response.output.isNotEmpty())
            assertEquals(providerId, response.resolvedProvider)
            assertEquals(modelId, response.resolvedModel)
            assertTrue(events.publishedEvents.any { it is RequestCompleted })
        }
}
