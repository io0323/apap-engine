package apap.infrastructure.distributed

import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.TokenBucketState
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** ローカルRedis（`docker compose -f tools/docker-compose.yaml up -d distributed-kvs`）に対する統合テスト。 */
class RedisRateLimitCounterStoreTest {
    private lateinit var store: RedisRateLimitCounterStore
    private val scope = RateLimitScope.TenantScope(TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0"))

    @BeforeEach
    fun setUp() {
        RedisTestSupport.flushAll()
        store = RedisRateLimitCounterStore(RedisTestSupport.stringConnection())
    }

    @Test
    fun `find on an unknown scope returns null`() {
        assertNull(store.find(scope))
    }

    @Test
    fun `save then find round-trips the bucket state`() {
        val state =
            TokenBucketState(
                tokens = 42.5,
                lastRefillAt = Instant.parse("2026-01-01T00:00:00Z"),
                capacity = 60,
                refillPerSecond = 1.0,
            )

        store.save(scope, state)

        assertEquals(state, store.find(scope))
    }

    @Test
    fun `tenant and provider scopes are stored independently`() {
        val providerScope = RateLimitScope.ProviderScope(ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1"))
        store.save(scope, TokenBucketState(1.0, Instant.parse("2026-01-01T00:00:00Z"), 60, 1.0))
        store.save(providerScope, TokenBucketState(2.0, Instant.parse("2026-01-01T00:00:00Z"), 60, 1.0))

        assertEquals(1.0, store.find(scope)?.tokens)
        assertEquals(2.0, store.find(providerScope)?.tokens)
    }
}
