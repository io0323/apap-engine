package apap.testkit.inmemory

import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.vo.TenantId
import apap.domain.port.QuotaPolicyRepository

class InMemoryQuotaPolicyRepository : QuotaPolicyRepository {
    private val policies = mutableMapOf<String, QuotaPolicy>()

    override fun findByTenant(tenantId: TenantId): List<QuotaPolicy> {
        val matching = policies.values.filter { it.tenantId == tenantId }
        return matching
    }

    override fun save(policy: QuotaPolicy) {
        policies[policy.quotaId] = policy
    }
}
