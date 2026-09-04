package apap.prompt

import apap.domain.model.execution.InputMessage
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.RequestId

/**
 * 03_基本設計.md 3.3.6 `PromptStage.apply(draft, ctx): draft` の`draft`本体。
 * `Pipeline`（Validation→Optimization→Rendering）の各Stageが読み書きする作業中の入力表現。
 * 最終的に[apap.domain.model.execution.ProcessedPrompt]へ変換される（[DefaultPromptEngine]参照）。
 */
data class PromptDraft(
    val requestId: RequestId,
    val capabilityId: CapabilityId,
    val input: List<ContentPart>,
    val outputSchema: String? = null,
    val templateVariables: Map<String, String> = emptyMap(),
    /**
     * role付きの発話列（ADR-0031）。[input]はこれを平坦化したもの。
     * Stageは両方を一貫して更新すること——片方だけ変えるとProviderへ渡る内容と
     * トークン計上がずれる。
     */
    val messages: List<InputMessage> = InputMessage.userOnly(input),
)
