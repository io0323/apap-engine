package apap.infrastructure.persistence.inmemory

import apap.domain.model.capability.CapabilityDefinition
import apap.domain.model.vo.CapabilityId
import apap.domain.port.CapabilityRepository
import java.util.concurrent.ConcurrentHashMap

/** [CapabilityRepository]の本番用In-Memory実装。 */
class InMemoryCapabilityRepository : CapabilityRepository {
    private val definitions = ConcurrentHashMap<CapabilityId, CapabilityDefinition>()

    override fun findById(id: CapabilityId): CapabilityDefinition? = definitions[id]

    override fun register(definition: CapabilityDefinition) {
        definitions[definition.capabilityId] = definition
    }

    override fun listAll(): List<CapabilityDefinition> = definitions.values.toList()
}
