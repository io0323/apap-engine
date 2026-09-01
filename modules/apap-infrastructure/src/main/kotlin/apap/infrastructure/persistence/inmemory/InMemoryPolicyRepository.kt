package apap.infrastructure.persistence.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.routing.PolicyScope
import apap.domain.model.routing.PolicyStatus
import apap.domain.model.routing.RoutingPolicy
import apap.domain.model.vo.TenantId
import apap.domain.port.PolicyRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PolicyRepository]の本番用In-Memory実装。
 *
 * `findEffective`はUSERスコープの識別に必要なprincipal/userId引数を持たない（Port自体のシグネチャ）ため、
 * USERスコープのPolicyはtenantId一致のみで返す。要件充足に影響しない実装判断のためADR化せず
 * ここに根拠を記す。
 */
class InMemoryPolicyRepository : PolicyRepository {
    private val policies = ConcurrentHashMap<String, RoutingPolicy>()
    private val eventsById = ConcurrentHashMap<String, CopyOnWriteArrayList<DomainEvent>>()

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
        eventsById.computeIfAbsent(policyId) { CopyOnWriteArrayList() }.addAll(events)
    }
}
