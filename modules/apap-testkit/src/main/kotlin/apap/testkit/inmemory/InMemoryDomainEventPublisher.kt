package apap.testkit.inmemory

import apap.domain.event.DomainEvent
import apap.domain.port.DomainEventPublisher

/**
 * UseCaseテストが発火したイベントを検証できるよう、publishされた順に蓄積する。
 * [subscribe]で登録したハンドラへはpublish時に同期的にfan-outする（実Event Busの購読を
 * In-Memoryで代替する。apap-routingの候補キャッシュ配線テスト等で使う）。
 */
class InMemoryDomainEventPublisher : DomainEventPublisher {
    private val events = mutableListOf<DomainEvent>()
    private val handlers = mutableListOf<(DomainEvent) -> Unit>()

    override fun publish(event: DomainEvent) {
        events.add(event)
        handlers.forEach { it(event) }
    }

    override fun subscribe(handler: (DomainEvent) -> Unit) {
        handlers.add(handler)
    }

    val publishedEvents: List<DomainEvent> get() = events.toList()

    fun clear() {
        events.clear()
    }
}
