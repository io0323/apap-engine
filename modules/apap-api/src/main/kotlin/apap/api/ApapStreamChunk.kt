package apap.api

import apap.domain.model.execution.ToolCall
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.NormalizedError
import apap.domain.model.vo.Usage

/** [apap.runtime.ApapEngine.executeStream]が返す`Flow`の要素型。`StreamChunkType`はapap-domainを再利用する。 */
enum class ApapStreamChunkType { MESSAGE_START, CONTENT_DELTA, TOOL_CALL_DELTA, USAGE, MESSAGE_END, ERROR, HEARTBEAT }

/**
 * `apap.domain.model.execution.StreamChunk`（内部表現）の公開版。[ApapResponse]と同じ理由
 * （CLAUDE.md不変条件3）でProvider/Model物理名は含まない——`StreamChunk`自体も元々含んでいない
 * ため、フィールド構成は実質同一（[ApapRequest]と同じくADR-0016のような明示的SPI境界は
 * 現時点では持たない）。
 */
data class ApapStreamChunk(
    val type: ApapStreamChunkType,
    val index: Int,
    val delta: ContentPart? = null,
    val toolCallDelta: ToolCall? = null,
    val usage: Usage? = null,
    val error: NormalizedError? = null,
)
