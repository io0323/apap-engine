package apap.testkit.inmemory

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.port.QuotaSnapshotRepository

/** 既定は無制限（[Int.MAX_VALUE]）。テストは[setRemaining]で特定の(tenant, provider, model)の残量を仕込める。 */
class InMemoryQuotaSnapshotRepository : QuotaSnapshotRepository {
    private val remainingByKey = mutableMapOf<Triple<TenantId, ProviderId, ModelId>, Int>()

    override fun remaining(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
    ): Int = remainingByKey[Triple(tenantId, providerId, modelId)] ?: Int.MAX_VALUE

    fun setRemaining(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        remaining: Int,
    ) {
        remainingByKey[Triple(tenantId, providerId, modelId)] = remaining
    }
}
