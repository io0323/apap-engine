package apap.cache

import apap.domain.model.execution.CanonicalResponse
import apap.domain.port.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 16_拡張ポイント.md 16.4 SPI: Cache本体のKVS抽象。Request Cache・Response Cacheの双方が
 * 共有する（キー空間は[CacheKeyStrategy]が`req:`/`resp:`prefixで分離する）。
 *
 * 値の型は[CanonicalResponse]に固定する（本リポジトリがキャッシュする対象はこれのみのため、
 * CLAUDE.md「ハイポセティカルな将来要件のために設計しない」に従いバイト列/汎用KVS抽象は採らない）。
 * `CanonicalResponse`はContentPartの直和型等を含みapap-domainがJacksonへ依存できない
 * （不変条件2: 依存は内向き一方向）ため素朴なreflectionシリアライズが使えず、分散KVS実装（P8想定）が
 * 必要とするバイト列変換は、その実装自身の責務としてこのインターフェースの外側で行う
 * （例: `RedisCacheStore`が内部でシリアライズ方式を選ぶ）。
 */
interface CacheStore {
    fun get(key: String): CanonicalResponse?

    fun put(
        key: String,
        value: CanonicalResponse,
        ttl: Duration,
    )

    fun delete(key: String)

    /** [prefix]で始まる全キーを列挙する（Alias切替時の一括無効化、[DefaultCacheEngine]参照）。 */
    fun scanByPrefix(prefix: String): List<String>
}

/**
 * ADR-0001の[apap.cache.ratelimit.TokenBucketRateLimiter]と同じ方針: 単一プロセス埋込利用では
 * 本in-memory実装が既定（分散KVS実装はP8想定）。TTL超過エントリは専用スイープスレッドを持たず、
 * 読取（[get]/[scanByPrefix]）時に遅延削除する。
 */
class InMemoryCacheStore(
    private val clock: Clock,
) : CacheStore {
    private class Entry(
        val value: CanonicalResponse,
        val expiresAt: Instant,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    override fun get(key: String): CanonicalResponse? {
        val entry = entries[key]
        val expired = entry != null && isExpired(entry)
        if (expired) entries.remove(key)
        return if (entry != null && !expired) entry.value else null
    }

    override fun put(
        key: String,
        value: CanonicalResponse,
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

    private fun isExpired(entry: Entry): Boolean = !entry.expiresAt.isAfter(clock.now())
}
