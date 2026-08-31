package apap.domain.port

import apap.domain.event.DomainEvent
import apap.domain.model.modelcatalog.Model
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId

/** 04_ドメイン設計.md 4.5: ModelはEvent Sourcing対象（`saveEvents`で追記、ADR-0026）。 */
interface ModelRepository {
    fun findById(id: ModelId): Model?

    fun findByProvider(providerId: ProviderId): List<Model>

    fun findByCapability(capabilityId: CapabilityId): List<Model>

    fun save(model: Model)

    fun saveEvents(
        id: ModelId,
        events: List<DomainEvent>,
    )
}
