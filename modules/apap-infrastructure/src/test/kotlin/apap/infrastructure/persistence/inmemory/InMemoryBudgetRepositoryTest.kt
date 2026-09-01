package apap.infrastructure.persistence.inmemory

import apap.domain.model.cost.Budget
import apap.domain.model.cost.RecurringPeriodType
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class InMemoryBudgetRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val otherTenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA1")

    private fun budget(
        id: String,
        tenant: TenantId,
    ) = Budget(
        budgetId = id,
        tenantId = tenant,
        periodType = RecurringPeriodType.MONTHLY,
        currentWindow = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")),
        limit = Money(BigDecimal("1000.00"), "USD"),
        consumed = Money.zero("USD"),
    )

    @Test
    fun `findByTenant returns only budgets for that tenant`() {
        val repo = InMemoryBudgetRepository()
        repo.save(budget("b1", tenantId))
        repo.save(budget("b2", otherTenantId))

        val found = repo.findByTenant(tenantId)
        assertEquals(1, found.size)
        assertEquals("b1", found.single().budgetId)
    }

    @Test
    fun `save overwrites an existing budget by id`() {
        val repo = InMemoryBudgetRepository()
        repo.save(budget("b1", tenantId))
        repo.save(budget("b1", tenantId).consume(Money(BigDecimal("50.00"), "USD")))

        val consumedAmount =
            repo
                .findByTenant(tenantId)
                .single()
                .consumed
                .amount
        assertTrue(consumedAmount.compareTo(BigDecimal("50.00")) == 0)
    }
}
