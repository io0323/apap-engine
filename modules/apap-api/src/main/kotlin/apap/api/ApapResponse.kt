package apap.api

import apap.domain.model.execution.ToolCall
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.Cost
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.Usage

/**
 * [apap.runtime.ApapEngine.execute]の公開応答型。
 *
 * CLAUDE.md不変条件3（公開APIにProvider/Model物理名を露出しない。応答にも既定で含めない）により、
 * 内部表現`apap.domain.model.execution.CanonicalResponse`が持つ`resolvedProvider`/`resolvedModel`
 * （`ProviderId`/`ModelId`）を意図的に含めない。どのProvider/Modelで処理されたかはAudit Log側で
 * 追跡する（FR-OBS-001）ものであり、実行応答そのものの公開契約には含めない。
 */
data class ApapResponse(
    val responseId: String,
    val requestId: String,
    val output: List<ContentPart>,
    val toolCalls: List<ToolCall>? = null,
    val finishReason: FinishReason,
    val usage: Usage,
    val cost: Cost,
    val cached: Boolean = false,
)
