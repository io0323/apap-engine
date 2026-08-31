package apap.execution.streaming

import apap.adapter.spi.AdapterException
import apap.cache.ratelimit.AcquireResult
import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.RateLimiter
import apap.cost.CostEngine
import apap.cost.quota.QuotaManager
import apap.cost.quota.Reservation
import apap.domain.event.EventMetadata
import apap.domain.event.FallbackExecuted
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.CbState
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.execution.StreamChunk
import apap.domain.model.execution.StreamChunkType
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.NormalizedError
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.ModelRepository
import apap.domain.port.ProviderRepository
import apap.domain.service.routing.Candidate
import apap.domain.service.routing.FallbackChain
import apap.execution.circuitbreaker.CircuitBreaker
import apap.execution.circuitbreaker.CircuitOpenException
import apap.execution.circuitbreaker.Permit
import apap.execution.mapping.RequestMapper
import apap.execution.mapping.ResponseMapper
import apap.provider.AdapterRegistry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import java.time.Duration

/**
 * 02_システム仕様.md 2.10 Streaming Flowのオーケストレーション本体（着手前レビューで解消:
 * `StreamingEngine`実装済みだが呼び出し元が無かった）。Prompt/Cache-bypass/Routing/Quota予約は
 * 呼び出し側（`DefaultExecutionEngine.executeStream`）が担い、本クラスはFallback Chain上の
 * 各候補への1回の試行（CB/RateLimiter取得 → Adapter.executeStream → StreamingEngine.normalize）と、
 * 初回チャンク送出前の失敗のみをFallback対象として次候補へ進める制御、message_end/中断時の
 * Quota確定・Cost記録・Turn記録を担う。
 *
 * 2.10「最初のチャンク送出**前**の失敗はFallback対象」は無条件のルール（`NormalizedError.fallbackable`
 * を問わない）であり、非Streamingの[apap.execution.fallback.FallbackEngine]とは異なる
 * （要件充足に影響しない実装判断のためADR化せず根拠をここに残す）。
 */
@Suppress("LongParameterList", "TooManyFunctions")
class StreamingRequestExecutor(
    private val providerRepository: ProviderRepository,
    private val modelRepository: ModelRepository,
    private val adapterRegistry: AdapterRegistry,
    private val circuitBreaker: CircuitBreaker,
    private val rateLimiter: RateLimiter,
    private val streamingEngine: StreamingEngine,
    private val streamingTurnRecorder: StreamingTurnRecorder,
    private val quotaManager: QuotaManager,
    private val costEngine: CostEngine,
    private val clock: Clock,
    private val eventPublisher: DomainEventPublisher,
    private val idGenerator: IdGenerator,
    private val tracer: Tracer,
    private val rateLimiterMaxWait: Duration = Duration.ofSeconds(RATE_LIMITER_MAX_WAIT_SECONDS),
) {
    @Suppress("LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")
    fun execute(
        chain: FallbackChain,
        prompt: ProcessedPrompt,
        req: CanonicalRequest,
        ctx: ExecutionContext,
        reservation: Reservation,
        parentSpan: Span,
    ): Flow<StreamChunk> =
        flow {
            var lastError: NormalizedError? = null
            var succeeded = false
            for (index in chain.candidates.indices) {
                val candidate = chain.candidates[index]
                if (circuitBreaker.state(CbKey(candidate.providerId, candidate.modelId)) == CbState.OPEN) continue
                // 02_システム仕様.md 2.19 Span構成の`attempt[n]`。非Streaming版（AttemptExecutor）と
                // 異なり本メソッドはFlowを返す遅延評価のため、Span終了はここ（呼び出し元、emitAll完了後）
                // で行う必要がある——attemptCandidate内で開閉するとチャンク送出中の実時間を計測できない。
                val attemptSpan =
                    tracer
                        .spanBuilder("attempt[${index + 1}]")
                        .setParent(Context.root().with(parentSpan))
                        .setAttribute(ATTR_PROVIDER, candidate.providerId.value)
                        .setAttribute(ATTR_MODEL, candidate.modelId.value)
                        .setAttribute(ATTR_ATTEMPT, (index + 1).toLong())
                        .startSpan()
                try {
                    emitAll(attemptCandidate(candidate, prompt, req, ctx, reservation))
                    attemptSpan.setStatus(StatusCode.OK)
                    succeeded = true
                    break
                } catch (e: StreamAbortedBeforeFirstChunkException) {
                    attemptSpan.recordException(e)
                    attemptSpan.setStatus(StatusCode.ERROR, e.normalizedError.message)
                    lastError = e.normalizedError
                    val nextCandidate = chain.candidates.getOrNull(index + 1) ?: break
                    val remaining = ctx.remaining(clock.now())
                    val needed = Duration.ofMillis(nextCandidate.p50LatencyMs.toLong())
                    if (remaining <= needed) break
                    publishFallbackExecuted(req, ctx, candidate, nextCandidate, e.normalizedError)
                } catch (e: Throwable) {
                    attemptSpan.recordException(e)
                    attemptSpan.setStatus(StatusCode.ERROR)
                    throw e
                } finally {
                    attemptSpan.end()
                }
            }
            if (!succeeded) {
                quotaManager.release(reservation)
                val error = lastError ?: noCandidateAvailableError()
                emit(StreamChunk(type = StreamChunkType.ERROR, index = 0, error = error))
            }
        }

    private suspend fun attemptCandidate(
        candidate: Candidate,
        prompt: ProcessedPrompt,
        req: CanonicalRequest,
        ctx: ExecutionContext,
        reservation: Reservation,
    ): Flow<StreamChunk> {
        val cbKey = CbKey(candidate.providerId, candidate.modelId)
        val permit =
            try {
                circuitBreaker.tryAcquire(cbKey, ctx.traceId)
            } catch (e: CircuitOpenException) {
                throw StreamAbortedBeforeFirstChunkException(providerUnavailableError(), e)
            }
        acquireRateLimitOrAbort(candidate, ctx, permit)

        val provider =
            providerRepository.findById(candidate.providerId)
                ?: failCandidateNotFound(permit, ctx)
        val model =
            modelRepository.findById(candidate.modelId)
                ?: failCandidateNotFound(permit, ctx)
        val resolved = adapterRegistry.resolve(provider.adapterPluginId)
        val authContext = resolved.adapter.authenticate()
        val remaining = ctx.remaining(clock.now())
        val adapterRequest = RequestMapper.map(prompt, req, model.modelName, authContext, remaining)

        val adapterStream =
            try {
                resolved.adapter.executeStream(adapterRequest)
            } catch (e: AdapterException) {
                val error = ResponseMapper.normalizeError(e)
                circuitBreaker.recordFailure(permit, error.cbRecordable, ctx.traceId)
                throw StreamAbortedBeforeFirstChunkException(error, e)
            }

        val normalized = streamingEngine.normalize(adapterStream, ctx)
        val withTurnRecording =
            req.conversationId?.let {
                streamingTurnRecorder.record(it, req.tenantId, candidate.modelId, normalized)
            } ?: normalized
        return withOutcomeTracking(withTurnRecording, permit, ctx, candidate, req, reservation)
    }

    private suspend fun acquireRateLimitOrAbort(
        candidate: Candidate,
        ctx: ExecutionContext,
        permit: Permit,
    ) {
        val tenantScope = RateLimitScope.TenantScope(ctx.tenantId)
        val tenantAcquire = rateLimiter.acquire(tenantScope, ctx.traceId, rateLimiterMaxWait)
        if (tenantAcquire is AcquireResult.Rejected) {
            circuitBreaker.recordFailure(permit, cbRecordable = false, traceId = ctx.traceId)
            throw StreamAbortedBeforeFirstChunkException(rateLimitedError(tenantAcquire))
        }
        val providerAcquire =
            rateLimiter.acquire(RateLimitScope.ProviderScope(candidate.providerId), ctx.traceId, rateLimiterMaxWait)
        if (providerAcquire is AcquireResult.Rejected) {
            circuitBreaker.recordFailure(permit, cbRecordable = false, traceId = ctx.traceId)
            throw StreamAbortedBeforeFirstChunkException(rateLimitedError(providerAcquire))
        }
    }

    private fun failCandidateNotFound(
        permit: Permit,
        ctx: ExecutionContext,
    ): Nothing {
        circuitBreaker.recordFailure(permit, cbRecordable = false, traceId = ctx.traceId)
        throw StreamAbortedBeforeFirstChunkException(candidateNotFoundError())
    }

    /**
     * 初回チャンク以降のcompletion（正常終了/ERRORチャンク終端/クライアント切断によるキャンセル）を
     * 検知し、Circuit Breaker記録・Quota確定・Cost記録を行う。クライアント切断（[CancellationException]）
     * はProvider側の障害ではないためCB記録の対象外とする（一般的なResilience実装の慣行）。
     */
    private fun withOutcomeTracking(
        chunks: Flow<StreamChunk>,
        permit: Permit,
        ctx: ExecutionContext,
        candidate: Candidate,
        req: CanonicalRequest,
        reservation: Reservation,
    ): Flow<StreamChunk> {
        var observedUsage: Usage? = null
        var lastType: StreamChunkType? = null
        return chunks
            .onEach { chunk ->
                lastType = chunk.type
                if (chunk.type == StreamChunkType.USAGE) chunk.usage?.let { observedUsage = it }
            }.onCompletion { cause ->
                finalizeOutcome(cause, lastType, observedUsage, permit, ctx, candidate, req, reservation)
            }
    }

    @Suppress("LongParameterList")
    private fun finalizeOutcome(
        cause: Throwable?,
        lastType: StreamChunkType?,
        observedUsage: Usage?,
        permit: Permit,
        ctx: ExecutionContext,
        candidate: Candidate,
        req: CanonicalRequest,
        reservation: Reservation,
    ) {
        val usage = observedUsage ?: Usage.of(TokenCount.ZERO, TokenCount.ZERO, estimated = true)
        if (cause is CancellationException) {
            settleQuotaAndCost(usage, candidate, req, reservation, FinishReason.CANCELLED)
            return
        }
        when (lastType) {
            StreamChunkType.MESSAGE_END -> {
                circuitBreaker.recordSuccess(permit, ctx.traceId)
                settleQuotaAndCost(usage, candidate, req, reservation, FinishReason.COMPLETED)
            }
            else -> {
                circuitBreaker.recordFailure(permit, cbRecordable = true, ctx.traceId)
                settleQuotaAndCost(usage, candidate, req, reservation, FinishReason.ERROR)
            }
        }
    }

    /**
     * 02_システム仕様.md 2.10「切断時: Usageは受信済分で確定」/ タスク要求「中断時は受信済み分でQuotaを
     * 部分commit」: 中断・エラー終端でも常にcommit（release ではない）とする。CanonicalResponseは
     * Streamingでは1つに定まらないため、CostEngine.recordが要求するCanonicalResponseは最小限の情報
     * （usage/cost/resolvedProvider/resolvedModel等）のみを保持する合成値とする（要件充足に影響しない
     * 実装判断のためADR化せず根拠をここに残す）。
     */
    private fun settleQuotaAndCost(
        usage: Usage,
        candidate: Candidate,
        req: CanonicalRequest,
        reservation: Reservation,
        finishReason: FinishReason,
    ) {
        val cost = costEngine.calculate(usage, candidate.modelId)
        quotaManager.commit(reservation, usage, cost.amount)
        val response =
            CanonicalResponse(
                responseId = idGenerator.newId(),
                requestId = req.requestId,
                output = emptyList(),
                finishReason = finishReason,
                usage = usage,
                cost = cost,
                resolvedProvider = candidate.providerId,
                resolvedModel = candidate.modelId,
            )
        costEngine.record(req, response, durationMs = 0)
    }

    private fun publishFallbackExecuted(
        req: CanonicalRequest,
        ctx: ExecutionContext,
        from: Candidate,
        to: Candidate,
        error: NormalizedError,
    ) {
        eventPublisher.publish(
            FallbackExecuted(
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
                fromCandidate = from.key,
                toCandidate = to.key,
                reason = error.code.name,
            ),
        )
    }

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
            message = "local rate limiter rejected this streaming attempt",
            retryable = true,
            fallbackable = true,
            cbRecordable = false,
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

    private fun noCandidateAvailableError(): NormalizedError =
        NormalizedError(
            code = ErrorCode.NO_CANDIDATE_AVAILABLE,
            category = AdapterErrorCategory.PROVIDER_UNAVAILABLE,
            message = "all candidates in the fallback chain failed before the first chunk was sent",
            retryable = false,
            fallbackable = false,
            cbRecordable = false,
        )

    private companion object {
        const val RATE_LIMITER_MAX_WAIT_SECONDS = 5L
        val ATTR_PROVIDER: AttributeKey<String> = AttributeKey.stringKey("provider")
        val ATTR_MODEL: AttributeKey<String> = AttributeKey.stringKey("model")
        val ATTR_ATTEMPT: AttributeKey<Long> = AttributeKey.longKey("attempt")
    }
}
