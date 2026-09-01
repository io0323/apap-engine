package apap.runtime

import apap.cache.CacheStore
import apap.cache.InMemoryCacheStore
import apap.context.CompactionStrategy
import apap.context.TruncateOldestCompactionStrategy
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.vo.SemVer
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.DomainEventSubscriber
import apap.domain.port.IdGenerator
import apap.domain.port.MetricsRecorder
import apap.domain.port.SecretStore
import apap.execution.retry.ExponentialBackoffJitterStrategy
import apap.execution.retry.RetryStrategy
import apap.infrastructure.eventbus.SynchronousEventBus
import apap.infrastructure.secret.EnvVarSecretStore
import apap.infrastructure.secret.SecretStoreAccessor
import apap.observability.health.HealthCheckService
import apap.observability.metrics.MetricsEngine
import apap.observability.metrics.OpenTelemetryMetricsRecorder
import apap.plugin.PluginManager
import apap.plugin.PluginSignatureVerifier
import apap.provider.AdapterRegistry
import apap.provider.CapabilityDiscoveryQuery
import apap.provider.ModelManager
import apap.provider.PluginNotFoundException
import apap.provider.ProviderManager
import apap.provider.ResolvedPlugin
import apap.routing.spi.LoadBalancer
import apap.routing.spi.RoutingStrategy
import apap.routing.spi.WeightedRoundRobinLoadBalancer
import apap.routing.spi.WeightedScoreRoutingStrategy
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import java.nio.file.Path
import java.security.PublicKey

/**
 * CLAUDE.md名前空間対応表: 埋込用ファサードモジュール（`modules/apap-runtime`）の唯一の
 * コンポジションルート。03_基本設計.md 3.15「コンストラクタ注入のみ、コンテナはコンポジション
 * ルートでのみ使用」に従い、DIコンテナは使わずここで手動配線する。
 *
 * 未指定時は全てIn-Memory実装 / In-Process EventBus / Noop Tracer&Meter / 環境変数SecretStoreを
 * 使うため、[ApapRepositories]と[adapterRegistry]（adapter-mock等）だけを渡せば
 * `apap-infrastructure-jdbc`/`apap-infrastructure-distributed`に一切依存せず[build]できる
 * （`EmbeddingDependencyGraphTest`で検証）。
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ApapEngineBuilder(
    private val repositories: ApapRepositories = ApapRepositories(),
    /**
     * ロード済みPluginからAdapterを解決する口。未指定かつ[pluginDirectory]も未指定の場合は、
     * どのpluginIdに対しても[PluginNotFoundException]を投げる空実装になる（Provider登録自体は
     * 可能だがVALIDATING検証は失敗する）。テスト等でadapter-mockを直接使う場合はここへ渡す。
     */
    private var adapterRegistry: AdapterRegistry? = null,
    private var pluginDirectory: Path? = null,
    private var pluginTrustedPublicKey: PublicKey? = null,
    private var secretStore: SecretStore = EnvVarSecretStore(),
    private var cacheStore: CacheStore<CanonicalResponse>? = null,
    private var eventBus: DomainEventBus = SynchronousEventBus().let { DomainEventBus(it, it) },
    private var meter: Meter = OpenTelemetry.noop().getMeter("apap-runtime"),
    private var tracer: Tracer = OpenTelemetry.noop().getTracer("apap-runtime"),
    private var routingStrategy: RoutingStrategy = WeightedScoreRoutingStrategy(),
    private var loadBalancer: LoadBalancer = WeightedRoundRobinLoadBalancer(),
    private var retryStrategy: RetryStrategy? = null,
    private var compactionStrategy: CompactionStrategy = TruncateOldestCompactionStrategy(),
    private var clock: Clock = SystemClock(),
    private var idGenerator: IdGenerator = UlidIdGenerator(),
    /**
     * ADR-0016のSPIバージョニング規約における「ホスト（apap-runtime）が対応するSPIバージョン」。
     * 現時点でこの値を一元管理する定数が`apap-adapter-spi`側に存在しないため、ここで既定値を持つ
     * （要件充足に影響しない実装判断のためADR化せずここに根拠を記す）。
     */
    private var pluginHostSpiVersion: SemVer = SemVer(1, 0, 0),
) {
    /** [DomainEventPublisher]/[DomainEventSubscriber]をまとめて1つのeventBusとして差し替える。 */
    data class DomainEventBus(
        val publisher: DomainEventPublisher,
        val subscriber: DomainEventSubscriber,
    )

    fun adapterRegistry(registry: AdapterRegistry) = apply { adapterRegistry = registry }

    fun pluginDirectory(
        directory: Path,
        trustedPublicKey: PublicKey,
    ) = apply {
        pluginDirectory = directory
        pluginTrustedPublicKey = trustedPublicKey
    }

    fun secretStore(store: SecretStore) = apply { secretStore = store }

    fun cacheStore(store: CacheStore<CanonicalResponse>) = apply { cacheStore = store }

    fun eventBus(bus: DomainEventBus) = apply { eventBus = bus }

    fun meter(meter: Meter) = apply { this.meter = meter }

    fun tracer(tracer: Tracer) = apply { this.tracer = tracer }

    fun routingStrategy(
        strategy: RoutingStrategy,
        loadBalancer: LoadBalancer = this.loadBalancer,
    ) = apply {
        routingStrategy = strategy
        this.loadBalancer = loadBalancer
    }

    fun retryStrategy(strategy: RetryStrategy) = apply { retryStrategy = strategy }

    fun compactionStrategy(strategy: CompactionStrategy) = apply { compactionStrategy = strategy }

    fun clock(clock: Clock) = apply { this.clock = clock }

    fun idGenerator(idGenerator: IdGenerator) = apply { this.idGenerator = idGenerator }

    @Suppress("LongMethod")
    fun build(): ApapEngine {
        val resolvedAdapterRegistry = resolveAdapterRegistry()
        val metricsRecorder: MetricsRecorder = OpenTelemetryMetricsRecorder(meter)
        val effectiveCacheStore = cacheStore ?: InMemoryCacheStore(clock)

        val composer =
            ExecutionEngineComposer(
                providerRepository = repositories.providerRepository,
                modelRepository = repositories.modelRepository,
                aliasRepository = repositories.aliasRepository,
                policyRepository = repositories.policyRepository,
                healthLatencyStatsRepository = repositories.healthLatencyStatsRepository,
                quotaSnapshotRepository = repositories.quotaSnapshotRepository,
                tenantEntitlementRepository = repositories.tenantEntitlementRepository,
                memoryRepository = repositories.memoryRepository,
                conversationRepository = repositories.conversationRepository,
                priceBookRepository = repositories.priceBookRepository,
                budgetRepository = repositories.budgetRepository,
                usageRepository = repositories.usageRepository,
                quotaPolicyRepository = repositories.quotaPolicyRepository,
                adapterRegistry = resolvedAdapterRegistry,
                clock = clock,
                idGenerator = idGenerator,
                eventPublisher = eventBus.publisher,
                eventSubscriber = eventBus.subscriber,
                tracer = tracer,
                metricsRecorder = metricsRecorder,
                retryStrategy = retryStrategy ?: ExponentialBackoffJitterStrategy(),
                routingStrategy = routingStrategy,
                loadBalancer = loadBalancer,
                compactionStrategy = compactionStrategy,
                cacheStore = effectiveCacheStore,
            )
        MetricsEngine(eventBus.subscriber, metricsRecorder, repositories.providerRepository)

        val providerManager =
            ProviderManager(
                repositories.providerRepository,
                eventBus.publisher,
                clock,
                idGenerator,
                resolvedAdapterRegistry,
            )
        val modelManager =
            ModelManager(
                repositories.modelRepository,
                repositories.aliasRepository,
                eventBus.publisher,
                clock,
                idGenerator,
            )
        val admin =
            ApapAdmin(
                providerManager,
                modelManager,
                repositories.providerRepository,
                repositories.modelRepository,
                repositories.aliasRepository,
                repositories.policyRepository,
                resolvedAdapterRegistry,
                SecretStoreAccessor(secretStore),
            )
        val health = DefaultApapHealth(HealthCheckService())
        val capabilityDiscoveryQuery =
            CapabilityDiscoveryQuery(repositories.capabilityRepository, repositories.policyRepository)

        return DefaultApapEngine(
            executionEngine = composer.build(),
            capabilityDiscoveryQuery = capabilityDiscoveryQuery,
            idGenerator = idGenerator,
            admin = admin,
            health = health,
            pluginManager = pluginManagerOrNull,
        )
    }

    private var pluginManagerOrNull: PluginManager? = null

    private fun resolveAdapterRegistry(): AdapterRegistry {
        val explicit = adapterRegistry
        val directory = pluginDirectory
        val publicKey = pluginTrustedPublicKey
        return if (explicit != null) {
            explicit
        } else if (directory != null && publicKey != null) {
            val manager =
                PluginManager(
                    eventBus.publisher,
                    idGenerator,
                    clock,
                    PluginSignatureVerifier(publicKey),
                    pluginHostSpiVersion,
                )
            manager.scan(directory)
            pluginManagerOrNull = manager
            PluginManagerAdapterRegistry(manager)
        } else {
            EmptyAdapterRegistry
        }
    }

    private object EmptyAdapterRegistry : AdapterRegistry {
        override fun resolve(pluginId: String): ResolvedPlugin = throw PluginNotFoundException(pluginId)
    }
}
