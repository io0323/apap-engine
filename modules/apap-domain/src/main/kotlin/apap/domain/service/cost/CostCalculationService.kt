package apap.domain.service.cost

import apap.domain.model.cost.PriceEntry
import apap.domain.model.vo.Cost
import apap.domain.model.vo.Money
import apap.domain.model.vo.Usage
import java.math.BigDecimal
import java.math.RoundingMode

/** 04_ドメイン設計.md 4.6: Usage×PriceBook→Cost算出、期間按分。 */
object CostCalculationService {
    private const val PER_1K_TOKENS = 1000
    private val PER_1K = BigDecimal(PER_1K_TOKENS)
    private const val SCALE = 6

    fun calculate(
        usage: Usage,
        priceEntry: PriceEntry,
    ): Cost {
        val currency = priceEntry.inputPer1k.currency
        val inputCost =
            priceEntry.inputPer1k.amount
                .multiply(BigDecimal(usage.inputTokens.value))
                .divide(PER_1K, SCALE, RoundingMode.HALF_UP)
        val outputCost =
            priceEntry.outputPer1k.amount
                .multiply(BigDecimal(usage.outputTokens.value))
                .divide(PER_1K, SCALE, RoundingMode.HALF_UP)
        val breakdown =
            mapOf(
                "input" to Money(inputCost, currency),
                "output" to Money(outputCost, currency),
            )
        val total = breakdown.values.fold(Money.zero(currency)) { acc, part -> acc + part }
        return Cost(total, breakdown)
    }

    /**
     * 期間按分: 実際に消費された比率でコストを按分する。内訳を先に按分・丸め、合計は
     * 按分後の内訳の総和として算出することで、[Cost]の「内訳合計=amount」不変条件を保つ。
     */
    fun prorate(
        cost: Cost,
        fractionOfPeriod: Double,
    ): Cost {
        require(fractionOfPeriod in 0.0..1.0) { "fractionOfPeriod must be within 0.0..1.0: $fractionOfPeriod" }
        val factor = BigDecimal.valueOf(fractionOfPeriod)
        if (cost.breakdown.isEmpty()) {
            val prorated =
                cost.amount.amount
                    .multiply(factor)
                    .setScale(SCALE, RoundingMode.HALF_UP)
            return Cost(Money(prorated, cost.amount.currency))
        }
        val proratedBreakdown =
            cost.breakdown.mapValues { (_, money) ->
                Money(money.amount.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP), money.currency)
            }
        val proratedAmount = proratedBreakdown.values.fold(Money.zero(cost.amount.currency)) { acc, part -> acc + part }
        return Cost(proratedAmount, proratedBreakdown)
    }
}
