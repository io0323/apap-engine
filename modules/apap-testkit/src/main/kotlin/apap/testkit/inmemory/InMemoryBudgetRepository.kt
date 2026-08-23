package apap.testkit.inmemory

import apap.domain.model.cost.Budget
import apap.domain.model.vo.TenantId
import apap.domain.port.BudgetRepository

class InMemoryBudgetRepository : BudgetRepository {
    private val budgets = mutableMapOf<String, Budget>()

    override fun findByTenant(tenantId: TenantId): List<Budget> = budgets.values.filter { it.tenantId == tenantId }

    override fun save(budget: Budget) {
        budgets[budget.budgetId] = budget
    }
}
