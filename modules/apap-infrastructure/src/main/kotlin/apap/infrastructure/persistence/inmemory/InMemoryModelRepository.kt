package apap.infrastructure.persistence.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.modelcatalog.Model
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.port.ModelRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** [ModelRepository]の本番用In-Memory実装。04_ドメイン設計.md 4.5によりEvent Sourcing対象（ADR-0026）。 */
class InMemoryModelRepository : ModelRepository {
    private val models = ConcurrentHashMap<ModelId, Model>()
    private val eventsById = ConcurrentHashMap<ModelId, CopyOnWriteArrayList<DomainEvent>>()

    override fun findById(id: ModelId): Model? = models[id]

    override fun findByProvider(providerId: ProviderId): List<Model> =
        models.values.filter {
            it.providerId == providerId
        }

    override fun findByCapability(capabilityId: CapabilityId): List<Model> =
        models.values.filter { model -> model.capabilities.any { it.capabilityId == capabilityId } }

    override fun save(model: Model) {
        models[model.modelId] = model
    }

    override fun saveEvents(
        id: ModelId,
        events: List<DomainEvent>,
    ) {
        eventsById.computeIfAbsent(id) { CopyOnWriteArrayList() }.addAll(events)
    }
}
