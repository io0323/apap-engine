package apap.infrastructure.distributed

import apap.domain.model.execution.CbState
import apap.domain.model.execution.CircuitBreakerState
import apap.domain.model.execution.WindowStats
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** ローカルRedis（`docker compose -f tools/docker-compose.yaml up -d distributed-kvs`）に対する統合テスト。 */
class RedisCircuitBreakerStateStoreTest {
    private lateinit var store: RedisCircuitBreakerStateStore
    private val cbKey = CbKey(ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA0"), ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA1"))

    @BeforeEach
    fun setUp() {
        RedisTestSupport.flushAll()
        store = RedisCircuitBreakerStateStore(RedisTestSupport.stringConnection())
    }

    @Test
    fun `find on an unknown key returns null`() {
        assertNull(store.find(cbKey))
    }

    @Test
    fun `save then find round-trips the full state, including nested WindowStats and openedAt`() {
        val state =
            CircuitBreakerState(
                cbKey = cbKey,
                state = CbState.OPEN,
                windowStats = WindowStats(requestCount = 10, failureCount = 6),
                openedAt = Instant.parse("2026-01-01T00:00:00Z"),
                openCount = 2,
            )

        store.save(state)

        assertEquals(state, store.find(cbKey))
    }
}
