package apap.domain.model.execution

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.Cost
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.Usage

/**
 * 03_基本設計.md 3.3.1 ToolCall（Provider応答からの正規化後）。[CanonicalResponse.toolCalls] /
 * [StreamChunk.toolCallDelta] 専用。
 */
data class ToolCall(
    val callId: String,
    val toolName: String,
    val arguments: String,
) {
    init {
        require(callId.isNotBlank()) { "ToolCall.callId must not be blank" }
        require(toolName.isNotBlank()) { "ToolCall.toolName must not be blank" }
    }
}

/** 03_基本設計.md 3.3.1 `CanonicalResponse`。 */
data class CanonicalResponse(
    val responseId: String,
    val requestId: RequestId,
    val output: List<ContentPart>,
    val toolCalls: List<ToolCall>? = null,
    val finishReason: FinishReason,
    val usage: Usage,
    val cost: Cost,
    val resolvedProvider: ProviderId,
    val resolvedModel: ModelId,
    val cached: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(responseId.isNotBlank()) { "CanonicalResponse.responseId must not be blank" }
    }
}
