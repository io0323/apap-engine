package apap.infrastructure.jdbc

import apap.domain.model.cost.UsageRecord
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** ローカルPostgreSQL（`docker compose -f tools/docker-compose.yaml up -d rdbms`）に対する統合テスト。 */
class JdbcUsageRepositoryTest {
    private lateinit var repo: JdbcUsageRepository
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")

    @BeforeEach
    fun setUp() {
        val dataSource = JdbcTestSupport.freshDataSource()
        repo = JdbcUsageRepository(dataSource)
        // usage_record.provider_id/model_id carry FK constraints per 12章 ER図.md (Provider/Model
        // JDBC repositories aren't implemented yet, so insert the minimal referenced rows directly).
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO plugin (plugin_id, name, version, spi_version, signature, status) " +
                        "VALUES ('01ARZ3NDEKTSV4RRFFQ69G5FAB', 'p', '1.0.0', '1.0', 'sig', 'LOADED')",
                ).use { it.executeUpdate() }
            conn
                .prepareStatement(
                    "INSERT INTO provider (provider_id, name, adapter_plugin_id, spi_version, auth_type, priority, " +
                        "status, rate_limit_rpm, rate_limit_tpm, rate_limit_concurrent, regions, created_at, updated_at) " +
                        "VALUES (?, 'test-provider', '01ARZ3NDEKTSV4RRFFQ69G5FAB', '1.0', 'api_key', 50, 'ACTIVE', " +
                        "60, 100000, 10, '[]', now(), now())",
                ).use { stmt ->
                    stmt.setString(1, providerId.value)
                    stmt.executeUpdate()
                }
            conn
                .prepareStatement(
                    "INSERT INTO model (model_id, provider_id, model_name, version, context_window, " +
                        "max_output_tokens, status, priority) VALUES (?, ?, 'm', 'v1', 8000, 1000, 'ACTIVE', 50)",
                ).use { stmt ->
                    stmt.setString(1, modelId.value)
                    stmt.setString(2, providerId.value)
                    stmt.executeUpdate()
                }
        }
    }

    private fun record(
        id: String,
        occurredAt: Instant,
        amount: String,
    ) = UsageRecord(
        usageId = id,
        requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA3"),
        tenantId = tenantId,
        capabilityId = CapabilityId("chat"),
        providerId = providerId,
        modelId = modelId,
        usage = Usage.of(TokenCount(10), TokenCount(5)),
        cost = Cost(Money(BigDecimal(amount), "USD")),
        durationMs = 100,
        status = "SUCCESS",
        occurredAt = occurredAt,
    )

    @Test
    fun `aggregate sums cost and token usage within the period, grouped by provider`() {
        repo.append(record("u1", Instant.parse("2026-01-10T00:00:00Z"), "1.00"))
        repo.append(record("u2", Instant.parse("2026-01-15T00:00:00Z"), "2.00"))
        // Outside the period.
        repo.append(record("u3", Instant.parse("2026-02-01T00:00:00Z"), "5.00"))

        val aggregates =
            repo.aggregate(
                tenantId,
                Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")),
                listOf("providerId"),
            )

        assertEquals(1, aggregates.size)
        val aggregate = aggregates.single()
        assertEquals(2L, aggregate.requestCount)
        assertEquals(providerId.value, aggregate.groupKey["providerId"])
        assertEquals(0, BigDecimal("3.00").compareTo(aggregate.totalCost.amount.amount))
        assertEquals(20, aggregate.totalUsage.inputTokens.value)
    }
}
