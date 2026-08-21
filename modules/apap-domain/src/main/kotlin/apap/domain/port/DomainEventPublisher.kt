package apap.domain.port

import apap.domain.event.DomainEvent

/**
 * 03_基本設計.md 3.11 / 02_システム仕様.md 2.3: ドメインイベントの発行・購読口
 * （実装はInfrastructure層のEvent Bus）。2.3は「Event Bus: ドメインイベントの発行・購読」と
 * 両方を明記しており、購読側（例: apap-routingの候補キャッシュ）が同一Portで配線できるようにする。
 */
interface DomainEventPublisher {
    fun publish(event: DomainEvent)

    fun subscribe(handler: (DomainEvent) -> Unit)
}
