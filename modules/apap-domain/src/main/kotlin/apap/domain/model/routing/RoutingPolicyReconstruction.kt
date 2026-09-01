package apap.domain.model.routing

import apap.domain.event.DomainEvent
import apap.domain.event.PolicyUpdated
import apap.domain.model.UnexpectedEventForAggregateException

private const val AGGREGATE_TYPE = "RoutingPolicy"

/**
 * ADR-0026: [apap.domain.model.reconstruct]と組み合わせ、RoutingPolicyのstream_idに記録された
 * イベント列からRoutingPolicyの現在状態を復元する純粋関数。[PolicyUpdated]は
 * [publishNewVersion]/[archive]の両方（新規発行含む）を表す唯一のイベントで、常に
 * その時点のフル状態（rules/version/status等）を運ぶため、直前状態に関わらずイベント1件で
 * 完全な状態が定まる。
 */
@Suppress("UnusedParameter") // reconstruct(events, initial, apply)のシグネチャ(T?, DomainEvent) -> Tに合わせる必要がある。
fun applyRoutingPolicyEvent(
    state: RoutingPolicy?,
    event: DomainEvent,
): RoutingPolicy =
    when (event) {
        is PolicyUpdated ->
            RoutingPolicy(
                policyId = event.policyId,
                scope = PolicyScope.valueOf(event.scope),
                tenantId = event.tenantId,
                workflowId = event.workflowId,
                rules = event.rules,
                version = event.version,
                status = event.status,
            )
        else -> throw UnexpectedEventForAggregateException(AGGREGATE_TYPE, event)
    }
