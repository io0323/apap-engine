package apap.adapter.anthropic

import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.ContentPart
import apap.adapter.spi.FinishReason
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.TokenCount
import apap.adapter.spi.ToolCall
import apap.adapter.spi.Usage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 非Streaming応答（`type: "message"`）を[AdapterResponse]へ写す。
 *
 * ## stop_reason の写し方
 *
 * | stop_reason | FinishReason | 補足 |
 * |---|---|---|
 * | end_turn / stop_sequence | COMPLETED | |
 * | max_tokens | LENGTH_LIMIT | |
 * | tool_use | TOOL_CALL | |
 * | refusal | CONTENT_FILTERED | **HTTP 200で返る**。エラーではなく応答として来る点に注意 |
 *
 * `refusal` が成功応答として来ることは、SPIの `AdapterErrorCategory.CONTENT_FILTERED`
 * （＝例外側の分類）と対になっていない。詳細は docs/adapter-spi-findings.md に記録している。
 */
object ResponseMapper {
    private val mapper = ObjectMapper()

    fun toResponse(body: String): AdapterResponse {
        val root = mapper.readTree(body)
        val contentBlocks = root.path("content")
        val output = mutableListOf<ContentPart>()
        val toolCalls = mutableListOf<ToolCall>()

        contentBlocks.forEach { block ->
            when (block.path("type").asText()) {
                "text" -> output.add(TextContentPart(block.path("text").asText()))
                "tool_use" ->
                    toolCalls.add(
                        ToolCall(
                            callId = block.path("id").asText(),
                            toolName = block.path("name").asText(),
                            arguments = mapper.writeValueAsString(block.path("input")),
                        ),
                    )
                // 将来の未知blockは黙って捨てず、テキストとして残す方が事故が小さい
                // （応答が空になると呼び出し側は「モデルが何も返さなかった」と誤読する）。
                else ->
                    block.path("text").takeIf { !it.isMissingNode }?.let {
                        output.add(TextContentPart(it.asText()))
                    }
            }
        }

        return AdapterResponse(
            output = output,
            finishReason = finishReasonOf(root.path("stop_reason").asText(null)),
            usage = usageOf(root.path("usage")),
            toolCalls = toolCalls,
            providerRequestId = root.path("id").asText(null),
        )
    }

    fun finishReasonOf(stopReason: String?): FinishReason =
        when (stopReason) {
            "end_turn", "stop_sequence" -> FinishReason.COMPLETED
            "max_tokens" -> FinishReason.LENGTH_LIMIT
            "tool_use" -> FinishReason.TOOL_CALL
            "refusal" -> FinishReason.CONTENT_FILTERED
            else -> FinishReason.COMPLETED
        }

    /**
     * usageは `input_tokens` / `output_tokens` に加えてキャッシュ関連の項目を持つ。
     * SPIの[Usage]は `cachedTokens` を持つのでそこへ写す（`estimated=false`＝Provider実測値）。
     */
    fun usageOf(node: JsonNode): Usage {
        val input = node.path("input_tokens").asInt(0)
        val output = node.path("output_tokens").asInt(0)
        val cacheRead = node.path("cache_read_input_tokens").asInt(0)
        return Usage.of(
            inputTokens = TokenCount(input),
            outputTokens = TokenCount(output),
            estimated = false,
            cachedTokens = cacheRead.takeIf { it > 0 }?.let { TokenCount(it) },
        )
    }
}
