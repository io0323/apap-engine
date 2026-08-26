package apap.cache

import apap.domain.port.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 16_拡張ポイント.md 16.4 SPI: Cache本体のKVS抽象。Request Cache・Response Cacheの双方が
 * 共有する（キー空間は[CacheKeyStrategy]が`req:`/`resp:`prefixで分離する）。
 *
 * 値の直列化表現の型[E]はSPI利用側（[DefaultCacheEngine]）が[CacheCodec]で決める。
 * `CanonicalResponse`（キャッシュする対象そのものの型）自体をここに固定しない: apap-domainは
 * Jacksonへ依存できない（不変条件2）ためCanonicalResponse自身にバイト列変換手段を持たせられず、
 * 分散KVS実装（ADR-0001, P8想定）はいずれ実際のバイト列直列化を必要とする。[CacheStore]の値型を
 * 先に`CanonicalResponse`固定で実装してしまうと、P8でバイト列が必要になった時点でこの
 * インターフェース自体の破壊的変更（SPI変更）になる。[E]を型パラメータとして残すことで、
 * P8では`CacheStore<ByteArray>`と`CacheCodec<CanonicalResponse, ByteArray>`を組み合わせるだけで
 * 済み、In-Memory実装（既定）は`CacheStore<CanonicalResponse>`と[PassthroughCacheCodec]
 * （恒等変換、直列化なし）を組み合わせる。
 */
interface CacheStore<E> {
    fun get(key: String): E?

    fun put(
        key: String,
        value: E,
        ttl: Duration,
    )

    fun delete(key: String)

    /** [prefix]で始まる全キーを列挙する（Alias切替時の一括無効化、[DefaultCacheEngine]参照）。 */
    fun scanByPrefix(prefix: String): List<String>
}

/**
 * ADR-0001の[apap.cache.ratelimit.TokenBucketRateLimiter]と同じ方針: 単一プロセス埋込利用では
 * 本in-memory実装が既定（分散KVS実装はP8想定）。TTL超過エントリは専用スイープスレッドを持たず、
 * 読取（[get]/[scanByPrefix]）時に遅延削除する。[E]は通常[PassthroughCacheCodec]と組み合わせ
 * `CanonicalResponse`のまま保持する（直列化しない）。
 */
class InMemoryCacheStore<E>(
    private val clock: Clock,
) : CacheStore<E> {
    private class Entry<E>(
        val value: E,
        val expiresAt: Instant,
    )

    private val entries = ConcurrentHashMap<String, Entry<E>>()

    override fun get(key: String): E? {
        val entry = entries[key]
        val expired = entry != null && isExpired(entry)
        if (expired) entries.remove(key)
        return if (entry != null && !expired) entry.value else null
    }

    override fun put(
        key: String,
        value: E,
        ttl: Duration,
    ) {
        entries[key] = Entry(value, clock.now().plus(ttl))
    }

    override fun delete(key: String) {
        entries.remove(key)
    }

    override fun scanByPrefix(prefix: String): List<String> {
        val now = clock.now()
        return entries.entries
            .filter { (key, entry) -> key.startsWith(prefix) && entry.expiresAt.isAfter(now) }
            .map { it.key }
    }

    private fun isExpired(entry: Entry<E>): Boolean = !entry.expiresAt.isAfter(clock.now())
}
