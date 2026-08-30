package apap.infrastructure.distributed

import apap.cache.ratelimit.RateLimitCounterStore
import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.TokenBucketState
import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.api.StatefulRedisConnection

/**
 * [RateLimitCounterStore]の分散KVS実装（ADR-0001, ADR-0025: Redis + Lettuce）。単一プロセス
 * 埋込利用では`apap-cache`の`InMemoryRateLimitCounterStore`（既定）で十分、マルチノード運用時に
 * こちらへ差し替える。プロセスをまたいだ真のアトミック性は保証しない
 * （`TokenBucketRateLimiter`のクラスKDoc参照、bounded waitは元々ベストエフォート）。
 */
class RedisRateLimitCounterStore(
    private val connection: StatefulRedisConnection<String, String>,
    private val objectMapper: ObjectMapper = RedisSupport.objectMapper,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
) : RateLimitCounterStore {
    private val commands = connection.sync()

    override fun find(scope: RateLimitScope): TokenBucketState? =
        commands.get(redisKey(scope))?.let { objectMapper.readValue(it, TokenBucketState::class.java) }

    override fun save(
        scope: RateLimitScope,
        state: TokenBucketState,
    ) {
        commands.set(redisKey(scope), objectMapper.writeValueAsString(state))
    }

    private fun redisKey(scope: RateLimitScope): String =
        when (scope) {
            is RateLimitScope.TenantScope -> "${keyPrefix}tenant:${scope.tenantId.value}"
            is RateLimitScope.ProviderScope -> "${keyPrefix}provider:${scope.providerId.value}"
        }

    private companion object {
        const val DEFAULT_KEY_PREFIX = "apap:ratelimit:"
    }
}
