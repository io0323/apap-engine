package apap.prompt

/** Pipelineの1段目（Validation）。検証失敗時は[PromptValidationFailedException]を送出する。 */
class ValidationStage(
    private val validator: PromptValidator,
) : PromptStage {
    override fun apply(
        draft: PromptDraft,
        ctx: PromptStageContext,
    ): PromptDraft {
        validator.validate(draft)
        return draft
    }
}
