package apap.infrastructure.jdbc

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** ローカルPostgreSQL（`docker compose -f tools/docker-compose.yaml up -d rdbms`）に対する統合テスト。 */
class JdbcAuditRepositoryTest {
    private lateinit var repo: JdbcAuditRepository
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")

    @BeforeEach
    fun setUp() {
        repo = JdbcAuditRepository(JdbcTestSupport.freshDataSource())
    }

    private fun record(
        id: String,
        tenant: TenantId,
        provider: ProviderId?,
        routingDecision: String = "policy=default; chain=a->b; reason=score",
    ) = AuditRecord(
        auditId = id,
        requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA2"),
        traceId = "trace-1",
        tenantId = tenant,
        principal = "user-1",
        capabilityId = "chat",
        providerId = provider,
        routingDecision = routingDecision,
        requestDigest = "digest",
        status = "COMPLETED",
        usage = Usage.of(TokenCount(10), TokenCount(5)),
        cost = Cost(Money(BigDecimal("0.05"), "USD")),
        durationMs = 42,
        retries = 1,
        fallbacks = 0,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `append then search round-trips every field, including the free-text routingDecision`() {
        repo.append(record("a1", tenantId, providerId))

        val found = repo.search(AuditSearchCriteria(tenantId = tenantId)).single()

        assertEquals("a1", found.auditId)
        assertEquals("policy=default; chain=a->b; reason=score", found.routingDecision)
        assertEquals(10, found.usage.inputTokens.value)
        assertEquals(0, BigDecimal("0.05").compareTo(found.cost.amount.amount))
        assertEquals(1, found.retries)
    }

    @Test
    fun `search filters by tenant and provider`() {
        repo.append(record("a1", tenantId, providerId))
        repo.append(record("a2", TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA3"), providerId))

        val results = repo.search(AuditSearchCriteria(tenantId = tenantId))

        assertEquals(listOf("a1"), results.map { it.auditId })
    }
}
