package apap.execution

import apap.cache.CacheEngine
import apap.cache.ratelimit.AcquireResult
import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.RateLimiter
import apap.cost.CostEngine
import apap.cost.quota.QuotaManager
import apap.cost.quota.Reservation
import apap.domain.event.DomainEvent
import apap.domain.event.EventMetadata
import apap.domain.event.RequestCompleted
import apap.domain.event.RequestFailed
import apap.domain.event.RequestReceived
import apap.domain.event.RequestStarted
import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.NormalizedError
import apap.domain.model.vo.TenantId
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.execution.attempt.AttemptResult
import apap.execution.estimation.TokenEstimator
import apap.execution.fallback.FallbackEngine
import apap.execution.mapping.ResponseMapper
import apap.prompt.PromptEngine
import apap.routing.RoutingEngine
import apap.routing.RoutingRequest
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** [ExecutionEngine.execute]がFallback Chain全体の失敗で終わった際に送出する。 */
class ExecutionFailedException(
    val error: NormalizedError,
) : Exception(error.message)

/** 03_基本設計.md 3.3.3 `ExecutionEngine`。 */
interface ExecutionEngine {
    suspend fun execute(request: CanonicalRequest): CanonicalResponse

    /**
     * 3.3.3は`executeStream(request, sink: ChunkSink): void`とコールバック形式で定義するが、
     * 本リポジトリはSPI/Streaming Engine双方でKotlin Flowを採用済み（`ProviderAdapter.asFlow()`等）
     * のため、それに揃えてFlowを返す形にする（要件充足に影響しない実装判断のためADR化せず
     * ここに根拠を残す）。
     */
    fun executeStream(request: CanonicalRequest): Flow<*>
}

/**
 * 02_システム仕様.md 2.8 Request Flow（非Streaming）の1〜12を順に実行するオーケストレーション本体。
 * 各フェーズの所要時間は[PhaseTimings]（Clock経由）で計測しログに残す（メトリクス出力はP8、
 * 計測点のみ本フェーズで用意する）。
 */
@Suppress("LongParameterList")
class DefaultExecutionEngine(
    private val promptEngine: PromptEngine,
    private val cacheEngine: CacheEngine,
    private val routingEngine: RoutingEngine,
    private val quotaManager: QuotaManager,
    private val costEngine: CostEngine,
    private val rateLimiter: RateLimiter,
    private val fallbackEngine: FallbackEngine,
    private val tokenEstimator: TokenEstimator,
    private val idempotencyGuard: IdempotencyGuard,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val eventPublisher: DomainEventPublisher,
    private val quotaPolicyProvider: (TenantId) -> QuotaPolicy? = { null },
) : ExecutionEngine {
    private val phases = PhaseTimings(clock)

    override suspend fun execute(request: CanonicalRequest): CanonicalResponse {
        publish(RequestReceived(meta(request), request.requestId, request.capabilityId, request.tenantId))

        val idempotencyKey = compositeIdempotencyKey(request)
        idempotencyGuard.claim(idempotencyKey)
        try {
            return executeClaimed(request)
        } finally {
            idempotencyGuard.release(idempotencyKey)
        }
    }

    override fun executeStream(request: CanonicalRequest): Flow<*> =
        throw UnsupportedOperationException(
            "Streaming orchestration wiring (Routing/Quota + StreamingEngine.normalize) is completed by " +
                "apap-runtime's composition root, not DefaultExecutionEngine itself; " +
                "see apap.execution.streaming.StreamingEngine.",
        )

    private suspend fun executeClaimed(request: CanonicalRequest): CanonicalResponse {
        val prompt = phases.time("prompt") { promptEngine.process(request) }

        val cached = phases.time("cache-lookup") { cacheEngine.lookup(request, prompt) }
        if (cached != null) {
            return handleCacheHit(request, cached)
        }

        val decision =
            phases.time("routing") {
                routingEngine.route(
                    RoutingRequest(
                        request.capabilityId,
                        request.tenantId,
                        request.modelAlias,
                        request.constraints,
                        request.preferences,
                        request.conversationId,
                    ),
                    request.requestId,
                )
            }
        val primaryCandidate = decision.chain.candidates.first()
        val estimatedTokens =
            phases.time("token-estimate") {
                tokenEstimator.estimate(primaryCandidate, primaryCandidate.modelId, prompt)
            }
        val estimatedCost = costEngine.estimate(primaryCandidate.providerId, primaryCandidate.modelId, prompt)

        val ctx =
            ExecutionContext.start(
                requestId = request.requestId,
                tenantId = request.tenantId,
                traceId = request.traceId,
                now = clock.now(),
                timeoutBudget = request.timeoutBudget,
                idempotencyKey = request.idempotencyKey,
            )
        val reservation =
            quotaManager.checkAndReserve(
                tenantId = request.tenantId,
                providerId = primaryCandidate.providerId,
                modelId = primaryCandidate.modelId,
                estimatedTokens = estimatedTokens,
                estimatedCost = estimatedCost,
                policy = quotaPolicyProvider(request.tenantId),
                traceId = request.traceId,
                now = clock.now(),
            )

        publish(RequestStarted(meta(request), request.requestId, request.capabilityId, request.tenantId))
        val startedAt = clock.now()

        val result =
            phases.time("execution") { fallbackEngine.executeWithChain(decision.chain, prompt, request, ctx) }

        return when (result) {
            is AttemptResult.Success -> onSuccess(request, prompt, result, reservation, startedAt)
            is AttemptResult.Failure -> onFailure(request, result, reservation)
        }
    }

    private fun onSuccess(
        request: CanonicalRequest,
        prompt: ProcessedPrompt,
        result: AttemptResult.Success,
        reservation: Reservation,
        startedAt: Instant,
    ): CanonicalResponse {
        val cost = costEngine.calculate(result.response.usage, result.candidate.modelId)
        val response =
            ResponseMapper.normalize(
                response = result.response,
                requestId = request.requestId,
                cost = cost,
                resolvedProvider = result.candidate.providerId,
                resolvedModel = result.candidate.modelId,
                idGenerator = idGenerator,
            )
        quotaManager.commit(reservation, result.response.usage, cost.amount)
        costEngine.record(response)
        cacheEngine.store(request, prompt, response)
        val durationMs = Duration.between(startedAt, clock.now()).toMillis()
        publish(
            RequestCompleted(
                meta(request),
                request.requestId,
                result.candidate.providerId.value,
                result.candidate.modelId.value,
                response.usage,
                response.cost,
                durationMs,
                response.finishReason,
            ),
        )
        return response
    }

    private fun onFailure(
        request: CanonicalRequest,
        result: AttemptResult.Failure,
        reservation: Reservation,
    ): CanonicalResponse {
        quotaManager.release(reservation)
        publish(
            RequestFailed(
                meta(request),
                request.requestId,
                result.error.code,
                result.attempts,
                result.fallbacks,
            ),
        )
        throw ExecutionFailedException(result.error)
    }

    private suspend fun handleCacheHit(
        request: CanonicalRequest,
        cached: CanonicalResponse,
    ): CanonicalResponse {
        quotaManager.recordCacheShortCircuit(request.tenantId, quotaPolicyProvider(request.tenantId))
        // NFR-PRF-004 (Cache Hit時応答 p99<=20ms): 有界待機を求めず即時可否判定のみとする。
        val acquireResult =
            rateLimiter.acquire(RateLimitScope.TenantScope(request.tenantId), request.traceId, Duration.ZERO)
        if (acquireResult is AcquireResult.Rejected) {
            throw ExecutionFailedException(cacheHitRateLimitedError())
        }
        return cached.copy(cached = true)
    }

    private fun cacheHitRateLimitedError(): NormalizedError =
        NormalizedError(
            code = ErrorCode.RATE_LIMIT_EXCEEDED,
            category = AdapterErrorCategory.RATE_LIMITED,
            message = "local rate limiter rejected this cache-hit short-circuit",
            retryable = true,
            fallbackable = false,
            cbRecordable = false,
        )

    private fun compositeIdempotencyKey(request: CanonicalRequest): String? =
        request.idempotencyKey?.let { "${request.tenantId.value}:$it" }

    private fun publish(event: DomainEvent) = eventPublisher.publish(event)

    private fun meta(request: CanonicalRequest): EventMetadata =
        EventMetadata(
            eventId = idGenerator.newId(),
            occurredAt = clock.now(),
            traceId = request.traceId,
            tenantId = request.tenantId,
            aggregateId = request.requestId.value,
            version = 0,
        )
}

/**
 * 02_システム仕様.md 2.8の各フェーズ所要時間の計測点（メトリクス出力自体はP8）。
 * `Clock`経由で計測するためテストでも決定的。
 */
private class PhaseTimings(
    private val clock: Clock,
) {
    suspend fun <T> time(
        phase: String,
        block: suspend () -> T,
    ): T {
        val start = clock.now()
        val result = block()
        val durationMs = Duration.between(start, clock.now()).toMillis()
        logger.debug("phase={} durationMs={}", phase, durationMs)
        return result
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PhaseTimings::class.java)
    }
}
