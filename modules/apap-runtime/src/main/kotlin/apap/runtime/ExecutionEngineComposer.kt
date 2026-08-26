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
import apap.cache.ratelimit.RateLimiter
import apap.cache.ratelimit.RateLimiterConfig
import apap.cache.ratelimit.TokenBucketRateLimiter
import apap.context.CompactionStrategy
import apap.context.ContextManager
import apap.context.ContextTokenCounter
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
import apap.domain.port.Clock
import apap.domain.port.ConversationRepository
import apap.domain.port.DomainEventPublisher
import apap.domain.port.DomainEventSubscriber
import apap.domain.port.HealthLatencyStatsRepository
import apap.domain.port.IdGenerator
import apap.domain.port.MemoryRepository
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
import apap.execution.retry.RetryConfig
import apap.execution.structuredoutput.StructuredOutputConfig
import apap.prompt.DefaultPromptEngine
import apap.prompt.PromptEngine
import apap.provider.AdapterRegistry
import apap.routing.CandidateFactory
import apap.routing.CostEstimator
import apap.routing.RealCostEstimator
import apap.routing.RoutingCandidateCache
import apap.routing.RoutingEngine

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
 * `ContextManager.build`へ渡す（02_システム仕様.md 2.8 step2、着手前レビューで読み取り側のみに
 * 限定）。Turn永続化（2.8 step11、応答成功後の書込）は対象外のため`SessionManager`/
 * `ConversationManager`/`apap.prompt.PromptTemplateManager`は本Composerが構築する
 * `ExecutionEngine`の依存には入らない。埋込先アプリケーションがそれぞれのRepositoryから
 * 直接構築して使う（Turn永続化はSession/Gateway層の責務）。
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
    private val quotaPolicyProvider: (TenantId) -> QuotaPolicy? = {
        quotaPolicyRepository.findByTenant(it).firstOrNull()
    },
    private val circuitBreakerConfig: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val retryConfig: RetryConfig = RetryConfig(),
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
    private val queryEmbedder: QueryEmbedder = NoOpQueryEmbedder(optedIn = true),
    private val memoryScopes: Set<MemoryScope> = MemoryScope.entries.toSet(),
    private val memoryTopK: Int = DEFAULT_MEMORY_TOP_K,
    private val memorySimilarityThreshold: Double = DEFAULT_MEMORY_SIMILARITY_THRESHOLD,
    private val cacheStore: CacheStore<CanonicalResponse> = InMemoryCacheStore(clock),
    private val cacheCodec: CacheCodec<CanonicalResponse, CanonicalResponse> = PassthroughCacheCodec(),
    private val cacheKeyStrategy: CacheKeyStrategy = NormalizedJsonCacheKeyStrategy(),
    private val cacheabilityPolicy: CacheabilityPolicy = DefaultCacheabilityPolicy(),
    private val cacheConfig: CacheConfig = CacheConfig(),
) {
    @Suppress("LongMethod")
    fun build(): ExecutionEngine {
        // CB状態はRouting（読取専用）とExecution（書込）の双方から同一インスタンスを参照する必要がある
        // （apap.domain.port.CircuitBreakerStateStoreのKDoc参照）。
        val cbStore = InMemoryCircuitBreakerStateStore()
        val candidateCache = RoutingCandidateCache()
        eventSubscriber.subscribe { candidateCache.apply(it) }

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
        val routingEngine = RoutingEngine(candidateFactory, policyRepository)

        val circuitBreaker = CircuitBreaker(cbStore, clock, eventPublisher, idGenerator, circuitBreakerConfig)
        val rateLimiter: RateLimiter = TokenBucketRateLimiter(clock, eventPublisher, idGenerator, rateLimiterConfig)
        val quotaManager: QuotaManager = DefaultQuotaManager(idGenerator, clock, eventPublisher, quotaManagerConfig)

        val promptEngine: PromptEngine = DefaultPromptEngine()
        val memoryManager = MemoryManager(memoryRepository, clock, idGenerator, eventPublisher)
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

        return DefaultExecutionEngine(
            promptEngine,
            contextManager,
            conversationRepository,
            cacheEngine,
            routingEngine,
            quotaManager,
            costEngine,
            rateLimiter,
            fallbackEngine,
            tokenEstimator,
            IdempotencyGuard(),
            clock,
            idGenerator,
            eventPublisher,
            quotaPolicyProvider,
        )
    }

    private companion object {
        const val DEFAULT_MEMORY_TOP_K = 5
        const val DEFAULT_MEMORY_SIMILARITY_THRESHOLD = 0.75
    }
}
