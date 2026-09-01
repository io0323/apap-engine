package apap.infrastructure.persistence.inmemory

import apap.domain.model.cost.QuotaLimits
import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.cost.RecurringPeriodType
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InMemoryQuotaPolicyRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val otherTenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA1")

    private fun policy(
        id: String,
        tenant: TenantId,
    ) = QuotaPolicy(
        quotaId = id,
        tenantId = tenant,
        scope = "tenant",
        period = RecurringPeriodType.MONTHLY,
        limits = QuotaLimits(requests = 1000L),
    )

    @Test
    fun `findByTenant returns only policies for that tenant`() {
        val repo = InMemoryQuotaPolicyRepository()
        repo.save(policy("q1", tenantId))
        repo.save(policy("q2", otherTenantId))

        assertEquals(listOf("q1"), repo.findByTenant(tenantId).map { it.quotaId })
    }
}
