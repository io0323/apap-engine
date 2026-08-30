package apap.infrastructure.persistence.inmemory

import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.vo.TenantId
import apap.domain.port.QuotaPolicyRepository
import java.util.concurrent.ConcurrentHashMap

/** [QuotaPolicyRepository]の本番用In-Memory実装。 */
class InMemoryQuotaPolicyRepository : QuotaPolicyRepository {
    private val policies = ConcurrentHashMap<String, QuotaPolicy>()

    override fun findByTenant(tenantId: TenantId): List<QuotaPolicy> {
        val matches = policies.values.filter { it.tenantId == tenantId }
        return matches
    }

    override fun save(policy: QuotaPolicy) {
        policies[policy.quotaId] = policy
    }
}
