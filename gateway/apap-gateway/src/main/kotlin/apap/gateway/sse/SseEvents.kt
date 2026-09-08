package apap.gateway.sse

import apap.api.ApapStreamChunk
import apap.api.ApapStreamChunkType
import apap.domain.model.vo.ContentPart
import apap.gateway.error.ApiError
import apap.gateway.error.ProblemDetails
import apap.gateway.json.GatewayJson

/**
 * 13_API設計.md 13.3「SSE Streaming」のイベント名。**設計書の綴りと完全一致させる**
 * （`SseEventNameTest`がこの集合を固定し、勝手なリネームを検出する）。
 */
object SseEventName {
    const val MESSAGE_START = "message_start"
    const val CONTENT_DELTA = "content_delta"
    const val TOOL_CALL_DELTA = "tool_call_delta"
    const val USAGE = "usage"
    const val MESSAGE_END = "message_end"
    const val ERROR = "error"
    const val HEARTBEAT = "heartbeat"

    /** 02_システム仕様.md 2.10のチャンク種別と13.3のイベント名の全体集合。 */
    val all: Set<String> =
        setOf(MESSAGE_START, CONTENT_DELTA, TOOL_CALL_DELTA, USAGE, MESSAGE_END, ERROR, HEARTBEAT)
}

/** SSEの1イベント（`event:`行と`data:`行）。 */
data class SseEvent(
    val name: String,
    val data: String,
)

// --- 13.3のdataペイロード。フィールド名は13.3の例と一対一（snake_case化はGatewayJsonが行う）。 ---

data class MessageStartData(
    val responseId: String,
    val index: Int,
)

data class ContentDeltaData(
    val index: Int,
    val delta: DeltaDto,
)

/** 13.3 `"delta":{"type":"text","text":"こん"}`。 */
data class DeltaDto(
    val type: String,
    val text: String? = null,
    val uri: String? = null,
    val mimeType: String? = null,
)

data class ToolCallDeltaData(
    val index: Int,
    val id: String,
    val name: String,
    /** ToolCallの引数は組立て途中の生JSON断片。Gatewayは解釈せずそのまま中継する。 */
    val argumentsDelta: String,
)

data class UsageData(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)

/**
 * 13.3 `event: message_end` / `data: {"finish_reason":"completed"}`。
 *
 * [finishReason]がnullのときはフィールドごと省略される（`GatewayJson`が
 * `NON_NULL`で直列化する）。エンジンが終了理由を申告しなかった場合に
 * `completed`と埋めると嘘になるため、無いなら黙って無いままにする。
 */
data class MessageEndData(
    val finishReason: String?,
)

/**
 * `heartbeat`は13.3で`data`の中身が規定されていない。接続維持が目的なので空オブジェクトを送る
 * （`data:`行そのものを省くとイベント境界を誤認するクライアント実装があるため、必ず`data`を持たせる）。
 */
val HEARTBEAT_EVENT = SseEvent(SseEventName.HEARTBEAT, "{}")

/**
 * [ApapStreamChunk]（apap-apiの公開チャンク）を13.3のSSEイベントへ変換する。
 *
 * Gatewayは**チャンクの意味を解釈しない**——ToolCallの組立てやUsage確定は
 * StreamingEngine側（2.10の9'/10'）で完了しており、ここは表現形式の変換だけを行う。
 */
fun ApapStreamChunk.toSseEvent(responseId: String): SseEvent =
    when (type) {
        ApapStreamChunkType.MESSAGE_START ->
            event(SseEventName.MESSAGE_START, MessageStartData(responseId = responseId, index = index))

        ApapStreamChunkType.CONTENT_DELTA ->
            event(SseEventName.CONTENT_DELTA, ContentDeltaData(index = index, delta = delta.toDeltaDto()))

        ApapStreamChunkType.TOOL_CALL_DELTA ->
            event(
                SseEventName.TOOL_CALL_DELTA,
                ToolCallDeltaData(
                    index = index,
                    id = toolCallDelta?.callId.orEmpty(),
                    name = toolCallDelta?.toolName.orEmpty(),
                    argumentsDelta = toolCallDelta?.arguments.orEmpty(),
                ),
            )

        ApapStreamChunkType.USAGE ->
            event(
                SseEventName.USAGE,
                UsageData(
                    inputTokens = usage?.inputTokens?.value ?: 0,
                    outputTokens = usage?.outputTokens?.value ?: 0,
                    totalTokens = usage?.totalTokens?.value ?: 0,
                ),
            )

        // 13.3の`message_end`どおり終了理由を載せる（P16でADR-0028をSupersede）。
        // 以前はStreamChunk/ApapStreamChunkにfinishReasonが無く送出できなかったため省略していたが、
        // その状態では**length_limitで切られたストリームが正常完了と区別できない**。
        // 申告が無い場合のみ省略する（"completed"を固定で埋めると嘘をつくことになる）。
        ApapStreamChunkType.MESSAGE_END ->
            event(SseEventName.MESSAGE_END, MessageEndData(finishReason?.name?.lowercase()))

        // 13.3「異常時はevent: error（bodyは13.4のエラー形式）で終端」。
        // NormalizedErrorは既に13.4のコードを持つので、そのままProblemDetailsへ写す。
        ApapStreamChunkType.ERROR ->
            errorEvent(
                error?.let {
                    ProblemDetails.of(
                        error = ApiError.of(it.code),
                        detail = it.message,
                        requestId = responseId,
                        retryAfterMs = it.retryAfterMs,
                    )
                } ?: ProblemDetails.of(
                    ApiError.of(apap.domain.model.vo.ErrorCode.INTERNAL_ERROR),
                    "Stream terminated with an unspecified error",
                    responseId,
                ),
            )

        ApapStreamChunkType.HEARTBEAT -> HEARTBEAT_EVENT
    }

/** 13.3「異常時は`event: error`（bodyは13.4のエラー形式）で終端」。 */
fun errorEvent(problem: ProblemDetails): SseEvent = event(SseEventName.ERROR, problem)

private fun ContentPart?.toDeltaDto(): DeltaDto =
    when (this) {
        is ContentPart.Text -> DeltaDto(type = "text", text = text)
        is ContentPart.Image -> DeltaDto(type = "image", uri = uri, mimeType = mimeType)
        is ContentPart.Audio -> DeltaDto(type = "audio", uri = uri, mimeType = mimeType)
        is ContentPart.Video -> DeltaDto(type = "video", uri = uri, mimeType = mimeType)
        is ContentPart.Json -> DeltaDto(type = "json", text = json)
        null -> DeltaDto(type = "text", text = "")
    }

private fun event(
    name: String,
    payload: Any,
): SseEvent = SseEvent(name, GatewayJson.mapper.writeValueAsString(payload))
