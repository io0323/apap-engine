package apap.testkit.inmemory

import apap.domain.model.capability.CapabilityDefinition
import apap.domain.model.vo.CapabilityId
import apap.domain.port.CapabilityRepository

class InMemoryCapabilityRepository : CapabilityRepository {
    private val definitions = mutableMapOf<CapabilityId, CapabilityDefinition>()

    override fun findById(id: CapabilityId): CapabilityDefinition? = definitions[id]

    override fun register(definition: CapabilityDefinition) {
        definitions[definition.capabilityId] = definition
    }

    override fun listAll(): List<CapabilityDefinition> = definitions.values.toList()
}
