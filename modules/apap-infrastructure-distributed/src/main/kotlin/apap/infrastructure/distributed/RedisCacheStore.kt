package apap.infrastructure.distributed

import apap.cache.CacheStore
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import java.time.Duration

/**
 * [CacheStore]の分散KVS実装（ADR-0001, ADR-0025: Redis + Lettuce）。値は常に`ByteArray`
 * （[apap.cache.CacheCodec]が上位で直列化を担う、`CacheStore`自身は直列化方式を知らない）。
 *
 * `connection`は呼び出し元（埋込ホスト）が構築・ライフサイクル管理する
 * （このクラス自身は`RedisClient`を所有・close()しない——複数のRedis利用箇所間で
 * コネクションを共有できるようにするため）。
 */
class RedisCacheStore(
    private val connection: StatefulRedisConnection<String, ByteArray>,
) : CacheStore<ByteArray> {
    private val commands = connection.sync()

    override fun get(key: String): ByteArray? = commands.get(key)

    override fun put(
        key: String,
        value: ByteArray,
        ttl: Duration,
    ) {
        commands.psetex(key, ttl.toMillis(), value)
    }

    override fun delete(key: String) {
        commands.del(key)
    }

    override fun scanByPrefix(prefix: String): List<String> {
        val keys = mutableListOf<String>()
        var cursor = ScanCursor.INITIAL
        do {
            val result = commands.scan(cursor, ScanArgs.Builder.matches("$prefix*"))
            keys += result.keys
            cursor = result
        } while (!cursor.isFinished)
        return keys
    }

    companion object {
        /** [CacheStore]用のキー=String/値=ByteArrayコーデック（`connect(codec, uri)`に渡す）。 */
        val CODEC: RedisCodec<String, ByteArray> = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)
    }
}
