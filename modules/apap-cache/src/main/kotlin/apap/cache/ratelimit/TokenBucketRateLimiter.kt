package apap.cache.ratelimit

import apap.domain.event.DomainEvent
import apap.domain.event.EventMetadata
import apap.domain.event.RateLimitExceeded
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RateLimitAction
import apap.domain.model.vo.TenantId
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.MetricsRecorder
import kotlinx.coroutines.delay
import java.time.Duration
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
 * ADR-0001: Rate Limiterのカウンタ実装はapap-cacheの責務、RDBMSに置かない。[store]の既定は
 * [InMemoryRateLimitCounterStore]（単一プロセス埋込利用ではこれで十分）、マルチノード運用時は
 * 分散KVS実装（`modules/apap-infrastructure-distributed`）に差し替える。
 *
 * [store]へのfind→更新→saveは[tryAcquire]/[estimateWaitMillis]内で`@Synchronized`により
 * プロセス内アトミック性を保つ（複数ノードにまたがる真のアトミック性は保証しない——`acquire()`の
 * 既存コメントの通りbounded waitは元々「保証ではなくベストエフォート」という前提であり、
 * 分散Store差替はこの前提を変えない。要件充足に影響しない実装判断のためADR化せずここに根拠を記す）。
 */
class TokenBucketRateLimiter(
    private val clock: Clock,
    private val eventPublisher: DomainEventPublisher,
    private val idGenerator: IdGenerator,
    private val config: RateLimiterConfig = RateLimiterConfig(),
    // 02_システム仕様.md 2.19 apap_rate_limit_events_total{action="wait"}。14章に定義の無い
    // イベントを新設するとDomainEventCoverageTestのクローズドセット制約に反するため、Event Bus
    // 経由ではなくMetricsRecorderへ直接記録する（要件充足に影響しない実装判断のためADR化せず
    // ここに根拠を記す）。宿主が未注入ならnullのまま記録をスキップする。
    private val metricsRecorder: MetricsRecorder? = null,
    private val store: RateLimitCounterStore = InMemoryRateLimitCounterStore(),
) : RateLimiter {
    override fun configure(
        scope: RateLimitScope,
        capacity: Int,
        refillPerSecond: Double,
    ) {
        require(capacity > 0) { "capacity must be positive: $capacity" }
        require(refillPerSecond > 0.0) { "refillPerSecond must be positive: $refillPerSecond" }
        store.save(scope, TokenBucketState(capacity.toDouble(), clock.now(), capacity, refillPerSecond))
    }

    @Synchronized
    override fun tryAcquire(
        scope: RateLimitScope,
        cost: Int,
    ): Boolean {
        require(cost > 0) { "cost must be positive: $cost" }
        val bucket = refill(currentBucket(scope))
        if (bucket.tokens < cost) return false
        store.save(scope, bucket.copy(tokens = bucket.tokens - cost))
        return true
    }

    private fun currentBucket(scope: RateLimitScope): TokenBucketState =
        store.find(scope) ?: TokenBucketState(
            config.defaultCapacity.toDouble(),
            clock.now(),
            config.defaultCapacity,
            config.defaultRefillPerSecond,
        )

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
        if (tryAcquire(scope, cost)) {
            metricsRecorder?.recordRateLimitEvent(scopeLabel(scope).first, RateLimitAction.WAIT)
            return AcquireResult.Acquired(scope, Permit(scope), waitedMillis = waitMillis)
        }
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
        val bucket = refill(currentBucket(scope))
        store.save(scope, bucket)
        val deficit = cost - bucket.tokens
        if (deficit <= 0.0) return 0L
        return ceil(deficit / bucket.refillPerSecond * MILLIS_PER_SECOND).toLong()
    }

    /** 純関数として新しい[TokenBucketState]を返す（永続化は呼び出し側の責務）。 */
    private fun refill(bucket: TokenBucketState): TokenBucketState {
        val now = clock.now()
        val elapsedSeconds = Duration.between(bucket.lastRefillAt, now).toMillis() / MILLIS_PER_SECOND
        if (elapsedSeconds <= 0.0) return bucket
        val refilledTokens = min(bucket.capacity.toDouble(), bucket.tokens + elapsedSeconds * bucket.refillPerSecond)
        return bucket.copy(tokens = refilledTokens, lastRefillAt = now)
    }

    private fun rateLimitExceededEvent(
        scope: RateLimitScope,
        traceId: String,
    ): DomainEvent {
        val (scopeLabel, tenantId, providerId) = scopeLabel(scope)
        return RateLimitExceeded(
            meta = eventMetadata(traceId, tenantId, scopeLabel),
            scope = scopeLabel,
            tenantId = tenantId,
            providerId = providerId,
        )
    }

    private fun scopeLabel(scope: RateLimitScope): Triple<String, TenantId?, ProviderId?> =
        when (scope) {
            is RateLimitScope.TenantScope -> Triple("tenant", scope.tenantId, null)
            is RateLimitScope.ProviderScope -> Triple("provider", null, scope.providerId)
        }

    private fun eventMetadata(
        traceId: String,
        tenantId: TenantId?,
        scopeLabel: String,
    ): EventMetadata =
        EventMetadata(
            eventId = idGenerator.newId(),
            occurredAt = clock.now(),
            traceId = traceId,
            tenantId = tenantId,
            aggregateId = scopeLabel,
            version = 0,
        )

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
