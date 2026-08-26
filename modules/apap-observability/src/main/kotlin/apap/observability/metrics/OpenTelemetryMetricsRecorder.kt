package apap.observability.metrics

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
import apap.domain.port.MetricsRecorder
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement
import java.util.concurrent.ConcurrentHashMap

private const val UNKNOWN = "unknown"

/**
 * 02_システム仕様.md 2.19の表に定義された全メトリクスを、OpenTelemetry Metrics APIで記録する実装。
 * `Meter`はホスト側が構築した`OpenTelemetrySdk`から取得したものを注入される想定
 * （本クラスはAPIのみに依存し、SDKを自前で構築・バンドルしない、CLAUDE.md不変条件6）。
 *
 * Gauge系メトリクス（circuit_breaker_state / provider_health / streaming_connections）は
 * 値が非同期に変化するため、OpenTelemetry APIの同期Gauge命令ではなく
 * [Meter.gaugeBuilder]の`buildWithCallback`（Observable Gauge）で実装する。直近の値を
 * `ConcurrentHashMap`に保持し、SDK側の収集タイミングでコールバックがまとめて読み出す。
 */
@Suppress("TooManyFunctions")
class OpenTelemetryMetricsRecorder(
    meter: Meter,
) : MetricsRecorder {
    private val requestsTotal =
        meter.counterBuilder("apap_requests_total").setDescription("total requests").build()
    private val requestDurationSeconds =
        meter
            .histogramBuilder("apap_request_duration_seconds")
            .setUnit("s")
            .setDescription("request duration")
            .build()
    private val overheadDurationSeconds =
        meter
            .histogramBuilder("apap_overhead_duration_seconds")
            .setUnit("s")
            .setDescription("per-phase overhead duration")
            .build()
    private val tokensTotal = meter.counterBuilder("apap_tokens_total").setDescription("total tokens").build()
    private val costTotal =
        meter
            .counterBuilder("apap_cost_total")
            .ofDoubles()
            .setDescription("total cost")
            .build()
    private val cacheEventsTotal =
        meter.counterBuilder("apap_cache_events_total").setDescription("cache events").build()
    private val retriesTotal = meter.counterBuilder("apap_retries_total").setDescription("total retries").build()
    private val fallbacksTotal =
        meter.counterBuilder("apap_fallbacks_total").setDescription("total fallbacks").build()
    private val rateLimitEventsTotal =
        meter.counterBuilder("apap_rate_limit_events_total").setDescription("rate limit events").build()

    private val circuitBreakerState = ConcurrentHashMap<Attributes, Double>()
    private val providerHealth = ConcurrentHashMap<Attributes, Double>()
    private val streamingConnections = ConcurrentHashMap<Attributes, Double>()

    init {
        meter
            .gaugeBuilder("apap_circuit_breaker_state")
            .setDescription("circuit breaker state (0=CLOSED, 1=HALF_OPEN, 2=OPEN)")
            .buildWithCallback { measurement -> publishGauge(measurement, circuitBreakerState) }
        meter
            .gaugeBuilder("apap_provider_health")
            .setDescription("provider health (0=UP, 1=DEGRADED, 2=DOWN)")
            .buildWithCallback { measurement -> publishGauge(measurement, providerHealth) }
        meter
            .gaugeBuilder("apap_streaming_connections")
            .setDescription("open streaming connections")
            .buildWithCallback { measurement -> publishGauge(measurement, streamingConnections) }
    }

    private fun publishGauge(
        measurement: ObservableDoubleMeasurement,
        values: Map<Attributes, Double>,
    ) {
        values.forEach { (attrs, value) -> measurement.record(value, attrs) }
    }

    override fun recordRequest(
        tenantId: TenantId,
        capabilityId: CapabilityId,
        providerId: ProviderId?,
        modelId: ModelId?,
        status: String,
    ) {
        requestsTotal.add(
            1,
            Attributes.of(
                TENANT,
                tenantId.value,
                CAPABILITY,
                capabilityId.value,
                PROVIDER,
                providerId?.value ?: UNKNOWN,
                MODEL,
                modelId?.value ?: UNKNOWN,
                STATUS,
                status,
            ),
        )
    }

    override fun recordRequestDuration(
        capabilityId: CapabilityId,
        providerId: ProviderId?,
        modelId: ModelId?,
        seconds: Double,
    ) {
        requestDurationSeconds.record(
            seconds,
            Attributes.of(
                CAPABILITY,
                capabilityId.value,
                PROVIDER,
                providerId?.value ?: UNKNOWN,
                MODEL,
                modelId?.value ?: UNKNOWN,
            ),
        )
    }

    override fun recordOverheadDuration(
        phase: String,
        seconds: Double,
    ) {
        overheadDurationSeconds.record(seconds, Attributes.of(PHASE, phase))
    }

    override fun recordTokens(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        direction: TokenDirection,
        count: Long,
    ) {
        tokensTotal.add(
            count,
            Attributes.of(
                TENANT,
                tenantId.value,
                PROVIDER,
                providerId.value,
                MODEL,
                modelId.value,
                DIRECTION,
                direction.name.lowercase(),
            ),
        )
    }

    override fun recordCost(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        currency: String,
        amount: Double,
    ) {
        costTotal.add(
            amount,
            Attributes.of(
                TENANT,
                tenantId.value,
                PROVIDER,
                providerId.value,
                MODEL,
                modelId.value,
                CURRENCY,
                currency,
            ),
        )
    }

    override fun recordCacheEvent(
        type: CacheEventType,
        cache: CacheType,
    ) {
        cacheEventsTotal.add(1, Attributes.of(TYPE, type.name.lowercase(), CACHE, cache.name.lowercase()))
    }

    override fun recordRetry(
        providerId: ProviderId,
        modelId: ModelId,
        reason: String,
    ) {
        retriesTotal.add(1, Attributes.of(PROVIDER, providerId.value, MODEL, modelId.value, REASON, reason))
    }

    override fun recordFallback(
        providerId: ProviderId,
        modelId: ModelId,
        reason: String,
    ) {
        fallbacksTotal.add(1, Attributes.of(PROVIDER, providerId.value, MODEL, modelId.value, REASON, reason))
    }

    override fun recordCircuitBreakerState(
        providerId: ProviderId,
        modelId: ModelId,
        state: CbState,
    ) {
        val attrs = Attributes.of(PROVIDER, providerId.value, MODEL, modelId.value)
        circuitBreakerState[attrs] = cbStateValue(state)
    }

    override fun recordProviderHealth(
        providerId: ProviderId,
        region: String,
        status: ProviderHealthStatus,
    ) {
        val attrs = Attributes.of(PROVIDER, providerId.value, REGION, region)
        providerHealth[attrs] = providerHealthValue(status)
    }

    override fun incrementStreamingConnections(tenantId: TenantId) {
        val attrs = Attributes.of(TENANT, tenantId.value)
        streamingConnections.merge(attrs, 1.0, Double::plus)
    }

    override fun decrementStreamingConnections(tenantId: TenantId) {
        val attrs = Attributes.of(TENANT, tenantId.value)
        streamingConnections.merge(attrs, -1.0, Double::plus)
    }

    override fun recordRateLimitEvent(
        scope: String,
        action: RateLimitAction,
    ) {
        rateLimitEventsTotal.add(1, Attributes.of(SCOPE, scope, ACTION, action.name.lowercase()))
    }

    private fun cbStateValue(state: CbState): Double =
        when (state) {
            CbState.CLOSED -> 0.0
            CbState.HALF_OPEN -> 1.0
            CbState.OPEN -> 2.0
        }

    private fun providerHealthValue(status: ProviderHealthStatus): Double =
        when (status) {
            ProviderHealthStatus.UP -> 0.0
            ProviderHealthStatus.DEGRADED -> 1.0
            ProviderHealthStatus.DOWN -> 2.0
        }

    private companion object {
        val TENANT = AttributeKey.stringKey("tenant")
        val CAPABILITY = AttributeKey.stringKey("capability")
        val PROVIDER = AttributeKey.stringKey("provider")
        val MODEL = AttributeKey.stringKey("model")
        val STATUS = AttributeKey.stringKey("status")
        val PHASE = AttributeKey.stringKey("phase")
        val DIRECTION = AttributeKey.stringKey("direction")
        val CURRENCY = AttributeKey.stringKey("currency")
        val TYPE = AttributeKey.stringKey("type")
        val CACHE = AttributeKey.stringKey("cache")
        val REASON = AttributeKey.stringKey("reason")
        val REGION = AttributeKey.stringKey("region")
        val SCOPE = AttributeKey.stringKey("scope")
        val ACTION = AttributeKey.stringKey("action")
    }
}
