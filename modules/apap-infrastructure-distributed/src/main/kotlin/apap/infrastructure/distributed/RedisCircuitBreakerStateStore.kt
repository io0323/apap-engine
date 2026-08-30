package apap.infrastructure.distributed

import apap.domain.model.execution.CircuitBreakerState
import apap.domain.model.vo.CbKey
import apap.domain.port.CircuitBreakerStateStore
import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.api.StatefulRedisConnection

/**
 * [CircuitBreakerStateStore]の分散KVS実装（ADR-0001, ADR-0025: Redis + Lettuce）。単一プロセス
 * 埋込利用では`apap-execution`の`InMemoryCircuitBreakerStateStore`（既定）で十分、マルチノード
 * 運用時にこちらへ差し替える。
 */
class RedisCircuitBreakerStateStore(
    private val connection: StatefulRedisConnection<String, String>,
    private val objectMapper: ObjectMapper = RedisSupport.objectMapper,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
) : CircuitBreakerStateStore {
    private val commands = connection.sync()

    override fun find(key: CbKey): CircuitBreakerState? =
        commands.get(redisKey(key))?.let { objectMapper.readValue(it, CircuitBreakerState::class.java) }

    override fun save(state: CircuitBreakerState) {
        commands.set(redisKey(state.cbKey), objectMapper.writeValueAsString(state))
    }

    private fun redisKey(key: CbKey): String = "$keyPrefix${key.providerId.value}:${key.modelId.value}"

    private companion object {
        const val DEFAULT_KEY_PREFIX = "apap:cb:"
    }
}
