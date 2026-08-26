package apap.infrastructure.eventbus

import apap.domain.event.DomainEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * `eventId`基準の重複排除を行うハンドララッパ。at-least-once配送（14_イベント一覧.md /
 * 03_基本設計.md 3.11）を前提とする本Event Busでは、同一eventIdの二重配送が起こり得るため、
 * 購読側が個別に[apap.domain.event.EventMetadata.eventId]をチェックする実装を毎回書かずに済む
 * よう、この既知パターン（`apap.routing.RoutingCandidateCache`が独自実装していたものと同じ）を
 * 再利用可能なヘルパとして提供する。
 *
 * 同一インスタンスに閉じたeventId集合を保持するため、購読ごとに新しい
 * [IdempotentEventHandler]を作ること（[SynchronousEventBus.subscribeIdempotent]参照）。
 * 集合は無限に増え続けるため、長時間稼働するプロセスでの上限は設けていない
 * （実装判断: 本Event Busは埋込単一プロセス想定＝プロセス寿命に対しeventId集合が問題になる
 * 規模にはならない前提。要件充足には影響しないためADR化せず、ここに根拠を記す）。
 */
class IdempotentEventHandler(
    private val delegate: (DomainEvent) -> Unit,
) : (DomainEvent) -> Unit {
    private val processedEventIds = ConcurrentHashMap.newKeySet<String>()

    override fun invoke(event: DomainEvent) {
        if (processedEventIds.add(event.meta.eventId)) {
            delegate(event)
        }
    }
}
