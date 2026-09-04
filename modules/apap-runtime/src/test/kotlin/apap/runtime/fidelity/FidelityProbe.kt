package apap.runtime.fidelity

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.TextContentPart
import apap.api.ApapRequest
import apap.domain.model.conversation.TurnRole
import apap.domain.model.execution.GenerationParams
import apap.domain.model.execution.InputMessage
import apap.domain.model.execution.ToolDefinition
import apap.domain.model.execution.ToolResult
import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.RoutingConstraints
import apap.domain.model.vo.SessionId
import apap.runtime.EngineFixture
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Adapterが実際に受け取った[AdapterRequest]を記録するデコレータ。
 *
 * 「変換が正しい」ことは、変換の**出力**を見なければ確かめられない。応答が返ったことや
 * イベントが飛んだことは、roleやoutputSchemaが落ちていても成立してしまう（P11-F4/F3）。
 *
 * 応答は[FidelitySentinels.OUTPUT_SCHEMA]に適合するJSONを返す。適合しない応答を返すと
 * P13で入れたStructured Output検証が是正リトライを起こし、記録が「是正後の2回目」に
 * 上書きされてしまうため。
 */
class RecordingAdapter(
    private val delegate: ProviderAdapter,
) : ProviderAdapter by delegate {
    private val seen = CopyOnWriteArrayList<AdapterRequest>()

    /** 最初にAdapterへ届いたリクエスト。到達性の検証はここを見る。 */
    fun first(): AdapterRequest = seen.firstOrNull() ?: error("Adapterが1度も呼ばれていません（到達性を検証できる状態にありません）")

    fun count(): Int = seen.size

    fun all(): List<AdapterRequest> = seen.toList()

    override suspend fun execute(request: AdapterRequest): AdapterResponse {
        seen += request
        val base = delegate.execute(request)
        return base.copy(output = listOf(TextContentPart(FidelitySentinels.SCHEMA_CONFORMING_OUTPUT)))
    }

    override suspend fun executeStream(request: AdapterRequest): ProviderAdapter.AdapterStream {
        seen += request
        return delegate.executeStream(request)
    }
}

/**
 * 到達性検査の見張り値。[RequestFidelityContract.probes]が「到達してはならない」側の値を持ち、
 * ここは「到達すべき」側の値も含めた実際のリクエスト構築に使う。
 */
object FidelitySentinels {
    val CAPABILITY: CapabilityId = CapabilityId("chat")

    const val PRINCIPAL = "sentinel-principal-4f2a"
    const val ALIAS = "sentinel-alias-7b2e"
    const val ALIAS_ID = "01ARZ3NDEKTSV4RRFFQ69G5FZ6"
    const val IDEMPOTENCY_KEY = "sentinel-idempotency-3d5c"
    const val REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FZ9"
    val CONVERSATION: ConversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FZ7")
    val SESSION: SessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FZ8")

    const val TRACE_ID = "sentinel-trace-8e1d"
    const val MAX_LATENCY_MS = 424243L

    const val SYSTEM_TEXT = "sentinel-system-2b7f"
    const val USER_TEXT = "sentinel-user-6c4a"
    const val ASSISTANT_TEXT = "sentinel-assistant-9f3e"
    const val FOLLOW_UP_TEXT = "sentinel-followup-1d8b"

    const val IMAGE_URI = "https://example.internal/sentinel-image-5a2c.png"
    const val AUDIO_URI = "https://example.internal/sentinel-audio-7e9d.wav"

    const val TOOL_NAME = "sentinel_tool_3f6a"
    const val TOOL_DESCRIPTION = "sentinel-tool-description-8b2d"
    const val TOOL_SCHEMA = """{"type":"object","properties":{"q":{"type":"string"}}}"""
    const val TOOL_CALL_ID = "sentinel-callid-2e7c"
    const val TOOL_RESULT_CONTENT = "sentinel-toolresult-4a9b"

    const val STOP_A = "sentinel-stop-a-6d1e"
    const val STOP_B = "sentinel-stop-b-3c8f"
    const val TEMPERATURE = 0.4242
    const val TOP_P = 0.4244
    const val MAX_TOKENS = 4243
    const val SEED = 424244L

    const val OUTPUT_SCHEMA =
        """{"type":"object","required":["sentinel_answer"],"properties":{"sentinel_answer":{"type":"string"}}}"""
    const val SCHEMA_CONFORMING_OUTPUT = """{"sentinel_answer":"ok"}"""

    /** タイムアウトは残予算として渡るため、下限・上限で挟んで検証する。 */
    val TIMEOUT_BUDGET: Duration = Duration.ofSeconds(97)

    /** Memory注入の見張り。[apap.runtime.ApapEngineBuilder.queryEmbedding]経由で有効化する。 */
    const val MEMORY_CONTENT = "sentinel-memory-5b3d"
    val MEMORY_VECTOR = listOf(1.0, 0.0, 0.0)

    /**
     * 全フィールドを見張り値で埋めたリクエスト。`params`/`tools`/`toolResults`/`outputSchema`まで
     * 含めるのは、1本のリクエストで「到達すべき」「到達してはならない」を同時に検査するため。
     */
    fun request(
        messages: List<InputMessage> =
            listOf(
                InputMessage(TurnRole.SYSTEM, listOf(ContentPart.Text(SYSTEM_TEXT))),
                InputMessage(TurnRole.USER, listOf(ContentPart.Text(USER_TEXT))),
                InputMessage(TurnRole.ASSISTANT, listOf(ContentPart.Text(ASSISTANT_TEXT))),
                InputMessage(TurnRole.USER, listOf(ContentPart.Text(FOLLOW_UP_TEXT))),
            ),
        conversationId: ConversationId? = null,
        outputSchema: String? = OUTPUT_SCHEMA,
        modelAlias: String? = ALIAS,
    ): ApapRequest =
        ApapRequest(
            tenantId = EngineFixture.TENANT,
            principal = PRINCIPAL,
            capabilityId = CAPABILITY,
            input = messages.flatMap { it.content },
            messages = messages,
            modelAlias = modelAlias,
            params =
                GenerationParams(
                    temperature = TEMPERATURE,
                    maxTokens = MAX_TOKENS,
                    topP = TOP_P,
                    stop = listOf(STOP_A, STOP_B),
                    seed = SEED,
                ),
            tools = listOf(ToolDefinition(TOOL_NAME, TOOL_DESCRIPTION, TOOL_SCHEMA)),
            toolResults = listOf(ToolResult(TOOL_CALL_ID, TOOL_RESULT_CONTENT)),
            outputSchema = outputSchema,
            conversationId = conversationId,
            sessionId = SESSION,
            idempotencyKey = IDEMPOTENCY_KEY,
            timeoutBudget = TIMEOUT_BUDGET,
            requestId = REQUEST_ID,
            traceId = TRACE_ID,
        )

    /** `constraints`（CanonicalRequest固有）はGatewayにも公開APIにも入口が無いため、直接組み立てる。 */
    fun constraintsSentinel(): RoutingConstraints = RoutingConstraints(maxLatencyMs = MAX_LATENCY_MS)

    fun mockConfig() = MockAdapterConfig(supportedCapabilities = setOf(CAPABILITY))

    /** [ALIAS]を[modelId]へ100%向ける。modelAliasが物理名へ解決されることの前提。 */
    fun assignAlias(
        fixture: EngineFixture.Fixture,
        modelId: ModelId,
    ) {
        fixture.engine.admin.models.assignAlias(
            tenantId = EngineFixture.TENANT,
            aliasId = AliasId(ALIAS_ID),
            name = ALIAS,
            targets = listOf(AliasTarget(modelId, weight = 100)),
        )
    }
}
