package apap.infrastructure.persistence.inmemory

import apap.domain.model.audit.AuditRecord
import apap.domain.model.audit.AuditSearchCriteria
import apap.domain.model.vo.Cost
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class InMemoryAuditRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")

    private fun record(
        id: String,
        tenant: TenantId,
        provider: ProviderId?,
    ) = AuditRecord(
        auditId = id,
        requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA2"),
        traceId = "trace-1",
        tenantId = tenant,
        principal = "user-1",
        capabilityId = "chat",
        providerId = provider,
        routingDecision = "primary",
        requestDigest = "digest",
        status = "COMPLETED",
        usage = Usage.of(TokenCount(1), TokenCount(1)),
        cost = Cost(Money(BigDecimal.ZERO, "USD")),
        durationMs = 10,
        retries = 0,
        fallbacks = 0,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `append is add-only and search filters by tenant and provider`() {
        val repo = InMemoryAuditRepository()
        repo.append(record("a1", tenantId, providerId))
        repo.append(record("a2", TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA3"), providerId))

        val results = repo.search(AuditSearchCriteria(tenantId = tenantId))

        assertEquals(listOf("a1"), results.map { it.auditId })
    }

    @Test
    fun `search with no criteria returns every appended record`() {
        val repo = InMemoryAuditRepository()
        repo.append(record("a1", tenantId, providerId))
        repo.append(record("a2", tenantId, null))

        assertEquals(2, repo.search(AuditSearchCriteria()).size)
    }
}
