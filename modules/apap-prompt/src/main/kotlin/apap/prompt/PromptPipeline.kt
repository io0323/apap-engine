package apap.prompt

/**
 * 03_基本設計.md 3.3.6 `PromptEngine`内部Pipeline（List<PromptStage>）。既定はValidation→
 * Optimization→Renderingの順（[DefaultPromptEngine]の組立参照）だが、[withStageAt]で
 * 16_拡張ポイント.md 16.7が求める「任意位置への挿入」ができる。
 */
class PromptPipeline(
    private val stages: List<PromptStage>,
) {
    fun run(
        draft: PromptDraft,
        ctx: PromptStageContext,
    ): PromptDraft = stages.fold(draft) { current, stage -> stage.apply(current, ctx) }

    /** [index]の位置に[stage]を挿入した新しいPipelineを返す（既存Pipelineは不変）。 */
    fun withStageAt(
        index: Int,
        stage: PromptStage,
    ): PromptPipeline {
        val newStages = stages.toMutableList().apply { add(index, stage) }
        return PromptPipeline(newStages)
    }

    companion object {
        /** 既定のValidation→Optimization→Rendering順のPipeline。 */
        fun default(
            validator: PromptValidator = PromptValidator(),
            optimizer: PromptOptimizer = PromptOptimizer(),
            renderingStage: PromptStage = RenderingStage(),
        ): PromptPipeline =
            PromptPipeline(
                listOf(ValidationStage(validator), OptimizationStage(optimizer), renderingStage),
            )
    }
}
