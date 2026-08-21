package apap.domain.port

import apap.domain.model.capability.CapabilityDefinition
import apap.domain.model.vo.CapabilityId

interface CapabilityRepository {
    fun findById(id: CapabilityId): CapabilityDefinition?

    fun register(definition: CapabilityDefinition)

    fun listAll(): List<CapabilityDefinition>
}
