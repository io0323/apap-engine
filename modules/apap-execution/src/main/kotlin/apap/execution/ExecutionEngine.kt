package apap.execution

import apap.cache.CacheEngine
import apap.cache.ratelimit.AcquireResult
import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.RateLimiter
import apap.context.ContextLengthExceededException
import apap.context.ContextManager
import apap.context.ConversationManager
import apap.cost.CostEngine
import apap.cost.quota.QuotaManager
import apap.cost.quota.Reservation
import apap.domain.event.DomainEvent
import apap.domain.event.EventMetadata
import apap.domain.event.RequestCompleted
import apap.domain.event.RequestFailed
import apap.domain.event.RequestReceived
import apap.domain.event.RequestStarted
import apap.domain.model.conversation.TurnRole
import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.NormalizedError
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.port.Clock
import apap.domain.port.ConversationRepository
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
@Suppress("LongParameterList", "TooManyFunctions")
class DefaultExecutionEngine(
    private val promptEngine: PromptEngine,
    private val contextManager: ContextManager,
    private val conversationRepository: ConversationRepository,
    private val conversationManager: ConversationManager,
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

        recordUserTurn(request)

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
        val contextualPrompt =
            phases.time("context") { buildContextualPrompt(request, prompt, primaryCandidate.modelId) }
        val estimatedTokens =
            phases.time("token-estimate") {
                tokenEstimator.estimate(primaryCandidate, primaryCandidate.modelId, contextualPrompt)
            }
        val estimatedCost =
            costEngine.estimate(primaryCandidate.providerId, primaryCandidate.modelId, contextualPrompt)

        val ctx = startExecutionContext(request)
        val reservation =
            reserveQuota(request, primaryCandidate.providerId, primaryCandidate.modelId, estimatedTokens, estimatedCost)

        publish(RequestStarted(meta(request), request.requestId, request.capabilityId, request.tenantId))
        val startedAt = clock.now()

        val result =
            phases.time("execution") {
                fallbackEngine.executeWithChain(decision.chain, contextualPrompt, request, ctx)
            }

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
        val durationMs = Duration.between(startedAt, clock.now()).toMillis()
        quotaManager.commit(reservation, result.response.usage, cost.amount)
        costEngine.record(request, response, durationMs)
        cacheEngine.store(request, prompt, response)
        recordAssistantTurn(request, response)
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
            throw ExecutionFailedException(cacheHitRateLimitedError(acquireResult))
        }
        return cached.copy(cached = true)
    }

    private fun startExecutionContext(request: CanonicalRequest): ExecutionContext =
        ExecutionContext.start(
            requestId = request.requestId,
            tenantId = request.tenantId,
            traceId = request.traceId,
            now = clock.now(),
            timeoutBudget = request.timeoutBudget,
            idempotencyKey = request.idempotencyKey,
        )

    @Suppress("LongParameterList")
    private fun reserveQuota(
        request: CanonicalRequest,
        providerId: ProviderId,
        modelId: ModelId,
        estimatedTokens: TokenCount,
        estimatedCost: Money,
    ): Reservation =
        quotaManager.checkAndReserve(
            tenantId = request.tenantId,
            providerId = providerId,
            modelId = modelId,
            estimatedTokens = estimatedTokens,
            estimatedCost = estimatedCost,
            policy = quotaPolicyProvider(request.tenantId),
            traceId = request.traceId,
            now = clock.now(),
        )

    /**
     * 02_システム仕様.md 2.8 step2/2.16: Conversation解決とContext組立。System Prompt→Memory注入→
     * 履歴→今回入力（[promptEngine]が既にValidation/Optimization/Renderingを終えたもの）の順で
     * 合成する（KDoc根拠、要件充足に影響しないためADR化せず）。
     *
     * Fallback候補ごとの再構成はしない（primaryCandidateのmodelId1つに対してのみ構築し、それでも
     * 超過する場合は[ContextLengthExceededException]をFallback不可（`fallbackable=false`）として
     * 即座に失敗させる）。
     */
    private suspend fun buildContextualPrompt(
        request: CanonicalRequest,
        prompt: ProcessedPrompt,
        modelId: ModelId,
    ): ProcessedPrompt {
        val conversation = request.conversationId?.let { conversationRepository.findById(it) }
        val assembled =
            try {
                contextManager.build(request, systemPrompt = emptyList(), conversation, modelId)
            } catch (e: ContextLengthExceededException) {
                throw ExecutionFailedException(contextLengthExceededError(e))
            }
        val historyContent = assembled.turns.flatMap { it.contentParts }
        val mergedInput = assembled.systemPrompt + assembled.memoryInjection + historyContent + prompt.input
        return ProcessedPrompt(input = mergedInput, estimatedTokens = assembled.estimatedTokens)
    }

    /**
     * 02_システム仕様.md 2.8 step11: user turnはリクエスト受理時（Provider呼出前）に書く
     * （Provider呼出が失敗しても入力を失わないため）。Cache Engineの冪等キー短絡
     * （[handleCacheHit]、`executeClaimed`でこの呼出より前に早期returnする）より後に置くことで、
     * 同一冪等キーでの再送がここへ到達せず二重書込にならない。Retry/Fallbackは`executeClaimed`
     * 1回の呼出につき高々1回しか通過しない経路（[fallbackEngine]呼出より前）に置くことで、
     * 内部リトライ・フォールバック候補の複数試行でも重複しない
     * （要件充足に影響しない実装判断のためADR化せず根拠をここに残す）。
     */
    private fun recordUserTurn(request: CanonicalRequest) {
        val conversationId = request.conversationId ?: return
        persistTurn(conversationId, TurnRole.USER, request.input, modelUsed = null, usage = null)
    }

    /**
     * 02_システム仕様.md 2.8 step11: assistant turnは応答確定後に書く。[onSuccess]内で
     * `executeClaimed`1回の呼出につき高々1回だけ呼ばれる（Fallback Chain全体が単一の
     * [AttemptResult.Success]へ収束した後のため、内部リトライ・フォールバックの試行回数に
     * 依存しない）。Tool Call専用応答（`output`が空、`toolCalls`のみ）はTurnの
     * ContentPart表現を持たない（04_ドメイン設計.md 4.3.4はTurnにToolCall専用の型を定義して
     * いない）ため、[toolCallSummary]で読める形のContentPart.Textへ変換して記録する
     * （要件充足に影響しない実装判断のためADR化せず根拠をここに残す）。
     */
    private fun recordAssistantTurn(
        request: CanonicalRequest,
        response: CanonicalResponse,
    ) {
        val conversationId = request.conversationId ?: return
        val contentParts = assistantTurnContent(response)
        if (contentParts.isEmpty()) {
            logger.warn(
                "skipping assistant turn persistence for conversationId={}: no output or tool calls to record",
                conversationId.value,
            )
            return
        }
        persistTurn(conversationId, TurnRole.ASSISTANT, contentParts, response.resolvedModel, response.usage)
    }

    private fun assistantTurnContent(response: CanonicalResponse): List<ContentPart> {
        if (response.output.isNotEmpty()) return response.output
        return response.toolCalls.orEmpty().map { call ->
            ContentPart.Text(toolCallSummary(call.toolName, call.arguments))
        }
    }

    private fun toolCallSummary(
        toolName: String,
        arguments: String,
    ): String = "[tool_call] $toolName($arguments)"

    /**
     * 永続化の失敗で応答を失敗させない（応答は既に生成済み、あるいはユーザ入力の受理は既に完了して
     * いるため）。ログのみに留める: `docs/design/14_イベント一覧.md`の50イベント一覧は編集不可
     * （CLAUDE.md不変条件8）であり、この失敗専用の新規DomainEventを追加できない
     * （要件充足に影響しない実装判断のためADR化せず根拠をここに残す。メトリクス化はP8
     * Observability着手時に改めて検討する）。
     */
    private fun persistTurn(
        conversationId: ConversationId,
        role: TurnRole,
        contentParts: List<ContentPart>,
        modelUsed: ModelId?,
        usage: Usage?,
    ) {
        runCatching {
            conversationManager.appendTurn(conversationId, role, contentParts, modelUsed, usage)
        }.onFailure { e ->
            logger.warn(
                "failed to persist {} turn for conversationId={}: {}",
                role,
                conversationId.value,
                e.message,
                e,
            )
        }
    }

    private fun contextLengthExceededError(e: ContextLengthExceededException): NormalizedError =
        NormalizedError(
            code = e.errorCode,
            category = AdapterErrorCategory.INVALID_REQUEST,
            message = e.message ?: "context length exceeded even after compaction",
            retryable = false,
            fallbackable = false,
            cbRecordable = false,
        )

    private fun cacheHitRateLimitedError(rejected: AcquireResult.Rejected): NormalizedError =
        NormalizedError(
            code = ErrorCode.RATE_LIMIT_EXCEEDED,
            category = AdapterErrorCategory.RATE_LIMITED,
            message = "local rate limiter rejected this cache-hit short-circuit",
            retryable = true,
            fallbackable = false,
            cbRecordable = false,
            // maxWait=Duration.ZEROで呼んでいるため常に0（即時可否判定のみ、待つ意思なし）。
            retryAfterMs = rejected.maxWaitMillis,
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

    private companion object {
        val logger = LoggerFactory.getLogger(DefaultExecutionEngine::class.java)
    }
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
