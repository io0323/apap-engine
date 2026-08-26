package apap.infrastructure.eventbus

import apap.domain.event.DomainEvent
import apap.domain.port.DomainEventPublisher
import apap.domain.port.DomainEventSubscriber
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 03_基本設計.md 3.11 Event Busの本番向け実装（apap-infrastructure）。
 *
 * - プロセス内配送: `publish()`を呼び出したスレッド上で、登録済み全購読者へ同期的にfan-outする
 *   （[apap.testkit.inmemory.InMemoryDomainEventPublisher]と同じ同期モデルだが、こちらは
 *   本番の埋込利用を想定した実装であり、外部Bus転送・購読者の例外分離・冪等購読ヘルパを持つ）。
 * - 外部Bus転送: [externalForwarder]（既定[LoggingExternalEventBusForwarder]）へ委譲する
 *   at-least-once転送。実メッセージング製品との結合はこのSPI実装側の責務（本クラスは知らない）。
 * - 購読者分離: 1購読者が例外を送出しても、他の購読者への配送・[publish]の呼び出し元へは
 *   伝播させない（1購読者の不具合で全体を止めないため）。ログに記録するのみ。
 * - 冪等購読: [subscribeIdempotent]は[IdempotentEventHandler]で`eventId`基準の重複排除を行う。
 *   通常の[subscribe]は生の（重複あり得る）配送をそのまま受け取る。
 */
class SynchronousEventBus(
    private val externalForwarder: ExternalEventBusForwarder = LoggingExternalEventBusForwarder(),
) : DomainEventPublisher,
    DomainEventSubscriber {
    private val handlers = CopyOnWriteArrayList<(DomainEvent) -> Unit>()

    override fun publish(event: DomainEvent) {
        externalForwarder.forward(event)
        handlers.forEach { handler ->
            runCatching { handler(event) }.onFailure { e ->
                logger.warn(
                    "event subscriber threw for eventId={} type={}: {}",
                    event.meta.eventId,
                    event::class.simpleName,
                    e.message,
                    e,
                )
            }
        }
    }

    override fun subscribe(handler: (DomainEvent) -> Unit) {
        handlers.add(handler)
    }

    /** [handler]をeventId基準で冪等化してから購読する。詳細は[IdempotentEventHandler]参照。 */
    fun subscribeIdempotent(handler: (DomainEvent) -> Unit) {
        subscribe(IdempotentEventHandler(handler))
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SynchronousEventBus::class.java)
    }
}
