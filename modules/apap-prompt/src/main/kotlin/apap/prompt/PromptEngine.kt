package apap.prompt

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.ProcessedPrompt

/**
 * 03_基本設計.md 3.3.6 `PromptEngine`: Prompt Pipeline（Validation→Optimization→Rendering）。
 * [DefaultPromptEngine]が[PromptPipeline]を実行する実装を提供する。
 */
interface PromptEngine {
    fun process(request: CanonicalRequest): ProcessedPrompt
}
