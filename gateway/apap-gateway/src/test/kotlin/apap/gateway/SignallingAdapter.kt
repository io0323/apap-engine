package apap.gateway

import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.AuthContext
import apap.adapter.spi.CapabilityConstraints
import apap.adapter.spi.DiscoveredModel
import apap.adapter.spi.HealthResult
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.ProviderCost
import apap.adapter.spi.ProviderToolFormat
import apap.adapter.spi.ProviderUsage
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.ValidationResult
import apap.adapter.spi.ToolDefinition
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.Period
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TokenCount
import java.util.concurrent.CountDownLatch

/**
 * `execute`へ入った瞬間に[entered]を開放するだけのデコレータ。
 *
 * 存在理由: 「リクエストが実行中である」ことを`delay(500)`のような**推測**で待つと、
 * 遅いCIでは実行前に次の手順へ進んでしまい、テストが偽陽性・偽陰性の両方を出す。
 * Adapterに実際に到達したという確定的なシグナルを使う
 * （`ApapEngineBuilderTest`が同じ目的で`RoutingStrategy`を使っているのと同じ考え方）。
 *
 * 委譲以外の振る舞いは持たない。
 */
class SignallingAdapter(
    private val delegate: ProviderAdapter,
    private val entered: CountDownLatch,
) : ProviderAdapter {
    override suspend fun execute(request: AdapterRequest): AdapterResponse {
        entered.countDown()
        return delegate.execute(request)
    }

    override suspend fun executeStream(request: AdapterRequest): ProviderAdapter.AdapterStream {
        entered.countDown()
        return delegate.executeStream(request)
    }

    override fun initialize(
        config: AdapterConfig,
        secretAccessor: SecretAccessor,
    ) = delegate.initialize(config, secretAccessor)

    override fun shutdown() = delegate.shutdown()

    override fun spiVersion(): SemVer = delegate.spiVersion()

    override fun supportedCapabilities(): Set<CapabilityId> = delegate.supportedCapabilities()

    override fun capabilityConstraints(capabilityId: CapabilityId): CapabilityConstraints =
        delegate.capabilityConstraints(capabilityId)

    override suspend fun authenticate(): AuthContext = delegate.authenticate()

    override suspend fun validateCredential(ref: CredentialRef): ValidationResult = delegate.validateCredential(ref)

    override fun translateTools(tools: List<ToolDefinition>): ProviderToolFormat = delegate.translateTools(tools)

    override suspend fun discoverModels(): List<DiscoveredModel> = delegate.discoverModels()

    override suspend fun healthCheck(): HealthResult = delegate.healthCheck()

    override suspend fun fetchUsage(period: Period): ProviderUsage? = delegate.fetchUsage(period)

    override suspend fun fetchCost(period: Period): ProviderCost? = delegate.fetchCost(period)

    override suspend fun estimateTokens(input: List<ContentPart>): TokenCount? = delegate.estimateTokens(input)
}
