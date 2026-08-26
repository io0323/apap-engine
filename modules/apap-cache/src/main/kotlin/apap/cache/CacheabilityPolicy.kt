package apap.cache

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.vo.CapabilityId

/**
 * 16_拡張ポイント.md 16.4 SPI: 要求がResponse Cacheの対象になりうるか（決定的か）を判定する。
 *
 * Streaming要求は本Policyの対象外: apap-executionの構造上、`DefaultExecutionEngine.execute`
 * （非Streaming経路）のみが`CacheEngine.lookup`/`store`を呼び、Streaming専用経路
 * （`apap.execution.streaming.StreamingEngine`）はCacheEngineに一切依存しないため、
 * Streaming要求はそもそも本Policyへ到達しない（要件充足に影響しない実装判断のためADR化せず
 * 根拠をここに残す）。Request Cache（冪等キー基準）はCacheabilityPolicyを経由せず常に対象とする
 * （[DefaultCacheEngine]参照）。
 */
interface CacheabilityPolicy {
    fun isCacheable(request: CanonicalRequest): Boolean
}

/**
 * 既定実装: [alwaysDeterministicCapabilities]に含まれるCapability、または`params.temperature`が
 * 明示的に`0.0`（`null`＝既定値は非決定的とみなし対象外）の場合にキャッシュ対象とする。
 */
class DefaultCacheabilityPolicy(
    private val alwaysDeterministicCapabilities: Set<CapabilityId> = DEFAULT_DETERMINISTIC_CAPABILITIES,
) : CacheabilityPolicy {
    override fun isCacheable(request: CanonicalRequest): Boolean =
        request.capabilityId in alwaysDeterministicCapabilities || request.params.temperature == ZERO_TEMPERATURE

    companion object {
        val DEFAULT_DETERMINISTIC_CAPABILITIES: Set<CapabilityId> =
            setOf(CapabilityId("embedding"), CapabilityId("image_analysis"))
        private const val ZERO_TEMPERATURE = 0.0
    }
}
