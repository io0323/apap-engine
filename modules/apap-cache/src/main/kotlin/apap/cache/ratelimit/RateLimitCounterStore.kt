package apap.cache.ratelimit

import java.time.Instant

/**
 * ADR-0001: Rate Limiterのトークンバケット状態はRDBMSに置かない。単一プロセス埋込利用では
 * [InMemoryRateLimitCounterStore]（既定）で十分、マルチノード運用時は分散KVS実装
 * （`modules/apap-infrastructure-distributed`）に差し替える。
 *
 * [apap.domain.port.CircuitBreakerStateStore]と同じ`find`/`save`の形だが、[RateLimitScope]が
 * `apap-cache`側の型（`apap-domain`のVOではない）であるため、本Portも同じ`apap-cache`パッケージに
 * 置く（[CacheStore][apap.cache.CacheStore]と同じ配置方針）。
 */
interface RateLimitCounterStore {
    fun find(scope: RateLimitScope): TokenBucketState?

    fun save(
        scope: RateLimitScope,
        state: TokenBucketState,
    )
}

data class TokenBucketState(
    val tokens: Double,
    val lastRefillAt: Instant,
    val capacity: Int,
    val refillPerSecond: Double,
)
