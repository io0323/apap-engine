package apap.cache

/**
 * [CacheStore]が保持する値[T]と、その直列化表現[E]との変換手段。
 *
 * [CacheStore]の値型は[T]（`CanonicalResponse`）に固定し、バイト列や特定の直列化方式をSPI契約に
 * 持ち込まない（apap-domainがJacksonへ依存できない、CLAUDE.md不変条件2）。しかし分散KVS実装
 * （ADR-0001, P8想定）は実際には[T]をバイト列へ変換して初めて外部ストアへ書ける。この変換を
 * [CacheStore]自身にもDefaultCacheEngineにも持たせず、独立したCodecとして切り出すことで、
 * P8で分散実装を追加する際に[CacheStore]/[CacheEngine]のインターフェース自体を変更せずに済む
 * （型パラメータ[E]を`CanonicalResponse`から`ByteArray`等へ差し替えるだけでよい）。
 *
 * In-Memory実装（既定、[InMemoryCacheStore]）は[PassthroughCacheCodec]（[E]=[T]の恒等変換）を使い、
 * 直列化を一切行わない。分散KVS実装は`CacheCodec<CanonicalResponse, ByteArray>`
 * （例: Jackson/Protobuf等の実直列化、実装側の責務）を注入する。
 */
interface CacheCodec<T, E> {
    fun encode(value: T): E

    fun decode(encoded: E): T
}

/** 恒等変換のみを行う[CacheCodec]。[InMemoryCacheStore]の既定として使う。 */
class PassthroughCacheCodec<T> : CacheCodec<T, T> {
    override fun encode(value: T): T = value

    override fun decode(encoded: T): T = encoded
}
