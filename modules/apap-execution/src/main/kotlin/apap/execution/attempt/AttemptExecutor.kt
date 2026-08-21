package apap.execution.attempt

import apap.adapter.spi.AdapterException
import apap.adapter.spi.AdapterResponse
import apap.cache.ratelimit.RateLimitExceededException
import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.RateLimiter
import apap.domain.event.EventMetadata
import apap.domain.event.RetryExecuted
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.NormalizedError
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.ModelRepository
import apap.domain.port.ProviderRepository
import apap.domain.service.routing.Candidate
import apap.execution.circuitbreaker.CircuitBreaker
import apap.execution.circuitbreaker.CircuitOpenException
import apap.execution.mapping.RequestMapper
import apap.execution.mapping.ResponseMapper
import apap.execution.retry.ExponentialBackoffJitterStrategy
import apap.execution.retry.RetryConfig
import apap.execution.retry.RetryStrategy
import apap.execution.structuredoutput.StructuredOutputCorrectionBudget
import apap.provider.AdapterRegistry
import kotlinx.coroutines.delay
import java.time.Duration

/**
 * 03_基本設計.md 3.3.5 `AttemptExecutor`: 1候補への試行（Retry内包）。
 * CB取得 → RateLimiter取得（テナント→Provider） → RequestMapper → adapter.execute →
 * 失敗時Retry判定（表2.11） → バックオフ → 予算残チェック。
 *
 * Structured Output是正（ADR-0011）: MODEL_ERROR分類はmaxAttemptsの内数として同じループで扱い、
 * 是正できた場合のみ[RequestMapper.withCorrectionNote]でプロンプトへ違反内容を追記して続行する。
 * 是正枠（[StructuredOutputCorrectionBudget]、requestId単位でグローバル）が尽きた場合はMODEL_ERROR
 * でもそれ以上リトライしない（是正なしの単純再送は無意味なため）。
 */
@Suppress("LongParameterList")
class AttemptExecutor(
    private val providerRepository: ProviderRepository,
    private val modelRepository: ModelRepository,
    private val adapterRegistry: AdapterRegistry,
    private val circuitBreaker: CircuitBreaker,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
    private val eventPublisher: DomainEventPublisher,
    private val idGenerator: IdGenerator,
    private val retryConfig: RetryConfig = RetryConfig(),
    private val retryStrategy: RetryStrategy = ExponentialBackoffJitterStrategy(retryConfig),
    private val rateLimiterMaxWait: Duration = Duration.ofSeconds(RATE_LIMITER_MAX_WAIT_SECONDS),
) {
    @Suppress("NestedBlockDepth", "ReturnCount")
    suspend fun execute(
        candidate: Candidate,
        initialPrompt: ProcessedPrompt,
        req: CanonicalRequest,
        ctx: ExecutionContext,
        correctionBudget: StructuredOutputCorrectionBudget,
    ): AttemptResult {
        val cbKey = CbKey(candidate.providerId, candidate.modelId)
        var prompt = initialPrompt
        var attempt = 1
        while (true) {
            val remainingBudget = ctx.remaining(clock.now())
            if (remainingBudget.isZero) {
                return AttemptResult.Failure(timeoutExhaustedError(), attempt - 1)
            }

            when (val outcome = attemptOnce(candidate, cbKey, prompt, req, ctx, remainingBudget)) {
                is AttemptOutcome.Success -> return AttemptResult.Success(outcome.response, prompt, candidate)
                is AttemptOutcome.Failed -> {
                    val error = outcome.error
                    if (error.category == AdapterErrorCategory.MODEL_ERROR) {
                        if (!correctionBudget.tryConsume()) {
                            return AttemptResult.Failure(error, attempt)
                        }
                        prompt = RequestMapper.withCorrectionNote(prompt, error.message)
                    }
                    val delay = retryStrategy.nextDelay(attempt, error, outcome.retryAfter)
                    val budgetAfterFailure = ctx.remaining(clock.now())
                    if (delay == null || budgetAfterFailure < delay || attempt >= retryConfig.maxAttempts) {
                        return AttemptResult.Failure(error, attempt)
                    }
                    publishRetryExecuted(req, ctx, candidate, attempt, error)
                    delay(delay.toMillis())
                    attempt += 1
                }
            }
        }
    }

    private sealed interface AttemptOutcome {
        data class Success(
            val response: AdapterResponse,
        ) : AttemptOutcome

        data class Failed(
            val error: NormalizedError,
            val retryAfter: Duration?,
        ) : AttemptOutcome
    }

    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun attemptOnce(
        candidate: Candidate,
        cbKey: CbKey,
        prompt: ProcessedPrompt,
        req: CanonicalRequest,
        ctx: ExecutionContext,
        remainingBudget: Duration,
    ): AttemptOutcome {
        val permit =
            try {
                circuitBreaker.tryAcquire(cbKey, ctx.traceId)
            } catch (_: CircuitOpenException) {
                return AttemptOutcome.Failed(providerUnavailableError(), retryAfter = null)
            }

        try {
            // 2.8 step8b: 有界待機。予算残と設定可能な上限の小さいほうでキャップする
            // （無制限待機でタイムアウト予算を食い潰さない）。
            val waitBudget = minOf(remainingBudget, rateLimiterMaxWait)
            rateLimiter.acquire(RateLimitScope.TenantScope(ctx.tenantId), ctx.traceId, waitBudget)
            rateLimiter.acquire(RateLimitScope.ProviderScope(candidate.providerId), ctx.traceId, waitBudget)
        } catch (_: RateLimitExceededException) {
            circuitBreaker.recordFailure(permit, cbRecordable = false, traceId = ctx.traceId)
            return AttemptOutcome.Failed(rateLimitedError(), retryAfter = null)
        }

        val provider = providerRepository.findById(candidate.providerId)
        val model = modelRepository.findById(candidate.modelId)
        if (provider == null || model == null) {
            circuitBreaker.recordFailure(permit, cbRecordable = false, traceId = ctx.traceId)
            return AttemptOutcome.Failed(candidateNotFoundError(), retryAfter = null)
        }

        val resolved = adapterRegistry.resolve(provider.adapterPluginId)
        val adapter = resolved.adapter
        val authContext = adapter.authenticate()
        val adapterRequest = RequestMapper.map(prompt, req, model.modelName, authContext, remainingBudget)

        return try {
            val response = adapter.execute(adapterRequest)
            circuitBreaker.recordSuccess(permit, ctx.traceId)
            AttemptOutcome.Success(response)
        } catch (e: AdapterException) {
            val error = ResponseMapper.normalizeError(e)
            circuitBreaker.recordFailure(permit, error.cbRecordable, ctx.traceId)
            AttemptOutcome.Failed(error, e.retryAfter)
        }
    }

    private fun publishRetryExecuted(
        req: CanonicalRequest,
        ctx: ExecutionContext,
        candidate: Candidate,
        attempt: Int,
        error: NormalizedError,
    ) {
        eventPublisher.publish(
            RetryExecuted(
                meta =
                    EventMetadata(
                        eventId = idGenerator.newId(),
                        occurredAt = clock.now(),
                        traceId = ctx.traceId,
                        tenantId = ctx.tenantId,
                        aggregateId = req.requestId.value,
                        version = 0,
                    ),
                requestId = req.requestId,
                candidate = candidate.key,
                attempt = attempt,
                reason = error.code.name,
            ),
        )
    }

    private fun timeoutExhaustedError(): NormalizedError =
        NormalizedError(
            code = ErrorCode.TIMEOUT,
            category = AdapterErrorCategory.PROVIDER_UNAVAILABLE,
            message = "timeout budget exhausted before attempt could start",
            retryable = false,
            fallbackable = true,
            cbRecordable = false,
        )

    private fun providerUnavailableError(): NormalizedError =
        NormalizedError(
            code = ErrorCode.NO_CANDIDATE_AVAILABLE,
            category = AdapterErrorCategory.PROVIDER_UNAVAILABLE,
            message = "circuit breaker is open for this candidate",
            retryable = false,
            fallbackable = true,
            cbRecordable = false,
        )

    private fun rateLimitedError(): NormalizedError =
        NormalizedError(
            code = ErrorCode.RATE_LIMIT_EXCEEDED,
            category = AdapterErrorCategory.RATE_LIMITED,
            message = "local rate limiter rejected this attempt",
            retryable = true,
            fallbackable = true,
            // APAP自身のRate Limiterによる拒否でありProviderの応答ではないため、CB記録対象としない。
            cbRecordable = false,
        )

    private fun candidateNotFoundError(): NormalizedError =
        NormalizedError(
            code = ErrorCode.NO_CANDIDATE_AVAILABLE,
            category = AdapterErrorCategory.PROVIDER_UNAVAILABLE,
            message = "provider or model no longer resolvable for this candidate",
            retryable = false,
            fallbackable = true,
            cbRecordable = false,
        )

    private companion object {
        const val RATE_LIMITER_MAX_WAIT_SECONDS = 5L
    }
}
