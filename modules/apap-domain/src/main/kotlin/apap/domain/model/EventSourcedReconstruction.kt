package apap.domain.model

import apap.domain.event.DomainEvent

/**
 * 04_ドメイン設計.md 4.5: Event Sourcing対象Aggregate（Provider / Model / ModelAlias /
 * RoutingPolicy / BatchJob）は、各Repositoryがイベント再生による再構築を担当する。
 * `apply(state, event): state`という各Aggregate専用の純粋関数（例:
 * [apap.domain.model.provider.apply]）と組み合わせて使う、共通のfold処理。
 *
 * ADR-0014が委譲していたスナップショット併用の再構築経路（loadSnapshotで得たAggregate状態を
 * 初期値とし、それ以降のイベントのみを再生する）は、呼び出し側で`initial`にスナップショットの
 * `state`を、`events`にスナップショット以降のイベントのみを渡すことで表現する
 * （スナップショットが無ければ`initial=null`、`events`は全件）。
 */
fun <T : Any> reconstruct(
    events: List<DomainEvent>,
    initial: T?,
    apply: (T?, DomainEvent) -> T,
): T? = events.fold(initial, apply)
