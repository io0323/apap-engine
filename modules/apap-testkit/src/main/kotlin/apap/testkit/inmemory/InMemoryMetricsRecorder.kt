package apap.testkit.inmemory

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

/**
 * テスト向け: [MetricsRecorder]への各呼出をそのまま記録する（実際にOpenTelemetryへ送出しない）。
 * UseCase/MetricsEngineのテストで「どのメトリクスがどのラベルで呼ばれたか」を直接検証するために使う。
 */
@Suppress("TooManyFunctions")
class InMemoryMetricsRecorder : MetricsRecorder {
    val requests = mutableListOf<Request>()
    val requestDurations = mutableListOf<RequestDuration>()
    val overheadDurations = mutableListOf<OverheadDuration>()
    val tokens = mutableListOf<Tokens>()
    val costs = mutableListOf<Cost>()
    val cacheEvents = mutableListOf<CacheEvent>()
    val retries = mutableListOf<Retry>()
    val fallbacks = mutableListOf<Fallback>()
    val circuitBreakerStates = mutableListOf<CircuitBreakerStateRecord>()
    val providerHealths = mutableListOf<ProviderHealth>()
    var streamingConnectionDelta = 0
        private set
    val rateLimitEvents = mutableListOf<RateLimitEvent>()

    override fun recordRequest(
        tenantId: TenantId,
        capabilityId: CapabilityId,
        providerId: ProviderId?,
        modelId: ModelId?,
        status: String,
    ) {
        requests += Request(tenantId, capabilityId, providerId, modelId, status)
    }

    override fun recordRequestDuration(
        capabilityId: CapabilityId,
        providerId: ProviderId?,
        modelId: ModelId?,
        seconds: Double,
    ) {
        requestDurations += RequestDuration(capabilityId, providerId, modelId, seconds)
    }

    override fun recordOverheadDuration(
        phase: String,
        seconds: Double,
    ) {
        overheadDurations += OverheadDuration(phase, seconds)
    }

    override fun recordTokens(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        direction: TokenDirection,
        count: Long,
    ) {
        tokens += Tokens(tenantId, providerId, modelId, direction, count)
    }

    override fun recordCost(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        currency: String,
        amount: Double,
    ) {
        costs += Cost(tenantId, providerId, modelId, currency, amount)
    }

    override fun recordCacheEvent(
        type: CacheEventType,
        cache: CacheType,
    ) {
        cacheEvents += CacheEvent(type, cache)
    }

    override fun recordRetry(
        providerId: ProviderId,
        modelId: ModelId,
        reason: String,
    ) {
        retries += Retry(providerId, modelId, reason)
    }

    override fun recordFallback(
        providerId: ProviderId,
        modelId: ModelId,
        reason: String,
    ) {
        fallbacks += Fallback(providerId, modelId, reason)
    }

    override fun recordCircuitBreakerState(
        providerId: ProviderId,
        modelId: ModelId,
        state: CbState,
    ) {
        circuitBreakerStates += CircuitBreakerStateRecord(providerId, modelId, state)
    }

    override fun recordProviderHealth(
        providerId: ProviderId,
        region: String,
        status: ProviderHealthStatus,
    ) {
        providerHealths += ProviderHealth(providerId, region, status)
    }

    override fun incrementStreamingConnections(tenantId: TenantId) {
        streamingConnectionDelta += 1
    }

    override fun decrementStreamingConnections(tenantId: TenantId) {
        streamingConnectionDelta -= 1
    }

    override fun recordRateLimitEvent(
        scope: String,
        action: RateLimitAction,
    ) {
        rateLimitEvents += RateLimitEvent(scope, action)
    }

    data class Request(
        val tenantId: TenantId,
        val capabilityId: CapabilityId,
        val providerId: ProviderId?,
        val modelId: ModelId?,
        val status: String,
    )

    data class RequestDuration(
        val capabilityId: CapabilityId,
        val providerId: ProviderId?,
        val modelId: ModelId?,
        val seconds: Double,
    )

    data class OverheadDuration(
        val phase: String,
        val seconds: Double,
    )

    data class Tokens(
        val tenantId: TenantId,
        val providerId: ProviderId,
        val modelId: ModelId,
        val direction: TokenDirection,
        val count: Long,
    )

    data class Cost(
        val tenantId: TenantId,
        val providerId: ProviderId,
        val modelId: ModelId,
        val currency: String,
        val amount: Double,
    )

    data class CacheEvent(
        val type: CacheEventType,
        val cache: CacheType,
    )

    data class Retry(
        val providerId: ProviderId,
        val modelId: ModelId,
        val reason: String,
    )

    data class Fallback(
        val providerId: ProviderId,
        val modelId: ModelId,
        val reason: String,
    )

    data class CircuitBreakerStateRecord(
        val providerId: ProviderId,
        val modelId: ModelId,
        val state: CbState,
    )

    data class ProviderHealth(
        val providerId: ProviderId,
        val region: String,
        val status: ProviderHealthStatus,
    )

    data class RateLimitEvent(
        val scope: String,
        val action: RateLimitAction,
    )
}
