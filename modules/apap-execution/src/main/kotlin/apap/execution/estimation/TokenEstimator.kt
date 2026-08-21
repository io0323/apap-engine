package apap.execution.estimation

import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TokenCount
import apap.domain.port.ProviderRepository
import apap.domain.service.execution.TokenEstimationConfig
import apap.domain.service.execution.TokenEstimationService
import apap.domain.service.routing.Candidate
import apap.provider.AdapterRegistry

/**
 * 02_システム仕様.md 2.8 step7（Quota事前チェック用の推定トークン算出）。ADR-0009/0010:
 * Adapterが[apap.adapter.spi.ProviderAdapter.estimateTokens]を提供する場合はそれをEXACTとして
 * 優先し、そうでなければ[TokenEstimationService.estimateHeuristic]でHEURISTIC推定する。
 *
 * `ResponseMapper`（応答正規化後）ではなく、Provider呼出**前**のこの事前チェック段階で使うのが
 * ADR-0009/0010の対象（AttemptExecutor.ktのKDoc参照: `AdapterResponse.usage`は必須項目であり
 * Adapterが自ら`estimated`を付与する契約のため、応答側では再推定しない）。
 */
class TokenEstimator(
    private val providerRepository: ProviderRepository,
    private val adapterRegistry: AdapterRegistry,
    private val config: TokenEstimationConfig = TokenEstimationConfig(),
) {
    suspend fun estimate(
        candidate: Candidate,
        modelId: ModelId,
        prompt: ProcessedPrompt,
    ): TokenCount {
        val provider = providerRepository.findById(candidate.providerId)
        val exact = provider?.let { adapterRegistry.resolve(it.adapterPluginId).adapter.estimateTokens(prompt.input) }
        return if (exact != null) {
            TokenEstimationService.estimateExact(exact.value, config)
        } else {
            TokenEstimationService.estimateHeuristic(toText(prompt.input), modelId, config)
        }
    }

    private fun toText(parts: List<ContentPart>): String =
        parts.joinToString(separator = " ") { part ->
            when (part) {
                is ContentPart.Text -> part.text
                // 非テキストmodalityは文字数ベースの近似に寄与しないため固定長のプレースホルダで代用する
                // （厳密なmodality別トークン算出は本フェーズの対象外）。
                else -> NON_TEXT_PLACEHOLDER
            }
        }

    private companion object {
        const val NON_TEXT_PLACEHOLDER = "____"
    }
}
