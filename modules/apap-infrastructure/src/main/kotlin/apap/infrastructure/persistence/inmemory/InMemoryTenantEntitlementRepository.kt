package apap.infrastructure.persistence.inmemory

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.port.TenantEntitlementRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * [TenantEntitlementRepository]の本番用In-Memory実装。既定は許可。[deny]で特定の
 * (tenant, capability, model)を拒否に設定できる（テナント契約管理自体は本Portの範囲外、KDoc参照）。
 */
class InMemoryTenantEntitlementRepository : TenantEntitlementRepository {
    private val denied = ConcurrentHashMap.newKeySet<Triple<TenantId, CapabilityId, ModelId>>()

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
