package apap.domain.port

import apap.domain.model.cost.Budget
import apap.domain.model.vo.TenantId

/** `Budget`（04_ドメイン設計.md 4.3.5 / 12_ER図.md BUDGET）のRepository。FR-OBS-005の実装に必要。 */
interface BudgetRepository {
    fun findByTenant(tenantId: TenantId): List<Budget>

    fun save(budget: Budget)
}
