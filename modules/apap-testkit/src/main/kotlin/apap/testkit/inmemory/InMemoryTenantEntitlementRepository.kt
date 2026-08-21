package apap.testkit.inmemory

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.port.TenantEntitlementRepository

/** 既定は許可。テストは[deny]で特定の(tenant, capability, model)を拒否に設定できる。 */
class InMemoryTenantEntitlementRepository : TenantEntitlementRepository {
    private val denied = mutableSetOf<Triple<TenantId, CapabilityId, ModelId>>()

    override fun isPermitted(
        tenantId: TenantId,
        capabilityId: CapabilityId,
        modelId: ModelId,
    ): Boolean = Triple(tenantId, capabilityId, modelId) !in denied

    fun deny(
        tenantId: TenantId,
        capabilityId: CapabilityId,
        modelId: ModelId,
    ) {
        denied.add(Triple(tenantId, capabilityId, modelId))
    }
}
