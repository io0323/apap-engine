package apap.testkit.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository

class InMemoryAliasRepository : AliasRepository {
    private val aliases = mutableMapOf<AliasId, ModelAlias>()
    private val tenantIdByAlias = mutableMapOf<AliasId, TenantId>()
    private val eventsById = mutableMapOf<AliasId, MutableList<DomainEvent>>()

    override fun findByName(
        tenantId: TenantId,
        name: String,
    ): ModelAlias? = aliases.values.firstOrNull { it.name == name && tenantIdByAlias[it.aliasId] == tenantId }

    override fun save(
        tenantId: TenantId,
        alias: ModelAlias,
    ) {
        aliases[alias.aliasId] = alias
        tenantIdByAlias[alias.aliasId] = tenantId
    }

    override fun listByModel(modelId: ModelId): List<ModelAlias> =
        aliases.values.filter { alias -> alias.targets.any { it.modelId == modelId } }

    override fun saveEvents(
        id: AliasId,
        events: List<DomainEvent>,
    ) {
        eventsById.getOrPut(id) { mutableListOf() }.addAll(events)
    }

    fun eventsFor(id: AliasId): List<DomainEvent> = eventsById[id].orEmpty()
}
