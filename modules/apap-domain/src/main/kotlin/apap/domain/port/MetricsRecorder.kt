package apap.domain.port

import apap.domain.event.CacheType
import apap.domain.model.execution.CbState
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.vo.CacheEventType
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RateLimitAction
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenDirection

/**
 * 02_システム仕様.md 2.19 Monitoring仕様の表に定義された全メトリクスを記録するPort。
 * 16_拡張ポイント.md 16.6の`MetricsExporter`拡張点はこのPortの実装側（OpenTelemetry API経由）が
 * 担う。名前・ラベルは2.19の表と完全一致させること（実装側で勝手に増減しない）。
 *
 * `apap-domain`は何にも依存しないため、実装（OpenTelemetry API呼出）は`apap-observability`に置く。
 * このPortはCbState/ProviderHealthStatus等の意味のある型を受け取り、Gauge値への数値エンコードは
 * 実装側の責務とする（呼び出し側にエンコード規約を漏らさないため）。
 */
@Suppress("TooManyFunctions")
interface MetricsRecorder {
    /** apap_requests_total{tenant, capability, provider, model, status} */
    fun recordRequest(
        tenantId: TenantId,
        capabilityId: CapabilityId,
        providerId: ProviderId?,
        modelId: ModelId?,
        status: String,
    )

    /** apap_request_duration_seconds{capability, provider, model} */
    fun recordRequestDuration(
        capabilityId: CapabilityId,
        providerId: ProviderId?,
        modelId: ModelId?,
        seconds: Double,
    )

    /** apap_overhead_duration_seconds{phase(gateway/prompt/routing/mapping)} */
    fun recordOverheadDuration(
        phase: String,
        seconds: Double,
    )

    /** apap_tokens_total{tenant, provider, model, direction(in/out)} */
    fun recordTokens(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        direction: TokenDirection,
        count: Long,
    )

    /** apap_cost_total{tenant, provider, model, currency} */
    fun recordCost(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        currency: String,
        amount: Double,
    )

    /** apap_cache_events_total{type(hit/miss/store), cache(request/response)} */
    fun recordCacheEvent(
        type: CacheEventType,
        cache: CacheType,
    )

    /** apap_retries_total{provider, model, reason} */
    fun recordRetry(
        providerId: ProviderId,
        modelId: ModelId,
        reason: String,
    )

    /** apap_fallbacks_total{provider, model, reason} */
    fun recordFallback(
        providerId: ProviderId,
        modelId: ModelId,
        reason: String,
    )

    /** apap_circuit_breaker_state{provider, model}（Gauge） */
    fun recordCircuitBreakerState(
        providerId: ProviderId,
        modelId: ModelId,
        state: CbState,
    )

    /** apap_provider_health{provider, region}（Gauge） */
    fun recordProviderHealth(
        providerId: ProviderId,
        region: String,
        status: ProviderHealthStatus,
    )

    /** apap_streaming_connections{tenant}（Gauge） */
    fun incrementStreamingConnections(tenantId: TenantId)

    /** apap_streaming_connections{tenant}（Gauge） */
    fun decrementStreamingConnections(tenantId: TenantId)

    /** apap_rate_limit_events_total{scope(tenant/provider), action(wait/reject)} */
    fun recordRateLimitEvent(
        scope: String,
        action: RateLimitAction,
    )
}
