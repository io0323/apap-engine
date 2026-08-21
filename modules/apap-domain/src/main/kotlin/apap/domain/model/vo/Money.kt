package apap.domain.model.vo

import java.math.BigDecimal

/**
 * 04_ドメイン設計.md 4.4: amount(Decimal), currency。負値不可、通貨混合演算不可。
 */
data class Money(
    val amount: BigDecimal,
    val currency: String,
) {
    init {
        require(amount.signum() >= 0) { "Money amount must not be negative: $amount" }
        require(CURRENCY_PATTERN.matches(currency)) { "currency must be an ISO 4217 3-letter code: $currency" }
    }

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount + other.amount, currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount - other.amount, currency)
    }

    operator fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Cannot operate on Money with different currencies: $currency vs ${other.currency}"
        }
    }

    // BigDecimal.equals()はスケール差(1.0 vs 1.00)を別値として扱うため、
    // data classが自動生成するequals/hashCodeでは値として等しいMoneyが不等になる。
    // 通貨とamountの数値としての等価性で比較するよう明示的に上書きする。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        return currency == other.currency && amount.compareTo(other.amount) == 0
    }

    override fun hashCode(): Int = 31 * currency.hashCode() + amount.stripTrailingZeros().hashCode()

    companion object {
        private val CURRENCY_PATTERN = Regex("^[A-Z]{3}$")

        fun zero(currency: String): Money = Money(BigDecimal.ZERO, currency)
    }
}
