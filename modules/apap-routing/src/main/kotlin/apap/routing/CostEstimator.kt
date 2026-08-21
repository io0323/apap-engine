package apap.routing

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId

/**
 * 02_システム仕様.md 2.5.2 S_cost算出に使う候補の推定コスト。本格的な単価管理・トークン推定
 * （PriceBook、Tokenizer）はapap-cost/Prompt Engineの責務であり本モジュールの対象外のため、
 * 3.8 Strategy一覧の思想（差替可能なStrategy）にならい、既定実装[ZeroCostEstimator]は
 * 常にゼロコストを返す（全候補が同一コストとなり、実質S_costはスコアに差を与えない）。
 * 将来apap-costが実装された際は、この口へ実コスト算出実装を差し替える。
 */
interface CostEstimator {
    fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
    ): Money
}

class ZeroCostEstimator(
    private val currency: String = DEFAULT_CURRENCY,
) : CostEstimator {
    override fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
    ): Money = Money.zero(currency)

    companion object {
        private const val DEFAULT_CURRENCY = "USD"
    }
}
