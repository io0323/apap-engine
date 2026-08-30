package apap.infrastructure.persistence.inmemory

import apap.domain.model.cost.Budget
import apap.domain.model.vo.TenantId
import apap.domain.port.BudgetRepository
import java.util.concurrent.ConcurrentHashMap

/** [BudgetRepository]の本番用In-Memory実装。 */
class InMemoryBudgetRepository : BudgetRepository {
    private val budgets = ConcurrentHashMap<String, Budget>()

    override fun findByTenant(tenantId: TenantId): List<Budget> = budgets.values.filter { it.tenantId == tenantId }

    override fun save(budget: Budget) {
        budgets[budget.budgetId] = budget
    }
}
