package apap.adapter.anthropic

import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.ContentPart
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.TokenCount
import apap.adapter.spi.ToolCall
import apap.adapter.spi.Usage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * ProviderのSSEを、SPIのpull型[ProviderAdapter.AdapterStream]へ写す。
 *
 * ## 写像
 *
 * | SSE event | AdapterChunk |
 * |---|---|
 * | `message_start` | MESSAGE_START（input_tokensを保持） |
 * | `content_block_start` (text) | 出さない（block種別だけ記録） |
 * | `content_block_start` (tool_use) | TOOL_CALL_DELTA（id/nameの通知、arguments空） |
 * | `content_block_delta` (text_delta) | CONTENT_DELTA |
 * | `content_block_delta` (input_json_delta) | TOOL_CALL_DELTA（partial_jsonを積む） |
 * | `content_block_stop` (tool_use) | TOOL_CALL_DELTA + **toolCallComplete=true**（ADR-0019） |
 * | `message_delta` | stop_reasonとoutput_tokensを保持 |
 * | `message_stop` | USAGE → MESSAGE_END |
 * | `ping` | HEARTBEAT |
 * | `error` | [apap.adapter.spi.AdapterException]を送出 |
 *
 * ## ADR-0019 の検証結果
 *
 * `content_block_stop` が**そのまま**「このtool_callの引数はここで完結した」を意味するため、
 * ADR-0019で追加した`toolCallComplete`シグナルは実ストリームから素直に供給できた。
 * ブレース数え上げのフォールバックは、このProviderでは不要。
 *
 * ## 1イベント→複数チャンク
 *
 * `message_stop` はUSAGEとMESSAGE_ENDの2つを生む。SPIの`next()`は1回1チャンクなので、
 * 生成済みチャンクを内部キューに置いて1つずつ返す。**イベント数とチャンク数が
 * 1対1ではない**ことは、pull型SPIをそのまま使えるかの分かれ目だったが、
 * キュー1本で吸収できている。
 */
class AnthropicAdapterStream(
    private val source: EventStream,
) : ProviderAdapter.AdapterStream {
    private val mapper = ObjectMapper()
    private val pending = ArrayDeque<AdapterChunk>()

    /** content_block index → 進行中のtool_use（id/name）。input_json_deltaがidを持たないため必要。 */
    private val toolBlocks = mutableMapOf<Int, ToolCall>()

    private var sequence = 0
    private var inputTokens = 0
    private var outputTokens = 0
    private var finished = false

    override suspend fun next(): AdapterChunk? {
        while (true) {
            pending.removeFirstOrNull()?.let { return it }
            // finished（message_stop到達）か、SSEが尽きたら終端。
            val event = if (finished) null else source.next()
            if (event == null) return null
            translate(event)
        }
    }

    override fun cancel() {
        source.cancel()
    }

    private fun translate(event: ServerSentEvent) {
        // `event:`行が無いストリームもあるため、データ側のtypeを正とする。
        val node = runCatching { mapper.readTree(event.data) }.getOrNull() ?: return
        when (node.path("type").asText(event.event ?: "")) {
            "message_start" -> {
                inputTokens =
                    node
                        .path("message")
                        .path("usage")
                        .path("input_tokens")
                        .asInt(0)
                emit(AdapterChunkType.MESSAGE_START)
            }
            "content_block_start" -> onBlockStart(node)
            "content_block_delta" -> onBlockDelta(node)
            "content_block_stop" -> onBlockStop(node)
            "message_delta" -> {
                outputTokens = node.path("usage").path("output_tokens").asInt(outputTokens)
            }
            "message_stop" -> onMessageStop()
            "ping" -> emit(AdapterChunkType.HEARTBEAT)
            "error" -> throw ErrorMapper.toException(HttpReply(STREAM_ERROR_STATUS, emptyMap(), event.data))
            else -> Unit
        }
    }

    private fun onBlockStart(node: JsonNode) {
        val block = node.path("content_block")
        if (block.path("type").asText() != "tool_use") return
        val index = node.path("index").asInt(0)
        val call =
            ToolCall(
                callId = block.path("id").asText(),
                toolName = block.path("name").asText(),
                arguments = "",
            )
        toolBlocks[index] = call
        emit(AdapterChunkType.TOOL_CALL_DELTA, toolCallDelta = call)
    }

    private fun onBlockDelta(node: JsonNode) {
        val delta = node.path("delta")
        when (delta.path("type").asText()) {
            "text_delta" ->
                emit(AdapterChunkType.CONTENT_DELTA, delta = TextContentPart(delta.path("text").asText()))
            "input_json_delta" -> {
                val index = node.path("index").asInt(0)
                val started = toolBlocks[index] ?: return
                emit(
                    AdapterChunkType.TOOL_CALL_DELTA,
                    toolCallDelta = started.copy(arguments = delta.path("partial_json").asText("")),
                )
            }
            // thinking_delta等の未知deltaは出さない（テキストとして混ぜると応答が汚れる）。
            else -> Unit
        }
    }

    /** ADR-0019: tool_useブロックの終わりを、そのまま完了シグナルとして流す。 */
    private fun onBlockStop(node: JsonNode) {
        val index = node.path("index").asInt(0)
        val started = toolBlocks.remove(index) ?: return
        emit(
            AdapterChunkType.TOOL_CALL_DELTA,
            toolCallDelta = started.copy(arguments = ""),
            toolCallComplete = true,
        )
    }

    private fun onMessageStop() {
        emit(
            AdapterChunkType.USAGE,
            usage =
                Usage.of(
                    inputTokens = TokenCount(inputTokens),
                    outputTokens = TokenCount(outputTokens),
                    estimated = false,
                ),
        )
        emit(AdapterChunkType.MESSAGE_END)
        finished = true
    }

    private fun emit(
        type: AdapterChunkType,
        delta: ContentPart? = null,
        toolCallDelta: ToolCall? = null,
        usage: Usage? = null,
        toolCallComplete: Boolean = false,
    ) {
        pending.addLast(
            AdapterChunk(
                type = type,
                index = sequence++,
                delta = delta,
                toolCallDelta = toolCallDelta,
                usage = usage,
                toolCallComplete = toolCallComplete,
            ),
        )
    }

    private companion object {
        /** ストリーム途中のerrorイベントはHTTPステータスを持たない。分類は本文のerror.typeで決まる。 */
        const val STREAM_ERROR_STATUS = 500
    }
}
