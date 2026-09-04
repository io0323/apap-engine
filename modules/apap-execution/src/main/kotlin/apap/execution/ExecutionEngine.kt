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
import apap.domain.model.execution.InputMessage
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.execution.StreamChunk
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
import apap.domain.port.MetricsRecorder
import apap.execution.attempt.AttemptResult
import apap.execution.estimation.TokenEstimator
import apap.execution.fallback.FallbackEngine
import apap.execution.mapping.ResponseMapper
import apap.execution.streaming.StreamingRequestExecutor
import apap.prompt.PromptEngine
import apap.routing.RoutingEngine
import apap.routing.RoutingRequest
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
     * ここに根拠を残す）。戻り値は[StreamChunk]（着手前レビューでStreaming経路の実配線が完了し、
     * ワイルドカード型を維持する理由が無くなったため確定させた）。
     */
    fun executeStream(request: CanonicalRequest): Flow<StreamChunk>
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
    private val streamingRequestExecutor: StreamingRequestExecutor,
    private val tokenEstimator: TokenEstimator,
    private val idempotencyGuard: IdempotencyGuard,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val eventPublisher: DomainEventPublisher,
    private val metricsRecorder: MetricsRecorder,
    private val quotaPolicyProvider: (TenantId) -> QuotaPolicy? = { null },
    private val tracer: Tracer = OpenTelemetry.noop().getTracer(TRACER_NAME),
) : ExecutionEngine {
    private val phases = PhaseTimings(clock, tracer, metricsRecorder)

    override suspend fun execute(request: CanonicalRequest): CanonicalResponse {
        publish(requestReceived(request))

        val idempotencyKey = compositeIdempotencyKey(request)
        idempotencyGuard.claim(idempotencyKey)
        try {
            return executeClaimed(request)
        } finally {
            idempotencyGuard.release(idempotencyKey)
        }
    }

    /**
     * 02_システム仕様.md 2.10 Streaming Flow（着手前レビューで解消: [StreamingEngine]実装済みだが
     * 呼び出し元が無かった）。Cacheは既定でバイパスする（2.14: temperature>0/Streamingは既定で
     * Response Cache対象外——`apap.cache.CacheabilityPolicy`の対象外である以前に、そもそも
     * 照会自体を行わない）。Prompt処理直後、Routing/Quota予約より前にuser turnを記録する
     * （[recordUserTurn]、Provider呼出が失敗しても入力を失わない、非Streaming版と同じ判断）。
     */
    @Suppress("TooGenericExceptionCaught")
    override fun executeStream(request: CanonicalRequest): Flow<StreamChunk> =
        flow {
            publish(requestReceived(request))
            val idempotencyKey = compositeIdempotencyKey(request)
            idempotencyGuard.claim(idempotencyKey)
            // 02_システム仕様.md 2.19 Span構成のルートSpan。非Streaming版(executeClaimed)と同じ
            // rootSpanパターンだが、Flowはcollect時まで遅延評価されるため、span.end()は
            // emitAll完了後(finally)に行う必要がある——同期メソッドのtry/finallyと構造は同じ。
            val rootSpan = tracer.spanBuilder("apap.execute").setSpanKind(SpanKind.SERVER).startSpan()
            try {
                emitAll(executeStreamClaimed(request, rootSpan))
                rootSpan.setStatus(StatusCode.OK)
            } catch (e: Throwable) {
                rootSpan.recordException(e)
                rootSpan.setStatus(StatusCode.ERROR)
                throw e
            } finally {
                rootSpan.end()
                idempotencyGuard.release(idempotencyKey)
            }
        }

    private suspend fun executeStreamClaimed(
        request: CanonicalRequest,
        rootSpan: Span,
    ): Flow<StreamChunk> {
        val prompt = phases.time("prompt", rootSpan) { promptEngine.process(request) }
        recordUserTurn(request)

        val decision =
            phases.time("routing", rootSpan) {
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
            phases.time("context", rootSpan) { buildContextualPrompt(request, prompt, primaryCandidate.modelId) }
        val estimatedTokens =
            phases.time("token-estimate", rootSpan) {
                tokenEstimator.estimate(primaryCandidate, primaryCandidate.modelId, contextualPrompt)
            }
        val estimatedCost =
            costEngine.estimate(primaryCandidate.providerId, primaryCandidate.modelId, contextualPrompt)

        val ctx = startExecutionContext(request)
        val reservation =
            reserveQuota(request, primaryCandidate.providerId, primaryCandidate.modelId, estimatedTokens, estimatedCost)

        publish(
            RequestStarted(
                meta(request),
                request.requestId,
                request.capabilityId,
                request.tenantId,
                decision.toAuditSummary(),
            ),
        )

        return streamingRequestExecutor.execute(decision.chain, contextualPrompt, request, ctx, reservation, rootSpan)
    }

    /**
     * 02_システム仕様.md 2.19 Span構成（gateway → prompt → routing → attempt[n] → mapping）の
     * ルートSpanを開く。`apap-gateway`（P10）が実装されるまでは本メソッドが実質的な入口のため
     * ルートSpanとして開始する。GatewayがW3C Trace Contextを受け取って親Spanを設定した場合は、
     * `tracer`に注入されたSDK側のContext伝播（宿主の責務）を通じてそちらが親になる
     * （要件充足に影響しない実装判断のためADR化せず、根拠をここに残す）。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeClaimed(request: CanonicalRequest): CanonicalResponse {
        val rootSpan = tracer.spanBuilder("apap.execute").setSpanKind(SpanKind.SERVER).startSpan()
        return try {
            val response = executeClaimedTraced(request, rootSpan)
            rootSpan.setStatus(StatusCode.OK)
            response
        } catch (e: Throwable) {
            // Spanのライフサイクル管理のため、送出元を問わず全ての例外を記録してからそのまま再送出する。
            rootSpan.recordException(e)
            rootSpan.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            rootSpan.end()
        }
    }

    private suspend fun executeClaimedTraced(
        request: CanonicalRequest,
        rootSpan: Span,
    ): CanonicalResponse {
        val prompt = phases.time("prompt", rootSpan) { promptEngine.process(request) }

        val cached = phases.time("cache-lookup", rootSpan) { cacheEngine.lookup(request, prompt) }
        if (cached != null) {
            return handleCacheHit(request, cached)
        }

        recordUserTurn(request)

        val decision =
            phases.time("routing", rootSpan) {
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
            phases.time("context", rootSpan) { buildContextualPrompt(request, prompt, primaryCandidate.modelId) }
        val estimatedTokens =
            phases.time("token-estimate", rootSpan) {
                tokenEstimator.estimate(primaryCandidate, primaryCandidate.modelId, contextualPrompt)
            }
        val estimatedCost =
            costEngine.estimate(primaryCandidate.providerId, primaryCandidate.modelId, contextualPrompt)

        val ctx = startExecutionContext(request)
        val reservation =
            reserveQuota(request, primaryCandidate.providerId, primaryCandidate.modelId, estimatedTokens, estimatedCost)

        publish(
            RequestStarted(
                meta(request),
                request.requestId,
                request.capabilityId,
                request.tenantId,
                decision.toAuditSummary(),
            ),
        )
        val startedAt = clock.now()

        val result =
            phases.time("execution", rootSpan, recordOverhead = false) { executionSpan ->
                fallbackEngine.executeWithChain(decision.chain, contextualPrompt, request, ctx, executionSpan)
            }

        return when (result) {
            is AttemptResult.Success -> onSuccess(request, prompt, result, reservation, startedAt, rootSpan)
            is AttemptResult.Failure -> onFailure(request, result, reservation, startedAt)
        }
    }

    private suspend fun onSuccess(
        request: CanonicalRequest,
        prompt: ProcessedPrompt,
        result: AttemptResult.Success,
        reservation: Reservation,
        startedAt: Instant,
        rootSpan: Span,
    ): CanonicalResponse {
        val cost = costEngine.calculate(result.response.usage, result.candidate.modelId)
        val response =
            phases.time("mapping", rootSpan) {
                ResponseMapper.normalize(
                    response = result.response,
                    requestId = request.requestId,
                    cost = cost,
                    resolvedProvider = result.candidate.providerId,
                    resolvedModel = result.candidate.modelId,
                    idGenerator = idGenerator,
                )
            }
        val durationMs = Duration.between(startedAt, clock.now()).toMillis()
        quotaManager.commit(reservation, result.response.usage, cost.amount)
        costEngine.record(request, response, durationMs)
        cacheEngine.store(request, prompt, response)
        recordAssistantTurn(request, response)
        publish(
            RequestCompleted(
                meta(request),
                request.requestId,
                request.capabilityId,
                result.candidate.providerId.value,
                result.candidate.modelId.value,
                response.usage,
                response.cost,
                durationMs,
                response.finishReason,
                retries = result.attempts,
                fallbacks = result.fallbacks,
                requestBody = serializeContent(request.input),
                responseBody = serializeContent(response.output),
            ),
        )
        return response
    }

    private fun onFailure(
        request: CanonicalRequest,
        result: AttemptResult.Failure,
        reservation: Reservation,
        startedAt: Instant,
    ): CanonicalResponse {
        quotaManager.release(reservation)
        val durationMs = Duration.between(startedAt, clock.now()).toMillis()
        publish(
            RequestFailed(
                meta(request),
                request.requestId,
                request.capabilityId,
                result.error.code,
                result.attempts,
                result.fallbacks,
                durationMs = durationMs,
                requestBody = serializeContent(request.input),
            ),
        )
        throw ExecutionFailedException(result.error)
    }

    /**
     * Audit Engine向けの生コンテンツ文字列化（[RequestCompleted]/[RequestFailed]のKDoc参照）。
     * `ContentPart`はdata classのため`toString()`で決定的な構造表現が得られる。要件充足に
     * 影響しない実装判断のためADR化せず、独自シリアライズ（Jackson等）は導入しない。
     */
    private fun serializeContent(parts: List<ContentPart>): String = parts.toString()

    private fun requestReceived(request: CanonicalRequest): RequestReceived =
        RequestReceived(
            meta(request),
            request.requestId,
            request.capabilityId,
            request.tenantId,
            request.principal,
            request.modelAlias,
            request.conversationId,
        )

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
        val conversation = request.conversationId?.let { conversationRepository.findById(it, request.tenantId) }
        // リクエストが持つSYSTEM発話をContext Managerへ渡す。P11-F4以前はここが
        // `emptyList()`固定で、System Promptの供給経路がそもそも存在しなかった。
        val systemPrompt = prompt.messages.filter { it.role == TurnRole.SYSTEM }.flatMap { it.content }
        val assembled =
            try {
                contextManager.build(request, systemPrompt, conversation, modelId)
            } catch (e: ContextLengthExceededException) {
                throw ExecutionFailedException(contextLengthExceededError(e))
            }

        // roleを保ったまま合成する（ADR-0031）。履歴のTurnはそれぞれの発話者を保持し、
        // Memory注入はSYSTEM相当の文脈として扱う。平坦化するとProviderからは
        // すべてが1人の発話に見え、マルチターンの意味が失われる。
        val messages =
            buildList {
                if (assembled.systemPrompt.isNotEmpty()) add(InputMessage(TurnRole.SYSTEM, assembled.systemPrompt))
                if (assembled.memoryInjection.isNotEmpty()) {
                    add(InputMessage(TurnRole.SYSTEM, assembled.memoryInjection))
                }
                assembled.turns.forEach { turn -> add(InputMessage(turn.role, turn.contentParts)) }
                // 今回の入力はSYSTEM分を除いた残り（SYSTEMは上でsystemPromptとして先頭に置いた）。
                addAll(prompt.messages.filter { it.role != TurnRole.SYSTEM })
            }
        return ProcessedPrompt(
            input = messages.flatMap { it.content },
            estimatedTokens = assembled.estimatedTokens,
            messages = messages,
        )
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
        // 今回のターンの**USER発話だけ**を記録する。`request.input`はmessagesを平坦化したもので
        // System Promptや利用側が付けた過去のassistant発話まで含む（Gatewayの`toApapRequest`参照）。
        // それを丸ごとUSER turnとして書くと、次のターンで履歴として読み戻したときに
        // 「ユーザがシステムプロンプトを喋った」ことになり、roleを保ったまま渡す意味が失われる
        // （ADR-0031 / P14のリクエスト忠実性検査で検出）。
        val content =
            if (request.messages.isEmpty()) {
                request.input
            } else {
                request.messages.filter { it.role == TurnRole.USER }.flatMap { it.content }
            }
        if (content.isEmpty()) return
        persistTurn(conversationId, request.tenantId, TurnRole.USER, content, modelUsed = null, usage = null)
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
        persistTurn(
            conversationId,
            request.tenantId,
            TurnRole.ASSISTANT,
            contentParts,
            response.resolvedModel,
            response.usage,
        )
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
    @Suppress("LongParameterList")
    private fun persistTurn(
        conversationId: ConversationId,
        tenantId: TenantId,
        role: TurnRole,
        contentParts: List<ContentPart>,
        modelUsed: ModelId?,
        usage: Usage?,
    ) {
        runCatching {
            conversationManager.appendTurn(conversationId, tenantId, role, contentParts, modelUsed, usage)
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
        const val TRACER_NAME = "apap-execution"
    }
}

/**
 * 02_システム仕様.md 2.8の各フェーズ所要時間の計測点、および2.19 Span計装点。`Clock`経由で
 * 計測するためテストでも決定的。`apap_overhead_duration_seconds{phase}`（2.19表）へも記録する
 * （ADR未満の実装判断: NFR-PRF-001の付加レイテンシ計測はこの計測点以外に手段が無く、
 * `MetricsEngine`のEvent Bus購読では導出不能なためここから直接呼ぶ。KDoc根拠は
 * `MetricsEngine`と同じ）。"gateway"フェーズはGateway自体（P10）が未実装のため計測点が無く、
 * ここでは"prompt"/"routing"/"context"/"token-estimate"/"cache-lookup"/"execution"/"mapping"の
 * 実測フェーズ名をそのまま記録する（2.19表の4ラベルはphaseの取りうる値の例示であり閉じた集合ではない）。
 *
 * `Span`は常にOpenTelemetry APIで子Spanを作り、成功/失敗を
 * `StatusCode`へ反映して`span.end()`する。CLAUDE.md不変条件5（ThreadLocal/CoroutineContext
 * 経由の暗黙コンテキスト禁止）に抵触しないよう、`Span`は常に引数として明示的に受け渡し、
 * `Span.current()`/`Context.current()`のような暗黙参照や`span.makeCurrent()`は使わない
 * （OpenTelemetry公式もsuspend関数内での`makeCurrent()`はスレッド切替で破綻するため非推奨としている）。
 */
private class PhaseTimings(
    private val clock: Clock,
    private val tracer: Tracer,
    private val metricsRecorder: MetricsRecorder,
) {
    @Suppress("TooGenericExceptionCaught")
    /**
     * @param recordOverhead `apap_overhead_duration_seconds`へ記録するかどうか。
     * Provider呼び出しを内包する区間（`execution`）は**APAPの付加分ではない**ため`false`にする。
     * Spanは常に作る（トレース上は実行区間として見えてほしいため）。ADR-0034参照。
     */
    suspend fun <T> time(
        phase: String,
        parentSpan: Span,
        recordOverhead: Boolean = true,
        block: suspend (Span) -> T,
    ): T {
        val span = tracer.spanBuilder(phase).setParent(Context.root().with(parentSpan)).startSpan()
        val start = clock.now()
        return try {
            val result = block(span)
            span.setStatus(StatusCode.OK)
            result
        } catch (e: Throwable) {
            // Spanのライフサイクル管理のため、送出元を問わず全ての例外を記録してからそのまま再送出する。
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            if (recordOverhead) recordDuration(phase, start)
            span.end()
        }
    }

    private fun recordDuration(
        phase: String,
        start: Instant,
    ) {
        val duration = Duration.between(start, clock.now())
        logger.debug("phase={} durationMs={}", phase, duration.toMillis())
        metricsRecorder.recordOverheadDuration(phase, duration.toNanos() / NANOS_PER_SECOND)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PhaseTimings::class.java)
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
