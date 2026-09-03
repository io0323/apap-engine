package apap.domain.model.execution

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.Cost
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.Usage

/**
 * 05_シーケンス設計.md 5.4 Tool Calling の後半——Agentが実行したToolの結果をAPAPへ返す往路。
 *
 * P11時点ではこの型がリポジトリのどこにも存在せず、`tool_calls`を受け取ることはできても
 * **結果を返せなかった**（往復が成立しない、P11-F7）。[callId]は先行する
 * [ToolCall.callId]と対応し、Provider側で呼出と結果を紐づけるために使う。
 */
data class ToolResult(
    val callId: String,
    val content: String,
    /** Tool実行が失敗したことをProviderへ伝える。Provider側の表現へはAdapterが変換する。 */
    val isError: Boolean = false,
) {
    init {
        require(callId.isNotBlank()) { "ToolResult.callId must not be blank" }
    }
}

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
