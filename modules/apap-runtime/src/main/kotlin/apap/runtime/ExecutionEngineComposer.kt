package apap.runtime

import apap.cache.CacheEngine
import apap.cache.PassthroughCacheEngine
import apap.cache.ratelimit.RateLimiter
import apap.cache.ratelimit.RateLimiterConfig
import apap.cache.ratelimit.TokenBucketRateLimiter
import apap.context.ContextManager
import apap.context.PassthroughContextManager
import apap.cost.CostEngine
import apap.cost.PassthroughCostEngine
import apap.cost.quota.DefaultQuotaManager
import apap.cost.quota.QuotaManager
import apap.cost.quota.QuotaManagerConfig
import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.DomainEventSubscriber
import apap.domain.port.HealthLatencyStatsRepository
import apap.domain.port.IdGenerator
import apap.domain.port.ModelRepository
import apap.domain.port.PolicyRepository
import apap.domain.port.ProviderRepository
import apap.domain.port.QuotaSnapshotRepository
import apap.domain.port.TenantEntitlementRepository
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
import apap.prompt.PassthroughPromptEngine
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
 * P6/P7未着手のPassthrough実装（[PassthroughPromptEngine]/[PassthroughContextManager]/
 * [PassthroughCacheEngine]/[PassthroughCostEngine]）は、[optInToStubs]が`true`の場合のみ使う。
 * `false`（既定）の場合はここで構築時例外となり、呼び出し側が「未実装の機能に依存している」ことを
 * 起動時に必ず認識させる。
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

        val promptEngine: PromptEngine = PassthroughPromptEngine(optedIn = requireStubOptIn("PromptEngine"))
        val contextManager: ContextManager = PassthroughContextManager(optedIn = requireStubOptIn("ContextManager"))
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
        val tokenEstimator = TokenEstimator(providerRepository, adapterRegistry)

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
            "$name has no real implementation yet (P6/P7). Construct ExecutionEngineComposer with " +
                "optInToStubs=true to acknowledge this and use the passthrough stub, or supply a real " +
                "implementation once available."
        }
        return true
    }
}
