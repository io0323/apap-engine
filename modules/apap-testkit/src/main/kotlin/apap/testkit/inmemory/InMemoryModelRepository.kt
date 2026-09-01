package apap.testkit.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.modelcatalog.Model
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.port.ModelRepository

class InMemoryModelRepository : ModelRepository {
    private val models = mutableMapOf<ModelId, Model>()
    private val eventsById = mutableMapOf<ModelId, MutableList<DomainEvent>>()

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
        eventsById.getOrPut(id) { mutableListOf() }.addAll(events)
    }

    fun eventsFor(id: ModelId): List<DomainEvent> = eventsById[id].orEmpty()
}
