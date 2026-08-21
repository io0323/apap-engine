package apap.testkit.inmemory

import apap.domain.model.cost.UsageAggregate
import apap.domain.model.cost.UsageRecord
import apap.domain.model.vo.Cost
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage
import apap.domain.port.UsageRepository

/**
 * `groupBy`は`providerId`/`modelId`/`capabilityId`/`status`のフィールド名をサポートする。
 * 集計コストは`amount`のみを合算し（`breakdown`はレコード間でキー構成が揃うとは限らないため）、
 * 結果の`Cost.breakdown`は常に空とする（テストダブルの簡略化。CLAUDE.md「ADR化するか否かの判断基準」:
 * 要件充足に影響しない集計方式の詳細）。
 */
class InMemoryUsageRepository : UsageRepository {
    private val records = mutableListOf<UsageRecord>()

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
