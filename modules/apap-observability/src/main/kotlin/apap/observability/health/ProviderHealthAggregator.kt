package apap.observability.health

import apap.domain.event.DomainEvent
import apap.domain.event.ProviderHealthChanged
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.vo.ProviderId
import apap.domain.port.DomainEventSubscriber
import apap.infrastructure.eventbus.IdempotentEventHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * 02_システム仕様.md 2.19 `/health/providers`。[ProviderHealthChanged]をEvent Bus経由で購読し、
 * 直近のProvider別健全性状態をメモリ上に集約する（[AuditEngine][apap.observability.audit.AuditEngine]/
 * [MetricsEngine][apap.observability.metrics.MetricsEngine]と同じEvent Bus購読パターン）。
 *
 * 注意: [ProviderHealthChanged]を実際に発火する周期ヘルスチェック機構（FR-PRV-006、Provider健全性の
 * 定期監視）は本タスクの範囲外で未実装のため、本クラス自体は正しく配線されているが、その機構が
 * 実装されイベントが届き始めるまでは空のスナップショット（[check]はUPを返す）のままになる
 * （`MetricsEngine`の`apap_provider_health`と同じ既知の未カバー範囲、KDoc/requirements-matrix.md参照）。
 */
class ProviderHealthAggregator(
    eventSubscriber: DomainEventSubscriber,
) : HealthIndicator {
    private val state = ConcurrentHashMap<ProviderId, ProviderHealthStatus>()

    init {
        eventSubscriber.subscribe(IdempotentEventHandler(::onEvent))
    }

    private fun onEvent(event: DomainEvent) {
        if (event is ProviderHealthChanged) {
            state[event.providerId] = event.to
        }
    }

    fun snapshot(): Map<ProviderId, ProviderHealthStatus> = state.toMap()

    override fun check(): HealthCheckResult {
        if (state.isEmpty()) return HealthCheckResult(HealthState.UP)
        val worstStatus = state.values.maxBy { severity(it) }
        val details = state.entries.associate { (providerId, status) -> providerId.value to status.name }
        return HealthCheckResult(toHealthState(worstStatus), details)
    }

    private fun severity(status: ProviderHealthStatus): Int =
        when (status) {
            ProviderHealthStatus.UP -> 0
            ProviderHealthStatus.DEGRADED -> 1
            ProviderHealthStatus.DOWN -> 2
        }

    private fun toHealthState(status: ProviderHealthStatus): HealthState =
        when (status) {
            ProviderHealthStatus.UP -> HealthState.UP
            ProviderHealthStatus.DEGRADED -> HealthState.DEGRADED
            ProviderHealthStatus.DOWN -> HealthState.DOWN
        }
}
