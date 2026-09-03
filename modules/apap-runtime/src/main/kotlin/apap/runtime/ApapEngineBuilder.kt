package apap.runtime

import apap.cache.CacheStore
import apap.cache.InMemoryCacheStore
import apap.cache.ratelimit.RateLimiterConfig
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
    private var rateLimiterConfig: RateLimiterConfig = RateLimiterConfig(),
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

    /**
     * Plugin署名検証の信頼鍵のみを設定する（[applyConfig]で`plugin.dir`を与えた場合、鍵は設定
     * ファイルに書けないためこちらで別途渡す）。鍵無しで[pluginDirectory]だけが設定された状態で
     * [build]すると例外になる（黙って`EmptyAdapterRegistry`へ縮退させない）。
     */
    fun pluginTrustedPublicKey(trustedPublicKey: PublicKey) = apply { pluginTrustedPublicKey = trustedPublicKey }

    /**
     * 03_基本設計.md 3.15の`application.yaml`（[ApapConfig]）で宣言されたSPIバインドを適用する。
     * [ApapConfig]はファイル/Map/プログラマティックの3経路で構築できる（[ApapConfig.fromYamlFile]/
     * [ApapConfig.fromMap]/コンストラクタ）。
     *
     * 名前で選べるのは引数無しで構築できる組込み実装（[KNOWN_ROUTING_STRATEGIES]等）に限る。
     * 外部システム接続を要する実装（3.15例の`distributed-kvs`/`vault-compatible`）は接続情報を
     * 名前だけでは決められないため、対応する実装インスタンスを[cacheStore]/[secretStore]へ
     * 直接渡すこと。未知の名前は黙って既定値へfall backせず例外にする（設定ミスの握り潰しを避ける）。
     */
    fun applyConfig(config: ApapConfig) =
        apply {
            config.routingStrategy?.let {
                routingStrategy = resolveNamed(it, KNOWN_ROUTING_STRATEGIES, "routing.strategy")
            }
            config.retryStrategy?.let { retryStrategy = resolveNamed(it, KNOWN_RETRY_STRATEGIES, "retry.strategy") }
            config.compactionStrategy?.let {
                compactionStrategy = resolveNamed(it, KNOWN_COMPACTION_STRATEGIES, "compaction.strategy")
            }
            config.secretStore?.let { secretStore = resolveNamed(it, KNOWN_SECRET_STORES, "secret.store") }
            config.cacheStore?.let { name ->
                // `in-memory`は名前の妥当性のみ検証し、生成はbuild()へ委ねる（InMemoryCacheStoreは
                // Clockを要るため、ここで生成すると applyConfig(...).clock(...) の順で呼ばれた際に
                // 古いClockを掴んだままになる）。cacheStore=nullのままならbuild()が最終的なclockで
                // InMemoryCacheStoreを構築する。
                if (name != CACHE_STORE_IN_MEMORY) {
                    throw unknownName("cache.store", name, setOf(CACHE_STORE_IN_MEMORY))
                }
                cacheStore = null
            }
            config.pluginDir?.let { pluginDirectory = Path.of(it) }
            require(config.pluginSignatureRequired) {
                "apap.plugin.signature.required=false is not supported: ApapEngineBuilder always verifies " +
                    "plugin signatures (PluginSignatureVerifier). Remove the setting or set it to true."
            }
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

    /**
     * Rate Limiterの既定トークンバケット設定（FR-EXE-003）。
     *
     * 既定は容量60・毎秒1トークン補充で、**バースト60件のあとは毎秒1リクエスト**に絞られる。
     * `RateLimiter.configure(scope, ...)`は本番配線から呼ばれておらず、登録済みProviderの
     * `rateLimits`（rpm/tpm/concurrent）はレート制限へ一切反映されない（P11-F10）。
     * そのため現状はこの既定値がすべてのスコープに適用される。スループットを要する構成では
     * 埋込ホストがここで明示的に上書きすること。
     *
     * 引数を`RateLimiterConfig`型ではなくプリミティブにしているのは、`apap-cache`が
     * `implementation`スコープで埋込ホストから見えないため（`HostCompileClasspathTest`が
     * その分離を検証している）。設定値だけを受け取り、内部で組み立てる。
     *
     * @param capacity バケット容量（＝瞬間的に許容するバースト件数）
     * @param refillPerSecond 毎秒のトークン補充数（＝定常状態の許容レート）
     */
    fun rateLimits(
        capacity: Int,
        refillPerSecond: Double,
    ) = apply { this.rateLimiterConfig = RateLimiterConfig(capacity, refillPerSecond) }

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
                rateLimiterConfig = rateLimiterConfig,
            )
        // MetricsEngineはExecutionEngineComposer.build()の中で構築・購読される。
        // ここでも構築すると同じEvent Busへ2つのMetricsEngineが購読し、
        // IdempotentEventHandlerの重複排除は**インスタンスごと**なので
        // 全イベントが2回処理される（＝イベント起因のメトリクスが2倍になる）。
        // ExecutionEngineComposerTestが二重購読の再発を検出する。

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
        require(directory == null || publicKey != null) {
            "pluginDirectory is set but no trusted public key was provided. " +
                "Pass it via pluginDirectory(directory, trustedPublicKey) or pluginTrustedPublicKey(key); " +
                "plugin signature verification cannot be skipped."
        }
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

    companion object {
        /** 03_基本設計.md 3.15 `routing.strategy`で選べる組込み実装名。 */
        val KNOWN_ROUTING_STRATEGIES: Map<String, () -> RoutingStrategy> =
            mapOf("weighted-score" to { WeightedScoreRoutingStrategy() })

        /** 3.15 `retry.strategy`。 */
        val KNOWN_RETRY_STRATEGIES: Map<String, () -> RetryStrategy> =
            mapOf("exp-backoff-jitter" to { ExponentialBackoffJitterStrategy() })

        /**
         * 3.15 `compaction.strategy`。`SummarizeCompactionStrategy`/`ImportanceCompactionStrategy`は
         * 要約器・重要度スコアラの注入を要し名前だけでは構築できないため、
         * [compactionStrategy]へ直接インスタンスを渡すこと。
         */
        val KNOWN_COMPACTION_STRATEGIES: Map<String, () -> CompactionStrategy> =
            mapOf("truncate-oldest" to { TruncateOldestCompactionStrategy() })

        /**
         * 3.15 `secret.store`。3.15例の`vault-compatible`（`ExternalSecretStore`）は接続情報を
         * 要するため名前では選べない——[secretStore]へ直接渡すこと。
         */
        val KNOWN_SECRET_STORES: Map<String, () -> SecretStore> =
            mapOf("env-var" to { EnvVarSecretStore() })

        /**
         * 3.15 `cache.store`。3.15例の`distributed-kvs`は`apap-infrastructure-distributed`の実装であり、
         * これを名前で解決可能にすると`apap-runtime`が同モジュールへ依存してしまう
         * （`EmbeddingConstraintTest`が禁止する既定依存グラフ違反）。[cacheStore]へ直接渡すこと。
         */
        const val CACHE_STORE_IN_MEMORY = "in-memory"

        private fun <T> resolveNamed(
            name: String,
            known: Map<String, () -> T>,
            settingKey: String,
        ): T = known[name]?.invoke() ?: throw unknownName(settingKey, name, known.keys)

        private fun unknownName(
            settingKey: String,
            name: String,
            known: Set<String>,
        ): IllegalArgumentException =
            IllegalArgumentException(
                "Unknown apap.$settingKey: '$name'. Known names: ${known.sorted()}. " +
                    "Implementations requiring external connection details cannot be selected by name — " +
                    "pass the instance to the corresponding ApapEngineBuilder method instead.",
            )
    }
}
