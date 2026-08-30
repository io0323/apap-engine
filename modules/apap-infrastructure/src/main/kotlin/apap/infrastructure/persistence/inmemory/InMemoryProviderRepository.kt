package apap.infrastructure.persistence.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ProviderId
import apap.domain.port.ProviderRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** [ProviderRepository]の本番用In-Memory実装。03_基本設計.md 3.4: 4.5によりEvent Sourcing対象。 */
class InMemoryProviderRepository : ProviderRepository {
    private val providers = ConcurrentHashMap<ProviderId, Provider>()
    private val eventsById = ConcurrentHashMap<ProviderId, CopyOnWriteArrayList<DomainEvent>>()

    override fun findById(id: ProviderId): Provider? = providers[id]

    override fun findByStatus(status: ProviderStatus): List<Provider> = providers.values.filter { it.status == status }

    override fun findAll(): List<Provider> = providers.values.toList()

    override fun save(provider: Provider) {
        providers[provider.providerId] = provider
    }

    override fun saveEvents(
        id: ProviderId,
        events: List<DomainEvent>,
    ) {
        eventsById.computeIfAbsent(id) { CopyOnWriteArrayList() }.addAll(events)
    }
}
