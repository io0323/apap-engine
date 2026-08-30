package apap.infrastructure.persistence.inmemory

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
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class InMemoryUsageRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")

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
    fun `aggregate sums cost and usage within the period, grouped by field, excluding out-of-period records`() {
        val repo = InMemoryUsageRepository()
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
        assertEquals(0, BigDecimal("3.00").compareTo(aggregate.totalCost.amount.amount))
    }
}
