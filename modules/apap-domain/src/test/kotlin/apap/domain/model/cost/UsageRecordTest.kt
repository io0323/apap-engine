package apap.domain.model.cost

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class UsageRecordTest {
    private fun record(
        durationMs: Long = 100,
        status: String = "COMPLETED",
    ) = UsageRecord(
        usageId = "u1",
        requestId = RequestId(testUlid('A')),
        tenantId = TenantId(testUlid('B')),
        capabilityId = CapabilityId("chat"),
        providerId = ProviderId(testUlid('C')),
        modelId = ModelId(testUlid('D')),
        usage = Usage.of(TokenCount(10), TokenCount(20)),
        cost = Cost(Money(BigDecimal("0.01"), "USD")),
        durationMs = durationMs,
        status = status,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `rejects negative durationMs`() {
        assertThrows(IllegalArgumentException::class.java) { record(durationMs = -1) }
    }

    @Test
    fun `rejects blank status`() {
        assertThrows(IllegalArgumentException::class.java) { record(status = " ") }
    }

    @Test
    fun `accepts a valid record`() {
        record()
    }

    @Test
    fun `UsageAggregate holds a group key plus totals as returned by UsageRepository aggregate`() {
        val aggregate =
            UsageAggregate(
                groupKey = mapOf("provider" to "p1"),
                requestCount = 5,
                totalUsage = Usage.of(TokenCount(50), TokenCount(100)),
                totalCost = Cost(Money(BigDecimal("0.05"), "USD")),
            )
        assertEquals(5L, aggregate.requestCount)
        assertEquals("p1", aggregate.groupKey["provider"])
    }
}
