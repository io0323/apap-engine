package apap.routing

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.port.Clock
import apap.domain.port.PriceBookRepository
import apap.domain.service.cost.CostCalculationService

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
 * 単価未登録のModelはADR-0021により`null`を返す。[CandidateFactory]はこれをCandidate生成の
 * 段階で除外する（ペナルティでスコアを不利にするだけでは、唯一の候補である場合や他軸が優越する
 * 場合に選択され得てしまい、選択後の`apap.cost.DefaultCostEngine`が`PriceEntryNotFoundException`で
 * 未処理のまま失敗する経路を防げないため）。
 */
class RealCostEstimator(
    private val priceBookRepository: PriceBookRepository,
    private val clock: Clock,
    private val representativeInputTokens: Int = DEFAULT_REPRESENTATIVE_TOKENS,
    private val representativeOutputTokens: Int = DEFAULT_REPRESENTATIVE_TOKENS,
) : CostEstimator {
    override fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
    ): Money? {
        val priceEntry = priceBookRepository.findCurrentEntry(modelId, clock.now()) ?: return null
        val usage = Usage.of(TokenCount(representativeInputTokens), TokenCount(representativeOutputTokens))
        return CostCalculationService.calculate(usage, priceEntry).amount
    }

    private companion object {
        const val DEFAULT_REPRESENTATIVE_TOKENS = 1000
    }
}
