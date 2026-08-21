package apap.domain.model.cost

import apap.domain.model.vo.Money
import apap.domain.model.vo.TenantId

/** 12_ER図.md QUOTA_POLICY: limit_requests / limit_tokens / limit_cost。 */
data class QuotaLimits(
    val requests: Long? = null,
    val tokens: Long? = null,
    val cost: Money? = null,
) {
    init {
        requests?.let { require(it >= 0) { "requests must not be negative: $it" } }
        tokens?.let { require(it >= 0) { "tokens must not be negative: $it" } }
    }
}

/** 04_ドメイン設計.md 4.3.5 QuotaPolicy Aggregate（Root）。 */
data class QuotaPolicy(
    val quotaId: String,
    val tenantId: TenantId,
    val scope: String,
    val period: RecurringPeriodType,
    val limits: QuotaLimits,
) {
    init {
        require(scope.isNotBlank()) { "scope must not be blank" }
    }
}
