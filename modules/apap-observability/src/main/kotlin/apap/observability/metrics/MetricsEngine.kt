package apap.observability.metrics

import apap.domain.event.CacheHit
import apap.domain.event.CacheStored
import apap.domain.event.CircuitBreakerStateChanged
import apap.domain.event.DomainEvent
import apap.domain.event.FallbackExecuted
import apap.domain.event.RateLimitExceeded
import apap.domain.event.RequestCompleted
import apap.domain.event.RequestFailed
import apap.domain.event.RetryExecuted
import apap.domain.event.StreamAborted
import apap.domain.event.StreamClosed
import apap.domain.event.StreamOpened
import apap.domain.model.vo.CacheEventType
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RateLimitAction
import apap.domain.model.vo.TokenDirection
import apap.domain.port.DomainEventSubscriber
import apap.domain.port.MetricsRecorder
import apap.infrastructure.eventbus.IdempotentEventHandler
import org.slf4j.LoggerFactory

private const val MILLIS_PER_SECOND = 1000.0

/**
 * 02_システム仕様.md 2.19 Monitoring仕様。[eventSubscriber]（Event Bus）を購読し、
 * 既に発火済みのDomain Eventから導出可能なメトリクスを[recorder]（[MetricsRecorder]）へ記録する。
 *
 * メトリクス記録（OpenTelemetry Counter/Histogram/Gaugeの更新）はインメモリかつ極めて低コストなため、
 * [apap.observability.audit.AuditEngine]と異なり非同期化しない（同期ハンドラ内で直接呼ぶ）。
 *
 * **既知の未カバー範囲**（`docs/traceability/requirements-matrix.md` NFR-OBS-002/FR-OBS-002参照）:
 * - `apap_cache_events_total`: [CacheHit]/[CacheStored]の型自体は存在しハンドラも実装済みだが、
 *   現時点でどちらも本番コードから一度もpublishされていない（`DefaultCacheEngine`/`ExecutionEngine`が
 *   未配線）。Cache miss相当のシグナルも存在しない。Cache層の配線は別タスク。
 * - `apap_overhead_duration_seconds`: phase別（gateway/prompt/routing/mapping）の所要時間はDomain
 *   Eventとして発火されておらず（`PhaseTimings`はログのみ）、Event Bus購読では導出不能。
 * - `apap_provider_health`: `ProviderHealthChanged`を発火するHealth Store自体が未実装
 *   （FR-PRV-006、本タスクの範囲外）。
 * - `apap_rate_limit_events_total{action="wait"}`: `RateLimitExceeded`はreject時のみ発火され、
 *   `AcquireResult.Acquired.waitedMillis`（wait成功）はEvent化されていない。
 *
 * 上記4件は`MetricsRecorder`のメソッド自体は用意済み（[apap.domain.port.MetricsRecorder]参照）だが、
 * 呼び出し元が存在しない。CapabilitySmokeTestと同じ理由で、存在を偽装せずここに明記する。
 */
class MetricsEngine(
    eventSubscriber: DomainEventSubscriber,
    private val recorder: MetricsRecorder,
) {
    init {
        eventSubscriber.subscribe(IdempotentEventHandler(::handle))
    }

    private fun handle(event: DomainEvent) {
        runCatching { dispatch(event) }.onFailure { e ->
            logger.warn("failed to record metrics for event type={}: {}", event::class.simpleName, e.message, e)
        }
    }

    private fun dispatch(event: DomainEvent) {
        when (event) {
            is RequestCompleted -> onRequestCompleted(event)
            is RequestFailed -> onRequestFailed(event)
            is RetryExecuted -> onRetryExecuted(event)
            is FallbackExecuted -> onFallbackExecuted(event)
            is CircuitBreakerStateChanged -> onCircuitBreakerStateChanged(event)
            is StreamOpened -> event.meta.tenantId?.let(recorder::incrementStreamingConnections)
            is StreamClosed -> event.meta.tenantId?.let(recorder::decrementStreamingConnections)
            is StreamAborted -> event.meta.tenantId?.let(recorder::decrementStreamingConnections)
            is RateLimitExceeded -> recorder.recordRateLimitEvent(event.scope, RateLimitAction.REJECT)
            is CacheHit -> recorder.recordCacheEvent(CacheEventType.HIT, event.cacheType)
            is CacheStored -> recorder.recordCacheEvent(CacheEventType.STORE, event.cacheType)
            else -> Unit
        }
    }

    private fun onRequestCompleted(event: RequestCompleted) {
        val tenantId = event.meta.tenantId ?: return
        val providerId = ProviderId(event.provider)
        val modelId = ModelId(event.model)
        recorder.recordRequest(tenantId, event.capabilityId, providerId, modelId, event.finishReason.name)
        recorder.recordRequestDuration(event.capabilityId, providerId, modelId, event.durationMs / MILLIS_PER_SECOND)
        recorder.recordTokens(
            tenantId,
            providerId,
            modelId,
            TokenDirection.IN,
            event.usage.inputTokens.value
                .toLong(),
        )
        recorder.recordTokens(
            tenantId,
            providerId,
            modelId,
            TokenDirection.OUT,
            event.usage.outputTokens.value
                .toLong(),
        )
        recorder.recordCost(
            tenantId,
            providerId,
            modelId,
            event.cost.amount.currency,
            event.cost.amount.amount
                .toDouble(),
        )
    }

    private fun onRequestFailed(event: RequestFailed) {
        val tenantId = event.meta.tenantId ?: return
        recorder.recordRequest(tenantId, event.capabilityId, null, null, "FAILED")
        recorder.recordRequestDuration(event.capabilityId, null, null, event.durationMs / MILLIS_PER_SECOND)
    }

    private fun onRetryExecuted(event: RetryExecuted) {
        val (providerId, modelId) = parseCandidateKey(event.candidate) ?: return
        recorder.recordRetry(providerId, modelId, event.reason)
    }

    private fun onFallbackExecuted(event: FallbackExecuted) {
        val (providerId, modelId) = parseCandidateKey(event.fromCandidate) ?: return
        recorder.recordFallback(providerId, modelId, event.reason)
    }

    private fun onCircuitBreakerStateChanged(event: CircuitBreakerStateChanged) {
        recorder.recordCircuitBreakerState(event.cbKey.providerId, event.cbKey.modelId, event.to)
    }

    /** [apap.domain.service.routing.Candidate.key]の`"providerId:modelId"`形式を分解する。 */
    private fun parseCandidateKey(candidate: String): Pair<ProviderId, ModelId>? {
        val parts = candidate.split(":", limit = 2)
        if (parts.size != 2) return null
        return runCatching { ProviderId(parts[0]) to ModelId(parts[1]) }.getOrNull()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(MetricsEngine::class.java)
    }
}
