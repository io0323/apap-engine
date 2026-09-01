package apap.domain.model.modelcatalog

import apap.domain.event.AliasChanged
import apap.domain.event.DomainEvent
import apap.domain.model.UnexpectedEventForAggregateException
import apap.domain.model.vo.AliasId

private const val AGGREGATE_TYPE = "ModelAlias"

/**
 * ADR-0026: [apap.domain.model.reconstruct]と組み合わせ、ModelAliasのstream_idに記録された
 * イベント列からModelAliasの現在状態を復元する純粋関数。[AliasChanged]は常に新しいtargets全件
 * （差分ではない）を運ぶため、直前状態に関わらずイベント1件で完全な状態が定まる
 * （[apap.provider.ModelManager.assignAlias]が新規作成/更新の両方をこのイベント1種類で表現する）。
 */
@Suppress("UnusedParameter") // reconstruct(events, initial, apply)のシグネチャ(T?, DomainEvent) -> Tに合わせる必要がある。
fun applyModelAliasEvent(
    state: ModelAlias?,
    event: DomainEvent,
): ModelAlias =
    when (event) {
        is AliasChanged ->
            ModelAlias(
                aliasId = AliasId(event.aliasId),
                name = event.name,
                targets = event.newTargets.map { AliasTarget(it.modelId, it.weight) },
            )
        else -> throw UnexpectedEventForAggregateException(AGGREGATE_TYPE, event)
    }
