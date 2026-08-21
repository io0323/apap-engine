package apap.domain.event

import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount

/** 14_イベント一覧.md 14.3 制限・コスト系。 */
data class RateLimitExceeded(
    override val meta: EventMetadata,
    val scope: String,
    val tenantId: TenantId? = null,
    val providerId: ProviderId? = null,
) : DomainEvent

data class TokenLimitExceeded(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val limit: TokenCount,
    val actual: TokenCount,
) : DomainEvent

data class QuotaExceeded(
    override val meta: EventMetadata,
    val tenantId: TenantId,
    val quotaId: String,
    val dimension: String,
) : DomainEvent

data class CostThresholdExceeded(
    override val meta: EventMetadata,
    val tenantId: TenantId,
    val budgetId: String,
    val threshold: Int,
    val consumed: Money,
) : DomainEvent

data class BudgetPeriodReset(
    override val meta: EventMetadata,
    val budgetId: String,
) : DomainEvent
