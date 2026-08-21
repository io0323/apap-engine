package apap.domain.model.audit

import apap.domain.model.vo.Cost
import apap.domain.model.vo.ErrorCode
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

class AuditRecordTest {
    private fun record(
        principal: String = "user-1",
        status: String = "COMPLETED",
        retries: Int = 0,
        fallbacks: Int = 0,
    ) = AuditRecord(
        auditId = "a1",
        requestId = RequestId(testUlid('A')),
        traceId = "trace-1",
        tenantId = TenantId(testUlid('B')),
        principal = principal,
        capabilityId = "chat",
        routingDecision = "{}",
        requestDigest = "digest",
        status = status,
        usage = Usage.of(TokenCount(1), TokenCount(1)),
        cost = Cost(Money(BigDecimal("0.001"), "USD")),
        durationMs = 10,
        retries = retries,
        fallbacks = fallbacks,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `rejects blank principal or status`() {
        assertThrows(IllegalArgumentException::class.java) { record(principal = " ") }
        assertThrows(IllegalArgumentException::class.java) { record(status = " ") }
    }

    @Test
    fun `rejects negative retries or fallbacks`() {
        assertThrows(IllegalArgumentException::class.java) { record(retries = -1) }
        assertThrows(IllegalArgumentException::class.java) { record(fallbacks = -1) }
    }

    @Test
    fun `rejects a negative durationMs`() {
        assertThrows(IllegalArgumentException::class.java) { record().copy(durationMs = -1) }
    }

    @Test
    fun `accepts a valid record`() {
        record()
    }

    @Test
    fun `AuditSearchCriteria holds the search dimensions used by AuditRepository search`() {
        val criteria =
            AuditSearchCriteria(
                fromInclusive = Instant.parse("2026-01-01T00:00:00Z"),
                toExclusive = Instant.parse("2026-02-01T00:00:00Z"),
                tenantId = TenantId(testUlid('A')),
                providerId = ProviderId(testUlid('B')),
                errorCode = ErrorCode.PROVIDER_ERROR,
                requestId = RequestId(testUlid('C')),
            )
        assertEquals(ErrorCode.PROVIDER_ERROR, criteria.errorCode)
        assertEquals(AuditSearchCriteria(), AuditSearchCriteria())
    }
}
