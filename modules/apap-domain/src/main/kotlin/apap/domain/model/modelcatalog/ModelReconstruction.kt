package apap.domain.model.modelcatalog

import apap.domain.event.DomainEvent
import apap.domain.event.ModelRegistered
import apap.domain.event.ModelStatusChanged
import apap.domain.model.UnexpectedEventForAggregateException

private const val AGGREGATE_TYPE = "Model"

/**
 * ADR-0026: [apap.domain.model.reconstruct]と組み合わせ、Modelのstream_idに記録された
 * イベント列からModelの現在状態を復元する純粋関数。[ModelStatusChanged]は`from`/`to`の両方を
 * 常に運ぶため（[apap.provider.ModelManager.changeStatus]が全ての遷移でこのイベント1種類のみを
 * 発行する）、Providerと異なり「14章に対応イベントが無い遷移」は存在しない。
 */
fun applyModelEvent(
    state: Model?,
    event: DomainEvent,
): Model =
    when (event) {
        is ModelRegistered ->
            Model(
                modelId = event.modelId,
                providerId = event.providerId,
                modelName = event.modelName,
                version = event.version,
                capabilities = event.capabilities,
                contextWindow = event.contextWindow,
                maxOutputTokens = event.maxOutputTokens,
                regions = event.regions,
                priority = event.priority,
            )
        is ModelStatusChanged -> requireState(state, event).copy(status = event.to)
        else -> throw UnexpectedEventForAggregateException(AGGREGATE_TYPE, event)
    }

private fun requireState(
    state: Model?,
    event: DomainEvent,
): Model = state ?: throw UnexpectedEventForAggregateException(AGGREGATE_TYPE, event)
