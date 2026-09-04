package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.api.ApapRequest
import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.domain.port.MetricsRecorder
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.RegisterModelCommand
import apap.provider.RegisterProviderCommand
import apap.provider.ResolvedPlugin
import java.math.BigDecimal
import java.time.Instant

/**
 * 本番の入口（[ApapEngineBuilder]）で組み立てたエンジンと、そこへProvider/Modelを
 * ACTIVE登録するまでの定型手順をまとめたテスト用の足場。
 *
 * P11の是正作業で「ビルダ経由でしか検出できない不具合」（AuditEngine未配線・
 * レート制限未反映・周期タスク未起動）が複数見つかったため、E2Eテストは
 * すべてこの足場を通す。各テストが80行の登録手順を書き写すと、
 * 手順そのものがテストごとにずれていく。
 */
object EngineFixture {
    val REGION: Region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    val TENANT: TenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FZ0")

    /** レート制限を計測の邪魔にしない値。絞りを見たいテストは明示的に低い値を渡す。 */
    const val UNTHROTTLED_RPM = 6_000

    class Fixture(
        val engine: ApapEngine,
        val repositories: ApapRepositories,
    ) : AutoCloseable {
        override fun close() = engine.close()
    }

    /**
     * @param plugins pluginId -> そのpluginが提供するAdapter。複数登録するとFallback/切替を検証できる。
     */
    fun build(
        capabilityId: CapabilityId,
        plugins: Map<String, ProviderAdapter>,
        repositories: ApapRepositories = ApapRepositories(),
        metricsRecorder: MetricsRecorder? = null,
        configure: ApapEngineBuilder.() -> Unit = {},
    ): Fixture {
        val engine =
            ApapEngineBuilder(repositories = repositories)
                .adapterRegistry(registryOf(capabilityId, plugins))
                .apply { metricsRecorder?.let { metricsRecorder(it) } }
                .apply(configure)
                .build()
        return Fixture(engine, repositories)
    }

    /** 単一のadapter-mockで足りる場合の短縮形。 */
    fun buildWithMock(
        capabilityId: CapabilityId,
        config: MockAdapterConfig = MockAdapterConfig(supportedCapabilities = setOf(capabilityId)),
        decorate: (ProviderAdapter) -> ProviderAdapter = { it },
        repositories: ApapRepositories = ApapRepositories(),
    ): Fixture = build(capabilityId, mapOf("plugin-a" to decorate(mock(config))), repositories)

    /** 初期化済みの[MockProviderAdapter]。`initialize`を忘れるとCredential解決で落ちる。 */
    fun mock(config: MockAdapterConfig): MockProviderAdapter =
        MockProviderAdapter(config).apply {
            initialize(
                AdapterConfig(
                    ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FZ1"),
                    listOf(Endpoint("ep1", REGION, "https://example.internal", 100)),
                    RateLimits(UNTHROTTLED_RPM, 1_000_000, 100),
                    setOf(REGION),
                ),
                object : SecretAccessor {
                    override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
                },
            )
        }

    private fun registryOf(
        capabilityId: CapabilityId,
        plugins: Map<String, ProviderAdapter>,
    ): AdapterRegistry =
        object : AdapterRegistry {
            override fun resolve(pluginId: String): ResolvedPlugin {
                val adapter = plugins[pluginId] ?: throw PluginNotFoundException(pluginId)
                return ResolvedPlugin(
                    adapter,
                    PluginManifest(
                        pluginId = pluginId,
                        version = SemVer(1, 0, 0),
                        spiVersionRange = SemVerRange.parse(">=1.0"),
                        entryPoint = "test.Entry",
                        capabilities = setOf(capabilityId),
                        authTypes = setOf("api_key"),
                        signature = "sig",
                    ),
                )
            }
        }

    /**
     * ProviderをACTIVEにし、[capabilityId]を持つACTIVEなModelを1件登録して返す。
     * ADR-0021により単価未登録Modelは候補から除外されるため、PriceEntryも登録する。
     */
    @Suppress("LongParameterList")
    suspend fun registerActive(
        fixture: Fixture,
        capabilityId: CapabilityId,
        pluginId: String = "plugin-a",
        providerName: String = pluginId,
        rpm: Int = UNTHROTTLED_RPM,
        priority: Int = 50,
    ): ModelId {
        val engine = fixture.engine
        val provider =
            engine.admin.providers.register(
                RegisterProviderCommand(
                    name = providerName,
                    adapterPluginId = pluginId,
                    spiVersion = SemVer(1, 0, 0),
                    endpoints = listOf(Endpoint("ep1", REGION, "https://example.internal", 100)),
                    authType = "api_key",
                    credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.STANDBY)),
                    rateLimits = RateLimits(rpm, 1_000_000, 100),
                    priority = priority,
                    regions = setOf(REGION),
                ),
            )
        engine.admin.providers.beginValidation(provider.providerId)
        engine.admin.providers.completeValidation(provider.providerId)
        engine.admin.providers.enable(provider.providerId, "test setup")

        val model =
            engine.admin.models.register(
                RegisterModelCommand(
                    providerId = provider.providerId,
                    modelName = "model-$pluginId",
                    version = "1.0",
                    capabilities = listOf(ModelCapability(capabilityId)),
                    contextWindow = 8000,
                    maxOutputTokens = 1000,
                    regions = setOf(REGION),
                    priority = priority,
                ),
            )
        engine.admin.models.changeStatus(model.modelId, ModelStatus.TESTING)
        engine.admin.models.changeStatus(model.modelId, ModelStatus.ACTIVE)

        fixture.repositories.priceBookRepository.save(
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
        return model.modelId
    }

    fun request(
        capabilityId: CapabilityId,
        text: String = "hello",
    ) = ApapRequest(
        tenantId = TENANT,
        principal = "user-1",
        capabilityId = capabilityId,
        input = listOf(ContentPart.Text(text)),
    )
}
