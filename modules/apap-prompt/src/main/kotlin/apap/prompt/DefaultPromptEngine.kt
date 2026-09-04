package apap.prompt

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.InputMessage
import apap.domain.model.execution.ProcessedPrompt

/** [PromptEngine]の実装: [pipeline]（既定は[PromptPipeline.default]）を実行して[ProcessedPrompt]化する。 */
class DefaultPromptEngine(
    private val pipeline: PromptPipeline = PromptPipeline.default(),
) : PromptEngine {
    override fun process(request: CanonicalRequest): ProcessedPrompt {
        // messagesを正とし、inputはその平坦化として揃える。両者がずれると
        // 「Providerには古い内容、トークン計上は新しい内容」のような分かりにくい不整合になる。
        val messages = request.messages.ifEmpty { InputMessage.userOnly(request.input) }
        val draft =
            PromptDraft(
                requestId = request.requestId,
                capabilityId = request.capabilityId,
                input = messages.flatMap { it.content },
                messages = messages,
                outputSchema = request.outputSchema,
            )
        val ctx = PromptStageContext(tenantId = request.tenantId, traceId = request.traceId)
        val result = pipeline.run(draft, ctx)
        return ProcessedPrompt(input = result.input, messages = result.messages)
    }
}
