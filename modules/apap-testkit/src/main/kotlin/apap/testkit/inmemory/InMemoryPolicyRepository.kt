package apap.testkit.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.routing.PolicyScope
import apap.domain.model.routing.PolicyStatus
import apap.domain.model.routing.RoutingPolicy
import apap.domain.model.vo.TenantId
import apap.domain.port.PolicyRepository

/**
 * `findEffective`はUSERスコープの識別に必要なprincipal/userId引数を持たない（Port自体のシグネチャ）ため、
 * USERスコープのPolicyはtenantId一致のみで返す（ベストエフォート。実Infrastructure実装がPort拡張で
 * 対応する想定。CLAUDE.md「ADR化するか否かの判断基準」: テストダブルの実装詳細でありFR/NFR充足に影響しない）。
 */
class InMemoryPolicyRepository : PolicyRepository {
    private val policies = mutableMapOf<String, RoutingPolicy>()
    private val eventsById = mutableMapOf<String, MutableList<DomainEvent>>()

    override fun findById(policyId: String): RoutingPolicy? = policies[policyId]

    override fun findEffective(
        tenantId: TenantId?,
        workflowId: String?,
    ): List<RoutingPolicy> =
        policies.values.filter { policy ->
            policy.status == PolicyStatus.ACTIVE &&
                when (policy.scope) {
                    PolicyScope.PLATFORM -> true
                    PolicyScope.TENANT -> policy.tenantId == tenantId
                    PolicyScope.WORKFLOW -> policy.tenantId == tenantId && policy.workflowId == workflowId
                    PolicyScope.USER -> policy.tenantId == tenantId
                }
        }

    override fun save(policy: RoutingPolicy) {
        policies[policy.policyId] = policy
    }

    override fun saveEvents(
        policyId: String,
        events: List<DomainEvent>,
    ) {
        eventsById.getOrPut(policyId) { mutableListOf() }.addAll(events)
    }

    fun eventsFor(policyId: String): List<DomainEvent> = eventsById[policyId].orEmpty()
}
