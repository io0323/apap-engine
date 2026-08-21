package apap.provider

import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.AuthContext
import apap.adapter.spi.CapabilityConstraints
import apap.adapter.spi.DiscoveredModel
import apap.adapter.spi.HealthResult
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.ProviderCost
import apap.adapter.spi.ProviderHealthStatus
import apap.adapter.spi.ProviderToolFormat
import apap.adapter.spi.ProviderUsage
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.ToolDefinition
import apap.adapter.spi.ValidationResult
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.Period
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TokenCount

/**
 * ProviderManager/ModelManagerのテストに使う最小限の[ProviderAdapter]フェイク。
 * `adapters/adapter-mock`（実配布Plugin）へ依存させず、apap-provider自身のテストソース内に
 * 自己完結させる（apap-providerは特定Adapter実装の知識を持つべきではないため）。
 */
@Suppress("TooManyFunctions")
class FakeProviderAdapter(
    private val supportedCapabilities: Set<CapabilityId> = setOf(CapabilityId("chat")),
    private val credentialValidationResult: ValidationResult = ValidationResult(valid = true),
    private val healthResult: HealthResult = HealthResult(ProviderHealthStatus.UP, java.time.Duration.ZERO),
    private val discoveredModels: List<DiscoveredModel> = emptyList(),
) : ProviderAdapter {
    override fun initialize(
        config: AdapterConfig,
        secrets: SecretAccessor,
    ) = Unit

    override fun shutdown() = Unit

    override fun spiVersion(): SemVer = SemVer(1, 0, 0)

    override fun supportedCapabilities(): Set<CapabilityId> = supportedCapabilities

    override fun capabilityConstraints(capabilityId: CapabilityId): CapabilityConstraints = CapabilityConstraints()

    override suspend fun authenticate(): AuthContext = AuthContext()

    override suspend fun validateCredential(ref: CredentialRef): ValidationResult = credentialValidationResult

    override suspend fun execute(request: AdapterRequest): AdapterResponse =
        throw UnsupportedOperationException("FakeProviderAdapter.execute is not used by ProviderManager tests")

    override suspend fun executeStream(request: AdapterRequest): ProviderAdapter.AdapterStream =
        throw UnsupportedOperationException("FakeProviderAdapter.executeStream is not used by ProviderManager tests")

    override fun translateTools(tools: List<ToolDefinition>): ProviderToolFormat = ProviderToolFormat(emptyList<Any>())

    override suspend fun discoverModels(): List<DiscoveredModel> = discoveredModels

    override suspend fun healthCheck(): HealthResult = healthResult

    override suspend fun fetchUsage(period: Period): ProviderUsage? = null

    override suspend fun fetchCost(period: Period): ProviderCost? = null

    override suspend fun estimateTokens(input: List<ContentPart>): TokenCount? = null
}
