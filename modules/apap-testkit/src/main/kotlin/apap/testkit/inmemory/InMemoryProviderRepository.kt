package apap.testkit.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ProviderId
import apap.domain.port.ProviderRepository

class InMemoryProviderRepository : ProviderRepository {
    private val providers = mutableMapOf<ProviderId, Provider>()
    private val eventsById = mutableMapOf<ProviderId, MutableList<DomainEvent>>()

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
        eventsById.getOrPut(id) { mutableListOf() }.addAll(events)
    }

    fun eventsFor(id: ProviderId): List<DomainEvent> = eventsById[id].orEmpty()
}
