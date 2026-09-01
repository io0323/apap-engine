package apap.domain.model

import apap.domain.event.DomainEvent

/**
 * ADR-0026: `apply(state, event): state`によるEvent Sourcing再構築中、対象Aggregateの
 * ストリームに現れるはずのないイベント型に遭遇した場合に投げる（9_状態遷移図.mdの
 * 「不正遷移は専用例外を投げる」という規約を、再構築処理にも同様に適用したもの）。
 */
class UnexpectedEventForAggregateException(
    aggregateType: String,
    event: DomainEvent,
) : IllegalStateException(
        "Event ${event::class.simpleName} (eventId=${event.meta.eventId}) is not applicable to " +
            "$aggregateType aggregate reconstruction",
    )
