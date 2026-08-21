package apap.testkit.inmemory

import apap.domain.event.DomainEvent
import apap.domain.port.DomainEventPublisher

/** UseCaseテストが発火したイベントを検証できるよう、publishされた順に蓄積する。 */
class InMemoryDomainEventPublisher : DomainEventPublisher {
    private val events = mutableListOf<DomainEvent>()

    override fun publish(event: DomainEvent) {
        events.add(event)
    }

    val publishedEvents: List<DomainEvent> get() = events.toList()

    fun clear() {
        events.clear()
    }
}
