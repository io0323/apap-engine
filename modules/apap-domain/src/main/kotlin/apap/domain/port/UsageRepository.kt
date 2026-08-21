package apap.domain.port

import apap.domain.model.cost.UsageAggregate
import apap.domain.model.cost.UsageRecord
import apap.domain.model.vo.Period
import apap.domain.model.vo.TenantId

interface UsageRepository {
    fun append(record: UsageRecord)

    fun aggregate(
        tenantId: TenantId,
        period: Period,
        groupBy: List<String>,
    ): List<UsageAggregate>
}
