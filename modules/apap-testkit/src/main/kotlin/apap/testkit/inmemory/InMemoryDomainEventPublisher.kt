package apap.testkit.inmemory

import apap.domain.event.DomainEvent
import apap.domain.port.DomainEventPublisher
import apap.domain.port.DomainEventSubscriber

/**
 * UseCaseテストが発火したイベントを検証できるよう、publishされた順に蓄積する。
 * [subscribe]で登録したハンドラへはpublish時に同期的にfan-outする（実Event Busの購読を
 * In-Memoryで代替する。apap-routingの候補キャッシュ配線テスト等で使う）。
 * [DomainEventPublisher]/[DomainEventSubscriber]は発行/購読でISPに沿って分離されているが、
 * In-Memoryの実Event Bus代替としては両方を1つの実装で提供する。
 */
class InMemoryDomainEventPublisher :
    DomainEventPublisher,
    DomainEventSubscriber {
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
