package apap.domain.port

import apap.domain.event.DomainEvent
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ProviderId

/** 03_基本設計.md 3.4: Providerは4.5によりEvent Sourcing対象（`saveEvents`で追記）。 */
interface ProviderRepository {
    fun findById(id: ProviderId): Provider?

    fun findByStatus(status: ProviderStatus): List<Provider>

    fun findAll(): List<Provider>

    fun save(provider: Provider)

    fun saveEvents(
        id: ProviderId,
        events: List<DomainEvent>,
    )
}
