package apap.observability.metrics

import apap.domain.event.CacheHit
import apap.domain.event.CacheStored
import apap.domain.event.CircuitBreakerStateChanged
import apap.domain.event.DomainEvent
import apap.domain.event.FallbackExecuted
import apap.domain.event.ProviderHealthChanged
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
import apap.domain.port.ProviderRepository
import apap.infrastructure.eventbus.IdempotentEventHandler
import org.slf4j.LoggerFactory

private const val MILLIS_PER_SECOND = 1000.0

/**
 * 02_システム仕様.md 2.19 Monitoring仕様。[eventSubscriber]（Event Bus）を購読し、
 * 既に発火済みのDomain Eventから導出可能なメトリクスを[recorder]（[MetricsRecorder]）へ記録する。
 * `apap.runtime.ExecutionEngineComposer`が本クラスを実際に構築・配線する（配線コード自体は本モジュールの
 * 責務外のため、apap-domainへの依存を持たないよう[eventSubscriber]/[recorder]/[providerRepository]は
 * すべて呼び出し側から注入される）。
 *
 * メトリクス記録（OpenTelemetry Counter/Histogram/Gaugeの更新）はインメモリかつ極めて低コストなため、
 * [apap.observability.audit.AuditEngine]と異なり非同期化しない（同期ハンドラ内で直接呼ぶ）。
 *
 * `apap_overhead_duration_seconds`（phase別所要時間）はDomain Eventとして発火されないため
 * （`PhaseTimings`が実測点そのもの）、Event Bus購読では導出不能——`apap.execution.PhaseTimings`が
 * [MetricsRecorder.recordOverheadDuration]を直接呼ぶ。`apap_rate_limit_events_total{action="wait"}`も
 * 同じ理由（14章に無いイベントを新設すると`DomainEventCoverageTest`のクローズドセット制約に反する）で
 * `TokenBucketRateLimiter`（apap-cache）が直接呼ぶ。
 *
 * **既知の未カバー範囲**: `apap_provider_health`は[onProviderHealthChanged]で配線済みだが、
 * [ProviderHealthChanged]を実際に発火する周期ヘルスチェック機構（Health Store、FR-PRV-006）自体が
 * 未実装のため、その機構が実装されイベントが届き始めるまでは値が記録されない
 * （`ProviderHealthAggregator`と同じ既知の未カバー範囲、KDoc/requirements-matrix.md参照）。
 */
class MetricsEngine(
    eventSubscriber: DomainEventSubscriber,
    private val recorder: MetricsRecorder,
    private val providerRepository: ProviderRepository,
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
            is ProviderHealthChanged -> onProviderHealthChanged(event)
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

    /**
     * `apap_provider_health{provider, region}`（2.19表）。[ProviderHealthChanged]自身はregionを
     * 運ばない（Providerは複数regionにまたがりうるため）ため、[providerRepository]から解決し、
     * 該当Providerが持つ全regionへ記録する。削除済み等でProviderが見つからない場合は記録しない。
     */
    private fun onProviderHealthChanged(event: ProviderHealthChanged) {
        val provider = providerRepository.findById(event.providerId) ?: return
        provider.regions.forEach { region -> recorder.recordProviderHealth(event.providerId, region.code, event.to) }
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
