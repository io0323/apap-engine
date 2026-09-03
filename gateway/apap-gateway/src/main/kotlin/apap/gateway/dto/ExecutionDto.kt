package apap.gateway.dto

import apap.api.ApapRequest
import apap.api.ApapResponse
import apap.domain.model.execution.GenerationParams
import apap.domain.model.execution.ToolDefinition
import apap.domain.model.execution.ToolResult
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.gateway.json.GatewayJson

/**
 * 13_API設計.md 13.2「POST /v1/chat」のリクエスト。
 *
 * フィールド名は13.2のJSON例と一対一に対応させる（snake_caseへの変換は`GatewayJson`の
 * NamingStrategyが行うため、Kotlin側はcamelCaseで書く）。
 * **`provider`/`model`のような物理名フィールドは意図的に存在しない**
 * （13.2「ProviderやModelの物理名は指定不可（Vendor Neutral原則）」/ CLAUDE.md不変条件3）。
 */
data class ChatRequestDto(
    val messages: List<MessageDto>,
    val modelAlias: String? = null,
    val params: ParamsDto? = null,
    val tools: List<ToolDto>? = null,
    /**
     * 05_シーケンス設計.md 5.4後半: Agentが実行したToolの結果。
     * これを受け付けないと`tool_calls`を返しても往復が閉じない（P11-F7）。
     */
    val toolResults: List<ToolResultDto>? = null,
    val outputSchema: String? = null,
    val stream: Boolean = false,
    val conversationId: String? = null,
    val sessionId: String? = null,
    val metadata: Map<String, String>? = null,
)

/**
 * 13.2 `POST /v1/embeddings`。Chatと違い`inputs`（文字列配列）を取る。
 */
data class EmbeddingRequestDto(
    val inputs: List<String>,
    val modelAlias: String? = null,
    val dimensions: Int? = null,
)

data class MessageDto(
    val role: String,
    val content: List<ContentPartDto>,
)

/**
 * 13.2の`content`はContentPart配列（`text` / `image` / `audio`）。
 * ドメインの[ContentPart]が現時点で表現できる種別のみ受け付け、未対応種別は
 * `INVALID_REQUEST`として明示的に弾く（黙って無視するとプロンプトの一部が
 * 落ちたまま実行される——最も危険な失敗の形になる）。
 */
data class ContentPartDto(
    val type: String,
    val text: String? = null,
    val uri: String? = null,
    val mimeType: String? = null,
)

data class ParamsDto(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
    val seed: Long? = null,
)

data class ToolDto(
    val name: String,
    val description: String,
    val inputSchema: String,
)

/** 13.2のリクエストに載せるTool実行結果。`callId`は直前の応答の`tool_calls[].id`と対応する。 */
data class ToolResultDto(
    val callId: String,
    val content: String,
    val isError: Boolean = false,
) {
    fun toToolResult() = ToolResult(callId = callId, content = content, isError = isError)
}

/** 13.3「200 OK（Chat）」。 */
data class ChatResponseDto(
    val responseId: String,
    val requestId: String,
    val output: OutputDto,
    val toolCalls: List<ToolCallDto>? = null,
    val finishReason: String,
    val usage: UsageDto,
    val cost: CostDto,
    val cached: Boolean,
    val modelAlias: String?,
    val metadata: Map<String, String> = emptyMap(),
)

data class OutputDto(
    val message: MessageDto,
)

data class ToolCallDto(
    val id: String,
    val name: String,
    /**
     * 13.3の例は`"arguments": { "city": "Tokyo" }`（オブジェクト）。ドメインの
     * [apap.domain.model.execution.ToolCall.arguments]はProviderから来た生JSON文字列なので、
     * ここでJSONとして解釈して構造のまま返す（文字列のまま返すと13.3と形が変わる）。
     * 解釈できない場合は握り潰さず、生文字列を`_raw`に入れて可視化する。
     */
    val arguments: Map<String, Any?>,
)

data class UsageDto(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val estimated: Boolean,
)

data class CostDto(
    val amount: java.math.BigDecimal,
    val currency: String,
)

/** 13.1 `GET /v1/capabilities`。 */
data class CapabilityDto(
    val capabilityId: String,
    val name: String,
    val streamable: Boolean,
    val inputSchema: String,
    val outputSchema: String,
)

/**
 * DTO → [ApapRequest] への変換。Gatewayが行うのは**形式変換だけ**で、
 * ルーティング・検証・既定値の決定といった判断はエンジン側に委ねる。
 */
fun ChatRequestDto.toApapRequest(
    tenantId: TenantId,
    principal: String,
    capabilityId: CapabilityId,
    requestId: String,
    idempotencyKey: String?,
): ApapRequest {
    require(messages.isNotEmpty()) { "messages must not be empty" }
    require(messages.size <= MAX_MESSAGES) { "messages must not exceed $MAX_MESSAGES entries" }
    return ApapRequest(
        tenantId = tenantId,
        principal = principal,
        capabilityId = capabilityId,
        input = messages.flatMap { message -> message.content.map { it.toContentPart() } },
        modelAlias = modelAlias,
        params = params?.toGenerationParams() ?: GenerationParams(),
        tools = tools?.map { it.toToolDefinition() },
        toolResults = toolResults?.map { it.toToolResult() }.orEmpty(),
        outputSchema = outputSchema,
        conversationId = conversationId?.let { ConversationId(it) },
        sessionId = sessionId?.let { SessionId(it) },
        idempotencyKey = idempotencyKey,
        requestId = requestId,
    )
}

fun EmbeddingRequestDto.toApapRequest(
    tenantId: TenantId,
    principal: String,
    capabilityId: CapabilityId,
    requestId: String,
    idempotencyKey: String?,
): ApapRequest {
    require(inputs.isNotEmpty()) { "inputs must not be empty" }
    return ApapRequest(
        tenantId = tenantId,
        principal = principal,
        capabilityId = capabilityId,
        input = inputs.map { ContentPart.Text(it) },
        modelAlias = modelAlias,
        idempotencyKey = idempotencyKey,
        requestId = requestId,
    )
}

private fun ContentPartDto.toContentPart(): ContentPart =
    when (type) {
        "text" ->
            ContentPart.Text(
                requireNotNull(text) { "content part of type 'text' must carry a 'text' field" },
            )
        "image" ->
            ContentPart.Image(
                uri = requireNotNull(uri) { "content part of type 'image' must carry a 'uri' field" },
                mimeType = requireNotNull(mimeType) { "content part of type 'image' must carry a 'mime_type' field" },
            )
        "audio" ->
            ContentPart.Audio(
                uri = requireNotNull(uri) { "content part of type 'audio' must carry a 'uri' field" },
                mimeType = requireNotNull(mimeType) { "content part of type 'audio' must carry a 'mime_type' field" },
            )
        "video" ->
            ContentPart.Video(
                uri = requireNotNull(uri) { "content part of type 'video' must carry a 'uri' field" },
                mimeType = requireNotNull(mimeType) { "content part of type 'video' must carry a 'mime_type' field" },
            )
        "json" ->
            ContentPart.Json(
                requireNotNull(text) { "content part of type 'json' must carry a 'text' field" },
            )
        // 未知の種別は黙って落とさず明示的に拒否する
        // （落とすとプロンプトの一部が欠けたまま実行される——最も気づきにくい失敗の形）。
        else ->
            throw IllegalArgumentException(
                "Unsupported content part type: '$type' (supported: text, image, audio, video, json)",
            )
    }

private fun ParamsDto.toGenerationParams(): GenerationParams =
    GenerationParams(
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        stop = stop ?: emptyList(),
        seed = seed,
    )

private fun ToolDto.toToolDefinition(): ToolDefinition =
    ToolDefinition(name = name, description = description, parametersSchema = inputSchema)

/** [ApapResponse] → 13.3の応答DTO。 */
fun ApapResponse.toChatResponseDto(modelAlias: String?): ChatResponseDto =
    ChatResponseDto(
        responseId = responseId,
        requestId = requestId,
        output =
            OutputDto(
                message =
                    MessageDto(
                        role = "assistant",
                        content = output.map { it.toContentPartDto() },
                    ),
            ),
        toolCalls =
            toolCalls?.map { call ->
                ToolCallDto(
                    id = call.callId,
                    name = call.toolName,
                    arguments = parseToolArguments(call.arguments),
                )
            },
        finishReason = finishReason.name.lowercase(),
        usage =
            UsageDto(
                inputTokens = usage.inputTokens.value,
                outputTokens = usage.outputTokens.value,
                totalTokens = usage.totalTokens.value,
                estimated = usage.estimated,
            ),
        cost = CostDto(cost.amount.amount, cost.amount.currency),
        cached = cached,
        // 13.3注: 応答に物理Provider/Model名は含めない（CLAUDE.md不変条件3）。
        // 返すのは利用側が指定したAlias（論理名）のみ。
        modelAlias = modelAlias,
    )

private fun ContentPart.toContentPartDto(): ContentPartDto =
    when (this) {
        is ContentPart.Text -> ContentPartDto(type = "text", text = text)
        is ContentPart.Image -> ContentPartDto(type = "image", uri = uri, mimeType = mimeType)
        is ContentPart.Audio -> ContentPartDto(type = "audio", uri = uri, mimeType = mimeType)
        is ContentPart.Video -> ContentPartDto(type = "video", uri = uri, mimeType = mimeType)
        is ContentPart.Json -> ContentPartDto(type = "json", text = json)
    }

/**
 * ToolCallの`arguments`（Provider由来の生JSON文字列）をオブジェクトへ復元する。
 * 壊れたJSONでも例外にせず`_raw`として可視化する——ここで失敗させると
 * 「Providerは応答したのにGatewayが500を返す」ことになり、原因の切り分けが難しくなる。
 */
private fun parseToolArguments(raw: String): Map<String, Any?> =
    runCatching {
        @Suppress("UNCHECKED_CAST")
        GatewayJson.mapper.readValue(raw, Map::class.java) as Map<String, Any?>
    }.getOrElse { mapOf("_raw" to raw) }

private const val MAX_MESSAGES = 1000
