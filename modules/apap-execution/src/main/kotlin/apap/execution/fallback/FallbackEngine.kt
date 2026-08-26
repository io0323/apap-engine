package apap.execution.fallback

import apap.context.ContextManager
import apap.domain.event.EventMetadata
import apap.domain.event.FallbackExecuted
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CbState
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.NormalizedError
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.service.routing.Candidate
import apap.domain.service.routing.FallbackChain
import apap.execution.attempt.AttemptExecutor
import apap.execution.attempt.AttemptResult
import apap.execution.circuitbreaker.CircuitBreaker
import apap.execution.structuredoutput.StructuredOutputConfig
import apap.execution.structuredoutput.StructuredOutputCorrectionBudget
import java.time.Duration

/**
 * 03_基本設計.md 3.3.5 `FallbackEngine.executeWithChain` / 02_システム仕様.md 2.12 /
 * 10_アクティビティ図.md 10.2。
 *
 * 移行条件: 直前の失敗が`fallbackable`、かつ予算残 > 次候補の最低所要見込み。設計書2.12通り
 * `Candidate.p50LatencyMs`（次候補のp50レイテンシ）を使う。ADR-0018はp90を使う判断をしていたが、
 * その根拠が実装コストであり設計論拠ではなかったためADR-0020でSupersededとし、本来の設計書通り
 * p50に復帰した（NFR-AVL-002へ影響しうる判断のためADR化済み——実装上の些末な選択として扱わないこと）。
 *
 * Structured Output是正枠（[StructuredOutputCorrectionBudget]）はrequestId単位で1個だけ生成し、
 * Chain中の全候補（全[AttemptExecutor.execute]呼出）へ同一インスタンスを渡す
 * （ADR-0011: Fallbackで別候補へ移ってもリセットしない）。
 */
@Suppress("LongParameterList")
class FallbackEngine(
    private val attemptExecutor: AttemptExecutor,
    private val circuitBreaker: CircuitBreaker,
    private val contextManager: ContextManager,
    private val clock: Clock,
    private val eventPublisher: DomainEventPublisher,
    private val idGenerator: IdGenerator,
    private val structuredOutputConfig: StructuredOutputConfig = StructuredOutputConfig(),
) {
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun executeWithChain(
        chain: FallbackChain,
        initialPrompt: ProcessedPrompt,
        req: CanonicalRequest,
        ctx: ExecutionContext,
    ): AttemptResult {
        val correctionBudget = StructuredOutputCorrectionBudget(structuredOutputConfig)
        var lastFailure: AttemptResult.Failure? = null
        var totalAttempts = 0
        var fallbackCount = 0

        for (index in chain.candidates.indices) {
            val candidate = chain.candidates[index]
            val cbKey = CbKey(candidate.providerId, candidate.modelId)
            if (circuitBreaker.state(cbKey) == CbState.OPEN) continue

            val prompt = contextManager.refit(initialPrompt, candidate.modelId)
            val result = attemptExecutor.execute(candidate, prompt, req, ctx, correctionBudget)
            if (result is AttemptResult.Success) {
                return result.copy(attempts = totalAttempts + result.attempts, fallbacks = fallbackCount)
            }

            val failure = result as AttemptResult.Failure
            totalAttempts += failure.attempts
            lastFailure = failure

            if (!failure.error.fallbackable) break
            val nextCandidate = chain.candidates.getOrNull(index + 1) ?: break
            val remaining = ctx.remaining(clock.now())
            val neededForNext = Duration.ofMillis(nextCandidate.p50LatencyMs.toLong())
            if (remaining <= neededForNext) break

            fallbackCount += 1
            publishFallbackExecuted(req, ctx, candidate, nextCandidate, failure.error)
        }

        val finalFailure = lastFailure ?: AttemptResult.Failure(noCandidateAvailableError(), attempts = 0)
        return finalFailure.copy(attempts = totalAttempts, fallbacks = fallbackCount)
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

    private fun noCandidateAvailableError(): NormalizedError =
        NormalizedError(
            code = ErrorCode.NO_CANDIDATE_AVAILABLE,
            category = AdapterErrorCategory.PROVIDER_UNAVAILABLE,
            message = "all candidates in the fallback chain have an open circuit breaker",
            retryable = false,
            fallbackable = false,
            cbRecordable = false,
        )
}
