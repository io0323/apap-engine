package apap.runtime

import apap.cache.CacheCodec
import apap.cache.CacheConfig
import apap.cache.CacheEngine
import apap.cache.CacheKeyStrategy
import apap.cache.CacheStore
import apap.cache.CacheabilityPolicy
import apap.cache.DefaultCacheEngine
import apap.cache.DefaultCacheabilityPolicy
import apap.cache.InMemoryCacheStore
import apap.cache.NormalizedJsonCacheKeyStrategy
import apap.cache.PassthroughCacheCodec
import apap.cache.ratelimit.InMemoryRateLimitCounterStore
import apap.cache.ratelimit.RateLimitCounterStore
import apap.cache.ratelimit.RateLimiter
import apap.cache.ratelimit.RateLimiterConfig
import apap.cache.ratelimit.TokenBucketRateLimiter
import apap.context.CompactionStrategy
import apap.context.ContextManager
import apap.context.ContextTokenCounter
import apap.context.ConversationManager
import apap.context.DefaultContextManager
import apap.context.HeuristicContextTokenCounter
import apap.context.MemoryManager
import apap.context.NoOpQueryEmbedder
import apap.context.QueryEmbedder
import apap.context.TruncateOldestCompactionStrategy
import apap.cost.CostEngine
import apap.cost.CostEngineConfig
import apap.cost.DefaultCostEngine
import apap.cost.quota.DefaultQuotaManager
import apap.cost.quota.QuotaManager
import apap.cost.quota.QuotaManagerConfig
import apap.domain.model.conversation.MemoryScope
import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository
import apap.domain.port.BudgetRepository
import apap.domain.port.CircuitBreakerStateStore
import apap.domain.port.Clock
import apap.domain.port.ConversationRepository
import apap.domain.port.DomainEventPublisher
import apap.domain.port.DomainEventSubscriber
import apap.domain.port.HealthLatencyStatsRepository
import apap.domain.port.IdGenerator
import apap.domain.port.MemoryRepository
import apap.domain.port.MetricsRecorder
import apap.domain.port.ModelRepository
import apap.domain.port.PolicyRepository
import apap.domain.port.PriceBookRepository
import apap.domain.port.ProviderRepository
import apap.domain.port.QuotaPolicyRepository
import apap.domain.port.QuotaSnapshotRepository
import apap.domain.port.TenantEntitlementRepository
import apap.domain.port.UsageRepository
import apap.domain.service.execution.TokenEstimationConfig
import apap.execution.DefaultExecutionEngine
import apap.execution.ExecutionEngine
import apap.execution.IdempotencyGuard
import apap.execution.adapter.out.InMemoryCircuitBreakerStateStore
import apap.execution.attempt.AttemptExecutor
import apap.execution.circuitbreaker.CircuitBreaker
import apap.execution.circuitbreaker.CircuitBreakerConfig
import apap.execution.estimation.TokenEstimator
import apap.execution.fallback.FallbackEngine
import apap.execution.retry.ExponentialBackoffJitterStrategy
import apap.execution.retry.RetryConfig
import apap.execution.retry.RetryStrategy
import apap.execution.streaming.StreamingConfig
import apap.execution.streaming.StreamingEngine
import apap.execution.streaming.StreamingRequestExecutor
import apap.execution.streaming.StreamingTurnRecorder
import apap.execution.structuredoutput.StructuredOutputConfig
import apap.observability.metrics.MetricsEngine
import apap.observability.metrics.OpenTelemetryMetricsRecorder
import apap.prompt.DefaultPromptEngine
import apap.prompt.PromptEngine
import apap.provider.AdapterRegistry
import apap.routing.CandidateFactory
import apap.routing.CostEstimator
import apap.routing.RealCostEstimator
import apap.routing.RoutingCandidateCache
import apap.routing.RoutingEngine
import apap.routing.spi.LoadBalancer
import apap.routing.spi.RoutingStrategy
import apap.routing.spi.WeightedRoundRobinLoadBalancer
import apap.routing.spi.WeightedScoreRoutingStrategy
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer

/**
 * 03_基本設計.md 3.15 DI構成のコンポジションルート: 実行エンジン一式（apap-execution）を
 * Routing/Prompt/Context/Cache/Costの各Portと配線する。コンストラクタ注入のみ（フィールド注入禁止）
 * という方針に従い、本クラス自体はコンテナを持たず、単に組立関数を提供するだけの薄い層とする。
 *
 * P5〜P7を通じて、当初Passthroughスタブだった4系統（Prompt/Context: P6、Cache/Cost: P7）は
 * すべて実装（[DefaultPromptEngine]/[DefaultContextManager]/[DefaultCacheEngine]/
 * [DefaultCostEngine]）へ置き換わった。参照先がゼロになったため、スタブ使用を明示的にopt-inさせる
 * 旧`optInToStubs`パラメータと`requireStubOptIn`メソッドは削除した（CLAUDE.md「確実に不要なら
 * 完全に削除する」方針）。
 *
 * `ExecutionEngine`は`conversationRepository`から読み取り専用でConversationを解決し
 * `ContextManager.build`へ渡す（02_システム仕様.md 2.8 step2）。Turn永続化（2.8 step11、
 * user turnはProvider呼出前・assistant turnは応答確定後）は`ConversationManager`経由で本Composerが
 * 配線する（着手前レビューで解消、`apap.execution.ExecutionEngine`のKDoc参照）。`SessionManager`/
 * `apap.prompt.PromptTemplateManager`は引き続き対象外（Session発行/検証やPrompt Template解決は
 * Gateway/Session層の責務であり、本Composerが構築する`ExecutionEngine`の依存には入らない）。
 *
 * [quotaPolicyRepository]と[quotaPolicyProvider]は役割が異なり併存する: 前者は登録・一覧・更新の
 * CRUD（管理API向け）、後者はExecutionEngine実行時の高速な解決口。[quotaPolicyProvider]の既定実装は
 * [quotaPolicyRepository]経由（`findByTenant`の先頭要素）とする。
 */
@Suppress("LongParameterList")
class ExecutionEngineComposer(
    private val providerRepository: ProviderRepository,
    private val modelRepository: ModelRepository,
    private val aliasRepository: AliasRepository,
    private val policyRepository: PolicyRepository,
    private val healthLatencyStatsRepository: HealthLatencyStatsRepository,
    private val quotaSnapshotRepository: QuotaSnapshotRepository,
    private val tenantEntitlementRepository: TenantEntitlementRepository,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val priceBookRepository: PriceBookRepository,
    private val budgetRepository: BudgetRepository,
    private val usageRepository: UsageRepository,
    private val quotaPolicyRepository: QuotaPolicyRepository,
    private val adapterRegistry: AdapterRegistry,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val eventPublisher: DomainEventPublisher,
    private val eventSubscriber: DomainEventSubscriber,
    private val tracer: Tracer = OpenTelemetry.noop().getTracer("apap-execution"),
    /**
     * 02_システム仕様.md 2.19 Monitoring仕様。既定はOpenTelemetryのnoop Meter（[tracer]と同じ
     * パターン）。埋込ホストが実SDKのMeterを使う場合は[OpenTelemetryMetricsRecorder]を明示的に渡す。
     * [build]内で[MetricsEngine]（Event Bus購読）へ配線し、`apap_overhead_duration_seconds`は
     * [apap.execution.PhaseTimings]から、`apap_rate_limit_events_total{action="wait"}`は
     * [TokenBucketRateLimiter]から直接呼ばれる（[MetricsEngine]のKDoc参照）。
     */
    private val metricsRecorder: MetricsRecorder =
        OpenTelemetryMetricsRecorder(OpenTelemetry.noop().getMeter("apap-execution")),
    private val quotaPolicyProvider: (TenantId) -> QuotaPolicy? = {
        quotaPolicyRepository.findByTenant(it).firstOrNull()
    },
    private val circuitBreakerConfig: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val retryConfig: RetryConfig = RetryConfig(),
    /** [ApapEngineBuilder]の`retryStrategy`差替点。既定は[AttemptExecutor]自身の既定と同じ。 */
    private val retryStrategy: RetryStrategy = ExponentialBackoffJitterStrategy(retryConfig),
    /** [ApapEngineBuilder]の`routingStrategy`差替点。既定は[RoutingEngine]自身の既定と同じ。 */
    private val routingStrategy: RoutingStrategy = WeightedScoreRoutingStrategy(),
    private val loadBalancer: LoadBalancer = WeightedRoundRobinLoadBalancer(),
    private val structuredOutputConfig: StructuredOutputConfig = StructuredOutputConfig(),
    private val rateLimiterConfig: RateLimiterConfig = RateLimiterConfig(),
    private val quotaManagerConfig: QuotaManagerConfig = QuotaManagerConfig(),
    private val costEngineConfig: CostEngineConfig = CostEngineConfig(),
    private val routingCostEstimator: CostEstimator = RealCostEstimator(priceBookRepository, clock),
    private val tokenEstimationConfig: TokenEstimationConfig = TokenEstimationConfig(),
    private val contextTokenCounterFactory: (ModelId) -> ContextTokenCounter = {
        HeuristicContextTokenCounter(it, tokenEstimationConfig)
    },
    private val compactionStrategy: CompactionStrategy = TruncateOldestCompactionStrategy(),
    /**
     * ADR-0023: 実装（P8以降）が[CircuitBreaker]/[RateLimiter]を正しく経由できるよう、既に構築済みの
     * インスタンスを受け取れるファクトリとする（[ResilientQueryEmbedder]参照）。既定はNoOpのまま
     * （ラップしても意味がないため既定では[ResilientQueryEmbedder]を挟まない）。
     */
    private val queryEmbedderFactory: (CircuitBreaker, RateLimiter) -> QueryEmbedder = { _, _ ->
        NoOpQueryEmbedder(optedIn = true)
    },
    private val memoryScopes: Set<MemoryScope> = MemoryScope.entries.toSet(),
    private val memoryTopK: Int = DEFAULT_MEMORY_TOP_K,
    private val memorySimilarityThreshold: Double = DEFAULT_MEMORY_SIMILARITY_THRESHOLD,
    private val cacheStore: CacheStore<CanonicalResponse> = InMemoryCacheStore(clock),
    private val cacheCodec: CacheCodec<CanonicalResponse, CanonicalResponse> = PassthroughCacheCodec(),
    private val cacheKeyStrategy: CacheKeyStrategy = NormalizedJsonCacheKeyStrategy(),
    private val cacheabilityPolicy: CacheabilityPolicy = DefaultCacheabilityPolicy(),
    private val cacheConfig: CacheConfig = CacheConfig(),
    private val streamingConfig: StreamingConfig = StreamingConfig(),
    // ADR-0001/ADR-0025: 既定はIn-Memory（単一プロセス埋込利用で十分）。マルチノード運用時は
    // 埋込ホストが`modules/apap-infrastructure-distributed`のRedis実装をここへ渡す
    // （`apap-runtime`自体はそのモジュールに依存しない）。
    private val cbStore: CircuitBreakerStateStore = InMemoryCircuitBreakerStateStore(),
    private val rateLimitCounterStore: RateLimitCounterStore = InMemoryRateLimitCounterStore(),
) {
    @Suppress("LongMethod")
    fun build(): ExecutionEngine {
        // CB状態はRouting（読取専用）とExecution（書込）の双方から同一インスタンスを参照する必要がある
        // （apap.domain.port.CircuitBreakerStateStoreのKDoc参照）。
        val candidateCache = RoutingCandidateCache()
        eventSubscriber.subscribe { candidateCache.apply(it) }
        MetricsEngine(eventSubscriber, metricsRecorder, providerRepository)

        val candidateFactory =
            CandidateFactory(
                providerRepository,
                modelRepository,
                aliasRepository,
                cbStore,
                healthLatencyStatsRepository,
                quotaSnapshotRepository,
                tenantEntitlementRepository,
                routingCostEstimator,
                candidateCache,
                clock,
            )
        val routingEngine = RoutingEngine(candidateFactory, policyRepository, routingStrategy, loadBalancer)

        val circuitBreaker = CircuitBreaker(cbStore, clock, eventPublisher, idGenerator, circuitBreakerConfig)
        val rateLimiter: RateLimiter =
            TokenBucketRateLimiter(
                clock,
                eventPublisher,
                idGenerator,
                rateLimiterConfig,
                metricsRecorder = metricsRecorder,
                store = rateLimitCounterStore,
            )
        val quotaManager: QuotaManager = DefaultQuotaManager(idGenerator, clock, eventPublisher, quotaManagerConfig)

        val promptEngine: PromptEngine = DefaultPromptEngine()
        val conversationManager = ConversationManager(conversationRepository, clock, idGenerator, eventPublisher)
        val memoryManager = MemoryManager(memoryRepository, clock, idGenerator, eventPublisher)
        val queryEmbedder = queryEmbedderFactory(circuitBreaker, rateLimiter)
        val contextManager: ContextManager =
            DefaultContextManager(
                modelRepository = modelRepository,
                memoryManager = memoryManager,
                tokenCounterFactory = contextTokenCounterFactory,
                clock = clock,
                idGenerator = idGenerator,
                eventPublisher = eventPublisher,
                compactionStrategy = compactionStrategy,
                queryEmbedder = queryEmbedder,
                estimationConfig = tokenEstimationConfig,
                memoryScopes = memoryScopes,
                memoryTopK = memoryTopK,
                memorySimilarityThreshold = memorySimilarityThreshold,
            )
        val defaultCacheEngine =
            DefaultCacheEngine(
                cacheStore,
                cacheCodec,
                cacheKeyStrategy,
                cacheabilityPolicy,
                cacheConfig,
                aliasRepository,
                clock,
                eventPublisher,
                idGenerator,
            )
        eventSubscriber.subscribe { defaultCacheEngine.apply(it) }
        val cacheEngine: CacheEngine = defaultCacheEngine
        val costEngine: CostEngine =
            DefaultCostEngine(
                priceBookRepository,
                budgetRepository,
                usageRepository,
                clock,
                idGenerator,
                eventPublisher,
                costEngineConfig,
            )

        val attemptExecutor =
            AttemptExecutor(
                providerRepository,
                modelRepository,
                adapterRegistry,
                circuitBreaker,
                rateLimiter,
                clock,
                eventPublisher,
                idGenerator,
                retryConfig,
                retryStrategy,
                tracer = tracer,
            )
        val fallbackEngine =
            FallbackEngine(
                attemptExecutor,
                circuitBreaker,
                contextManager,
                clock,
                eventPublisher,
                idGenerator,
                structuredOutputConfig,
            )
        val tokenEstimator = TokenEstimator(providerRepository, adapterRegistry, tokenEstimationConfig)

        val streamingEngine = StreamingEngine(clock, eventPublisher, idGenerator, streamingConfig)
        val streamingTurnRecorder = StreamingTurnRecorder(conversationManager)
        val streamingRequestExecutor =
            StreamingRequestExecutor(
                providerRepository,
                modelRepository,
                adapterRegistry,
                circuitBreaker,
                rateLimiter,
                streamingEngine,
                streamingTurnRecorder,
                quotaManager,
                costEngine,
                clock,
                eventPublisher,
                idGenerator,
                tracer,
            )

        return DefaultExecutionEngine(
            promptEngine,
            contextManager,
            conversationRepository,
            conversationManager,
            cacheEngine,
            routingEngine,
            quotaManager,
            costEngine,
            rateLimiter,
            fallbackEngine,
            streamingRequestExecutor,
            tokenEstimator,
            IdempotencyGuard(),
            clock,
            idGenerator,
            eventPublisher,
            metricsRecorder,
            quotaPolicyProvider,
            tracer = tracer,
        )
    }

    private companion object {
        const val DEFAULT_MEMORY_TOP_K = 5
        const val DEFAULT_MEMORY_SIMILARITY_THRESHOLD = 0.75
    }
}
