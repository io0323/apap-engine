package apap.domain.port

import apap.domain.event.DomainEvent

/** 03_基本設計.md 3.11: ドメインイベントの発行口（実装はInfrastructure層のEvent Bus）。 */
interface DomainEventPublisher {
    fun publish(event: DomainEvent)
}
