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
 */
data class AdapterChunk(
    val type: AdapterChunkType,
    val index: Int,
    val delta: ContentPart? = null,
    val toolCallDelta: ToolCall? = null,
    val usage: Usage? = null,
    val errorMessage: String? = null,
) {
    init {
        require(index >= 0) { "AdapterChunk.index must not be negative: $index" }
    }
}
