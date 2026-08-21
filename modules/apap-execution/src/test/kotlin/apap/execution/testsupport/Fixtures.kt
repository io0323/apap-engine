package apap.execution.testsupport

import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CbState
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.domain.service.routing.Candidate
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.ResolvedPlugin
import java.time.Duration

val TEST_REGION: Region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
val TEST_CAPABILITY: CapabilityId = CapabilityId("chat")

fun testTenantId(suffix: String = "0"): TenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA$suffix")

fun testRequestId(suffix: String = "1"): RequestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA$suffix")

fun testProviderId(suffix: String = "2"): ProviderId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA$suffix")

fun testModelId(suffix: String = "3"): ModelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA$suffix")

fun testProvider(
    id: ProviderId = testProviderId(),
    pluginId: String = "plugin-mock",
): Provider =
    Provider(
        providerId = id,
        name = "provider-${id.value}",
        adapterPluginId = pluginId,
        spiVersion = SemVer(1, 0, 0),
        endpoints = listOf(Endpoint("ep1", TEST_REGION, "https://example.internal", 100)),
        authType = "api_key",
        credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.ACTIVE)),
        rateLimits = RateLimits(rpm = 600, tpm = 100_000, concurrent = 10),
        priority = 50,
        regions = setOf(TEST_REGION),
        status = ProviderStatus.ACTIVE,
    )

fun testModel(
    id: ModelId = testModelId(),
    providerId: ProviderId = testProviderId(),
    modelName: String = "model-${id.value}",
): Model =
    Model(
        modelId = id,
        providerId = providerId,
        modelName = modelName,
        version = "1.0",
        capabilities = emptyList(),
        contextWindow = 8000,
        maxOutputTokens = 1000,
        regions = setOf(TEST_REGION),
        status = ModelStatus.ACTIVE,
        priority = 50,
    )

fun testCandidate(
    providerId: ProviderId = testProviderId(),
    modelId: ModelId = testModelId(),
    cbState: CbState = CbState.CLOSED,
    p90LatencyMs: Double = 100.0,
): Candidate =
    Candidate(
        providerId = providerId,
        modelId = modelId,
        providerStatus = ProviderStatus.ACTIVE,
        modelStatus = ModelStatus.ACTIVE,
        cbState = cbState,
        health = ProviderHealthStatus.UP,
        supportedRegions = setOf(TEST_REGION),
        estimatedCost = Money.zero("USD"),
        p90LatencyMs = p90LatencyMs,
        successRate = 1.0,
        providerPriority = 50,
        modelPriority = 50,
        hasPermission = true,
        quotaRemaining = true,
    )

fun testCanonicalRequest(
    requestId: RequestId = testRequestId(),
    tenantId: TenantId = testTenantId(),
    idempotencyKey: String? = null,
    timeoutBudget: Duration = Duration.ofSeconds(60),
): CanonicalRequest =
    CanonicalRequest(
        requestId = requestId,
        tenantId = tenantId,
        principal = "user-1",
        capabilityId = TEST_CAPABILITY,
        input = listOf(ContentPart.Text("hello")),
        idempotencyKey = idempotencyKey,
        timeoutBudget = timeoutBudget,
        traceId = "trace-${requestId.value}",
    )

/** テスト専用の最小[AdapterRegistry]: pluginIdごとに固定Adapterへ紐づける。 */
class FakeAdapterRegistry(
    private val adaptersByPluginId: Map<String, ProviderAdapter>,
) : AdapterRegistry {
    constructor(pluginId: String, adapter: ProviderAdapter) : this(mapOf(pluginId to adapter))

    override fun resolve(pluginId: String): ResolvedPlugin {
        val adapter = adaptersByPluginId[pluginId] ?: throw PluginNotFoundException(pluginId)
        return ResolvedPlugin(adapter, testManifest(pluginId))
    }

    private fun testManifest(pluginId: String): PluginManifest =
        PluginManifest(
            pluginId = pluginId,
            version = SemVer(1, 0, 0),
            spiVersionRange = SemVerRange(listOf(SemVerRange.Comparator(SemVerRange.Op.GTE, SemVer(1, 0, 0)))),
            entryPoint = "apap.adapter.mock.MockProviderAdapter",
            capabilities = setOf(TEST_CAPABILITY),
            authTypes = setOf("api_key"),
            signature = "test-signature",
        )
}
