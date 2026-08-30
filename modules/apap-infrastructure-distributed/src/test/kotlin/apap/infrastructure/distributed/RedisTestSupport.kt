package apap.infrastructure.distributed

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec

/** `docker compose -f tools/docker-compose.yaml up -d distributed-kvs`（CLAUDE.md「コマンド」参照）に接続する。 */
object RedisTestSupport {
    private val client: RedisClient by lazy { RedisClient.create("redis://localhost:6379") }

    fun stringConnection(): StatefulRedisConnection<String, String> = client.connect(StringCodec.UTF8)

    fun byteArrayConnection(): StatefulRedisConnection<String, ByteArray> = client.connect(RedisCacheStore.CODEC)

    fun flushAll() {
        stringConnection().use { it.sync().flushall() }
    }
}
