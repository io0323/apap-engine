package apap.domain.port

import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId

interface AliasRepository {
    fun findByName(
        tenantId: TenantId,
        name: String,
    ): ModelAlias?

    fun save(alias: ModelAlias)

    fun listByModel(modelId: ModelId): List<ModelAlias>
}
