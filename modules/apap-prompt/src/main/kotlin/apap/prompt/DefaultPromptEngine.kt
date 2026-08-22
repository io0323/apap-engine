package apap.prompt

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.ProcessedPrompt

/** [PromptEngine]の実装: [pipeline]（既定は[PromptPipeline.default]）を実行して[ProcessedPrompt]化する。 */
class DefaultPromptEngine(
    private val pipeline: PromptPipeline = PromptPipeline.default(),
) : PromptEngine {
    override fun process(request: CanonicalRequest): ProcessedPrompt {
        val draft =
            PromptDraft(
                requestId = request.requestId,
                capabilityId = request.capabilityId,
                input = request.input,
                outputSchema = request.outputSchema,
            )
        val ctx = PromptStageContext(tenantId = request.tenantId, traceId = request.traceId)
        val result = pipeline.run(draft, ctx)
        return ProcessedPrompt(input = result.input)
    }
}
