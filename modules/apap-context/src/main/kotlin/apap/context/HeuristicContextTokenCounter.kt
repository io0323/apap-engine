package apap.context

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TokenCount
import apap.domain.service.execution.TokenEstimationConfig
import apap.domain.service.execution.TokenEstimationMode
import apap.domain.service.execution.TokenEstimationService

/**
 * ADR-0009のHEURISTIC推定（文字数ベース近似）を使う[ContextTokenCounter]。安全マージンは
 * ここでは適用しない（生カウントを返す。マージンは[apap.domain.service.conversation.ContextAssemblyService.computeBudget]が
 * budget側で一括適用するため、chunk単位の計上で二重適用しない）。
 */
class HeuristicContextTokenCounter(
    private val modelId: ModelId,
    private val config: TokenEstimationConfig = TokenEstimationConfig(),
) : ContextTokenCounter {
    override val mode: TokenEstimationMode = TokenEstimationMode.HEURISTIC

    override fun count(parts: List<ContentPart>): TokenCount {
        val text =
            parts.joinToString(separator = " ") { part -> if (part is ContentPart.Text) part.text else PLACEHOLDER }
        return TokenCount(TokenEstimationService.rawHeuristicTokenCount(text, modelId, config))
    }

    private companion object {
        // 非テキストmodalityは文字数ベースの近似に寄与しないため固定長のプレースホルダで代用する
        // （apap.execution.estimation.TokenEstimatorと同じ判断）。
        const val PLACEHOLDER = "____"
    }
}
