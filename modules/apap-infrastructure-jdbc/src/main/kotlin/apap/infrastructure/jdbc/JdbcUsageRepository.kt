package apap.infrastructure.jdbc

import apap.domain.model.cost.UsageAggregate
import apap.domain.model.cost.UsageRecord
import apap.domain.model.vo.Cost
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.port.UsageRepository
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * [UsageRepository]のJDBC実装（ADR-0025）。`groupBy`は`providerId`/`modelId`/`capabilityId`/`status`の
 * フィールド名をサポートする（Port契約、`apap-infrastructure`のIn-Memory実装と同じ）。
 * 集計コストは`amount`のみを合算し、結果の`Cost.breakdown`は常に空とする
 * （In-Memory実装と同じ実装判断、要件充足に影響しないためADR化せずここに根拠を記す）。
 */
class JdbcUsageRepository(
    private val dataSource: DataSource,
) : UsageRepository {
    @Suppress("MagicNumber") // JDBC positional parameter indices, not meaningful as named constants.
    override fun append(record: UsageRecord) {
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO usage_record (
                        usage_id, request_id, tenant_id, capability_id, provider_id, model_id,
                        input_tokens, output_tokens, cost_amount, currency, duration_ms, status, occurred_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, record.usageId)
                    stmt.setString(2, record.requestId.value)
                    stmt.setString(3, record.tenantId.value)
                    stmt.setString(4, record.capabilityId.value)
                    stmt.setString(5, record.providerId.value)
                    stmt.setString(6, record.modelId.value)
                    stmt.setInt(7, record.usage.inputTokens.value)
                    stmt.setInt(8, record.usage.outputTokens.value)
                    stmt.setBigDecimal(9, record.cost.amount.amount)
                    stmt.setString(10, record.cost.amount.currency)
                    stmt.setLong(11, record.durationMs)
                    stmt.setString(12, record.status)
                    stmt.setTimestamp(13, Timestamp.from(record.occurredAt))
                    stmt.executeUpdate()
                }
        }
    }

    // MagicNumber: JDBC positional parameter index (3) for the WHERE-clause bind.
    // NestedBlockDepth: connection/statement/resultset/while is inherent to raw JDBC.
    @Suppress("MagicNumber", "NestedBlockDepth")
    override fun aggregate(
        tenantId: TenantId,
        period: Period,
        groupBy: List<String>,
    ): List<UsageAggregate> {
        val columns = groupBy.map(::columnFor)
        val selectList = (columns + AGGREGATE_SELECTS).joinToString(", ")
        val groupByClause = if (columns.isEmpty()) "" else "GROUP BY " + columns.joinToString(", ")
        val sql =
            """
            SELECT $selectList
            FROM usage_record
            WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ?
            $groupByClause
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, tenantId.value)
                stmt.setTimestamp(2, Timestamp.from(period.from))
                stmt.setTimestamp(3, Timestamp.from(period.to))
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<UsageAggregate>()
                    while (rs.next()) {
                        results += toAggregate(rs, groupBy)
                    }
                    return results
                }
            }
        }
    }

    private fun columnFor(field: String): String =
        GROUP_BY_COLUMNS[field] ?: throw IllegalArgumentException("Unsupported groupBy field: $field")

    private fun toAggregate(
        rs: ResultSet,
        groupBy: List<String>,
    ): UsageAggregate {
        val groupKey = groupBy.associateWith { field -> rs.getString(GROUP_BY_COLUMNS.getValue(field)) }
        val inputTokens = rs.getLong("sum_input_tokens")
        val outputTokens = rs.getLong("sum_output_tokens")
        val currency = rs.getString("any_currency") ?: "USD"
        val totalUsage = Usage.of(TokenCount(inputTokens.toInt()), TokenCount(outputTokens.toInt()))
        val totalCost = Cost(Money(rs.getBigDecimal("sum_cost") ?: BigDecimal.ZERO, currency))
        return UsageAggregate(
            groupKey = groupKey,
            requestCount = rs.getLong("request_count"),
            totalUsage = totalUsage,
            totalCost = totalCost,
        )
    }

    private companion object {
        val GROUP_BY_COLUMNS =
            mapOf(
                "providerId" to "provider_id",
                "modelId" to "model_id",
                "capabilityId" to "capability_id",
                "status" to "status",
            )
        val AGGREGATE_SELECTS =
            listOf(
                "COUNT(*) AS request_count",
                "SUM(input_tokens) AS sum_input_tokens",
                "SUM(output_tokens) AS sum_output_tokens",
                "SUM(cost_amount) AS sum_cost",
                "MIN(currency) AS any_currency",
            )
    }
}
