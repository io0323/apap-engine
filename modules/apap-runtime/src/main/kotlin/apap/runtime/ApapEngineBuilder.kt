package apap.runtime

import apap.cache.CacheStore
import apap.cache.InMemoryCacheStore
import apap.cache.ratelimit.RateLimiter
import apap.cache.ratelimit.RateLimiterConfig
import apap.context.CompactionStrategy
import apap.context.NoOpQueryEmbedder
import apap.context.QueryEmbedder
import apap.context.TruncateOldestCompactionStrategy
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.SemVer
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.DomainEventSubscriber
import apap.domain.port.IdGenerator
import apap.domain.port.MetricsRecorder
import apap.domain.port.SecretStore
import apap.execution.circuitbreaker.CircuitBreaker
import apap.execution.retry.ExponentialBackoffJitterStrategy
import apap.execution.retry.RetryStrategy
import apap.infrastructure.eventbus.SynchronousEventBus
import apap.infrastructure.secret.EnvVarSecretStore
import apap.infrastructure.secret.SecretStoreAccessor
import apap.observability.audit.AuditConfig
import apap.observability.audit.AuditEngine
import apap.observability.health.HealthCheckService
import apap.observability.health.ProviderHealthAggregator
import apap.observability.metrics.OpenTelemetryMetricsRecorder
import apap.plugin.PluginManager
import apap.plugin.PluginSignatureVerifier
import apap.provider.AdapterRegistry
import apap.provider.CapabilityDiscoveryQuery
import apap.provider.CapabilityRegistry
import apap.provider.ModelManager
import apap.provider.PluginNotFoundException
import apap.provider.ProviderHealthCheckTask
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
    private var queryEmbedding: (suspend (List<ContentPart>) -> List<Double>)? = null,
    /**
     * 2.19のメトリクス記録口。既定は[meter]から作る[OpenTelemetryMetricsRecorder]。
     * テストや、独自の計測基盤を持つ埋込ホストが差し替えられるようにする。
     */
    private var metricsRecorderOverride: MetricsRecorder? = null,
    /**
     * 監査ログの設定（ADR-0005: 本文保存は既定OFF、opt-in時はMaskingStrategy必須）。
     * P11-F1: `AuditEngine`自体がどこからも構築されておらず、監査ログが1件も
     * 記録されていなかった。[build]で構築し、[ApapEngine.close]で停止する。
     */
    private var auditConfig: AuditConfig = AuditConfig(),
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

    /** [MetricsRecorder]を明示的に差し替える（既定は[meter]から作る）。 */
    fun metricsRecorder(recorder: MetricsRecorder) = apply { this.metricsRecorderOverride = recorder }

    /** 監査ログの設定（本文保存のopt-inとマスキング戦略）。 */
    fun auditConfig(config: AuditConfig) = apply { this.auditConfig = config }

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
     * ここで設定するのは**明示設定の無いスコープ**に適用される既定バケットである。
     * Providerスコープは`Provider.rateLimits`の`rpm`から`ProviderRateLimitConfigurer`が
     * 自動設定するため、通常この既定値を触る必要は無い。
     *
     * テナントスコープにはドメイン上の設定元が無く（ADR-0035）、現状は既定バケットが
     * そのまま適用される。テナント別に絞りたい場合はここで上限を指定すること。
     * 既定は実質無制限——絞る根拠の無いスコープを既定値で絞ると、意図しない全体スロットルになる
     * （P11-F10でこれが出荷時 4.7 req/s の原因だった）。
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

    /**
     * Memory類似検索（FR-CTX-004 / 2.17）のクエリ埋め込み。
     *
     * 既定は[apap.context.NoOpQueryEmbedder]（常に空ベクトル）で、**Memory注入は一切起きない**。
     * ベクトル化の実体はAPAP側に無く（ADR-0023で経路だけが確定している）、埋込ホストが供給する
     * までMemoryは実行経路上で機能しない。ここを通さない限りホストにはその供給手段が無かった
     * ——`ExecutionEngineComposer`はファクトリ引数を持つが、本番の入口である本ビルダが
     * 露出していなかった（P14で検出。実装済みだが到達不能だったF1/F3と同じ形）。
     *
     * 引数を`QueryEmbedder`型ではなくラムダにしているのは[rateLimits]と同じ理由——`apap-context`は
     * `implementation`スコープで埋込ホストから見えない（`HostCompileClasspathTest`がその分離を
     * 検証している）。返すベクトルの次元は`Memory.embedding`と揃えること（合わないと類似度が
     * 0になり、黙って「該当なし」になる）。
     *
     * **既知の制約**: ADR-0023は「埋め込み呼出もメインリクエストと同じCircuit Breaker /
     * Rate Limiterを経由する」と決めているが、[apap.context.QueryEmbedder.embed]は
     * `parts`しか受け取らず、[ResilientQueryEmbedder]が必要とするtenantId/traceId/
     * providerId/modelIdを渡す口が無い。ここで渡した実装は**そのまま呼ばれる**
     * （保護の無いまま呼ばれることを黙らせない。ホスト側で自前の保護を掛けるか、
     * `embed`のシグネチャ拡張を待つこと）。docs/verification-report.md参照。
     */
    fun queryEmbedding(embed: suspend (List<ContentPart>) -> List<Double>) = apply { this.queryEmbedding = embed }

    fun clock(clock: Clock) = apply { this.clock = clock }

    fun idGenerator(idGenerator: IdGenerator) = apply { this.idGenerator = idGenerator }

    @Suppress("LongMethod")
    fun build(): ApapEngine {
        val resolvedAdapterRegistry = resolveAdapterRegistry()
        val metricsRecorder: MetricsRecorder = metricsRecorderOverride ?: OpenTelemetryMetricsRecorder(meter)
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
                queryEmbedderFactory = resolveQueryEmbedderFactory(),
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
        // FR-CAP-017 / P11-F3: Capabilityスキーマの登録・検証。参照側（CapabilityDiscoveryQuery）
        // だけが配線され、登録側が本番のどこからも生成されていなかった。
        val capabilityRegistry = CapabilityRegistry(repositories.capabilityRepository)
        val admin =
            ApapAdmin(
                providerManager,
                modelManager,
                repositories.providerRepository,
                repositories.modelRepository,
                repositories.aliasRepository,
                repositories.policyRepository,
                resolvedAdapterRegistry,
                capabilityRegistry,
                SecretStoreAccessor(secretStore),
            )
        // ProviderHealthAggregatorはEvent Busを購読してProvider別状態を保持する（P11-F2で未配線と判明）。
        // ProviderHealthCheckTaskが発火するProviderHealthChangedを受け取り、/health/providersへ反映する。
        val providerHealthAggregator = ProviderHealthAggregator(eventBus.subscriber)
        val health = DefaultApapHealth(HealthCheckService(providerHealthIndicator = providerHealthAggregator))
        val capabilityDiscoveryQuery =
            CapabilityDiscoveryQuery(repositories.capabilityRepository, repositories.policyRepository)

        // FR-PRV-006 / ADR-0032: 周期実行タスク。ここでは**生成するだけで実行しない**
        // （埋込ライブラリが常駐スレッドを起こさない）。駆動は宿主かGatewayが行う。
        val scheduledTasks =
            listOf(
                ProviderHealthCheckTask(
                    providerRepository = repositories.providerRepository,
                    adapterRegistry = resolvedAdapterRegistry,
                    eventPublisher = eventBus.publisher,
                    clock = clock,
                    idGenerator = idGenerator,
                ),
            )

        // FR-OBS-001 / FR-SEC-006（P11-F1）: 監査ログの記録。initでEvent Busを購読し、
        // 単一のデーモンスレッドで書き込む。close()で確実に止めるためDefaultApapEngineへ渡す。
        val auditEngine =
            AuditEngine(
                eventSubscriber = eventBus.subscriber,
                auditRepository = repositories.auditRepository,
                config = auditConfig,
                idGenerator = idGenerator,
            )

        return DefaultApapEngine(
            executionEngine = composer.build(),
            capabilityDiscoveryQuery = capabilityDiscoveryQuery,
            idGenerator = idGenerator,
            admin = admin,
            health = health,
            metrics = metricsRecorder,
            scheduledTasks = scheduledTasks,
            auditEngine = auditEngine,
            pluginManager = pluginManagerOrNull,
        )
    }

    private var pluginManagerOrNull: PluginManager? = null

    /**
     * [queryEmbedding]が未設定なら既定の[NoOpQueryEmbedder]（＝Memory注入なし）。
     * Composerのファクトリは[CircuitBreaker]/[RateLimiter]を渡してくるが、上記の既知の制約により
     * ここでは使わない（使えるふりをしない）。
     */
    private fun resolveQueryEmbedderFactory(): (CircuitBreaker, RateLimiter) -> QueryEmbedder {
        val embed = queryEmbedding ?: return { _, _ -> NoOpQueryEmbedder(optedIn = true) }
        return { _, _ -> QueryEmbedder { parts -> embed(parts) } }
    }

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
