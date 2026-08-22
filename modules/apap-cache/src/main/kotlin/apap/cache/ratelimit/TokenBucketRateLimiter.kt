package apap.cache.ratelimit

import apap.domain.event.DomainEvent
import apap.domain.event.EventMetadata
import apap.domain.event.RateLimitExceeded
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * CLAUDE.md不変条件7に従い設定可能。未[RateLimiter.configure]のスコープに適用する既定バケット。
 * [defaultMaxWait]は呼び出し側が[RateLimiter.acquire]の`maxWait`に迷った際の参考値
 * （呼び出し側は本来「残タイムアウト予算とこの値の小さいほう」を渡すべきだが、その計算をしない
 * 呼び出し元向けの妥当な既定値として提供する）。[apap.execution.retry.RetryConfig.backoffCapMs]
 * と同じ桁（数秒）に揃える。
 */
data class RateLimiterConfig(
    val defaultCapacity: Int = 60,
    val defaultRefillPerSecond: Double = 1.0,
    val defaultMaxWait: Duration = Duration.ofSeconds(DEFAULT_MAX_WAIT_SECONDS),
) {
    init {
        require(defaultCapacity > 0) { "defaultCapacity must be positive: $defaultCapacity" }
        require(defaultRefillPerSecond > 0.0) { "defaultRefillPerSecond must be positive: $defaultRefillPerSecond" }
        require(!defaultMaxWait.isNegative) { "defaultMaxWait must not be negative: $defaultMaxWait" }
    }

    private companion object {
        const val DEFAULT_MAX_WAIT_SECONDS = 5L
    }
}

/**
 * ADR-0001: Rate Limiterのカウンタ実装はapap-cacheの責務、RDBMSに置かない。単一プロセス埋込利用
 * では本in-memory実装が既定（分散KVS実装はP8想定）。
 */
class TokenBucketRateLimiter(
    private val clock: Clock,
    private val eventPublisher: DomainEventPublisher,
    private val idGenerator: IdGenerator,
    private val config: RateLimiterConfig = RateLimiterConfig(),
) : RateLimiter {
    private data class Bucket(
        var tokens: Double,
        var lastRefillAt: Instant,
        val capacity: Int,
        val refillPerSecond: Double,
    )

    private val buckets = ConcurrentHashMap<RateLimitScope, Bucket>()

    override fun configure(
        scope: RateLimitScope,
        capacity: Int,
        refillPerSecond: Double,
    ) {
        require(capacity > 0) { "capacity must be positive: $capacity" }
        require(refillPerSecond > 0.0) { "refillPerSecond must be positive: $refillPerSecond" }
        buckets[scope] = Bucket(capacity.toDouble(), clock.now(), capacity, refillPerSecond)
    }

    @Synchronized
    override fun tryAcquire(
        scope: RateLimitScope,
        cost: Int,
    ): Boolean {
        require(cost > 0) { "cost must be positive: $cost" }
        val bucket =
            buckets.computeIfAbsent(scope) {
                Bucket(
                    config.defaultCapacity.toDouble(),
                    clock.now(),
                    config.defaultCapacity,
                    config.defaultRefillPerSecond,
                )
            }
        refill(bucket)
        if (bucket.tokens < cost) return false
        bucket.tokens -= cost
        return true
    }

    @Suppress("ReturnCount")
    override suspend fun acquire(
        scope: RateLimitScope,
        traceId: String,
        maxWait: Duration,
        cost: Int,
    ): AcquireResult {
        require(!maxWait.isNegative) { "maxWait must not be negative: $maxWait" }
        if (tryAcquire(scope, cost)) return AcquireResult.Acquired(scope, Permit(scope), waitedMillis = 0L)

        val waitMillis = estimateWaitMillis(scope, cost)
        if (waitMillis > maxWait.toMillis()) {
            return reject(scope, traceId, waitMillis, maxWait)
        }

        delay(waitMillis)

        // The bucket may have been drained by a concurrent acquirer while we waited; re-check
        // rather than assume success (bounded wait, not a guarantee).
        if (tryAcquire(scope, cost)) return AcquireResult.Acquired(scope, Permit(scope), waitedMillis = waitMillis)
        return reject(scope, traceId, waitMillis, maxWait)
    }

    private fun reject(
        scope: RateLimitScope,
        traceId: String,
        waitMillis: Long,
        maxWait: Duration,
    ): AcquireResult.Rejected {
        eventPublisher.publish(rateLimitExceededEvent(scope, traceId))
        return AcquireResult.Rejected(scope, waitedMillis = waitMillis, maxWaitMillis = maxWait.toMillis())
    }

    @Synchronized
    private fun estimateWaitMillis(
        scope: RateLimitScope,
        cost: Int,
    ): Long {
        val bucket =
            buckets.computeIfAbsent(scope) {
                Bucket(
                    config.defaultCapacity.toDouble(),
                    clock.now(),
                    config.defaultCapacity,
                    config.defaultRefillPerSecond,
                )
            }
        refill(bucket)
        val deficit = cost - bucket.tokens
        if (deficit <= 0.0) return 0L
        return ceil(deficit / bucket.refillPerSecond * MILLIS_PER_SECOND).toLong()
    }

    private fun refill(bucket: Bucket) {
        val now = clock.now()
        val elapsedSeconds = Duration.between(bucket.lastRefillAt, now).toMillis() / MILLIS_PER_SECOND
        if (elapsedSeconds <= 0.0) return
        bucket.tokens = min(bucket.capacity.toDouble(), bucket.tokens + elapsedSeconds * bucket.refillPerSecond)
        bucket.lastRefillAt = now
    }

    private fun rateLimitExceededEvent(
        scope: RateLimitScope,
        traceId: String,
    ): DomainEvent {
        val (scopeLabel, tenantId, providerId) =
            when (scope) {
                is RateLimitScope.TenantScope -> Triple("tenant", scope.tenantId, null)
                is RateLimitScope.ProviderScope -> Triple("provider", null, scope.providerId)
            }
        return RateLimitExceeded(
            meta =
                EventMetadata(
                    eventId = idGenerator.newId(),
                    occurredAt = clock.now(),
                    traceId = traceId,
                    tenantId = tenantId,
                    aggregateId = scopeLabel,
                    version = 0,
                ),
            scope = scopeLabel,
            tenantId = tenantId,
            providerId = providerId,
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
