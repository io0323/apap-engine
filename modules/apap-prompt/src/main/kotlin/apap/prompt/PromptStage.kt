package apap.prompt

import apap.domain.model.vo.TenantId

/** [PromptStage.apply]呼出時に渡すリクエストスコープの文脈情報。 */
data class PromptStageContext(
    val tenantId: TenantId,
    val traceId: String,
)

/**
 * 03_基本設計.md 3.3.6 / 16_拡張ポイント.md 16.7: Prompt Pipelineの拡張点。
 * 任意の位置へ挿入可能なSPI（[PromptPipeline.withStageAt]参照）。
 */
fun interface PromptStage {
    fun apply(
        draft: PromptDraft,
        ctx: PromptStageContext,
    ): PromptDraft
}
