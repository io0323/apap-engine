package apap.cache.ratelimit

import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryRateLimitCounterStoreTest {
    private val scope = RateLimitScope.TenantScope(TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0"))

    @Test
    fun `find on an unknown scope returns null, and save then find round-trips the state`() {
        val store = InMemoryRateLimitCounterStore()
        assertNull(store.find(scope))

        val state = TokenBucketState(10.0, Instant.parse("2026-01-01T00:00:00Z"), 60, 1.0)
        store.save(scope, state)

        assertEquals(state, store.find(scope))
    }
}
