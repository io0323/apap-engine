package apap.runtime

import apap.cache.CacheEngine
import apap.cache.PassthroughCacheEngine
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
import apap.cost.PassthroughCostEngine
import apap.cost.quota.DefaultQuotaManager
import apap.cost.quota.QuotaManager
import apap.cost.quota.QuotaManagerConfig
import apap.domain.model.conversation.MemoryScope
import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.DomainEventSubscriber
import apap.domain.port.HealthLatencyStatsRepository
import apap.domain.port.IdGenerator
import apap.domain.port.MemoryRepository
import apap.domain.port.ModelRepository
import apap.domain.port.PolicyRepository
import apap.domain.port.ProviderRepository
import apap.domain.port.QuotaSnapshotRepository
import apap.domain.port.TenantEntitlementRepository
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
import apap.routing.RoutingCandidateCache
import apap.routing.RoutingEngine
import apap.routing.ZeroCostEstimator

/**
 * 03_基本設計.md 3.15 DI構成のコンポジションルート: 実行エンジン一式（apap-execution）を
 * Routing/Prompt/Context/Cache/Costの各Portと配線する。コンストラクタ注入のみ（フィールド注入禁止）
 * という方針に従い、本クラス自体はコンテナを持たず、単に組立関数を提供するだけの薄い層とする。
 *
 * P6でPrompt/Contextは実装（[DefaultPromptEngine]/[DefaultContextManager]）へ置き換わり、
 * `optInToStubs`の対象からは外れた（無条件に構築する）。P7未着手のPassthrough実装
 * （[PassthroughCacheEngine]/[PassthroughCostEngine]）のみ、[optInToStubs]が`true`の場合に使う。
 * `false`（既定）の場合はここで構築時例外となり、呼び出し側が「未実装の機能に依存している」ことを
 * 起動時に必ず認識させる。
 *
 * `SessionManager`/`ConversationManager`/`apap.prompt.PromptTemplateManager`は本Composerが
 * 構築する`ExecutionEngine`のいずれの依存にも入らない（現行の既定Prompt Pipeline/Fallback
 * refitはSession/Conversation解決やTemplate参照をまだ消費しない、KDoc参照:
 * [apap.context.ContextManager]）ため、意図的にここへは配線しない。埋込先アプリケーションが
 * それぞれのRepositoryから直接構築して使う。
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
    private val adapterRegistry: AdapterRegistry,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val eventPublisher: DomainEventPublisher,
    private val eventSubscriber: DomainEventSubscriber,
    private val optInToStubs: Boolean = false,
    private val quotaPolicyProvider: (TenantId) -> QuotaPolicy? = { null },
    private val circuitBreakerConfig: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val retryConfig: RetryConfig = RetryConfig(),
    private val structuredOutputConfig: StructuredOutputConfig = StructuredOutputConfig(),
    private val rateLimiterConfig: RateLimiterConfig = RateLimiterConfig(),
    private val quotaManagerConfig: QuotaManagerConfig = QuotaManagerConfig(),
    private val routingCostEstimator: CostEstimator = ZeroCostEstimator(),
    private val tokenEstimationConfig: TokenEstimationConfig = TokenEstimationConfig(),
    private val contextTokenCounterFactory: (ModelId) -> ContextTokenCounter = {
        HeuristicContextTokenCounter(it, tokenEstimationConfig)
    },
    private val compactionStrategy: CompactionStrategy = TruncateOldestCompactionStrategy(),
    private val queryEmbedder: QueryEmbedder = NoOpQueryEmbedder(optedIn = true),
    private val memoryScopes: Set<MemoryScope> = MemoryScope.entries.toSet(),
    private val memoryTopK: Int = DEFAULT_MEMORY_TOP_K,
    private val memorySimilarityThreshold: Double = DEFAULT_MEMORY_SIMILARITY_THRESHOLD,
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
        val cacheEngine: CacheEngine = PassthroughCacheEngine(optedIn = requireStubOptIn("CacheEngine"))
        val costEngine: CostEngine = PassthroughCostEngine(optedIn = requireStubOptIn("CostEngine"))

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

    private fun requireStubOptIn(name: String): Boolean {
        check(optInToStubs) {
            "$name has no real implementation yet (P7). Construct ExecutionEngineComposer with " +
                "optInToStubs=true to acknowledge this and use the passthrough stub, or supply a real " +
                "implementation once available."
        }
        return true
    }

    private companion object {
        const val DEFAULT_MEMORY_TOP_K = 5
        const val DEFAULT_MEMORY_SIMILARITY_THRESHOLD = 0.75
    }
}
