package apap.infrastructure.persistence.inmemory

import apap.domain.model.cost.UsageAggregate
import apap.domain.model.cost.UsageRecord
import apap.domain.model.vo.Cost
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage
import apap.domain.port.UsageRepository
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [UsageRepository]の本番用In-Memory実装。`groupBy`は`providerId`/`modelId`/`capabilityId`/`status`の
 * フィールド名をサポートする。集計コストは`amount`のみを合算し（`breakdown`はレコード間でキー構成が
 * 揃うとは限らないため）、結果の`Cost.breakdown`は常に空とする。要件充足に影響しない実装判断の
 * ためADR化せずここに根拠を記す。
 */
class InMemoryUsageRepository : UsageRepository {
    private val records = CopyOnWriteArrayList<UsageRecord>()

    override fun append(record: UsageRecord) {
        records.add(record)
    }

    override fun aggregate(
        tenantId: TenantId,
        period: Period,
        groupBy: List<String>,
    ): List<UsageAggregate> {
        val filtered =
            records.filter { record ->
                record.tenantId == tenantId &&
                    !record.occurredAt.isBefore(period.from) &&
                    record.occurredAt.isBefore(period.to)
            }
        return filtered
            .groupBy { record -> groupBy.associateWith { field -> fieldValue(record, field) } }
            .map { (groupKey, group) ->
                val firstRecord = group.first()
                val currency = firstRecord.cost.amount.currency
                UsageAggregate(
                    groupKey = groupKey,
                    requestCount = group.size.toLong(),
                    totalUsage = group.map { it.usage }.reduce(::combineUsage),
                    totalCost = Cost(group.fold(Money.zero(currency)) { acc, record -> acc + record.cost.amount }),
                )
            }
    }

    private fun fieldValue(
        record: UsageRecord,
        field: String,
    ): String =
        when (field) {
            "providerId" -> record.providerId.value
            "modelId" -> record.modelId.value
            "capabilityId" -> record.capabilityId.value
            "status" -> record.status
            else -> throw IllegalArgumentException("Unsupported groupBy field: $field")
        }

    private fun combineUsage(
        a: Usage,
        b: Usage,
    ): Usage =
        Usage.of(
            inputTokens = a.inputTokens + b.inputTokens,
            outputTokens = a.outputTokens + b.outputTokens,
            estimated = a.estimated || b.estimated,
        )
}
