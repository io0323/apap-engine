package apap.domain.model.vo

/**
 * 04_ドメイン設計.md 4.4: amount(Decimal), currency。負値不可、通貨混合演算不可。
 * `breakdown` は3.3.1 CanonicalResponse.cost の内訳（例: input/output別）。指定時は
 * amountと同一通貨であり、内訳の合計がamountと一致すること。
 */
data class Cost(
    val amount: Money,
    val breakdown: Map<String, Money> = emptyMap(),
) {
    init {
        breakdown.values.forEach { part ->
            require(part.currency == amount.currency) {
                "Cost breakdown entries must share amount's currency (${amount.currency}): found ${part.currency}"
            }
        }
        if (breakdown.isNotEmpty()) {
            val sum = breakdown.values.fold(Money.zero(amount.currency)) { acc, part -> acc + part }
            require(sum == amount) { "Cost breakdown must sum to amount: breakdown sum=$sum, amount=$amount" }
        }
    }
}
