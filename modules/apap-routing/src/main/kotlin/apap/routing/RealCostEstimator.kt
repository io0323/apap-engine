package apap.routing

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.port.Clock
import apap.domain.port.PriceBookRepository
import apap.domain.service.cost.CostCalculationService
import java.math.BigDecimal

/**
 * [CostEstimator]の既定実装（[ZeroCostEstimator]の差替先）。FR-RTE-002: S_costが実効性を持つように
 * `apap.domain.port.PriceBookRepository`から実単価を引く。
 *
 * ここで使う「トークン数」は実prompt依存の推定ではなく、Routing候補間の**相対比較用の代表値**
 * （既定入力/出力各1000トークン、設定可能）である。Routing完了前（`contextualPrompt`確定前）に
 * 呼ばれるため実トークン数は原理的に得られず、実prompt依存の正確な推定は
 * `apap.cost.CostEngine.estimate`（実行直前、apap-cost）の責務とする
 * （要件充足に影響しない実装判断のためADR化せず根拠をここに残す）。
 *
 * 単価未登録のModelは、タスク要求「ルーティング候補にできない」を完全除外ではなく
 * [unpricedModelPenalty]（既定で他候補より十分高い値）を返すことで満たす:
 * `RoutingDomainService.computeScores`のmin-max正規化によりS_costが最劣後になり、
 * cost最適化時に選ばれにくくなる（0除外ではなくスコアで不利にする設計、要件充足に影響しない
 * 実装判断のためADR化せず根拠をここに残す）。
 */
class RealCostEstimator(
    private val priceBookRepository: PriceBookRepository,
    private val clock: Clock,
    private val representativeInputTokens: Int = DEFAULT_REPRESENTATIVE_TOKENS,
    private val representativeOutputTokens: Int = DEFAULT_REPRESENTATIVE_TOKENS,
    private val unpricedModelPenalty: Money = Money(BigDecimal(UNPRICED_PENALTY_AMOUNT), DEFAULT_CURRENCY),
) : CostEstimator {
    override fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
    ): Money {
        val priceEntry = priceBookRepository.findCurrentEntry(modelId, clock.now()) ?: return unpricedModelPenalty
        val usage = Usage.of(TokenCount(representativeInputTokens), TokenCount(representativeOutputTokens))
        return CostCalculationService.calculate(usage, priceEntry).amount
    }

    companion object {
        private const val DEFAULT_REPRESENTATIVE_TOKENS = 1000
        private const val UNPRICED_PENALTY_AMOUNT = "1000000"
        private const val DEFAULT_CURRENCY = "USD"
    }
}
