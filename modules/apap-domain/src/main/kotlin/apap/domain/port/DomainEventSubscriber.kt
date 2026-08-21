package apap.domain.port

import apap.domain.event.DomainEvent

/**
 * 02_システム仕様.md 2.3: 「Event Bus: ドメインイベントの発行・購読」の購読側。
 * [DomainEventPublisher]（発行）とは別インターフェースに分離する（ISP: 発行専用の利用側
 * （Aggregateを更新するUseCase等）へ購読責務まで押し付けない）。実Event Bus実装は
 * [DomainEventPublisher]と本インターフェースの両方を実装してよい。
 */
interface DomainEventSubscriber {
    fun subscribe(handler: (DomainEvent) -> Unit)
}
