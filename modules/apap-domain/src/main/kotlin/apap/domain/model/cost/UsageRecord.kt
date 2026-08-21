package apap.domain.model.cost

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage
import java.time.Instant

/** 12_ER図.md USAGE_RECORD: Cost/Analytics Query用の集計Read Modelの元データ。 */
data class UsageRecord(
    val usageId: String,
    val requestId: RequestId,
    val tenantId: TenantId,
    val capabilityId: CapabilityId,
    val providerId: ProviderId,
    val modelId: ModelId,
    val usage: Usage,
    val cost: Cost,
    val durationMs: Long,
    val status: String,
    val occurredAt: Instant,
) {
    init {
        require(durationMs >= 0) { "durationMs must not be negative: $durationMs" }
        require(status.isNotBlank()) { "status must not be blank" }
    }
}

/** `UsageRepository.aggregate` の集計結果1行分。 */
data class UsageAggregate(
    val groupKey: Map<String, String>,
    val requestCount: Long,
    val totalUsage: Usage,
    val totalCost: Cost,
)
