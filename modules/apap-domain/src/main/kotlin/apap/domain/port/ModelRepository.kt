package apap.domain.port

import apap.domain.model.modelcatalog.Model
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId

interface ModelRepository {
    fun findById(id: ModelId): Model?

    fun findByProvider(providerId: ProviderId): List<Model>

    fun findByCapability(capabilityId: CapabilityId): List<Model>

    fun save(model: Model)
}
