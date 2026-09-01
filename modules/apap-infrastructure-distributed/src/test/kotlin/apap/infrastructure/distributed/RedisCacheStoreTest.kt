package apap.infrastructure.distributed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** ローカルRedis（`docker compose -f tools/docker-compose.yaml up -d distributed-kvs`）に対する統合テスト。 */
class RedisCacheStoreTest {
    private lateinit var store: RedisCacheStore

    @BeforeEach
    fun setUp() {
        RedisTestSupport.flushAll()
        store = RedisCacheStore(RedisTestSupport.byteArrayConnection())
    }

    @Test
    fun `put then get round-trips the byte value`() {
        store.put("req:tenant1:abc", "hello".toByteArray(), Duration.ofMinutes(1))

        assertEquals("hello", store.get("req:tenant1:abc")?.decodeToString())
    }

    @Test
    fun `get of a missing key returns null`() {
        assertNull(store.get("no-such-key"))
    }

    @Test
    fun `delete removes the entry`() {
        store.put("k1", "v".toByteArray(), Duration.ofMinutes(1))
        store.delete("k1")

        assertNull(store.get("k1"))
    }

    @Test
    fun `scanByPrefix finds only matching keys`() {
        store.put("resp:alias:aliasA:1", "a".toByteArray(), Duration.ofMinutes(1))
        store.put("resp:alias:aliasA:2", "b".toByteArray(), Duration.ofMinutes(1))
        store.put("resp:alias:aliasB:1", "c".toByteArray(), Duration.ofMinutes(1))

        val found = store.scanByPrefix("resp:alias:aliasA:")

        assertEquals(2, found.size)
        assertTrue(found.all { it.startsWith("resp:alias:aliasA:") })
    }

    @Test
    fun `an entry expires after its TTL`() {
        // Tests Redis's own real-clock-driven expiry (PEXPIRE), not our injected-Clock domain logic,
        // so a real sleep is the correct tool here (unlike CLAUDE.md's Clock-injection rule, which is
        // about our own code's timing determinism).
        store.put("short-lived", "v".toByteArray(), Duration.ofMillis(1))

        Thread.sleep(200)

        assertNull(store.get("short-lived"))
    }
}
