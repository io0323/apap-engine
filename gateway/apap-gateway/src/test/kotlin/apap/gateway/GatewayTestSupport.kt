package apap.gateway

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.gateway.auth.TokenVerificationException
import apap.gateway.auth.TokenVerifier
import apap.gateway.auth.VerifiedCaller
import apap.gateway.config.AuthConfig
import apap.gateway.config.GatewayConfig
import apap.gateway.metrics.InMemoryCollectingReader
import apap.gateway.metrics.OpenMetricsRenderer
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.RegisterModelCommand
import apap.provider.RegisterProviderCommand
import apap.provider.ResolvedPlugin
import apap.runtime.ApapEngine
import apap.runtime.ApapEngineBuilder
import apap.runtime.ApapRepositories
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-0004: テストは**自己署名JWT相当のテストダブル**で行い、CIAP実体に依存しない。
 *
 * ここでは[TokenVerifier]（腐敗防止層のinterface）を差し替えることで、JWT/JWKSの
 * 実装詳細に一切触れずに認証の成否・スコープ有無を再現する。
 * `JwksTokenVerifier`自体の検証は署名鍵を使う別テストで行う。
 */
class FakeTokenVerifier(
    private val tenantId: TenantId,
    private val validTokens: Map<String, Set<String>>,
) : TokenVerifier {
    override suspend fun verify(token: String): VerifiedCaller {
        val scopes = validTokens[token] ?: throw TokenVerificationException("Token verification failed")
        return VerifiedCaller(tenantId = tenantId, principal = "test-principal", scopes = scopes)
    }
}

const val VALID_TOKEN = "valid-token"
const val VALID_ADMIN_TOKEN = "valid-admin-token"
val TEST_TENANT = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FD0")

fun testGatewayConfig() =
    GatewayConfig(
        auth =
            AuthConfig(
                issuer = "https://issuer.internal",
                audience = "apap",
                jwksUri = "https://issuer.internal/jwks",
            ),
        // heartbeat間隔はテストで待てる長さに落とす（既定15秒はSSEテストには長すぎる）。
        sseHeartbeatSeconds = 1,
    )

fun testTokenVerifier() =
    FakeTokenVerifier(
        tenantId = TEST_TENANT,
        validTokens =
            mapOf(
                VALID_TOKEN to emptySet(),
                VALID_ADMIN_TOKEN to setOf(AuthConfig.DEFAULT_ADMIN_SCOPE),
            ),
    )

fun testMetricsRenderer(): Pair<OpenMetricsRenderer, SdkMeterProvider> {
    val reader = InMemoryCollectingReader()
    val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
    return OpenMetricsRenderer(reader) to provider
}

/** adapter-mockだけで動くエンジン（依存ゼロ構成、apap-runtimeのApapEngineBuilderTestと同じ考え方）。 */
class TestEngineFixture(
    adapterConfig: MockAdapterConfig = MockAdapterConfig(supportedCapabilities = setOf(CapabilityId("chat"))),
) {
    val repositories = ApapRepositories()
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val adapter = MockProviderAdapter(adapterConfig)

    val engine: ApapEngine =
        ApapEngineBuilder(repositories = repositories)
            .adapterRegistry(registry(adapterConfig.supportedCapabilities))
            .build()

    init {
        adapter.initialize(
            AdapterConfig(
                ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FD1"),
                listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                RateLimits(600, 100_000, 10),
                setOf(region),
            ),
            object : SecretAccessor {
                override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
            },
        )
    }

    private fun registry(capabilities: Set<CapabilityId>) =
        object : AdapterRegistry {
            override fun resolve(pluginId: String): ResolvedPlugin {
                if (pluginId != "plugin-a") throw PluginNotFoundException(pluginId)
                return ResolvedPlugin(
                    adapter,
                    PluginManifest(
                        pluginId = "plugin-a",
                        version = SemVer(1, 0, 0),
                        spiVersionRange = SemVerRange.parse(">=1.0"),
                        entryPoint = "test.Entry",
                        capabilities = capabilities,
                        authTypes = setOf("api_key"),
                        signature = "sig",
                    ),
                )
            }
        }

    /** Provider/ModelをACTIVEにし、単価も登録する（ADR-0021: 単価未登録Modelは候補から除外される）。 */
    suspend fun registerActiveModel(capabilityId: CapabilityId) {
        val provider =
            engine.admin.providers.register(
                RegisterProviderCommand(
                    name = "provider-a",
                    adapterPluginId = "plugin-a",
                    spiVersion = SemVer(1, 0, 0),
                    endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                    authType = "api_key",
                    credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.STANDBY)),
                    rateLimits = RateLimits(600, 100_000, 10),
                    priority = 50,
                    regions = setOf(region),
                ),
            )
        engine.admin.providers.beginValidation(provider.providerId)
        engine.admin.providers.completeValidation(provider.providerId)
        engine.admin.providers.enable(provider.providerId, "test setup")

        val model =
            engine.admin.models.register(
                RegisterModelCommand(
                    providerId = provider.providerId,
                    modelName = "model-a",
                    version = "1.0",
                    capabilities = listOf(ModelCapability(capabilityId)),
                    contextWindow = 8000,
                    maxOutputTokens = 1000,
                    regions = setOf(region),
                    priority = 50,
                ),
            )
        engine.admin.models.changeStatus(model.modelId, ModelStatus.TESTING)
        engine.admin.models.changeStatus(model.modelId, ModelStatus.ACTIVE)

        repositories.priceBookRepository.save(
            PriceBook(
                priceBookId = "pb-${model.modelId.value}",
                entries =
                    listOf(
                        PriceEntry(
                            modelId = model.modelId,
                            inputPer1k = Money(BigDecimal("0.01"), "USD"),
                            outputPer1k = Money(BigDecimal("0.01"), "USD"),
                            period = Period(Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z")),
                        ),
                    ),
            ),
        )
    }
}
