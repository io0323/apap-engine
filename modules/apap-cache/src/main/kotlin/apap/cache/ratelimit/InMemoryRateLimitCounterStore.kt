package apap.cache.ratelimit

import java.util.concurrent.ConcurrentHashMap

/** [RateLimitCounterStore]の本番用In-Memory実装（既定、ADR-0001）。 */
class InMemoryRateLimitCounterStore : RateLimitCounterStore {
    private val states = ConcurrentHashMap<RateLimitScope, TokenBucketState>()

    override fun find(scope: RateLimitScope): TokenBucketState? = states[scope]

    override fun save(
        scope: RateLimitScope,
        state: TokenBucketState,
    ) {
        states[scope] = state
    }
}
