package apap.domain.model.execution

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.NormalizedError
import apap.domain.model.vo.Usage

/** 03_基本設計.md 3.3.1 `StreamChunk.type` / 02_システム仕様.md 2.10のチャンク種別6値+heartbeat。 */
enum class StreamChunkType {
    MESSAGE_START,
    CONTENT_DELTA,
    TOOL_CALL_DELTA,
    USAGE,
    MESSAGE_END,
    ERROR,
    HEARTBEAT,
}

/** 03_基本設計.md 3.3.1 `StreamChunk`: Streaming Engineが正規化して中継する共通チャンク単位。 */
data class StreamChunk(
    val type: StreamChunkType,
    val index: Int,
    val delta: ContentPart? = null,
    val toolCallDelta: ToolCall? = null,
    val usage: Usage? = null,
    val error: NormalizedError? = null,
) {
    init {
        require(index >= 0) { "StreamChunk.index must not be negative: $index" }
    }
}
