package apap.execution.attempt

import apap.adapter.spi.AdapterException
import apap.adapter.spi.AdapterResponse
import apap.cache.ratelimit.AcquireResult
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
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
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
    private val tracer: Tracer = OpenTelemetry.noop().getTracer(TRACER_NAME),
) {
    @Suppress("NestedBlockDepth", "ReturnCount", "LongParameterList")
    suspend fun execute(
        candidate: Candidate,
        initialPrompt: ProcessedPrompt,
        req: CanonicalRequest,
        ctx: ExecutionContext,
        correctionBudget: StructuredOutputCorrectionBudget,
        parentSpan: Span,
    ): AttemptResult {
        val cbKey = CbKey(candidate.providerId, candidate.modelId)
        var prompt = initialPrompt
        var attempt = 1
        while (true) {
            val remainingBudget = ctx.remaining(clock.now())
            if (remainingBudget.isZero) {
                return AttemptResult.Failure(timeoutExhaustedError(), attempt - 1)
            }

            when (
                val outcome =
                    attemptOnceTraced(candidate, cbKey, prompt, req, ctx, remainingBudget, attempt, parentSpan)
            ) {
                is AttemptOutcome.Success ->
                    return AttemptResult.Success(outcome.response, prompt, candidate, attempts = attempt)
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

    /**
     * 02_システム仕様.md 2.19 Span構成の`attempt[n]`（adapter呼出毎）。CLAUDE.md不変条件5に
     * 従いSpanは常に引数（`parentSpan`）で明示的に受け渡し、`Span.current()`/`makeCurrent()`は
     * 使わない（[apap.execution.ExecutionEngine]内`PhaseTimings`のKDoc参照）。
     */
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    private suspend fun attemptOnceTraced(
        candidate: Candidate,
        cbKey: CbKey,
        prompt: ProcessedPrompt,
        req: CanonicalRequest,
        ctx: ExecutionContext,
        remainingBudget: Duration,
        attempt: Int,
        parentSpan: Span,
    ): AttemptOutcome {
        val span =
            tracer
                .spanBuilder("attempt[$attempt]")
                .setParent(Context.root().with(parentSpan))
                .setAttribute(ATTR_PROVIDER, candidate.providerId.value)
                .setAttribute(ATTR_MODEL, candidate.modelId.value)
                .setAttribute(ATTR_ATTEMPT, attempt.toLong())
                .startSpan()
        return try {
            val outcome = attemptOnce(candidate, cbKey, prompt, req, ctx, remainingBudget)
            when (outcome) {
                is AttemptOutcome.Success -> span.setStatus(StatusCode.OK)
                is AttemptOutcome.Failed -> span.setStatus(StatusCode.ERROR, outcome.error.message)
            }
            outcome
        } catch (e: Throwable) {
            // Spanのライフサイクル管理のため、送出元を問わず全ての例外を記録してからそのまま再送出する。
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            span.end()
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

        // 2.8 step8b: 有界待機。予算残と設定可能な上限の小さいほうでキャップする
        // （無制限待機でタイムアウト予算を食い潰さない）。wait/rejectはAcquireResultの型で
        // 判定する（例外を使わない。2.19のメトリクスラベルaction(wait/reject)をログ文字列
        // から復元させないための構造化データとして戻り値に持たせている）。
        val waitBudget = minOf(remainingBudget, rateLimiterMaxWait)
        val tenantAcquire = rateLimiter.acquire(RateLimitScope.TenantScope(ctx.tenantId), ctx.traceId, waitBudget)
        if (tenantAcquire is AcquireResult.Rejected) {
            circuitBreaker.recordFailure(permit, cbRecordable = false, traceId = ctx.traceId)
            return AttemptOutcome.Failed(rateLimitedError(tenantAcquire), retryAfter = null)
        }
        val providerAcquire =
            rateLimiter.acquire(RateLimitScope.ProviderScope(candidate.providerId), ctx.traceId, waitBudget)
        if (providerAcquire is AcquireResult.Rejected) {
            circuitBreaker.recordFailure(permit, cbRecordable = false, traceId = ctx.traceId)
            return AttemptOutcome.Failed(rateLimitedError(providerAcquire), retryAfter = null)
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

    private fun rateLimitedError(rejected: AcquireResult.Rejected): NormalizedError =
        NormalizedError(
            code = ErrorCode.RATE_LIMIT_EXCEEDED,
            category = AdapterErrorCategory.RATE_LIMITED,
            message = "local rate limiter rejected this attempt",
            retryable = true,
            fallbackable = true,
            // APAP自身のRate Limiterによる拒否でありProviderの応答ではないため、CB記録対象としない。
            cbRecordable = false,
            // maxWaitMillisは「呼び出し側が待つ意思のあった上限」であり、次回の再試行猶予の
            // 妥当な目安として使う（waitedMillisではなく、待機してもなお不足だった上限側を使う）。
            retryAfterMs = rejected.maxWaitMillis,
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
        const val TRACER_NAME = "apap-execution"
        val ATTR_PROVIDER: AttributeKey<String> = AttributeKey.stringKey("provider")
        val ATTR_MODEL: AttributeKey<String> = AttributeKey.stringKey("model")
        val ATTR_ATTEMPT: AttributeKey<Long> = AttributeKey.longKey("attempt")
    }
}
