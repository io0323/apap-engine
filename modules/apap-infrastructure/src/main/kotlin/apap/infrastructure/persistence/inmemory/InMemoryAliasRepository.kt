package apap.infrastructure.persistence.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** [AliasRepository]の本番用In-Memory実装（単一プロセス埋込利用の既定、ADR-0001）。 */
class InMemoryAliasRepository : AliasRepository {
    private val aliases = ConcurrentHashMap<AliasId, ModelAlias>()
    private val tenantIdByAlias = ConcurrentHashMap<AliasId, TenantId>()
    private val eventsById = ConcurrentHashMap<AliasId, CopyOnWriteArrayList<DomainEvent>>()

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
        eventsById.computeIfAbsent(id) { CopyOnWriteArrayList() }.addAll(events)
    }
}
