package apap.domain.port

import apap.domain.event.DomainEvent
import apap.domain.model.routing.RoutingPolicy
import apap.domain.model.vo.TenantId

/** 04_ドメイン設計.md 4.5: RoutingPolicyはEvent Sourcing対象（`saveEvents`で追記、ADR-0026）。 */
interface PolicyRepository {
    fun findById(policyId: String): RoutingPolicy?

    /** 02_システム仕様.md 2.5.3: PLATFORM/TENANT/WORKFLOW/USERのうち有効なPolicyを解決する。 */
    fun findEffective(
        tenantId: TenantId?,
        workflowId: String?,
    ): List<RoutingPolicy>

    fun save(policy: RoutingPolicy)

    fun saveEvents(
        policyId: String,
        events: List<DomainEvent>,
    )
}
