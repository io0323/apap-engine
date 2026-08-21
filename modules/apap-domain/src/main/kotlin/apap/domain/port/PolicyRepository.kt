package apap.domain.port

import apap.domain.model.routing.RoutingPolicy
import apap.domain.model.vo.TenantId

interface PolicyRepository {
    /** 02_システム仕様.md 2.5.3: PLATFORM/TENANT/WORKFLOW/USERのうち有効なPolicyを解決する。 */
    fun findEffective(
        tenantId: TenantId?,
        workflowId: String?,
    ): List<RoutingPolicy>

    fun save(policy: RoutingPolicy)
}
