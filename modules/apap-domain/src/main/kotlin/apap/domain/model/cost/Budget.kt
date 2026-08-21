package apap.domain.model.cost

import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.TenantId

/**
 * 04_ドメイン設計.md 4.3.5 Budget Aggregate（Root）。
 * 不変条件: consumedは単調増加（期間リセットのみ）。[Money]は負値を持てないため、
 * [consume]による加算は常にconsumedを増加（または不変）させる。値を減少させる唯一の手段は
 * [resetForNewPeriod]（期間リセット）であり、それ以外の直接的な減少手段は公開しない。
 */
data class Budget(
    val budgetId: String,
    val tenantId: TenantId,
    val periodType: RecurringPeriodType,
    val currentWindow: Period,
    val limit: Money,
    val thresholds: List<Int> = listOf(DEFAULT_THRESHOLD_WARNING, DEFAULT_THRESHOLD_CRITICAL),
    val consumed: Money,
) {
    init {
        require(consumed.currency == limit.currency) {
            "consumed and limit must share the same currency: ${consumed.currency} vs ${limit.currency}"
        }
        // consumedはlimitを超過しうる（CostThresholdExceeded等で超過状態を検知するため、
        // ここでは上限超過そのものを不変条件違反として拒否しない）。
        require(thresholds.all { it in 1..MAX_THRESHOLD_PERCENT }) {
            "thresholds must be within 1..$MAX_THRESHOLD_PERCENT: $thresholds"
        }
    }

    fun consume(additional: Money): Budget {
        require(additional.currency == limit.currency) {
            "additional must share Budget's currency: ${additional.currency} vs ${limit.currency}"
        }
        return copy(consumed = consumed + additional)
    }

    private val zeroConsumed: Money get() = Money.zero(limit.currency)

    fun resetForNewPeriod(newWindow: Period): Budget = copy(currentWindow = newWindow, consumed = zeroConsumed)

    companion object {
        private const val DEFAULT_THRESHOLD_WARNING = 80
        private const val DEFAULT_THRESHOLD_CRITICAL = 100
        private const val MAX_THRESHOLD_PERCENT = 1000
    }
}
