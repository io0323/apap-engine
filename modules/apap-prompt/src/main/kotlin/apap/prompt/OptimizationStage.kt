package apap.prompt

/** Pipelineの2段目（Optimization）。 */
class OptimizationStage(
    private val optimizer: PromptOptimizer,
) : PromptStage {
    override fun apply(
        draft: PromptDraft,
        ctx: PromptStageContext,
    ): PromptDraft = optimizer.optimize(draft)
}
