package apap.adapter.spi

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.Usage

/** 03_基本設計.md 3.3.1 `StreamChunk.type`。Adapter層のチャンク単位でも同じ6値を用いる。 */
enum class AdapterChunkType {
    MESSAGE_START,
    CONTENT_DELTA,
    TOOL_CALL_DELTA,
    USAGE,
    MESSAGE_END,
    ERROR,
    HEARTBEAT,
}

/**
 * 03_基本設計.md 3.3.2 `AdapterStream.next(): AdapterChunk?`。
 * `index` はチャンクの通し番号（3.3.1 `StreamChunk.index`）。
 *
 * [toolCallComplete]: ADR-0019。`type=TOOL_CALL_DELTA`の[toolCallDelta]が、その`callId`の
 * 最終断片であることをAdapterが明示的に示すオプショナルフィールド（既定false）。Provider APIが
 * tool call境界をネイティブに通知する場合、Adapter実装はそれをここへマップすることが推奨される。
 * 未対応のAdapter（既定値のまま）に対しては、コア側（`apap.execution.streaming.ToolCallAssembler`）
 * が中括弧バランスのヒューリスティックへフォールバックする。
 */
data class AdapterChunk(
    val type: AdapterChunkType,
    val index: Int,
    val delta: ContentPart? = null,
    val toolCallDelta: ToolCall? = null,
    val usage: Usage? = null,
    val errorMessage: String? = null,
    val toolCallComplete: Boolean = false,
) {
    init {
        require(index >= 0) { "AdapterChunk.index must not be negative: $index" }
    }
}
